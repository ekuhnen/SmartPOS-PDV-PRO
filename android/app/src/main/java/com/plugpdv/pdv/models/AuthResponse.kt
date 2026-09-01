package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class AuthDevice(
    val id: String? = null,
    @SerializedName("api_version")
    val apiVersion: Int? = null,
    val blocked: Boolean? = null
)

data class AuthResponse(
    val access_token: String? = null,
    val token_type: String? = null,
    val user: User? = null,
    val mesa: Boolean? = null,
    val venda_direta: Boolean? = null,
    val comanda: Boolean? = null,
    /**
     * Identidade canônica de tenant retornada pelo backend no auth-login.
     * É a ÚNICA fonte autorizada de tenant identity no Android.
     * NUNCA derivar tenant de user.id, user_metadata.invited_by ou email.
     * Se null/blank → login falha fechado (TENANT_ID_NOT_RETURNED).
     */
    @SerializedName("owner_id")
    val ownerId: String? = null,
    val device: AuthDevice? = null
)
