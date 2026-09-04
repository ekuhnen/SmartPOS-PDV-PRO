package com.plugpdv.pdv.ui.sale

/** Provider callback correlation survives Activity recreation via requestId. */
object CheckoutOperationCorrelation {
    fun resolve(providerRequestId: String?, pendingOperationId: String?): String? =
        providerRequestId?.takeIf { it.isNotBlank() } ?: pendingOperationId?.takeIf { it.isNotBlank() }
}
