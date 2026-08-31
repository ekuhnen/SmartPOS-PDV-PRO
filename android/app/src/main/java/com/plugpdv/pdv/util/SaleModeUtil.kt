package com.plugpdv.pdv.util

/**
 * Utility object for handling sale mode authorization logic.
 * This is extracted from DirectSaleActivity to allow unit testing without pulling in Android UI classes.
 */
object SaleModeUtil {
    /**
     * Authorized sale modes.
     */
    enum class AuthorizedMode {
        MESA,
        VENDA_DIRETA,
        COMANDA
    }

    /**
     * Returns the list of authorized modes based on the provided flags.
     * The default for each flag is `false` to enforce fail‑closed behaviour.
     */
    fun getAuthorizedModes(
        hasMesa: Boolean,
        hasVendaDireta: Boolean,
        hasComanda: Boolean
    ): List<AuthorizedMode> {
        val list = mutableListOf<AuthorizedMode>()
        if (hasMesa) list.add(AuthorizedMode.MESA)
        if (hasVendaDireta) list.add(AuthorizedMode.VENDA_DIRETA)
        if (hasComanda) list.add(AuthorizedMode.COMANDA)
        return list
    }
}
