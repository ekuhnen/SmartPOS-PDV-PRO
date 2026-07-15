package com.plugpdv.pdv.models

data class User(
    val id: String? = null,
    val email: String? = null,
    val user_metadata: UserMetadata? = null
)

data class UserMetadata(
    val full_name: String? = null
)
