package com.plugpdv.pdv.repository

import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class RestaurantOpsRepositoryTest {
    private val api = mock<PosApiService>()
    private val repository = RestaurantOpsRepository(api)

    private fun tableResponse(
        complete: Boolean = true,
        truncated: Boolean = false,
        matches: Boolean = true,
        count: Int = 2,
        returned: Int = 2,
        comandas: List<RestaurantOpsComanda> = listOf(
            RestaurantOpsComanda("evandro-id", "A02979C1", "Evandro", "Evandro", "EM_CONSUMO"),
            RestaurantOpsComanda("bk-id", "883A74C1", "BK", "BK", "ABERTA")
        )
    ) = RestaurantOpsTablesResponse(
        exact_mesa = true, discovery_complete = complete, truncated = truncated,
        open_comanda_count = count, returned_comanda_count = returned, count_matches = matches,
        tables = listOf(RestaurantOpsTable("mesa-20", 20, "VIP", "IN_USE", count, comandas))
    )

    @Test fun exactMesaReturnsAllIndependentComandas() = runBlocking {
        whenever(api.getRestaurantOpsTables(any(), any(), any())).thenReturn(tableResponse())
        val result = repository.discoverComandasForMesa("token", "mesa-20").getOrThrow()
        assertEquals(listOf("evandro-id", "bk-id"), result.comandas.map { it.comandaId })
        assertEquals(listOf("Evandro", "BK"), result.comandas.map { it.displayLabel })
    }

    @Test fun incompleteMetadataFailsClosed() = runBlocking {
        whenever(api.getRestaurantOpsTables(any(), any(), any())).thenReturn(tableResponse(complete = false))
        assertTrue(repository.discoverComandasForMesa("token", "mesa-20").isFailure)
        whenever(api.getRestaurantOpsTables(any(), any(), any())).thenReturn(tableResponse(truncated = true))
        assertTrue(repository.discoverComandasForMesa("token", "mesa-20").isFailure)
        whenever(api.getRestaurantOpsTables(any(), any(), any())).thenReturn(tableResponse(matches = false))
        assertTrue(repository.discoverComandasForMesa("token", "mesa-20").isFailure)
    }

    @Test fun countMismatchFailsClosedAndZeroIsValidWhenComplete() = runBlocking {
        whenever(api.getRestaurantOpsTables(any(), any(), any())).thenReturn(tableResponse(count = 2, returned = 1, comandas = emptyList()))
        assertTrue(repository.discoverComandasForMesa("token", "mesa-20").isFailure)
        whenever(api.getRestaurantOpsTables(any(), any(), any())).thenReturn(tableResponse(count = 0, returned = 0, comandas = emptyList()))
        assertTrue(repository.discoverComandasForMesa("token", "mesa-20").getOrThrow().comandas.isEmpty())
    }

    @Test fun searchIsDiscoveryOnlyAndPreservesDuplicateNames() = runBlocking {
        whenever(api.getComandasList(any(), anyOrNull(), eq("none"))).thenReturn(ComandasListResponse(total = 0, comandas = emptyList()))
        whenever(api.searchRestaurantOps(any(), any(), any(), any())).thenReturn(
            RestaurantOpsSearchResponse(result_count = 2, results = listOf(
                RestaurantOpsSearchHit("a", "AAA11111", "Evandro", "Evandro", "ABERTA", mesa_id = "m1", mesa_numero = 20),
                RestaurantOpsSearchHit("b", "BBB22222", "Evandro", "Evandro", "ABERTA", mesa_id = "m2", mesa_numero = 7)
            ))
        )
        val results = repository.search("token", "Evandro").getOrThrow()
        assertEquals(2, results.size)
        assertEquals(listOf("AAA11111", "BBB22222"), results.map { it.controlCode })
    }

    @Test fun standaloneNameSearchIncludesNullMesaOpenComanda() = runBlocking {
        whenever(api.getComandasList(any(), anyOrNull(), eq("none"))).thenReturn(
            ComandasListResponse(total = 1, comandas = listOf(
                ComandasListResponse.ComandaListItem("standalone-id", null, "EM_CONSUMO", null, "Evandro", null)
            ))
        )
        whenever(api.searchRestaurantOps(any(), any(), any(), any())).thenReturn(RestaurantOpsSearchResponse())
        val result = repository.search("token", " evandro ").getOrThrow()
        assertEquals(listOf("standalone-id"), result.map { it.comandaId })
        assertNull(result.single().physicalMesaId)
    }

    @Test fun incompleteStandaloneListFailsClosed() = runBlocking {
        whenever(api.getComandasList(any(), anyOrNull(), eq("none"))).thenReturn(
            ComandasListResponse(total = 2, comandas = listOf(
                ComandasListResponse.ComandaListItem("one", null, "ABERTA", null, "Evandro", null)
            ))
        )
        assertTrue(repository.search("token", "Evandro").isFailure)
    }
}
