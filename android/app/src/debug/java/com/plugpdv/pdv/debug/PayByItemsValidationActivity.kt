package com.plugpdv.pdv.debug

import android.app.Activity
import android.os.Bundle
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.ExchangeResponse
import com.plugpdv.pdv.models.TableItemPayment
import com.plugpdv.pdv.ui.sale.PayByItemsAdapter
import com.plugpdv.pdv.utils.ComandaItemHydrator
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.MoneyDecimal
import com.plugpdv.pdv.utils.LanguageManager
import java.math.BigDecimal

/** In-memory, debug-only pay-by-items validation. It has no persistence or payment path. */
class PayByItemsValidationActivity : Activity() {
    private val cm = CurrencyManager.getInstance()
    private lateinit var rows: MutableList<TableItemPayment>
    private lateinit var adapter: PayByItemsAdapter
    private lateinit var summary: TextView
    private lateinit var mode: TextView
    private var lang: String = "pt"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        lang = LanguageManager.getLanguage(this)
        cm.setRates(ExchangeResponse("BRL", listOf(ExchangeResponse.CurrencyRate("PYG", 1160.0))))
        cm.selectedCurrency = "PYG"
        val json = Gson().toJson(listOf(
            mapOf("id" to "DEBUG-ITEM-1", "produto_id" to "DEBUG-PRODUCT-1", "nome" to "Cachorro Quente", "quantidade" to 1, "preco_unitario" to 18.0),
            mapOf("id" to "DEBUG-ITEM-2", "produto_id" to "DEBUG-PRODUCT-2", "nome" to "Vinho Concha Y Toro", "quantidade" to 1, "preco_unitario" to 98.0),
            mapOf("id" to "DEBUG-ITEM-3", "produto_id" to "DEBUG-PRODUCT-3", "nome" to "Cerveja Teste", "quantidade" to 3, "paid_quantity" to 1, "preco_unitario" to 10.0)
        ))
        val hydrated = ComandaItemHydrator.fromSnapshot(json, "BRL")
        rows = hydrated.filter { !it.removed && it.quantity > it.paidQuantity }.map { TableItemPayment(it) }.toMutableList()
        buildUi(hydrated.size, rows.size)
    }

    private fun buildUi(snapshotCount: Int, eligibleCount: Int) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
        val l = if (lang.startsWith("es")) "es" else "pt"
        root.addView(TextView(this).apply { text = if (l == "es") "Validación de pago por ítems (DEBUG)" else "Validação de pagamento por itens (DEBUG)"; textSize = 20f })
        root.addView(TextView(this).apply { text = if (l == "es") "Ítems de snapshot: $snapshotCount   Hidratados: $snapshotCount   Elegibles: $eligibleCount" else "Itens do snapshot: $snapshotCount   Hidratados: $snapshotCount   Elegíveis: $eligibleCount" })
        val languages = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        languages.addView(Button(this).apply { text = "PT"; setOnClickListener { LanguageManager.setLanguage(this@PayByItemsValidationActivity, "pt"); recreate() } })
        languages.addView(Button(this).apply { text = "ES"; setOnClickListener { LanguageManager.setLanguage(this@PayByItemsValidationActivity, "es"); recreate() } })
        root.addView(languages)
        val currencies = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        currencies.addView(Button(this).apply { text = "BRL"; setOnClickListener { cm.selectedCurrency = "BRL"; adapter.notifyDataSetChanged(); recalc() } })
        currencies.addView(Button(this).apply { text = "PYG"; setOnClickListener { cm.selectedCurrency = "PYG"; adapter.notifyDataSetChanged(); recalc() } })
        root.addView(currencies)
        mode = TextView(this).apply { text = "Modo: ÍTEMS"; textSize = 16f }; root.addView(mode)
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("TOTAL", "DIVIDIR", "ÍTEMS").forEach { label -> modes.addView(Button(this).apply { text = label; setOnClickListener { mode.text = "Modo: $label"; recalc() } }) }
        root.addView(modes)
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@PayByItemsValidationActivity) }
        adapter = PayByItemsAdapter(rows, { p, selected -> rows[p].selected = selected; rows[p].selectedQuantity = if (selected) (rows[p].item.quantity - rows[p].item.paidQuantity) else 0; adapter.notifyItemChanged(p); recalc() }, { p, d -> val r = rows[p]; r.selectedQuantity = (r.selectedQuantity + d).coerceIn(0, r.item.quantity - r.item.paidQuantity); r.selected = r.selectedQuantity > 0; adapter.notifyItemChanged(p); recalc() })
        list.adapter = adapter; root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        summary = TextView(this).apply { textSize = 16f }; root.addView(summary)
        root.addView(Button(this).apply { text = if (l == "es") "Cobrar (validación, sin pago)" else "Cobrar (validação, sem pagamento)"; setOnClickListener { Toast.makeText(this@PayByItemsValidationActivity, getString(R.string.select_item_for_payment), Toast.LENGTH_SHORT).show() } })
        setContentView(root); recalc()
    }

    private fun recalc() {
        val selected = rows.sumOf { if (it.selected) it.selectedQuantity else 0 }
        val base = rows.fold(BigDecimal.ZERO) { acc, row -> acc + BigDecimal.valueOf(row.item.product.selling_price ?: 0.0).multiply(BigDecimal.valueOf((if (row.selected) row.selectedQuantity else 0).toLong())) }
        val quote = cm.quoteBaseAmount(base, "BRL", cm.selectedCurrency).getOrNull()
        val subtotal = quote?.transactionAmount ?: BigDecimal.ZERO
        val tax = MoneyDecimal.roundToCurrency(subtotal.multiply(BigDecimal("0.10")), cm.selectedCurrency)
        val total = subtotal.add(tax)
        val es = lang.startsWith("es")
        summary.text = "${if (es) "Snapshot/hidratados" else "Snapshot/hidratados"}: ${rows.size}/${rows.size}\n${if (es) "Cantidad seleccionada" else "Quantidade selecionada"}: $selected\n${getString(R.string.subtotal)}: ${cm.formatExplicit(subtotal.toDouble(), cm.selectedCurrency)}\nIVA 10%: ${cm.formatExplicit(tax.toDouble(), cm.selectedCurrency)}\n${getString(R.string.total_to_pay_label)}: ${cm.formatExplicit(total.toDouble(), cm.selectedCurrency)}"
    }
}
