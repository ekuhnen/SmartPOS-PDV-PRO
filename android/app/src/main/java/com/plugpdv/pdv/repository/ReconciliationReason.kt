package com.plugpdv.pdv.repository

/**
 * Semantic reason constants for reconciliation.
 *
 * These constants define the canonical values stored in
 * [com.plugpdv.pdv.database.ComandaMutationEntity.reconciliationReason].
 *
 * They are written by [com.plugpdv.pdv.dispatcher.ComandaOutboxDispatcher]
 * and read by the reconciliation UI and resolution logic.
 *
 * IMPORTANT: these strings are persisted to the database.
 * Do NOT rename existing constants without a Room migration.
 */
object ReconciliationReason {

    /** HTTP 409 TABLE_ALREADY_OCCUPIED: the table was already opened by another session. */
    const val TABLE_ALREADY_OCCUPIED = "TABLE_ALREADY_OCCUPIED"

    /** HTTP 403 OPERATION_MODE_DISABLED: the Mesa/Comanda feature is disabled for this tenant. */
    const val OPERATION_MODE_DISABLED = "OPERATION_MODE_DISABLED"

    /** HTTP 403 generic FORBIDDEN: server refused for an unspecified authorization reason. */
    const val FORBIDDEN = "FORBIDDEN"

    /** HTTP 404: the Mesa/table no longer exists on the server. */
    const val MESA_NOT_FOUND = "MESA_NOT_FOUND"

    /** HTTP 409 MESA_INACTIVE: the Mesa is closed and cannot be reopened. */
    const val MESA_INACTIVE = "MESA_INACTIVE"

    /** HTTP 2xx with empty/missing server ID in body: result is ambiguous. */
    const val EMPTY_SERVER_ID = "EMPTY_SERVER_ID"

    /** HTTP 409/422 IDEMPOTENCY_KEY_REUSED: same K used for a different operation. */
    const val IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"

    /** HTTP 422: server could not process the request. */
    const val UNPROCESSABLE_ENTITY = "UNPROCESSABLE_ENTITY"

    /** HTTP 400 with unknown error code: malformed request. */
    const val BAD_REQUEST = "BAD_REQUEST_400"

    /** HTTP 400 IDEMPOTENCY_KEY_INVALID: K does not meet format requirements. */
    const val IDEMPOTENCY_KEY_INVALID = "IDEMPOTENCY_KEY_INVALID"
}
