package com.plugpdv.pdv.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object DirectSaleTicketQr {
    const val SIZE = 280
    const val MARGIN = 4
    val ERROR_CORRECTION = ErrorCorrectionLevel.M

    /** No native size-unit fallback and no resizing/interpolation of QR modules. */
    fun bitmap(raw: String): Bitmap {
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, SIZE, SIZE, mapOf(
            EncodeHintType.MARGIN to MARGIN,
            EncodeHintType.ERROR_CORRECTION to ERROR_CORRECTION
        ))
        check(matrix.width == SIZE && matrix.height == SIZE)
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565).apply {
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}
