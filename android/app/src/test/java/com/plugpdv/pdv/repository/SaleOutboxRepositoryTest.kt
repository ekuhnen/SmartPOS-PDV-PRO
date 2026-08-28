package com.plugpdv.pdv.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.LocalSaleEntity
import com.plugpdv.pdv.models.SaleItem
import com.plugpdv.pdv.models.SaleRequest
import com.plugpdv.pdv.models.SaleResponse
import com.plugpdv.pdv.utils.Constants
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.PaymentAttemptDao

@RunWith(RobolectricTestRunner::class)
class SaleOutboxRepositoryTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var fakeLocalSaleDao: FakeLocalSaleDao
    private lateinit var apiService: PosApiService
    private lateinit var repository: SaleOutboxRepository
    private lateinit var appDatabase: AppDatabase
    private lateinit var paymentAttemptDao: PaymentAttemptDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sharedPreferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        sharedPreferences.edit().putString(Constants.TOKEN, "valid_test_token").apply()

        appDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        paymentAttemptDao = appDatabase.paymentAttemptDao()
        fakeLocalSaleDao = FakeLocalSaleDao()
        apiService = mock()

        repository = SaleOutboxRepository(context, fakeLocalSaleDao, apiService, appDatabase, paymentAttemptDao)
    }

    @Test
    fun testEnqueueSale_persistsAsPendingWithImmutablePayloadAndIdempotencyKeyUsedTrue() {
        runBlocking {
            val saleRequest = SaleRequest(
                customerName = "Consumidor Final",
                total = 150.0,
                items = listOf(SaleItem(productId = "p1", productName = "Produto Teste", quantity = 2, price = 75.0)),
                paymentMethod = "MONEY",
                currency = "BRL",
                caixa_session_id = "session_123",
                operatorId = "op_1",
                operatorName = "Operador 1"
            )

            val entity = repository.enqueueSale(saleRequest, "BRL", "local_uuid_1")

            assertEquals("local_uuid_1", entity.localId)
            assertEquals(LocalSaleEntity.STATUS_PENDING, entity.syncStatus)
            assertTrue(entity.idempotencyKeyUsed)
            assertEquals(150.0, entity.total, 0.001)

            val saved = fakeLocalSaleDao.sales.firstOrNull { it.localId == "local_uuid_1" }
            assertTrue(saved != null)
            assertEquals(LocalSaleEntity.STATUS_PENDING, saved!!.syncStatus)
            assertTrue(saved.idempotencyKeyUsed)
        }
    }

    @Test
    fun testProcessOutboxBatch_deserializesSaleIdToApiIdAndMarksSynced() {
        runBlocking {
            val saleRequest = SaleRequest(
                customerName = "Consumidor Final",
                total = 100.0,
                items = emptyList(),
                paymentMethod = "CARD",
                currency = "BRL",
                caixa_session_id = "sess_1"
            )

            val pendingSale = LocalSaleEntity(
                localId = "local_100",
                createdAt = 1000L,
                updatedAt = 1000L,
                total = 100.0,
                currency = "BRL",
                paymentMethod = "CARD",
                itemsJson = "[]",
                payloadJson = Gson().toJson(saleRequest),
                syncStatus = LocalSaleEntity.STATUS_PENDING,
                idempotencyKeyUsed = true
            )

            fakeLocalSaleDao.insert(pendingSale)

            // Backend retorna { "sale_id": "api_sale_999" }
            whenever(apiService.registerSale(eq("Bearer valid_test_token"), eq("local_100"), any()))
                .thenReturn(SaleResponse(id = null, saleId = "api_sale_999", status = "SUCCESS"))

            val result = repository.processOutboxBatch()

            assertEquals(1, result.processedCount)
            val updated = fakeLocalSaleDao.sales.first { it.localId == "local_100" }
            assertEquals(LocalSaleEntity.STATUS_SYNCED, updated.syncStatus)
            assertEquals("api_sale_999", updated.apiId)
            assertTrue(updated.syncedToApi)
        }
    }

    @Test
    fun testProcessOutboxBatch_legacyStaleSyncingRecoveryToUnknownZeroPost() {
        runBlocking {
            val legacyStaleSale = LocalSaleEntity(
                localId = "legacy_stale_1",
                createdAt = 1000L,
                updatedAt = 1000L,
                total = 80.0,
                currency = "BRL",
                paymentMethod = "MONEY",
                itemsJson = "[]",
                payloadJson = "{}",
                syncStatus = LocalSaleEntity.STATUS_SYNCING,
                idempotencyKeyUsed = false // Legado
            )

            fakeLocalSaleDao.insert(legacyStaleSale)

            val result = repository.processOutboxBatch()

            assertEquals(0, result.processedCount)
            val updated = fakeLocalSaleDao.sales.first { it.localId == "legacy_stale_1" }
            assertEquals(LocalSaleEntity.STATUS_UNKNOWN, updated.syncStatus)
            verify(apiService, never()).registerSale(any(), any(), any())
        }
    }

    @Test
    fun testProcessOutboxBatch_keyedStaleSyncingRecoveryToPendingAndRetries() {
        runBlocking {
            val keyedStaleSale = LocalSaleEntity(
                localId = "keyed_stale_1",
                createdAt = 1000L,
                updatedAt = 1000L,
                total = 80.0,
                currency = "BRL",
                paymentMethod = "MONEY",
                itemsJson = "[]",
                payloadJson = "{}",
                syncStatus = LocalSaleEntity.STATUS_SYNCING,
                idempotencyKeyUsed = true // Keyed
            )

            fakeLocalSaleDao.insert(keyedStaleSale)

            whenever(apiService.registerSale(any(), eq("keyed_stale_1"), any()))
                .thenReturn(SaleResponse(id = "api_recovered_1", status = "SUCCESS"))

            val result = repository.processOutboxBatch()

            assertEquals(1, result.processedCount)
            val updated = fakeLocalSaleDao.sales.first { it.localId == "keyed_stale_1" }
            assertEquals(LocalSaleEntity.STATUS_SYNCED, updated.syncStatus)
            assertEquals("api_recovered_1", updated.apiId)
        }
    }

    @Test
    fun testProcessOutboxBatch_legacyPendingNeverAttemptedActivatesIdempotencyKey() {
        runBlocking {
            val legacyNeverAttemptedSale = LocalSaleEntity(
                localId = "legacy_pending_1",
                createdAt = 1000L,
                updatedAt = 1000L,
                total = 50.0,
                currency = "BRL",
                paymentMethod = "MONEY",
                itemsJson = "[]",
                payloadJson = "{}",
                syncStatus = LocalSaleEntity.STATUS_PENDING,
                attemptCount = 0,
                lastAttemptAt = null,
                idempotencyKeyUsed = false // Legado ainda não ativado
            )

            fakeLocalSaleDao.insert(legacyNeverAttemptedSale)

            whenever(apiService.registerSale(any(), eq("legacy_pending_1"), any()))
                .thenReturn(SaleResponse(id = "api_legacy_1", status = "SUCCESS"))

            val result = repository.processOutboxBatch()

            assertEquals(1, result.processedCount)
            val updated = fakeLocalSaleDao.sales.first { it.localId == "legacy_pending_1" }
            assertEquals(LocalSaleEntity.STATUS_SYNCED, updated.syncStatus)
            assertTrue(updated.idempotencyKeyUsed) // Ativou a chave antes do POST!
        }
    }

    @Test
    fun testProcessOutboxBatch_409OperationInProgressInterruptsBatch() {
        runBlocking {
            val pendingSale = LocalSaleEntity(
                localId = "local_in_progress",
                createdAt = 1000L,
                updatedAt = 1000L,
                total = 50.0,
                currency = "BRL",
                paymentMethod = "MONEY",
                itemsJson = "[]",
                payloadJson = "{}",
                syncStatus = LocalSaleEntity.STATUS_PENDING,
                idempotencyKeyUsed = true
            )

            fakeLocalSaleDao.insert(pendingSale)

            val errorResponse = Response.error<SaleResponse>(409, "{\"code\":\"OPERATION_IN_PROGRESS\"}".toResponseBody(null))
            whenever(apiService.registerSale(any(), eq("local_in_progress"), any()))
                .thenAnswer { throw HttpException(errorResponse) }

            repository.processOutboxBatch()

            val updated = fakeLocalSaleDao.sales.first { it.localId == "local_in_progress" }
            assertEquals(LocalSaleEntity.STATUS_PENDING, updated.syncStatus)
            assertTrue(updated.lastError?.contains("OPERATION_IN_PROGRESS") == true)
        }
    }
}
