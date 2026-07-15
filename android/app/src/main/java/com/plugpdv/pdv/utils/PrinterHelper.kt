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

    @JvmStatic
    fun printReceipt(context: Context, content: String) {
        val printer = HardwareFactory.getPrinter(context)

        if (printer != null) {
            try {
                printer.init()
                printer.reset()

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
                    printer.printText("OBRIGADO PELA PREFERENCIA")
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
                    printer.printText("   OBRIGADO PELA PREFERENCIA    \n")
                    printer.lineFeed(3)
                    printer.close()
                }

                showToast(context, "Imprimindo Cupom...")
            } catch (e: Exception) {
                showToast(context, "Erro na impressão: ${e.message}")
            }
        } else {
            showToast(context, "Impressora não detectada ou não suportada.")
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
        val printer = HardwareFactory.getPrinter(context)
        val dateStr = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.getDefault()).format(Date())
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
                    printer.setFontSize(28f)
                    printer.printText("PlugPDV\n")
                    printer.setFontSize(24f)
                    printer.setBold(false)
                    printer.printText("   CUPOM DE RETIRADA   \n")
                    printer.setAlignment(0)
                    printer.printText("DATA: $dateStr\n")
                    if (!operatorName.isNullOrBlank()) {
                        printer.printText("OPERADOR: $operatorName\n")
                    }
                    
                    printer.lineFeed(1)

                    // --- Detalhes do Item ---
                    printer.setAlignment(1)
                    printer.setBold(true)
                    printer.sendRaw(byteArrayOf(0x1D, 0x42, 1)) // Inverte para fundo preto
                    printer.setFontSize(36f)
                    printer.printText(" $productName \n")
                    printer.setFontSize(24f)
                    printer.sendRaw(byteArrayOf(0x1D, 0x42, 0)) // Volta para fundo branco
                    printer.setBold(false)
                    
                    printer.setAlignment(0)
                    printer.printText("QTD: $ticketQty (Via $i/$totalItemQty)   UNIT: ${cm.format(unitPrice)}\n")
                    printer.printText("SUBTOTAL: ${cm.format(ticketSubtotal)}\n")

                    // --- QR Code Detalhado ---
                    val safeOpName = (operatorName ?: "N/A").replace("-", " ")
                    val safeProdName = productName.replace("-", " ")
                    // Formato: saleId-date-operator-prodId-prodName-qty-unitPrice-subtotal-via
                    val qrData = "$saleId-$dateStr-$safeOpName-$productId-$safeProdName-${ticketQty}-${cm.format(unitPrice)}-${cm.format(ticketSubtotal)}-via$i"
                    
                    printer.setAlignment(1)
                    val qrBitmap = generateQRCodeBitmap(qrData, 250)
                    if (qrBitmap != null) {
                        printer.printImage(qrBitmap)
                    } else {
                        printer.printQRCode(qrData, 8)
                    }

                    // --- Rodapé do Item ---
                    printer.lineFeed(1)
                    printer.setAlignment(0)
                    printer.setBold(true)
                    printer.printText("TOTAL COMPRA: ${cm.format(total)} $currency\n")
                    printer.setBold(false)
                    printer.printText("FORMA: $paymentMethod\n")
                    
                    printer.lineFeed(1)
                    printer.setAlignment(1)
                    printer.printText("APRESENTE ESTE CUPOM\n")
                    printer.printText("NO BALCÃO PARA RETIRADA\n")
                    
                    // Espaço para corte entre os tickets
                    printer.lineFeed(4)
                    }
                }

                printer.close()

                showToast(context, "Cupom impresso com sucesso!")
            } catch (e: Exception) {
                showToast(context, "Erro na impressão: ${e.message}")
            }
        } else {
            showToast(context, "Impressora não detectada. Cupom não impresso.", Toast.LENGTH_LONG)
        }
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
        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val facturaNum = "FAC-${System.currentTimeMillis() % 100000}"
        val cm = CurrencyManager.getInstance()

        val content = buildString {
            append("*** FACTURA ELETRÔNICA ***\n")
            append("(DOCUMENTO SIMULADO)\n")
            append("--------------------------------\n")
            append("Nº: $facturaNum\n")
            append("DATA: $dateStr\n")
            if (!operatorName.isNullOrBlank()) append("EMISSOR: $operatorName\n")
            append("--------------------------------\n")
            append("TOTAL: ${cm.format(total)} $currency\n")
            append("--------------------------------\n")
            append("** EMISSÃO EM PROCESSAMENTO **\n")
            append("Você receberá o documento\n")
            append("no e-mail cadastrado.\n")
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
