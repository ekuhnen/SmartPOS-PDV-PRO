package com.plugpdv.pdv.models

data class AuthResponse(
    val access_token: String? = null,
    val token_type: String? = null,
    val user: User? = null,
    val mesa: Boolean? = null,
    val venda_direta: Boolean? = null,
    val comanda: Boolean? = null
)
