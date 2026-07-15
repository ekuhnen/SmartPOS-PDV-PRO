package com.plugpdv.pdv.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.CommandActionRequest

data class PendingTransfer(
    val token: String,
    val request: CommandActionRequest
)

class TransferQueueManager(context: Context) {
    private val prefs = context.getSharedPreferences("transfer_queue", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun addToQueue(token: String, request: CommandActionRequest) {
        val queue = getQueue().toMutableList()
        queue.add(PendingTransfer(token, request))
        saveQueue(queue)
    }

    fun getQueue(): List<PendingTransfer> {
        val json = prefs.getString("queue", "[]")
        val type = object : TypeToken<List<PendingTransfer>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveQueue(queue: List<PendingTransfer>) {
        prefs.edit().putString("queue", gson.toJson(queue)).apply()
    }

    suspend fun processQueue(apiService: PosApiService) {
        val queue = getQueue().toMutableList()
        if (queue.isEmpty()) return

        Log.d("TransferQueueManager", "Processing ${queue.size} pending transfers")
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            try {
                val response = apiService.manageComanda("Bearer ${pending.token}", pending.request)
                if (response.isSuccessful) {
                    iterator.remove()
                    Log.d("TransferQueueManager", "Transfer processed successfully")
                } else {
                    Log.e("TransferQueueManager", "Failed to process transfer: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("TransferQueueManager", "Error processing transfer, will retry later", e)
                break // Stop processing on network error
            }
        }
        saveQueue(queue)
    }
}
