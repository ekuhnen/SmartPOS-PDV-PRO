package com.plugpdv.pdv.ui.reconciliation

import com.plugpdv.pdv.repository.ReconciliationReason

object ReconciliationReasonMapper {

    /**
     * Maps an internal reconciliation reason or error code to a human-readable message.
     */
    fun toHumanMessage(reason: String?): String {
        return when (reason?.uppercase()) {
            ReconciliationReason.TABLE_ALREADY_OCCUPIED ->
                "Esta mesa já está ocupada por outra operação."

            ReconciliationReason.OPERATION_MODE_DISABLED ->
                "Este tipo de operação não está disponível no momento."

            ReconciliationReason.FORBIDDEN, "REMOTE_STATE_CONFLICT" ->
                "O estado da comanda mudou no servidor e esta operação precisa ser revisada."

            ReconciliationReason.MESA_NOT_FOUND, "REMOTE_ENTITY_NOT_FOUND" ->
                "A comanda ou mesa associada a esta operação não foi encontrada no servidor."

            ReconciliationReason.MESA_INACTIVE, "REMOTE_ENTITY_ALREADY_CLOSED" ->
                "A comanda já foi encerrada."

            ReconciliationReason.EMPTY_SERVER_ID,
            ReconciliationReason.IDEMPOTENCY_KEY_REUSED,
            "AMBIGUOUS_REMOTE_RESULT" ->
                "Não foi possível confirmar se esta operação foi concluída no servidor."

            ReconciliationReason.UNPROCESSABLE_ENTITY,
            ReconciliationReason.BAD_REQUEST,
            ReconciliationReason.IDEMPOTENCY_KEY_INVALID,
            "LOCAL_REMOTE_STATE_DIVERGENCE" ->
                "Os dados deste terminal estão diferentes do estado atual do servidor."

            else ->
                "Os dados deste terminal estão diferentes do estado atual do servidor."
        }
    }

    /**
     * Returns the human-readable label for operation types.
     */
    fun toHumanOperationType(operationType: String?): String {
        return when (operationType?.uppercase()) {
            "OPEN_TABLE" -> "Abrir mesa"
            "ADD_ITEM" -> "Adicionar item"
            "SEND_KITCHEN" -> "Enviar para cozinha"
            else -> operationType ?: "Operação"
        }
    }
}
