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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object PrinterHelper {

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
            } catch (e: Exception) {
                showToast(context, String.format(ctx.getString(R.string.print_error), e.message))
            }
        } else {
            showToast(context, ctx.getString(R.string.print_not_detected))
        }
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
        val dateStr = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale(lang)).format(Date())
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
                        printer.printText("${ctx.getString(R.string.print_qty_label)} $ticketQty ($copyStr)  ${ctx.getString(R.string.print_unit_price_label)} ${cm.format(unitPrice)}\n")
                        printer.printText("${ctx.getString(R.string.print_subtotal_label)} ${cm.format(ticketSubtotal)}\n")

                        // --- QR Code Detalhado ---
                        val safeOpName = (operatorName ?: "N/A").replace("-", " ")
                        val safeProdName = productName.replace("-", " ")
                        val qrData = "$saleId-$dateStr-$safeOpName-$productId-$safeProdName-${ticketQty}-${cm.format(unitPrice)}-${cm.format(ticketSubtotal)}-via$i"
                        
                        printer.setAlignment(1)
                        val qrBitmap = generateQRCodeBitmap(qrData, 160)
                        if (qrBitmap != null) {
                            printer.printImage(qrBitmap)
                        } else {
                            printer.printQRCode(qrData, 5)
                        }

                        // --- Rodapé do Item ---
                        printer.setAlignment(0)
                        printer.setBold(true)
                        printer.printText("${ctx.getString(R.string.print_total_purchase_label)} ${cm.format(total)} $currency\n")
                        printer.setBold(false)
                        printer.printText("${ctx.getString(R.string.print_payment_method_label)} $paymentMethod\n")
                        
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
                sb.append(String.format("  ${ctx.getString(R.string.print_pending_balance_label)} %s\n", cm.format(item.pendingAmountBrl)))
            }
            sb.append("--------------------------------\n")
            sb.append(String.format("${ctx.getString(R.string.print_total_pending_label)} %s\n", cm.format(reportSummary.totalPendingTablesAmountBrl)))
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
            sb.append(String.format("%-18s %13s\n", pm.name.take(18), cm.format(pm.total)))
        }
        sb.append("--------------------------------\n\n")

        // 4. RESUMO POR MOEDA
        sb.append(ctx.getString(R.string.print_currency_summary_title)).append("\n")
        reportSummary.currencySummaries.forEach { cs ->
            val currCode = cs.currencyCode ?: "BRL"
            sb.append(String.format("%-12s %19s\n", currCode, cm.formatExplicit(cs.total, currCode)))
        }
        sb.append("================================\n")
        sb.append(String.format("${ctx.getString(R.string.print_total_sales_label)} %s\n", cm.format(reportSummary.totalSalesAmountBrl)))
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
            append("${ctx.getString(R.string.print_total_label)} ${cm.format(total)} $currency\n")
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
        val printer = HardwareFactory.getPrinter(context)

        when (printer) {
            is KozenPrinter -> PrinterUtil8.printReceipt(context, data)
            is GertecPrinter -> GeneralPrinterUtil.printPOIReceipt(context, data)
            else -> {
                val content = buildString {
                    append("Mesa/Comanda: ${data.getTransactionId()}\n")
                    append("Data: ${data.getDate()} ${data.getTime()}\n")
                    append("Total: ${data.getCurrency()} ${data.getAmount()}\n")
                    append("Pgto: ${data.getPaymentMethod()}\n")
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

    private fun generateQRCodeBitmap(data: String, size: Int): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
