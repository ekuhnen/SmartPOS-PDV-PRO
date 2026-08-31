package com.plugpdv.pdv.utils

import kotlinx.coroutines.delay
import android.util.Log

suspend fun <T> retryIO(
    times: Int = 3,
    initialDelay: Long = 1000,
    maxDelay: Long = 5000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(times - 1) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            Log.e("NetworkRetry", "Attempt ${attempt + 1} failed: ${e.message}")
        }
        delay(currentPercentualDelay(currentDelay))
        currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
    }
    return block() // Last attempt
}

private fun currentPercentualDelay(delay: Long): Long {
    val jitter = (delay * 0.1).toLong()
    return delay + ((-jitter..jitter).random())
}
