package com.plugpdv.pdv.ui.reconciliation

import androidx.annotation.StringRes
import com.plugpdv.pdv.R
import com.plugpdv.pdv.repository.ReconciliationReason

object ReconciliationReasonMapper {

    /**
     * Maps an internal reconciliation reason or error code to a human-readable message.
     */
    @StringRes
    fun messageRes(reason: String?): Int {
        return when (reason?.uppercase()) {
            ReconciliationReason.TABLE_ALREADY_OCCUPIED ->
                R.string.reconciliation_table_occupied

            ReconciliationReason.OPERATION_MODE_DISABLED ->
                R.string.reconciliation_operation_disabled

            ReconciliationReason.FORBIDDEN, "REMOTE_STATE_CONFLICT" ->
                R.string.reconciliation_remote_state_changed

            ReconciliationReason.MESA_NOT_FOUND, "REMOTE_ENTITY_NOT_FOUND" ->
                R.string.reconciliation_entity_not_found

            ReconciliationReason.MESA_INACTIVE, "REMOTE_ENTITY_ALREADY_CLOSED" ->
                R.string.reconciliation_comanda_closed

            ReconciliationReason.EMPTY_SERVER_ID,
            ReconciliationReason.IDEMPOTENCY_KEY_REUSED,
            "AMBIGUOUS_REMOTE_RESULT" ->
                R.string.reconciliation_remote_result_unknown

            ReconciliationReason.UNPROCESSABLE_ENTITY,
            ReconciliationReason.BAD_REQUEST,
            ReconciliationReason.IDEMPOTENCY_KEY_INVALID,
            "LOCAL_REMOTE_STATE_DIVERGENCE" ->
                R.string.reconciliation_state_divergence

            else ->
                R.string.reconciliation_state_divergence
        }
    }

    /**
     * Returns the human-readable label for operation types.
     */
    @StringRes
    fun operationTypeRes(operationType: String?): Int {
        return when (operationType?.uppercase()) {
            "OPEN_TABLE" -> R.string.open_table
            "ADD_ITEM" -> R.string.reconciliation_add_item
            "SEND_KITCHEN" -> R.string.reconciliation_send_kitchen
            else -> R.string.reconciliation_operation
        }
    }
}
