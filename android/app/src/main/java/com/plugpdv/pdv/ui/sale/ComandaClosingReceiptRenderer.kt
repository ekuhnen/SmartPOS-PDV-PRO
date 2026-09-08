package com.plugpdv.pdv.ui.sale

import android.content.Context
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.ComandaPaymentDto
import com.plugpdv.pdv.models.ComandaReceiptResponse
import com.plugpdv.pdv.models.ReceiptAddress
import com.plugpdv.pdv.models.ReceiptIssuer
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.CurrencyManager
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure presentation of the authoritative closed-comanda snapshot. */
object ComandaClosingReceiptRenderer {
    fun render(context: Context, table: Table, state: CheckoutUiState, reprint: Boolean = false, receipt: ComandaReceiptResponse? = null): String {
        val cm = CurrencyManager.getInstance()
        val currency = requireNotNull(state.baseCurrency).also { require(it.isNotBlank()) }
        val digits = requireNotNull(state.baseMinorUnitDigits)
        fun minor(v: Long?): Double = v?.let { BigDecimal.valueOf(it).movePointLeft(digits).toDouble() } ?: 0.0
        fun money(v: Double?) = cm.formatExplicit(v ?: 0.0, currency)
        val lang = com.plugpdv.pdv.utils.LanguageManager.getLanguage(context).lowercase()
        val es = lang.startsWith("es")
        val en = lang.startsWith("en")
        fun t(pt: String, esText: String, enText: String) = when { es -> esText; en -> enText; else -> pt }
        val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("================================\n")
        appendIssuer(sb, receipt, ::t)
        receipt?.customer?.takeIf { it.isJsonObject }?.asJsonObject?.let { customer ->
            // Identity is copied only from the canonical receipt response.
            customer.string("name", "nome", "legal_name")?.let { appendWrapped(sb, it) }
            customer.string("document_number", "document", "ruc")?.let { appendWrapped(sb, it) }
            customer.string("email")?.let { appendWrapped(sb, it) }
        }
        if (receipt?.issuer == null && receipt?.empresa == null) sb.append("           PlugPDV\n")
        sb.append("================================\n")
        if (reprint) sb.append(t("REIMPRESSÃO", "REIMPRESIÓN", "REPRINT")).append("\n")
        sb.append(t("FECHAMENTO DE MESA", "CIERRE DE MESA", "TABLE CLOSING")).append("\n")
        sb.append(t("SEM VALOR FISCAL", "SIN VALOR FISCAL", "NON-FISCAL")).append("\n")
        sb.append("--------------------------------\n")
        sb.append(t("MESA", "MESA", "TABLE")).append(": ").append(table.number).append("\n")
        sb.append(t("COMANDA", "COMANDA", "ORDER")).append(": ").append(table.comandaId.orEmpty()).append("\n")
        sb.append(t("MONEDA", "MONEDA", "CURRENCY")).append(": ").append(currency).append("\n")
        sb.append(t("FECHA", "FECHA", "DATE")).append(": ").append(now).append("\n")
        sb.append("--------------------------------\n")
        sb.append(t("PRODUCTO       CANT.   IMPORTE", "PRODUCTO       CANT.   IMPORTE", "PRODUCT          QTY   AMOUNT")).append("\n")
        table.items.filter { !it.removed && it.quantity > 0 }.forEach { item ->
            val name = (item.product.name ?: "").take(18).padEnd(18)
            sb.append(name).append(" ").append(item.quantity.toString().padStart(4)).append(" ")
                .append(money((item.product.selling_price ?: 0.0) * item.quantity)).append("\n")
        }
        sb.append("--------------------------------\n")
        sb.append(t("SUBTOTAL", "SUBTOTAL", "SUBTOTAL")).append(" ").append(money(state.authoritativeSubtotal)).append("\n")
        sb.append(t("DESCONTO", "DESCUENTO", "DISCOUNT")).append(" ").append(money(0.0)).append("\n")
        sb.append(t("IVA", "IVA", "TAX")).append(" ").append(money(state.authoritativeTaxAmount)).append("\n")
        sb.append(t("SERVIÇO", "SERVICIO", "SERVICE")).append(" ").append(money(state.authoritativeServiceFee)).append("\n")
        sb.append(t("TOTAL", "TOTAL", "TOTAL")).append(" ").append(money(state.authoritativeTotal)).append("\n")
        sb.append("================================\n")
        sb.append(t("PAGAMENTOS", "PAGOS", "PAYMENTS")).append("\n")
        state.paymentsHistory.forEach { payment -> appendPayment(sb, payment, cm, currency, ::t) }
        sb.append("--------------------------------\n")
        sb.append(t("TOTAL PAGO", "TOTAL PAGADO", "TOTAL PAID")).append(" ").append(money(minor(state.paidBaseMinor))).append("\n")
        sb.append(t("SALDO", "SALDO", "BALANCE")).append(" ").append(money(minor(state.balanceBaseMinor))).append("\n")
        sb.append("================================\n")
        sb.append(t("MESA FECHADA\nCOMANDA FECHADA", "MESA CERRADA\nCOMANDA CERRADA", "TABLE CLOSED\nORDER CLOSED")).append("\n")
        sb.append(t("Obrigado pela preferência", "Gracias por su preferencia", "Thank you for your preference")).append("\n")
        sb.append("================================\n\n\n")
        return sb.toString()
    }

    private fun appendIssuer(
        sb: StringBuilder,
        receipt: ComandaReceiptResponse?,
        t: (String, String, String) -> String
    ) {
        val issuer = receipt?.issuer
        if (issuer != null) {
            appendIssuerFields(sb, issuer, t)
            return
        }
        // The contract explicitly permits empresa only for legacy receipts.
        // Preserve that fallback without sourcing identity from Android state.
        val legacy = receipt?.empresa ?: return
        val trade = legacy.string("trade_name", "nome_fantasia", "fantasy_name")
        val legal = legacy.string("legal_name", "razao_social", "name")
        val document = legacy.string("document_number", "ruc", "document")
        val primary = trade ?: legal
        primary?.let { appendWrapped(sb, it) }
        if (!trade.isNullOrBlank() && !legal.isNullOrBlank() && !trade.equals(legal, true)) appendWrapped(sb, legal)
        document?.let { sb.append(t("Documento", "Documento", "Document")).append(": ").append(it).append('\n') }
        legacy.string("phone", "telefone")?.let { sb.append(t("Telefone", "Teléfono", "Phone")).append(": ").append(it).append('\n') }
        legacy.string("email", "e_mail")?.let { sb.append(t("E-mail", "E-mail", "Email")).append(": ").append(it).append('\n') }
        legacy.string("address", "endereco", "dirección")?.let { sb.append(it).append('\n') }
    }

    private fun appendIssuerFields(sb: StringBuilder, issuer: ReceiptIssuer, t: (String, String, String) -> String) {
        val trade = issuer.tradeName.clean()
        val legal = issuer.legalName.clean()
        val primary = trade ?: legal
        primary?.let { appendWrapped(sb, it) }
        if (!trade.isNullOrBlank() && !legal.isNullOrBlank() && !trade.equals(legal, true)) appendWrapped(sb, legal)
        val document = (issuer.documentNumber ?: issuer.ruc).clean()
        if (document != null) {
            val label = if (issuer.documentType.equals("RUC", true)) "RUC" else t("Documento", "Documento", "Document")
            sb.append(label).append(": ").append(document).append('\n')
        }
        issuer.phone.clean()?.let { sb.append(t("Telefone", "Teléfono", "Phone")).append(": ").append(it).append('\n') }
        issuer.email.clean()?.let { sb.append(t("E-mail", "E-mail", "Email")).append(": ").append(it).append('\n') }
        appendAddress(sb, issuer.address, t)
    }

    private fun appendAddress(sb: StringBuilder, address: ReceiptAddress?, t: (String, String, String) -> String) {
        if (address == null) return
        val first = listOf(address.line1.clean(), address.number.clean(), address.complement.clean())
            .filterNotNull().joinToString(", ")
        val second = listOf(address.neighborhood.clean(), address.city.clean(), address.state.clean(), address.country.clean())
            .filterNotNull().joinToString(" - ")
        if (first.isNotBlank()) sb.append(first).append('\n')
        if (second.isNotBlank()) sb.append(second).append('\n')
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "-" && !it.equals("null", true) }

    private fun appendWrapped(sb: StringBuilder, value: String, width: Int = 32) {
        value.chunked(width).forEach { sb.append(it).append('\n') }
    }

    private fun com.google.gson.JsonObject.string(vararg names: String): String? = names.asSequence()
        .mapNotNull { get(it)?.takeIf { value -> value.isJsonPrimitive }?.asString?.trim() }
        .firstOrNull { it.isNotEmpty() && it != "-" && !it.equals("null", true) }

    private fun appendPayment(sb: StringBuilder, payment: ComandaPaymentDto, cm: CurrencyManager, baseCurrency: String, t: (String, String, String) -> String) {
        val method = when (payment.forma.uppercase()) { "DINHEIRO", "CASH" -> t("DINHEIRO", "EFECTIVO", "CASH"); "PIX", "PIX_TRANSFERENCIA" -> "PIX"; else -> payment.forma }
        val coverage = payment.valorBase
        val tender = payment.valor
        sb.append(method).append(" ")
        if (coverage != null && coverage != tender) {
            sb.append(t("Conta", "Cuenta", "Account")).append(": ").append(cm.formatExplicit(coverage, baseCurrency)).append(" ")
            sb.append(t("Recebido", "Recibido", "Received")).append(": ").append(cm.formatExplicit(tender, payment.moeda)).append("\n")
        } else sb.append(cm.formatExplicit(tender, payment.moeda)).append("\n")
    }
}
