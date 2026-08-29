package com.plugpdv.pdv.repository

import android.content.Context
import android.util.Log
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.CatalogDao
import com.plugpdv.pdv.database.TableDao
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.models.MesaItemDto
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TableReadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tableDao: TableDao,
    private val apiService: PosApiService,
    private val catalogDao: CatalogDao
) {

    companion object {
        private const val TAG = "TableReadRepository"

        fun mapStatus(status: String?, comandaId: String?, items: List<MesaItemDto>? = null): String {
            val upper = status?.uppercase() ?: ""
            if (upper.contains("OCUPADA") || upper.contains("CONSUMO") || upper.contains("PAGAMENTO") || upper.contains("BUSY") || upper.contains("OCCUPIED")) {
                return Table.Status.OCCUPIED
            }
            if (upper.contains("BLOQUEADA") || upper.contains("RESERVADA") || upper.contains("RESERVED")) {
                return Table.Status.RESERVED
            }
            if (!comandaId.isNullOrEmpty() || (!items.isNullOrEmpty() && items.any { it.status != "CANCELADO" && it.status != "REMOVIDO" })) {
                return Table.Status.OCCUPIED
            }
            return Table.Status.AVAILABLE
        }

        fun mapEntityToTable(entity: TableEntity): Table {
            return Table(number = entity.number).apply {
                id = entity.id
                sectorName = entity.sectorName
                sectorId = entity.sectorId
                status = entity.status
                customerName = entity.customerName ?: ""
                comandaId = entity.comandaId
                people_count = entity.peopleCount
            }
        }
    }

    /**
     * Observes all tables ordered by sectorName and number as domain Table objects directly from Room.
     */
    fun observeTables(): Flow<List<Table>> {
        return tableDao.observeAllTables().map { entities ->
            entities.map { mapEntityToTable(it) }
        }
    }

    suspend fun getTableById(id: String): Table? {
        val entity = tableDao.getTableById(id) ?: return null
        return mapEntityToTable(entity)
    }

    suspend fun getTableByNumber(number: Int, sectorId: String? = null): Table? {
        val entity = if (sectorId.isNullOrEmpty()) {
            tableDao.getTableByNumber(number)
        } else {
            tableDao.getTableByNumberAndSector(number, sectorId)
        } ?: return null
        return mapEntityToTable(entity)
    }

    suspend fun getAllTables(): List<Table> {
        return tableDao.getAllTables().map { mapEntityToTable(it) }
    }

    suspend fun applyServerConfirmedOpen(
        tableId: String,
        comandaId: String,
        customerName: String?,
        peopleCount: Int = 1
    ) {
        val existing = tableDao.getTableById(tableId)
        if (existing != null) {
            val updated = existing.copy(
                status = Table.Status.OCCUPIED,
                comandaId = comandaId,
                customerName = customerName ?: existing.customerName,
                peopleCount = peopleCount,
                updatedAt = System.currentTimeMillis()
            )
            tableDao.insert(updated)
        } else {
            val newEntity = TableEntity(
                id = tableId,
                number = 0,
                status = Table.Status.OCCUPIED,
                sectorName = "",
                sectorId = "",
                customerName = customerName,
                comandaId = comandaId,
                peopleCount = peopleCount,
                updatedAt = System.currentTimeMillis()
            )
            tableDao.insert(newEntity)
        }
    }

    /**
     * Refreshes tables from network and persists atomically into Room.
     * On network/parsing failure or invalid topology, existing Room tables are preserved untouched.
     */
    suspend fun refreshTables(token: String): Result<Unit> {
        return try {
            val response = retryIO { apiService.getMesas("Bearer $token") }
            val setores = response.setores
            if (setores == null) {
                Log.w(TAG, "getMesas returned null setores; preserving existing Room cache")
                return Result.failure(IllegalStateException("Invalid topology: setores is null"))
            }

            var rawTablesCount = 0
            val entities = setores.flatMap { sector ->
                val mesas = sector.mesas.orEmpty()
                rawTablesCount += mesas.size
                mesas.mapNotNull { mesaDto ->
                    val mesaId = mesaDto.id
                    if (mesaId.isNullOrBlank()) return@mapNotNull null
                    val mappedStatus = mapStatus(mesaDto.status, mesaDto.comanda_id, mesaDto.itens)
                    TableEntity(
                        id = mesaId,
                        number = mesaDto.numero,
                        status = mappedStatus,
                        sectorName = sector.nome.orEmpty(),
                        sectorId = sector.id.orEmpty(),
                        customerName = mesaDto.nome_cliente,
                        comandaId = mesaDto.comanda_id,
                        peopleCount = mesaDto.pessoas_qtd ?: 1,
                        totalBalance = 0.0,
                        paidAmount = 0.0,
                        pendingBalance = 0.0,
                        itemsJson = "[]",
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }

            if (rawTablesCount > 0 && entities.isEmpty()) {
                Log.w(TAG, "getMesas contained $rawTablesCount tables but 0 valid entities; preserving existing Room cache")
                return Result.failure(IllegalStateException("Invalid topology: all table IDs were invalid"))
            }

            tableDao.replaceAll(entities)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HttpException) {
            Log.e(TAG, "HTTP error refreshing tables: ${e.code()} ${e.message()}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing tables from network: ${e.message}", e)
            Result.failure(e)
        }
    }
}
