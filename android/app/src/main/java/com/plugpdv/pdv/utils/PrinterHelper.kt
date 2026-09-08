package com.plugpdv.pdv.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.plugpdv.pdv.R
import com.plugpdv.pdv.hardware.HardwareFactory
import com.plugpdv.pdv.hardware.KozenPrinter
import com.plugpdv.pdv.hardware.GertecPrinter
import com.plugpdv.pdv.hardware.printer.ReceiptData
import com.plugpdv.pdv.hardware.printer.PrinterUtil8
import com.plugpdv.pdv.hardware.printer.GeneralPrinterUtil
import com.plugpdv.pdv.ui.sale.SaleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PrinterHelper {

    @JvmStatic
    fun localizedPaymentMethod(context: Context, code: String): String = when (code.uppercase()) {
        "DINHEIRO", "CASH" -> context.getString(R.string.cash)
        "CREDITO", "CREDIT", "CREDIT_INSTALLMENTS" -> context.getString(R.string.credit)
        "DEBITO", "DEBIT" -> context.getString(R.string.debit)
        "PIX", "PIX_TRANSFERENCIA" -> context.getString(R.string.pix)
        else -> code
    }

    private fun showToast(context: Context, message: String, length: Int = Toast.LENGTH_SHORT) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, length).show()
        }
    }

    private fun getLocalizedContext(context: Context): Context {
        val lang = com.plugpdv.pdv.utils.LanguageManager.getLanguage(context)
        return com.plugpdv.pdv.utils.LanguageManager.updateResources(context, lang)
    }

    @JvmStatic
    fun printReceipt(context: Context, content: String) {
        printReceiptWithResult(context, content)
    }

    /** Reports dispatch failure without changing payment state. Hardware acknowledgement
     * is limited to the existing synchronous printer interface. */
    fun printReceiptWithResult(context: Context, content: String): Boolean {
        val ctx = getLocalizedContext(context)
        val printer = HardwareFactory.getPrinter(context)

        if (printer != null) {
            try {
                printer.init()
                printer.reset()

                val thanksText = ctx.getString(R.string.print_thank_you)
                if (printer is KozenPrinter) {
                    printer.setAlignment(1)
                    printer.setFontSize(26f)
                    printer.setBold(true)
                    printer.printText("PlugPDV")
                    printer.setBold(false)
                    printer.setFontSize(20f)
                    printer.setAlignment(0)
                    printer.printText("--------------------------------")
                    printer.printText(content)
                    printer.printText("--------------------------------")
                    printer.setAlignment(1)
                    printer.printText(thanksText)
                    printer.lineFeed(3)
                    printer.close()
                } else {
                    printer.setAlignment(1)
                    printer.printText("--------------------------------\n")
                    printer.printText("          PlugPDV              \n")
                    printer.printText("--------------------------------\n")
                    printer.setAlignment(0)
                    printer.printText(content)
                    printer.printText("\n--------------------------------\n")
                    printer.setAlignment(1)
                    printer.printText("   $thanksText   \n")
                    printer.lineFeed(3)
                    printer.close()
                }

                showToast(context, ctx.getString(R.string.print_printing))
                return true
            } catch (e: Exception) {
                showToast(context, String.format(ctx.getString(R.string.print_error), e.message))
            }
        } else {
            showToast(context, ctx.getString(R.string.print_not_detected))
        }
        return false
    }

    /**
     * Imprime cupom de retirada detalhado para venda direta.
     * Um QR Code por produto com o product_id.
     */
    @JvmStatic
    fun printDirectSaleReceipt(
        context: Context,
        cartItems: List<SaleViewModel.CartItem>,
        total: Double,
        currency: String,
        paymentMethod: String,
        operatorName: String?,
        saleId: String
    ) {
        val ctx = getLocalizedContext(context)
        val printer = HardwareFactory.getPrinter(context)
        val lang = com.plugpdv.pdv.utils.LanguageManager.getLanguage(context)
        val issuedAt = Date()
        val dateStr = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale(lang)).format(issuedAt)
        val qrIssuedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(issuedAt)
        val cm = CurrencyManager.getInstance()

        if (printer != null) {
            try {
                printer.init()
                printer.reset()

                for (item in cartItems) {
                    val unitPrice = item.product.selling_price ?: 0.0
                    val productName = item.product.name ?: "Produto"
                    val productId = item.product.id
                    val totalItemQty = item.quantity

                    for (i in 1..totalItemQty) {
                        val ticketQty = 1
                        val ticketSubtotal = unitPrice * ticketQty

                        // --- Cabeçalho ---
                        printer.setAlignment(1)
                        printer.setBold(true)
                        printer.setFontSize(22f)
                        printer.printText("PlugPDV\n")
                        printer.setFontSize(18f)
                        printer.setBold(false)
                        printer.printText("${ctx.getString(R.string.print_pickup_ticket)}\n")
                        printer.setAlignment(0)
                        printer.printText("${ctx.getString(R.string.print_date_label)} $dateStr\n")
                        if (!operatorName.isNullOrBlank()) {
                            printer.printText("${ctx.getString(R.string.print_operator_label)} $operatorName\n")
                        }

                        // --- Detalhes do Item ---
                        printer.setAlignment(1)
                        printer.setBold(true)
                        printer.sendRaw(byteArrayOf(0x1D, 0x42, 1)) // Inverte para fundo preto
                        printer.setFontSize(24f)
                        printer.printText(" $productName \n")
                        printer.setFontSize(18f)
                        printer.sendRaw(byteArrayOf(0x1D, 0x42, 0)) // Volta para fundo branco
                        printer.setBold(false)
                        
                        printer.setAlignment(0)
                        val copyStr = ctx.getString(R.string.print_copy_via, i, totalItemQty)
                        val txUnitPrice = cm.fromBrl(unitPrice, currency)
                        val unitFormatted = cm.formatExplicit(txUnitPrice, currency)
                        val subtotalFormatted = cm.formatExplicit(cm.convert(ticketSubtotal), currency)
                        printer.printText("${ctx.getString(R.string.print_qty_label)} $ticketQty ($copyStr)  ${ctx.getString(R.string.print_unit_price_label)} $unitFormatted\n")
                        printer.printText("${ctx.getString(R.string.print_subtotal_label)} $subtotalFormatted\n")

                        // --- QR Code Detalhado ---
                        // Preserve explicit product money. Legacy products without a currency
                        // reuse the already prepared numeric ticket price; the codec does no FX.
                        val productCurrency = item.product.price_currency?.takeIf { it.isNotBlank() }
                        val qrData = DirectSaleQrPayloadCodec.encode(DirectSaleQrPayload(
                            saleId = saleId,
                            productId = productId,
                            productName = productName,
                            quantity = ticketQty,
                            unitPrice = java.math.BigDecimal.valueOf(if (productCurrency != null) unitPrice else txUnitPrice),
                            currency = productCurrency ?: currency,
                            issuedAt = qrIssuedAt,
                            copy = i,
                            operatorName = operatorName
                        ))
                        // Generate before dispatch. Failure reaches the existing visible print
                        // error path instead of a native fallback with ambiguous size units.
                        val qrBitmap = DirectSaleTicketQr.bitmap(qrData)
                        printer.setAlignment(1)
                        printer.printImage(qrBitmap)

                        // --- Rodapé do Item ---
                        printer.setAlignment(0)
                        printer.setBold(true)
                        printer.printText("${ctx.getString(R.string.print_total_purchase_label)} ${cm.formatExplicit(total, currency)}\n")
                        printer.setBold(false)
                        printer.printText("${ctx.getString(R.string.print_payment_method_label)} ${localizedPaymentMethod(ctx, paymentMethod)}\n")
                        
                        printer.setAlignment(1)
                        printer.printText("${ctx.getString(R.string.print_present_at_counter)}\n")
                        
                        // Espaço para corte entre os tickets
                        printer.lineFeed(2)
                    }
                }

                printer.close()

                showToast(context, ctx.getString(R.string.print_printing))
            } catch (e: Exception) {
                showToast(context, String.format(ctx.getString(R.string.print_error), e.message))
            }
        } else {
            showToast(context, ctx.getString(R.string.print_not_detected))
        }
    }

    /**
     * Imprime relatório de auditoria completo (online/offline, saldos por mesa, pagamento, moeda)
     */
    @JvmStatic
    fun printAuditReport(
        context: Context,
        reportSummary: com.plugpdv.pdv.models.ReportSummary,
        operatorName: String?
    ) {
        val ctx = getLocalizedContext(context)
        val cm = CurrencyManager.getInstance()
        // ReportSummary fields ending in *Brl are explicitly BRL-denominated.
        val baseCurrency = "BRL"
        val lang = com.plugpdv.pdv.utils.LanguageManager.getLanguage(context)
        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale(lang)).format(Date())
        val sb = StringBuilder()

        sb.append("================================\n")
        sb.append("   ${ctx.getString(R.string.print_audit_report_title)}   \n")
        if (reportSummary.isOfflineData) {
            sb.append("   ${ctx.getString(R.string.print_offline_mode_label)}\n")
        }
        sb.append("================================\n\n")
        sb.append(ctx.getString(R.string.print_period_label)).append(" ").append(reportSummary.dateFilterLabel).append("\n")
        sb.append(ctx.getString(R.string.print_emission_label)).append(" ").append(dateStr).append("\n")
        if (!operatorName.isNullOrEmpty()) {
            sb.append(ctx.getString(R.string.print_operator_label)).append(" ").append(operatorName).append("\n")
        }
        sb.append("--------------------------------\n\n")

        // 1. MESAS / COMANDAS EM ABERTO PARA COBRANÇA
        sb.append(ctx.getString(R.string.print_open_balances_title)).append("\n")
        if (reportSummary.occupiedTables.isEmpty()) {
            sb.append(ctx.getString(R.string.print_no_pending_tables)).append("\n")
        } else {
            reportSummary.occupiedTables.forEach { item ->
                val clientStr = if (!item.customerName.isNullOrEmpty()) " (${item.customerName})" else ""
                sb.append(String.format("${ctx.getString(R.string.print_table_word)} %-3d%s\n", item.number, clientStr))
                sb.append(String.format("  ${ctx.getString(R.string.print_pending_balance_label)} %s\n", cm.formatExplicit(item.pendingAmountBrl, baseCurrency)))
            }
            sb.append("--------------------------------\n")
            sb.append(String.format("${ctx.getString(R.string.print_total_pending_label)} %s\n", cm.formatExplicit(reportSummary.totalPendingTablesAmountBrl, baseCurrency)))
        }
        sb.append("--------------------------------\n\n")

        // 2. PRODUTOS VENDIDOS
        sb.append(ctx.getString(R.string.print_products_sold_title)).append("\n")
        val allItems = reportSummary.sales.flatMap { it.items ?: emptyList() }
        val grouped = allItems.groupBy { it.productName ?: "Prod ${it.productId}" }
        if (grouped.isEmpty()) {
            sb.append(ctx.getString(R.string.print_no_products_sold)).append("\n")
        } else {
            grouped.forEach { (name, list) ->
                val qty = list.sumOf { it.quantity }
                sb.append(String.format("%-22s x%d\n", name.take(22), qty))
            }
        }
        sb.append("--------------------------------\n\n")

        // 3. RESUMO POR FORMA DE PAGAMENTO
        sb.append(ctx.getString(R.string.print_payment_summary_title)).append("\n")
        reportSummary.paymentSummaries.forEach { pm ->
            sb.append(String.format("%-18s %13s\n", localizedPaymentMethod(ctx, pm.name).take(18), cm.formatExplicit(pm.total, baseCurrency)))
        }
        sb.append("--------------------------------\n\n")

        // 4. RESUMO POR MOEDA
        sb.append(ctx.getString(R.string.print_currency_summary_title)).append("\n")
        reportSummary.currencySummaries.forEach { cs ->
            val currCode = cs.currencyCode ?: "BRL"
            sb.append(String.format("%-12s %19s\n", currCode, cm.formatExplicit(cs.total, currCode)))
        }
        sb.append("================================\n")
        sb.append(String.format("${ctx.getString(R.string.print_total_sales_label)} %s\n", cm.formatExplicit(reportSummary.totalSalesAmountBrl, baseCurrency)))
        sb.append("================================\n\n\n\n")

        printReceipt(context, sb.toString())
    }

    /**
     * Imprime factura eletrônica mockada.
     */
    @JvmStatic
    fun printMockFactura(
        context: Context,
        total: Double,
        currency: String,
        operatorName: String?
    ) {
        val ctx = getLocalizedContext(context)
        val lang = com.plugpdv.pdv.utils.LanguageManager.getLanguage(context)
        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale(lang)).format(Date())
        val facturaNum = "FAC-${System.currentTimeMillis() % 100000}"
        val cm = CurrencyManager.getInstance()

        val content = buildString {
            append("${ctx.getString(R.string.print_electronic_invoice_title)}\n")
            append("${ctx.getString(R.string.print_simulated_doc)}\n")
            append("--------------------------------\n")
            append("${ctx.getString(R.string.print_number_label)} $facturaNum\n")
            append("${ctx.getString(R.string.print_date_label)} $dateStr\n")
            if (!operatorName.isNullOrBlank()) append("${ctx.getString(R.string.print_issuer_label)} $operatorName\n")
            append("--------------------------------\n")
            append("${ctx.getString(R.string.print_total_label)} ${cm.formatExplicit(total, currency)}\n")
            append("--------------------------------\n")
            append("${ctx.getString(R.string.print_issuance_in_process)}\n")
            append("${ctx.getString(R.string.print_invoice_email_notice)}\n")
        }

        printReceipt(context, content)
    }

    /**
     * Versão de alto nível para impressão de recibos com layouts complexos.
     */
    @JvmStatic
    fun printRichReceipt(context: Context, data: ReceiptData) {
        // Capture the localized resource context before asynchronous printer work starts.
        val localizedContext = getLocalizedContext(context)
        val printer = HardwareFactory.getPrinter(context)

        when (printer) {
            is KozenPrinter -> PrinterUtil8.printReceipt(localizedContext, data)
            is GertecPrinter -> GeneralPrinterUtil.printPOIReceipt(localizedContext, data)
            else -> {
                val ctx = getLocalizedContext(context)
                val content = buildString {
                    append("${ctx.getString(R.string.print_table_label)}: ${data.getTransactionId()}\n")
                    append("${ctx.getString(R.string.print_date_label)}: ${data.getDate()} ${data.getTime()}\n")
                    append("${ctx.getString(R.string.print_total_label)} ${data.getCurrency()} ${data.getAmount()}\n")
                    append("${ctx.getString(R.string.print_payment_method_label)} ${localizedPaymentMethod(ctx, data.getPaymentMethod())}\n")
                }
                printReceipt(context, content)
            }
        }
    }

    /**
     * Carrega a logo do PlugPDV (ic_stat_push) e a converte para Bitmap
     * monocromático preto no branco, adequado para impressoras térmicas.
     *
     * A logo original é branca com fundo transparente.
     * Para impressão térmica, invertemos: símbolo preto em fundo branco.
     *
     * @param widthPx  Largura desejada em pixels (a altura é calculada proporcionalmente)
     */
    private fun loadMonochromeLogo(context: Context, widthPx: Int = 300): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_stat_push) ?: return null

            // Calcula altura proporcional
            val aspect = drawable.intrinsicHeight.toFloat() / drawable.intrinsicWidth.toFloat()
            val heightPx = (widthPx * aspect).toInt().coerceAtLeast(1)

            // 1. Renderiza o drawable (branco) num bitmap com fundo transparente
            val srcBitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val srcCanvas = Canvas(srcBitmap)
            drawable.setBounds(0, 0, widthPx, heightPx)
            drawable.draw(srcCanvas)

            // 2. Cria bitmap de saída com fundo branco
            val outBitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
            val outCanvas = Canvas(outBitmap)
            outCanvas.drawColor(Color.WHITE)

            // 3. Pinta os pixels brancos (do logo) de preto no fundo branco
            //    usando ColorMatrix para inverter as cores preservando o alpha
            val paint = Paint().apply {
                val cm = ColorMatrix().apply {
                    // Inverte R, G, B mas mantém A
                    set(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                         0f,-1f, 0f, 0f, 255f,
                         0f, 0f,-1f, 0f, 255f,
                         0f, 0f, 0f, 1f,   0f
                    ))
                }
                colorFilter = ColorMatrixColorFilter(cm)
                // Multiplica alpha para que pixels transparentes fiquem brancos
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            outCanvas.drawBitmap(srcBitmap, 0f, 0f, paint)

            srcBitmap.recycle()
            outBitmap
        } catch (e: Exception) {
            null // Fallback para texto
        }
    }
}
