package com.plugpdv.pdv.ui.sale

import com.plugpdv.pdv.models.ComandaDetailResponse
import com.plugpdv.pdv.utils.CurrencyRulesProvider

/** Render the server total in its own currency; item edits never supply a total. */
internal object CommandTotalDisplay {
    fun format(detail: ComandaDetailResponse?, rules: CurrencyRulesProvider): String {
        // Missing canonical currency must not be guessed from the display selection.
        val currency = detail?.baseCurrency?.trim()?.takeIf { it.isNotEmpty() } ?: return "—"
        return rules.formatExplicit(detail.total, currency)
    }
}
