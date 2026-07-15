package com.plugpdv.pdv.models

data class CatalogInfo(
    val catalog: CatalogDetail,
    val products: List<Product>? = null
)

data class CatalogDetail(
    val id: String,
    val name: String,
    val description: String? = null
)
