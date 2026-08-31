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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TableReadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tableDao: TableDao,
    private val apiService: PosApiService,
    private val catalogDao: CatalogDao,
    private val comandaSnapshotDao: com.plugpdv.pdv.database.ComandaSnapshotDao? = null
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

    val tablesFlow: Flow<List<Table>> = tableDao.observeAllTables().map { entities ->
        entities.map { mapEntityToTable(it) }
    }

    fun observeTables(): Flow<List<Table>> = tablesFlow

    suspend fun getAllTables(): List<Table> {
        return tableDao.getAllTables().map { mapEntityToTable(it) }
    }

    suspend fun getTableById(id: String): Table? {
        val entity = tableDao.getTableById(id) ?: return null
        return mapEntityToTable(entity)
    }

    suspend fun getTableByNumber(number: Int, sectorId: String? = null): Table? {
        val entity = if (sectorId != null) {
            tableDao.getTableByNumberAndSector(number, sectorId)
        } else {
            tableDao.getTableByNumber(number)
        } ?: return null
        return mapEntityToTable(entity)
    }

    suspend fun getTablesBySector(sectorId: String): List<Table> {
        return tableDao.getAllTables().filter { it.sectorId == sectorId }.map { mapEntityToTable(it) }
    }

    suspend fun applyServerConfirmedOpen(
        tableId: String?,
        comandaId: String?,
        customerName: String?,
        peopleCount: Int,
        knownNumber: Int,
        knownSectorId: String?,
        knownSectorName: String?
    ) {
        val existing = if (!tableId.isNullOrEmpty()) tableDao.getTableById(tableId) else null
        val number = existing?.number ?: knownNumber
        val sectorId = existing?.sectorId ?: knownSectorId.orEmpty()
        val sectorName = existing?.sectorName ?: knownSectorName.orEmpty()
        val finalTableId = existing?.id ?: tableId ?: UUID.randomUUID().toString()

        val updated = TableEntity(
            id = finalTableId,
            number = number,
            status = Table.Status.OCCUPIED,
            sectorName = sectorName,
            sectorId = sectorId,
            customerName = customerName ?: existing?.customerName,
            comandaId = comandaId ?: existing?.comandaId,
            peopleCount = peopleCount,
            totalBalance = existing?.totalBalance ?: 0.0,
            paidAmount = existing?.paidAmount ?: 0.0,
            pendingBalance = existing?.pendingBalance ?: 0.0,
            itemsJson = existing?.itemsJson ?: "[]",
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(updated)
    }

    suspend fun applyServerConfirmedClose(tableId: String) {
        val existing = tableDao.getTableById(tableId) ?: return
        val updated = existing.copy(
            status = Table.Status.AVAILABLE,
            customerName = null,
            comandaId = null,
            peopleCount = 1,
            totalBalance = 0.0,
            paidAmount = 0.0,
            pendingBalance = 0.0,
            itemsJson = "[]",
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(updated)
    }

    /**
     * Sincroniza topologia remota de mesas e persiste no Room.
     * Preserva linkage de comanda apenas se validado por snapshot utilizável.
     */
    suspend fun refreshTables(token: String): Result<Unit> {
        return try {
            val response = retryIO { apiService.getMesas("Bearer $token") }
            val setores = response.setores
            if (setores == null) {
                Log.w(TAG, "getMesas returned null setores; preserving existing Room cache")
                return Result.failure(IllegalStateException("Invalid topology: setores is null"))
            }

            val activeTenantId = com.plugpdv.pdv.utils.TenantBindingStore.getActiveTenantId(context)
            val snapshotDao = comandaSnapshotDao ?: try {
                com.plugpdv.pdv.database.AppDatabase.getDatabase(context).comandaSnapshotDao()
            } catch (e: Exception) {
                null
            }

            val existingTablesMap = tableDao.getAllTables().associateBy { it.id }
            var rawTablesCount = 0
            val entities = setores.flatMap { sector ->
                val mesas = sector.mesas.orEmpty()
                rawTablesCount += mesas.size
                mesas.mapNotNull { mesaDto ->
                    val mesaId = mesaDto.id
                    if (mesaId.isNullOrBlank()) return@mapNotNull null
                    val existingTable = existingTablesMap[mesaId]
                    val mappedStatus = mapStatus(mesaDto.status, mesaDto.comanda_id, mesaDto.itens)

                    val effectiveComandaId = if (!mesaDto.comanda_id.isNullOrBlank()) {
                        mesaDto.comanda_id
                    } else if (mappedStatus == Table.Status.OCCUPIED && !activeTenantId.isNullOrBlank() && snapshotDao != null) {
                        val candidates = snapshotDao.getSnapshotsByTableId(activeTenantId, mesaId)
                        val usable = candidates.filter { candidate ->
                            val isNotClosedOrCancelled = candidate.localStatus !in listOf("CLOSED", "CANCELLED") &&
                                    candidate.serverStatus !in listOf("FECHADA", "CANCELADA")
                            val isTenantMatch = candidate.tenantId == activeTenantId
                            val isTableMatch = candidate.tableId == mesaId
                            val decision = com.plugpdv.pdv.utils.ComandaSnapshotAuthorityPolicy.evaluate(candidate, candidate.serverComandaId, context)

                            isTenantMatch && isTableMatch && isNotClosedOrCancelled && decision == com.plugpdv.pdv.utils.SnapshotAuthorityDecision.USABLE
                        }
                        if (usable.size == 1) {
                            val candidate = usable[0]
                            if (existingTable?.comandaId != null && candidate.serverComandaId != null && existingTable.comandaId != candidate.serverComandaId) {
                                null
                            } else {
                                candidate.serverComandaId ?: existingTable?.comandaId
                            }
                        } else {
                            null
                        }
                    } else {
                        null
                    }

                    TableEntity(
                        id = mesaId,
                        number = mesaDto.numero,
                        status = mappedStatus,
                        sectorName = sector.nome.orEmpty(),
                        sectorId = sector.id.orEmpty(),
                        customerName = mesaDto.nome_cliente ?: existingTable?.customerName,
                        comandaId = effectiveComandaId,
                        peopleCount = mesaDto.pessoas_qtd ?: existingTable?.peopleCount ?: 1,
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
