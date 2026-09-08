package com.plugpdv.pdv

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.plugpdv.pdv.hardware.HardwareFactory
import com.plugpdv.pdv.hardware.Printer
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.ui.sale.SaleViewModel
import com.plugpdv.pdv.utils.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class DirectSaleQrTest {
    private val payload = DirectSaleQrPayload(
        saleId = "550e8400-e29b-41d4-a716-446655440000",
        productId = "123e4567-e89b-12d3-a456-426614174000",
        productName = "Café - Pão de queijo", quantity = 1,
        unitPrice = BigDecimal("11600"), currency = "PYG",
        issuedAt = "2026-09-08T12:00:00Z", copy = 2, operatorName = "João - Núñez"
    )

    private fun raw(json: String) = "PDV1:" + Base64.encodeToString(json.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun json() = String(Base64.decode(DirectSaleQrPayloadCodec.encode(payload).substring(5), Base64.URL_SAFE), Charsets.UTF_8)
    private fun rejected(raw: String) { assertThrows(IllegalArgumentException::class.java) { DirectSaleQrPayloadCodec.decode(raw) } }

    @Test fun deterministicRoundtripPreservesUuidsHyphensUtf8AndExplicitMoney() {
        val encoded = DirectSaleQrPayloadCodec.encode(payload)
        assertEquals(payload, DirectSaleQrPayloadCodec.decode(encoded))
        assertEquals(encoded, DirectSaleQrPayloadCodec.encode(payload))
        assertTrue(encoded.matches(Regex("PDV1:[A-Za-z0-9_-]+")))
        assertTrue(json().contains("\"unit_price\":11600"))
        assertFalse(json().contains("Gs."))
    }

    @Test fun quantityAndDecimalAmountAreNotParsedWithLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))
            val decimal = payload.copy(quantity = 7, unitPrice = BigDecimal("10.25"), currency = "USD", operatorName = null)
            assertEquals(decimal, DirectSaleQrPayloadCodec.decode(DirectSaleQrPayloadCodec.encode(decimal)))
        } finally { Locale.setDefault(old) }
    }

    @Test fun prefixesAndLegacyAreRejected() {
        listOf("", "PDV2:abcd", "pdv1:abcd", "550e8400-e29b-product-name-via1").forEach(::rejected)
    }

    @Test fun malformedBase64IsRejected() {
        listOf("PDV1:!", "PDV1:A", "PDV1:ab+cd", "PDV1:YWJj=", "PDV1:YW\nJj").forEach(::rejected)
    }

    @Test fun malformedJsonIsRejected() {
        listOf("{", "[]", "{v:1}", json() + "{}", "{}").map(::raw).forEach(::rejected)
    }

    @Test fun unsupportedVersionIsRejected() {
        rejected(raw(json().replace("\"v\":1", "\"v\":2")))
        assertThrows(IllegalArgumentException::class.java) { DirectSaleQrPayloadCodec.encode(payload.copy(v = 2)) }
    }

    @Test fun wrongTypesDuplicateFieldsAndLocalizedAmountsAreRejected() {
        rejected(raw(json().replace("\"quantity\":1", "\"quantity\":\"1\"")))
        rejected(raw(json().replace("\"unit_price\":11600", "\"unit_price\":\"Gs. 11.600\"")))
        rejected(raw(json().replace("\"v\":1", "\"v\":1,\"v\":1")))
    }

    @Test fun malformedUtf8IsRejected() {
        rejected("PDV1:" + Base64.encodeToString(byteArrayOf(0xc3.toByte(), 0x28), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    }

    @Test fun publishedScannerExampleMatchesActualCodec() {
        val file = listOf("../DIRECT-SALE-QR-CONTRACT.md", "DIRECT-SALE-QR-CONTRACT.md", "android/DIRECT-SALE-QR-CONTRACT.md")
            .map { java.io.File(it) }.first { it.exists() }
        val doc = file.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val example = Regex("```text\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).find(doc)!!.groupValues[1]
        val decodedJson = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).find(doc)!!.groupValues[1]
        assertEquals(DirectSaleQrPayloadCodec.encode(payload), example)
        assertEquals(payload, DirectSaleQrPayloadCodec.decode(example))
        assertEquals(raw(decodedJson), example)
    }

    private fun read(bitmap: Bitmap): com.google.zxing.Result {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
    }

    @Test fun bitmapIs320BlackWhiteScannableWithFourModuleMarginAndCorrectionM() {
        val encoded = DirectSaleQrPayloadCodec.encode(payload)
        val bitmap = DirectSaleTicketQr.bitmap(encoded)
        assertEquals(320, bitmap.width)
        assertEquals(320, bitmap.height)
        assertEquals(4, DirectSaleTicketQr.MARGIN)
        val minimal = QRCodeWriter().encode(encoded, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val border = 4 * (320 / minimal.width)
        for (y in 0 until 320) for (x in 0 until 320) {
            val color = bitmap.getPixel(x, y)
            assertTrue(color == Color.BLACK || color == Color.WHITE)
            if (x < border || y < border || x >= 320 - border || y >= 320 - border) assertEquals(Color.WHITE, color)
        }
        val result = read(bitmap)
        assertEquals(encoded, result.text)
        assertEquals("M", result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test fun actualDirectTicketKeepsHumanFieldsPrintsOneBitmapPerUnitAndNeverNativeTinyQr() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        // Deterministic resource labels without requiring packaged APK resources.
        val labels = object : android.content.res.Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
            override fun getString(id: Int): String = "label:$id"
            override fun getString(id: Int, vararg formatArgs: Any): String = "label:$id " + formatArgs.joinToString()
        }
        val ctx = object : ContextWrapper(app) {
            override fun createConfigurationContext(config: Configuration): Context = this
            override fun getResources(): android.content.res.Resources = labels
        }
        val printer = mock<Printer>()
        val field = HardwareFactory::class.java.getDeclaredField("printerInstance").apply { isAccessible = true }
        val previous = field.get(null)
        val cm = CurrencyManager.getInstance()
        val selection = cm.selectedCurrency
        val locale = Locale.getDefault()
        try {
            field.set(null, printer)
            cm.setRates(ExchangeResponse("PYG", listOf(ExchangeResponse.CurrencyRate("PYG", 1160.0))))
            cm.selectedCurrency = "PYG"
            PrinterHelper.printDirectSaleReceipt(ctx, listOf(SaleViewModel.CartItem(
                Product(id = payload.productId, name = payload.productName, selling_price = 11600.0, price_currency = "PYG"), 2)),
                23200.0, "PYG", "DINHEIRO", payload.operatorName, payload.saleId)
            val images = argumentCaptor<Bitmap>()
            verify(printer, times(2)).printImage(images.capture())
            val decoded = images.allValues.map { DirectSaleQrPayloadCodec.decode(read(it).text) }
            assertEquals(listOf(1, 2), decoded.map { it.copy })
            decoded.forEach { assertEquals(BigDecimal("11600.0"), it.unitPrice); assertEquals("PYG", it.currency) }
            verify(printer, never()).printQRCode(any(), any())
            val text = argumentCaptor<String>()
            verify(printer, atLeastOnce()).printText(text.capture())
            val all = text.allValues.joinToString("\n")
            listOf(R.string.print_qty_label, R.string.print_unit_price_label, R.string.print_subtotal_label,
                R.string.print_total_purchase_label, R.string.print_payment_method_label,
                R.string.print_date_label, R.string.print_operator_label, R.string.print_present_at_counter)
                .forEach { assertTrue("Missing $it", all.contains("label:$it")) }
            assertTrue(all.contains(payload.productName))
            assertTrue(all.contains("23.200"))
        } finally { field.set(null, previous); cm.setRates(null); cm.selectedCurrency = selection; Locale.setDefault(locale) }
    }
}
