package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class SyncBatchRequest(
    @SerializedName("operations") val operations: List<SyncOperationItem>
)

data class SyncOperationItem(
    @SerializedName("id") val id: String,
    @SerializedName("operation_type") val operationType: String,
    @SerializedName("target_group_key") val targetGroupKey: String,
    @SerializedName("idempotency_key") val idempotencyKey: String,
    @SerializedName("client_created_at") val clientCreatedAt: Long,
    @SerializedName("payload") val payloadJson: String
)

data class SyncBatchResponse(
    @SerializedName("results") val results: List<SyncOperationResult>
)

data class SyncOperationResult(
    @SerializedName("operation_id") val operationId: String,
    @SerializedName("success") val success: Boolean,
    @SerializedName("server_seq") val serverSeq: Long? = null,
    @SerializedName("error_code") val errorCode: String? = null,
    @SerializedName("message_key") val messageKey: String? = null,
    @SerializedName("retriable") val retriable: Boolean = true
)
