package com.plugpdv.pdv.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.LocalSaleDao
import com.plugpdv.pdv.database.PaymentAttemptDao
import com.plugpdv.pdv.database.TableDao
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.TableManager
import com.plugpdv.pdv.utils.retryIO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

enum class DateFilterOption {
    TODAY,
    YESTERDAY,
    ALL_TIME
}

@Singleton
class ReportRepository @Inject constructor(
    private val apiService: PosApiService,
    private val localSaleDao: LocalSaleDao,
    private val paymentAttemptDao: PaymentAttemptDao,
    private val tableDao: TableDao
) {
    private val gson = Gson()

    suspend fun getReport(
        token: String?,
        sessionId: String?,
        dateOption: DateFilterOption
    ): ReportSummary = coroutineScope {
        var isOffline = false
        var salesList: List<SaleHistoryItem> = emptyList()
        var cashierOps: List<CashierSession> = emptyList()
        var tableReportItems: List<TableReportItem> = emptyList()

        val cm = CurrencyManager.getInstance()
        val calendar = Calendar.getInstance()

        val (startMillis, endMillis) = when (dateOption) {
            DateFilterOption.TODAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis
                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                calendar.set(Calendar.MILLISECOND, 999)
                Pair(start, calendar.timeInMillis)
            }
            DateFilterOption.YESTERDAY -> {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis
                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                calendar.set(Calendar.MILLISECOND, 999)
                Pair(start, calendar.timeInMillis)
            }
            DateFilterOption.ALL_TIME -> Pair(0L, Long.MAX_VALUE)
        }

        val dateLabel = when (dateOption) {
            DateFilterOption.TODAY -> "Hoje"
            DateFilterOption.YESTERDAY -> "Ontem"
            DateFilterOption.ALL_TIME -> "Todo o Período"
        }

        // Tentar obter dados Online
        if (!token.isNullOrEmpty()) {
            try {
                val salesDeferred = async {
                    if (!sessionId.isNullOrEmpty()) {
                        retryIO { apiService.getSales("Bearer $token", sessionId) }
                    } else null
                }
                val historyDeferred = async {
                    retryIO { apiService.getCashierHistory("Bearer $token", null) }
                }
                val mesasDeferred = async {
                    retryIO { apiService.getMesas("Bearer $token") }
                }

                val salesResponse = try { salesDeferred.await() } catch (e: Exception) { null }
                val historyResponse = try { historyDeferred.await() } catch (e: Exception) { null }
                val mesasResponse = try { mesasDeferred.await() } catch (e: Exception) { null }

                if (salesResponse != null || mesasResponse != null || historyResponse != null) {
                    salesList = salesResponse?.let { it.sales ?: it.data ?: it.items } ?: emptyList()

                    val allOps = historyResponse?.let { it.operacoes ?: it.history ?: it.data } ?: emptyList()
                    cashierOps = if (!sessionId.isNullOrEmpty()) {
                        allOps.filter { it.caixa_session_id == sessionId || it.id == sessionId }
                    } else allOps

                    // Mapeia e atualiza cache de mesas
                    if (mesasResponse != null) {
                        val entitiesToCache = mutableListOf<TableEntity>()
                        val activeItems = mutableListOf<TableReportItem>()

                        mesasResponse.setores.orEmpty().flatMap { it.mesas.orEmpty() }.forEach { mesaDto ->
                            val isOccupied = mesaDto.status.equals("OCCUPIED", ignoreCase = true) || mesaDto.status.equals("OCUPADA", ignoreCase = true)
                            val tableNumber = mesaDto.numero

                            var totalBalanceBrl = 0.0
                            var totalItemCount = 0
                            val itemsList = mutableListOf<SaleItem>()

                            mesaDto.itens?.filter { it.status != "CANCELADO" && it.status != "REMOVIDO" }?.forEach { itemDto ->
                                val priceBrl = cm.toBrl(itemDto.nestedProduct?.selling_price ?: itemDto.preco_unitario ?: 0.0, itemDto.nestedProduct?.price_currency ?: "BRL")
                                val qty = itemDto.quantidade ?: 1
                                totalBalanceBrl += priceBrl * qty
                                totalItemCount += qty
                                itemsList.add(SaleItem(itemDto.produto_id.orEmpty(), itemDto.nome ?: "Item", qty, priceBrl))
                            }

                            val itemsJsonStr = gson.toJson(itemsList)
                            val tableEntity = TableEntity(
                                id = mesaDto.id ?: "table_$tableNumber",
                                number = tableNumber,
                                status = mesaDto.status ?: "AVAILABLE",
                                customerName = mesaDto.nome_cliente,
                                comandaId = mesaDto.comanda_id,
                                peopleCount = mesaDto.pessoas_qtd ?: 1,
                                totalBalance = totalBalanceBrl,
                                paidAmount = 0.0,
                                pendingBalance = totalBalanceBrl,
                                itemsJson = itemsJsonStr
                            )
                            entitiesToCache.add(tableEntity)

                            if (isOccupied && totalBalanceBrl > 0) {
                                activeItems.add(
                                    TableReportItem(
                                        number = tableNumber,
                                        customerName = mesaDto.nome_cliente,
                                        comandaId = mesaDto.comanda_id,
                                        totalAmountBrl = totalBalanceBrl,
                                        paidAmountBrl = 0.0,
                                        pendingAmountBrl = totalBalanceBrl,
                                        itemCount = totalItemCount
                                    )
                                )
                            }
                        }

                        tableDao.deleteAll()
                        tableDao.insertAll(entitiesToCache)
                        tableReportItems = activeItems
                    }
                } else {
                    isOffline = true
                }
            } catch (e: Exception) {
                Log.e("ReportRepository", "Error fetching online report data", e)
                isOffline = true
            }
        } else {
            isOffline = true
        }

        // Se estiver offline ou falhar, carrega do Room local
        if (isOffline) {
            Log.w("ReportRepository", "Modo Offline Ativado para o Relatório")
            val localSalesEntities = localSaleDao.getRecentSales().filter { it.timestamp in startMillis..endMillis }
            
            salesList = localSalesEntities.map { entity ->
                val itemsType = object : TypeToken<List<SaleItem>>() {}.type
                val items: List<SaleItem> = try { gson.fromJson(entity.itemsJson, itemsType) } catch (e: Exception) { emptyList() }
                SaleHistoryItem(
                    id = entity.localId,
                    total = entity.total,
                    convertedTotal = entity.total,
                    currency = entity.currency,
                    paymentMethod = entity.paymentMethod,
                    createdAt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entity.timestamp)),
                    items = items
                )
            }

            val localTables = tableDao.getOccupiedTables()
            tableReportItems = if (localTables.isNotEmpty()) {
                localTables.map { entity ->
                    TableReportItem(
                        number = entity.number,
                        customerName = entity.customerName,
                        comandaId = entity.comandaId,
                        totalAmountBrl = entity.totalBalance,
                        paidAmountBrl = entity.paidAmount,
                        pendingAmountBrl = entity.pendingBalance,
                        itemCount = 1
                    )
                }
            } else {
                // Fallback adicional no TableManager em memória
                TableManager.getTables().filter { it.status == Table.Status.OCCUPIED && it.getPendingBalance() > 0 }.map { table ->
                    TableReportItem(
                        number = table.number,
                        customerName = table.customerName,
                        comandaId = table.comandaId?.toString(),
                        totalAmountBrl = table.calculateTotal(),
                        paidAmountBrl = table.paidAmount,
                        pendingAmountBrl = table.getPendingBalance(),
                        itemCount = table.items.filter { !it.removed }.sumOf { it.quantity }
                    )
                }
            }
        }

        // Agrupamento por Meio de Pagamento
        val paymentSummariesList = mutableListOf<PaymentMethodSummary>()
        val paymentGroups = salesList.groupBy { (it.paymentMethod ?: "DINHEIRO").uppercase() }
        paymentGroups.forEach { (method, items) ->
            val sumTotal = items.sumOf { it.total }
            paymentSummariesList.add(PaymentMethodSummary(method, sumTotal, 0))
        }

        // Agrupamento por Moeda
        val currencySummariesList = mutableListOf<PaymentMethodSummary>()
        val currencyGroups = salesList.groupBy { (it.currency ?: "BRL").uppercase() }
        currencyGroups.forEach { (curr, items) ->
            val sumTotal = items.sumOf { it.convertedTotal ?: it.total }
            currencySummariesList.add(PaymentMethodSummary("Total $curr", sumTotal, 0, curr))
        }

        val totalSales = salesList.sumOf { it.total }
        val totalPendingTables = tableReportItems.sumOf { it.pendingAmountBrl }
        val totalSangrias = cashierOps.filter { (it.tipo ?: "").uppercase().contains("SANGRIA") }.sumOf { it.valor }

        ReportSummary(
            isOfflineData = isOffline,
            dateFilterLabel = dateLabel,
            sales = salesList,
            occupiedTables = tableReportItems,
            paymentSummaries = paymentSummariesList,
            currencySummaries = currencySummariesList,
            totalSalesAmountBrl = totalSales,
            totalPendingTablesAmountBrl = totalPendingTables,
            totalSangriaAmountBrl = totalSangrias
        )
    }
}
