package com.plugpdv.pdv.utils

object Constants {
    const val PREFS_NAME = "POS_PREFS"
    const val SESSION_ID = "SESSION_ID"
    const val OPERATOR_ID = "OPERATOR_ID"
    const val OPERATOR_NAME = "OPERATOR_NAME"
    const val TOKEN = "TOKEN"
    const val EMAIL = "EMAIL"
    const val PASSWORD = "PASSWORD"
    const val HAS_MESA = "HAS_MESA"
    const val HAS_VENDA_DIRETA = "HAS_VENDA_DIRETA"
    const val HAS_COMANDA = "HAS_COMANDA"
    const val USER_ID = "USER_ID"
    const val LOGIN_TIME = "LOGIN_TIME"
}

enum class PaymentMethod(val apiValue: String) {
    CREDIT("CREDITO"),
    DEBIT("DEBITO"),
    CASH("DINHEIRO"),
    PIX("PIX");

    companion object {
        fun fromString(value: String?): PaymentMethod {
            return entries.find { it.name.equals(value, ignoreCase = true) || it.apiValue.equals(value, ignoreCase = true) } ?: PIX
        }
    }
}

enum class PaymentStatus {
    APPROVED,
    PENDING,
    REJECTED,
    CANCELLED
}
