package com.plugpdv.pdv.api

import com.plugpdv.pdv.models.*
import retrofit2.Response
import retrofit2.http.*

interface PosApiService {
    @POST("auth-login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @GET("user-permissions")
    suspend fun getPermissions(@Header("Authorization") token: String): UserPermissions

    @GET("api-catalogs")
    suspend fun getCatalogs(@Header("Authorization") token: String): CatalogResponse

    @POST("api-vendas")
    suspend fun registerSale(
        @Header("Authorization") token: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body sale: SaleRequest
    ): SaleResponse

    @GET("api-vendas")
    suspend fun getSales(
        @Header("Authorization") token: String,
        @Query("caixa_session_id") sessionId: String?
    ): SalesHistoryResponse

    @GET("api-caixa")
    suspend fun getCashierHistory(
        @Header("Authorization") token: String,
        @Query("date") date: String?
    ): CashierHistoryResponse

    @POST("api-caixa")
    suspend fun operateCashier(
        @Header("Authorization") token: String,
        @Body request: CashierRequest
    ): Response<Void>

    @POST("api-cambio")
    suspend fun getExchangeRates(
        @Header("Authorization") token: String,
        @Body request: ExchangeRequest
    ): ExchangeResponse

    @GET("api-mesas")
    suspend fun getMesas(@Header("Authorization") token: String): RestaurantResponse

    @POST("api-comandas")
    suspend fun manageComanda(
        @Header("Authorization") token: String,
        @Body request: CommandActionRequest
    ): Response<Map<String, Any>>

    @GET("api-comandas")
    suspend fun getComandaDetail(
        @Header("Authorization") token: String,
        @Query("id") id: String
    ): ComandaDetailResponse

    @GET("api-comandas")
    suspend fun getComandasList(
        @Header("Authorization") token: String,
        @Query("status") status: String? = null
    ): ComandasListResponse

    @GET("api-taxes")
    suspend fun getTaxes(@Header("Authorization") token: String): TaxResponse

    @POST("api/v2/terminal/sync_batch")
    suspend fun syncBatch(
        @Header("Authorization") token: String,
        @Body request: SyncBatchRequest
    ): Response<SyncBatchResponse>

    @GET("api/v2/terminal/capabilities")
    suspend fun getCapabilities(
        @Header("Authorization") token: String
    ): CapabilitiesResponse
}
