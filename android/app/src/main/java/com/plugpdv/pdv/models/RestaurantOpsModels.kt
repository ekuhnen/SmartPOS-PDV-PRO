package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class RestaurantOpsTablesResponse(
    val outcome: String? = null,
    val tables: List<RestaurantOpsTable> = emptyList(),
    val exact_mesa: Boolean? = null,
    val discovery_complete: Boolean? = null,
    val truncated: Boolean? = null,
    val open_comanda_count: Int? = null,
    val returned_comanda_count: Int? = null,
    val count_matches: Boolean? = null
)

data class RestaurantOpsTable(
    val mesa_id: String,
    val numero: Int,
    val setor: String? = null,
    val operational_status: String? = null,
    val open_comanda_count: Int = 0,
    val open_comandas: List<RestaurantOpsComanda> = emptyList()
)

data class RestaurantOpsComanda(
    val comanda_id: String,
    val control_code: String? = null,
    val display_name: String? = null,
    val label: String,
    val status: String,
    val origin: String? = null,
    val currency: String? = null,
    val versao: Long? = null
)

data class RestaurantOpsSearchResponse(
    val outcome: String? = null,
    val query: String? = null,
    val result_count: Int = 0,
    val results: List<RestaurantOpsSearchHit> = emptyList()
)

data class RestaurantOpsSearchHit(
    val comanda_id: String,
    val control_code: String? = null,
    val display_name: String? = null,
    val label: String,
    val status: String,
    val origin: String? = null,
    val currency: String? = null,
    val versao: Long? = null,
    val mesa_id: String? = null,
    val mesa_numero: Int? = null,
    val setor: String? = null
    ,val match_type: String? = null
)

data class ComandaDiscoveryItem(
    val comandaId: String,
    val physicalMesaId: String?,
    val mesaNumero: Int?,
    val setor: String?,
    val displayName: String?,
    val controlCode: String?,
    val label: String,
    val status: String,
    val origin: String?,
    val currency: String?,
    val versao: Long?,
    val matchType: String? = null,
    val searchResultCount: Int? = null
) {
    val displayLabel: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: label
}
