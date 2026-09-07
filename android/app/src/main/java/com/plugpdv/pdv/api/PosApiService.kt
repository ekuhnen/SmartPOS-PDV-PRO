package com.plugpdv.pdv.api

import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.api.dto.DashboardReportDto
import retrofit2.Response
import retrofit2.http.*

interface PosApiService {
    @GET("api-relatorios")
    suspend fun getDashboardReport(
        @Header("Authorization") token: String,
        @Query("period") period: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("branch_id") branchId: String? = null,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): DashboardReportDto

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
        @Body sale: SaleCreateRequest
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

    @GET("api-caixa")
    suspend fun getCurrentCashSession(
        @Header("Authorization") token: String,
        @Query("current_session") currentSession: String = "1",
        @Query("terminal_id") terminalId: String? = null
    ): CashSessionResponse

    @POST("api-caixa")
    suspend fun operateCashier(
        @Header("Authorization") token: String,
        @Header("Idempotency-Key") idempotencyKey: String?,
        @Body request: CashierRequest
    ): Response<CashActionResponse>

    @POST("api-cambio")
    suspend fun getExchangeRates(
        @Header("Authorization") token: String,
        @Body request: ExchangeRequest
    ): ExchangeResponse

    @GET("api-mesas")
    suspend fun getMesas(@Header("Authorization") token: String): RestaurantResponse

    @GET("api-restaurant-ops")
    suspend fun getRestaurantOpsTables(
        @Header("Authorization") token: String,
        @Query("view") view: String = "tables",
        @Query("mesa_id") mesaId: String? = null
    ): RestaurantOpsTablesResponse

    @GET("api-restaurant-ops")
    suspend fun searchRestaurantOps(
        @Header("Authorization") token: String,
        @Query("view") view: String = "search",
        @Query("q") query: String,
        @Query("limit") limit: Int = 100
    ): RestaurantOpsSearchResponse

    @POST("api-comandas")
    suspend fun manageComanda(
        @Header("Authorization") token: String,
        @Body request: CommandActionRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null
    ): Response<Map<String, Any>>

    @POST("api-comandas")
    suspend fun setComandaServiceFee(
        @Header("Authorization") token: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CommandActionRequest
    ): Response<SetComandaServiceFeeResponse>

    @POST("api-comandas")
    suspend fun commitComandaCheckout(
        @Header("Authorization") token: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CommandCheckoutCommitRequest
    ): Response<ComandaCheckoutCommitResponse>

    @POST("api-comandas")
    suspend fun quoteComandaItems(
        @Header("Authorization") token: String,
        @Body request: PaymentQuoteRequest
    ): PaymentQuoteResponse

    @GET("api-comandas")
    suspend fun getComandaDetail(
        @Header("Authorization") token: String,
        @Query("id") id: String
    ): ComandaDetailResponse

    /** Payment allocation state only; never use this response as the Mesa item source. */
    @GET("api-comandas")
    suspend fun getComandaPaymentState(
        @Header("Authorization") token: String,
        @Query("recibo") comandaId: String
    ): ComandaPaymentStateResponse?

    @GET("api-comandas")
    suspend fun getComandaReceipt(
        @Header("Authorization") token: String,
        @Query("recibo") comandaId: String
    ): ComandaReceiptResponse

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
