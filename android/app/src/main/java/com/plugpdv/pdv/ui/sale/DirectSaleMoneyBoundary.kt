package com.plugpdv.pdv.ui.sale

/**
 * Currency boundary for the direct-sale catalog. CatalogRepository normalizes
 * downloaded prices to BRL and marks Product.price_currency=BRL. Keeping this
 * explicit prevents a company base currency (for example PYG) from being used
 * to reinterpret the normalized catalog amount.
 */
internal object DirectSaleMoneyBoundary {
    const val NORMALIZED_CATALOG_CURRENCY = "BRL"

    fun catalogCurrency(items: List<SaleViewModel.CartItem>): String? {
        if (items.isEmpty()) return null
        val explicit = items.mapNotNull { it.product.price_currency?.trim()?.uppercase()?.takeIf { value -> value.isNotEmpty() } }.distinct()
        if (explicit.isEmpty()) return NORMALIZED_CATALOG_CURRENCY
        return explicit.singleOrNull()
    }
}
