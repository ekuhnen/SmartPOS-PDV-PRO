package com.plugpdv.pdv.ui.cashier

import com.plugpdv.pdv.R
import com.plugpdv.pdv.utils.CashierAuthorityState

data class CashierUiState(
    val isOpenEnabled: Boolean,
    val isSangriaEnabled: Boolean,
    val isCloseEnabled: Boolean,
    val isDashboardEnabled: Boolean,
    val isSafeBackEnabled: Boolean,
    val openButtonTextRes: Int?,
    val openButtonCustomText: String? = null
)

object CashierUiPolicy {
    fun calculateUiState(
        state: CashierAuthorityState,
        isOffline: Boolean,
        isLoading: Boolean
    ): CashierUiState {
        if (isLoading) {
            return CashierUiState(
                isOpenEnabled = false,
                isSangriaEnabled = false,
                isCloseEnabled = false,
                isDashboardEnabled = false,
                isSafeBackEnabled = isOffline || state !is CashierAuthorityState.CLOSED,
                openButtonTextRes = R.string.open_cashier
            )
        }

        return when (state) {
            is CashierAuthorityState.OPEN -> {
                CashierUiState(
                    isOpenEnabled = false,
                    isSangriaEnabled = !isOffline,
                    isCloseEnabled = !isOffline,
                    isDashboardEnabled = !isOffline,
                    isSafeBackEnabled = true,
                    openButtonTextRes = R.string.cashier_already_open
                )
            }
            is CashierAuthorityState.CLOSED -> {
                CashierUiState(
                    isOpenEnabled = !isOffline,
                    isSangriaEnabled = false,
                    isCloseEnabled = false,
                    isDashboardEnabled = false,
                    isSafeBackEnabled = isOffline,
                    openButtonTextRes = R.string.open_cashier
                )
            }
            is CashierAuthorityState.UNKNOWN -> {
                CashierUiState(
                    isOpenEnabled = false,
                    isSangriaEnabled = false,
                    isCloseEnabled = false,
                    isDashboardEnabled = false,
                    isSafeBackEnabled = true,
                    openButtonTextRes = null,
                    openButtonCustomText = if (isOffline) "Caixa Indisponível (Sem Conexão)" else "Carregando autoridade do caixa..."
                )
            }
        }
    }
}
