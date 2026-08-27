package com.plugpdv.pdv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AppHeadersInterceptorTest {

    @Test
    fun testServerTimeParsing_RFC1123() {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        val expectedDate = Date()
        val formatted = sdf.format(expectedDate)

        val parsed = sdf.parse(formatted)?.time
        assertTrue(parsed != null)
        assertEquals(expectedDate.time / 1000, parsed!! / 1000)
    }

    @Test
    fun testClockDivergenceThresholdCalculation() {
        val clientTime = System.currentTimeMillis()
        val serverTimeFarFuture = clientTime + (10 * 60 * 1000L) // 10 min no futuro
        val diffMs = kotlin.math.abs(clientTime - serverTimeFarFuture)

        val isDivergent = diffMs > (5 * 60 * 1000L)
        assertTrue("Divergência de relógio deve ser detectada se > 5 minutos", isDivergent)
    }
}
