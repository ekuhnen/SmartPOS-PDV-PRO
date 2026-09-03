package com.plugpdv.pdv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HumanTestFixPackTest {
    @Test
    fun spanishCashAndReceiptTermsUseOperatorVocabulary() {
        val path = File("src/main/res/values-es/strings.xml")
        assertTrue("Spanish resources must be present", path.isFile)
        val rendered = path.readText().uppercase(Locale.ROOT)
        listOf(
            "OPERACIONES DE CAJA", "EFECTIVO", "COMPROBANTE DE PAGO",
            "COMPROBANTE DE %S"
        ).forEach { assertTrue("Spanish output missing $it", rendered.contains(it)) }
        listOf("COMPROVANTE", "PAGAMENTO", "FECHAR", "DINHEIRO", "DONHEIRO").forEach {
            assertFalse("Spanish output leaked $it", rendered.contains(it))
        }
    }
}
