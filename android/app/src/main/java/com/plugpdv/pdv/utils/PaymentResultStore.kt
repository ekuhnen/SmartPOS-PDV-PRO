package com.plugpdv.pdv.utils

/**
 * Singleton que serve de "canal" entre PaymentHandlerActivity e CheckoutActivity.
 *
 * Como o deeplink do app de pagamento pode criar uma nova instância de
 * PaymentHandlerActivity (em vez de reusar a existente via onNewIntent),
 * não podemos confiar no mecanismo ActivityResult (setResult/paymentLauncher).
 *
 * Fluxo:
 *  1. PaymentHandlerActivity recebe o deeplink de callback
 *  2. Grava o resultado aqui via [setResult]
 *  3. Navega para CheckoutActivity com FLAG_ACTIVITY_CLEAR_TOP
 *  4. CheckoutActivity.onResume() chama [consume] e processa o pagamento
 */
object PaymentResultStore {

    @Volatile
    private var pending: PaymentResult? = null

    data class PaymentResult(
        val status: String,
        val paymentId: String?,
        val method: String?,
        val message: String?
    )

    fun setResult(result: PaymentResult) {
        pending = result
    }

    /** Retorna o resultado pendente e limpa o store (consome uma única vez). */
    fun consume(): PaymentResult? {
        val result = pending
        pending = null
        return result
    }

    fun hasPending(): Boolean = pending != null
}
