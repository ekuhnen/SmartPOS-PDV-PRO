package com.plugpdv.pdv.utils

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

data class SyncMetricEntry(
    val groupOrSaleId: String,
    val createdAt: Long,
    val flushedAt: Long,
    val elapsedMs: Long,
    val itemsCount: Int,
    val exceeded60sWindow: Boolean
)

@Singleton
class SyncMetricsTracker @Inject constructor() {

    companion object {
        private const val TAG = "SyncMetricsTracker"
        private const val WINDOW_60S_MS = 60_000L
    }

    private val recentMetrics = mutableListOf<SyncMetricEntry>()

    /**
     * Registra o tempo decorrido entre a criação da venda/operação e a inserção/descarte do último item na outbox.
     * Alimenta a análise empírica para decisão sobre a janela de 60s no backend.
     */
    fun recordSyncFlush(
        groupOrSaleId: String,
        createdAt: Long,
        itemsCount: Int = 1,
        flushedAt: Long = System.currentTimeMillis()
    ) {
        val elapsedMs = (flushedAt - createdAt).coerceAtLeast(0L)
        val elapsedSec = elapsedMs / 1000.0
        val isExceeded = elapsedMs > WINDOW_60S_MS

        val entry = SyncMetricEntry(
            groupOrSaleId = groupOrSaleId,
            createdAt = createdAt,
            flushedAt = flushedAt,
            elapsedMs = elapsedMs,
            itemsCount = itemsCount,
            exceeded60sWindow = isExceeded
        )

        synchronized(recentMetrics) {
            if (recentMetrics.size >= 100) {
                recentMetrics.removeAt(0)
            }
            recentMetrics.add(entry)
        }

        val windowStatus = if (isExceeded) "⚠️ EXCEEDED_60S_LIMIT" else "✅ WITHIN_60S_LIMIT"
        Log.i(
            TAG,
            "[SYNC_METRICS] Group/Sale: $groupOrSaleId | Elapsed: ${String.format("%.2f", elapsedSec)}s (${elapsedMs}ms) | Items: $itemsCount | Window: $windowStatus"
        )
    }

    fun getRecentMetrics(): List<SyncMetricEntry> {
        return synchronized(recentMetrics) {
            recentMetrics.toList()
        }
    }
}
