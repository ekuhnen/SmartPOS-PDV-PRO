package com.plugpdv.pdv.models

data class LoginRequest(
    val email: String,
    val password: String,
    val connection: String = "Username-Password-Authentication"
)
