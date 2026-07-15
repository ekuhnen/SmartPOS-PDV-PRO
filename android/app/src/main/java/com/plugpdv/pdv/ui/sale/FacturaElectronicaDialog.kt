package com.plugpdv.pdv.ui.sale

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.TextView
import com.plugpdv.pdv.R

/**
 * Dialog que pergunta se o usuário deseja emitir Factura Eletrônica.
 * A emissão real é mockada por enquanto.
 *
 * @param onDismissed Callback chamado após o usuário escolher Sim ou Não.
 *                    O parâmetro indica se escolheu emitir (true) ou não (false).
 */
class FacturaElectronicaDialog(
    context: Context,
    private val onDismissed: (emitir: Boolean) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_factura_electronica, null)
        setContentView(view)

        // Força a janela a ocupar 90% da largura da tela (DEVE ser após setContentView)
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.9).toInt()
        window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        setCancelable(false)

        view.findViewById<Button>(R.id.btnFacturaSim).setOnClickListener {
            dismiss()
            onDismissed(true)
        }

        view.findViewById<Button>(R.id.btnFacturaNao).setOnClickListener {
            dismiss()
            onDismissed(false)
        }
    }
}
