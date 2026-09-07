package com.plugpdv.pdv.repository

import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.ComandaDiscoveryItem
import com.plugpdv.pdv.models.RestaurantOpsComanda
import com.plugpdv.pdv.models.RestaurantOpsSearchHit
import com.plugpdv.pdv.models.ComandasListResponse
import javax.inject.Singleton
import javax.inject.Inject

data class ExactMesaDiscovery(
    val mesaId: String,
    val mesaNumero: Int,
    val setor: String?,
    val comandas: List<ComandaDiscoveryItem>
)

class DiscoveryIncompleteException : IllegalStateException("Restaurant discovery completeness invariant failed")

@Singleton
class RestaurantOpsRepository @Inject constructor(
    private val api: PosApiService
) {
    suspend fun discoverComandasForMesa(token: String, mesaId: String): Result<ExactMesaDiscovery> = runCatching {
        val response = api.getRestaurantOpsTables("Bearer $token", "tables", mesaId)
        val table = response.tables.singleOrNull { it.mesa_id == mesaId }
            ?: throw DiscoveryIncompleteException()
        val complete = response.exact_mesa == true && response.discovery_complete == true &&
            response.truncated == false && response.count_matches == true &&
            response.open_comanda_count == response.returned_comanda_count &&
            response.open_comanda_count == table.open_comanda_count &&
            table.open_comandas.all { it.status in OPEN_STATUSES }
        if (!complete) throw DiscoveryIncompleteException()
        ExactMesaDiscovery(table.mesa_id, table.numero, table.setor, table.open_comandas.map { it.toItem(table.mesa_id, table.numero, table.setor) })
    }

    suspend fun search(token: String, query: String): Result<List<ComandaDiscoveryItem>> = runCatching {
        val standalone = api.getComandasList("Bearer $token", mesaId = "none")
        if (standalone.total != standalone.comandas.size) throw DiscoveryIncompleteException()
        val standaloneMatches = standalone.comandas
            .filter { it.status in OPEN_STATUSES && it.nomeCliente?.trim()?.equals(query.trim(), ignoreCase = true) == true }
            .map { it.toItem() }
        val operational = runCatching {
            val response = api.searchRestaurantOps("Bearer $token", "search", query, 100)
            if (response.result_count > response.results.size) throw DiscoveryIncompleteException()
            response.results
                .map { it.toItem(it.mesa_id, it.mesa_numero, it.setor, response.result_count) }
                .filter { it.displayLabel.trim().equals(query.trim(), ignoreCase = true) }
        }.getOrElse { emptyList() }
        (operational + standaloneMatches).distinctBy { it.comandaId }
    }

    private fun RestaurantOpsComanda.toItem(mesaId: String?, mesaNumero: Int?, setor: String?) =
        ComandaDiscoveryItem(comanda_id, physicalMesaId = mesaId, mesaNumero, setor, display_name, control_code, label, status, origin, currency, versao)

    private fun RestaurantOpsSearchHit.toItem(mesaId: String?, mesaNumero: Int?, setor: String?, resultCount: Int) =
        ComandaDiscoveryItem(comanda_id, mesaId, mesaNumero, setor, display_name, control_code, label, status, origin, currency, versao, match_type, resultCount)

    private fun ComandasListResponse.ComandaListItem.toItem() =
        ComandaDiscoveryItem(id, null, null, null, nomeCliente, null, nomeCliente ?: id, status, null, null, null)

    companion object {
        val OPEN_STATUSES = setOf("ABERTA", "EM_CONSUMO", "AGUARDANDO_PAGAMENTO")
    }
}
