package com.plugpdv.pdv.models

data class TaxRate(
    val id: String,
    val name: String,
    val percentage: Double,
    val currency: String,
    val active: Boolean
)
