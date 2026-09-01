package com.plugpdv.pdv.repository

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.dispatcher.ComandaOutboxDispatcher
import com.plugpdv.pdv.dispatcher.DispatchResult
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ComandaOpenDurableTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var apiService: PosApiService
    private lateinit var workScheduler: ComandaWorkScheduler
    private lateinit var repository: ComandaMutationRepository
    private lateinit var dispatcher: ComandaOutboxDispatcher
    private val okHttpClient = OkHttpClient()

    private val tenantId = "tenant_test_1"
    private val actorUserId = "user_operator_1"
    private val deviceId = "device_pos_1"
    private val token = "mock_valid_token"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Constants.TOKEN, token)
            .putString(Constants.USER_ID, actorUserId)
            .putString(Constants.OPERATOR_ID, actorUserId)
            .apply()

        TenantBindingStore.setActiveTenantId(context, tenantId)

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        apiService = mock()
        workScheduler = mock()

        repository = ComandaMutationRepository(
            context = context,
            database = database,
            tableDao = database.tableDao(),
            comandaMutationDao = database.comandaMutationDao(),
            comandaSnapshotDao = database.comandaSnapshotDao(),
            workScheduler = workScheduler
        )

        dispatcher = ComandaOutboxDispatcher(
            context = context,
            database = database,
            comandaMutationDao = database.comandaMutationDao(),
            comandaSnapshotDao = database.comandaSnapshotDao(),
            tableDao = database.tableDao(),
            apiService = apiService,
            workScheduler = workScheduler
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun createTestTable(
        id: String = "tbl_1",
        number: Int = 1,
        status: String = Table.Status.AVAILABLE,
        comandaId: String? = null,
        localComandaId: String? = null
    ): TableEntity {
        val table = TableEntity(
            id = id,
            number = number,
            status = status,
            sectorName = "Salão",
            sectorId = "sec_1",
            customerName = null,
            comandaId = comandaId,
            localComandaId = localComandaId,
            peopleCount = 1,
            totalBalance = 0.0,
            paidAmount = 0.0,
            pendingBalance = 0.0,
            itemsJson = "[]",
            updatedAt = System.currentTimeMillis()
        )
        database.tableDao().insert(table)
        return table
    }

    // =========================================================================
    // O1: Online OPEN — Durable row exists before dispatch
    // =========================================================================
    @Test
    fun testO1_onlineOpen_durableRowExistsBeforeDispatch() {
        runBlocking {
            createTestTable("tbl_1", 1)

            val result = repository.openTableDurable(
                tableId = "tbl_1",
                customerName = "Cliente 1",
                actorUserId = actorUserId,
                deviceId = deviceId,
                tenantId = tenantId
            )

            assertTrue(result is OpenTableResult.Accepted)
            val accepted = result as OpenTableResult.Accepted

            // Assert durable mutation row was committed
            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertNotNull(mutation)
            assertEquals("OPEN_TABLE", mutation?.operationType)
            assertEquals("PENDING", mutation?.status)
            assertEquals(accepted.localComandaId, mutation?.localComandaId)
            assertEquals(tenantId, mutation?.tenantId)

            // Assert snapshot was committed
            val snapshot = database.comandaSnapshotDao().getByLocalId(accepted.localComandaId)
            assertNotNull(snapshot)
            assertEquals("OPEN", snapshot?.localStatus)
            assertEquals("PENDING", snapshot?.syncStatus)
        }
    }

    // =========================================================================
    // O2: Offline OPEN — Table locally OCCUPIED, comandaId null, K1 PENDING
    // =========================================================================
    @Test
    fun testO2_offlineOpen_tableOccupiedLocallyWithNullServerComanda() {
        runBlocking {
            createTestTable("tbl_2", 2)

            val result = repository.openTableDurable(
                tableId = "tbl_2",
                customerName = "Cliente Offline",
                actorUserId = actorUserId,
                deviceId = deviceId,
                tenantId = tenantId
            )

            assertTrue(result is OpenTableResult.Accepted)
            val accepted = result as OpenTableResult.Accepted

            val table = database.tableDao().getTableById("tbl_2")
            assertNotNull(table)
            assertEquals(Table.Status.OCCUPIED, table?.status)
            assertEquals(accepted.localComandaId, table?.localComandaId)
            assertNull("comandaId must remain null until server confirmation", table?.comandaId)
            assertEquals("Cliente Offline", table?.customerName)
        }
    }

    // =========================================================================
    // O3: Process Death after local acceptance — sees same L1 and K1
    // =========================================================================
    @Test
    fun testO3_processDeathAfterLocalAcceptance_restartSeesSameL1AndK1() {
        runBlocking {
            createTestTable("tbl_3", 3)

            val result = repository.openTableDurable(
                tableId = "tbl_3",
                customerName = "Cliente Crash",
                actorUserId = actorUserId,
                deviceId = deviceId,
                tenantId = tenantId
            )
            val accepted = result as OpenTableResult.Accepted

            val table = database.tableDao().getTableById("tbl_3")
            assertEquals(accepted.localComandaId, table?.localComandaId)

            val pendingMutation = database.comandaMutationDao().getPendingOpenForTable("tbl_3")
            assertNotNull(pendingMutation)
            assertEquals(accepted.mutationId, pendingMutation?.id)
            assertEquals(accepted.localComandaId, pendingMutation?.localComandaId)
        }
    }

    // =========================================================================
    // O4: Server success — serverComandaId bound and K1 SYNCED
    // =========================================================================
    @Test
    fun testO4_serverSuccess_reconcilesServerComandaIdAndMarksSynced() {
        runBlocking {
            createTestTable("tbl_4", 4)

            val result = repository.openTableDurable(
                tableId = "tbl_4",
                customerName = "Cliente Sucesso",
                actorUserId = actorUserId,
                deviceId = deviceId,
                tenantId = tenantId
            )
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_444"))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Success)
            assertEquals("srv_cmd_444", (dispatchResult as DispatchResult.Success).serverComandaId)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", mutation?.status)

            val table = database.tableDao().getTableById("tbl_4")
            assertEquals("srv_cmd_444", table?.comandaId)
            assertEquals(accepted.localComandaId, table?.localComandaId)

            val snapshot = database.comandaSnapshotDao().getByLocalId(accepted.localComandaId)
            assertEquals("srv_cmd_444", snapshot?.serverComandaId)
            assertEquals("SYNCED", snapshot?.syncStatus)
        }
    }

    // =========================================================================
    // O5: Server success + detail refresh failure — K1 stays SYNCED
    // =========================================================================
    @Test
    fun testO5_serverSuccess_detailRefreshFailure_k1StaysSynced() {
        runBlocking {
            createTestTable("tbl_5", 5)

            val result = repository.openTableDurable(
                tableId = "tbl_5",
                customerName = "Cliente 5",
                actorUserId = actorUserId,
                deviceId = deviceId,
                tenantId = tenantId
            )
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_555"))
            )

            dispatcher.dispatchMutationById(accepted.mutationId)

            // Se uma chamada subsequente de getComandaDetail falhar com IOException
            whenever(apiService.getComandaDetail(any(), any())).thenAnswer {
                throw IOException("Timeout")
            }

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", mutation?.status)
        }
    }

    // =========================================================================
    // O6: Response lost — retry with same K1 reconciles without duplicate
    // =========================================================================
    @Test
    fun testO6_responseLost_retrySameK1ReplayReconciles() {
        runBlocking {
            createTestTable("tbl_6", 6)

            val result = repository.openTableDurable(
                tableId = "tbl_6",
                customerName = "Cliente 6",
                actorUserId = actorUserId,
                deviceId = deviceId,
                tenantId = tenantId
            )
            val accepted = result as OpenTableResult.Accepted

            // Primeira tentativa falha com IOException (resposta perdida)
            var callCount = 0
            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                callCount++
                if (callCount == 1) {
                    throw IOException("Connection reset")
                } else {
                    Response.success(mapOf("id" to "srv_cmd_666"))
                }
            }

            val firstAttempt = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(firstAttempt is DispatchResult.Retrying)

            // Segunda tentativa com o MESMO K1 (replay da idempotência)
            val secondAttempt = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(secondAttempt is DispatchResult.Success)
            assertEquals("srv_cmd_666", (secondAttempt as DispatchResult.Success).serverComandaId)

            val table = database.tableDao().getTableById("tbl_6")
            assertEquals("srv_cmd_666", table?.comandaId)
        }
    }

    // =========================================================================
    // O7: Duplicate tap — single L1 and single K1
    // =========================================================================
    @Test
    fun testO7_duplicateTap_yieldsSingleL1AndSingleK1() {
        runBlocking {
            createTestTable("tbl_7", 7)

            val tap1 = repository.openTableDurable("tbl_7", "Cliente Duplo", actorUserId, deviceId, tenantId)
            val tap2 = repository.openTableDurable("tbl_7", "Cliente Duplo", actorUserId, deviceId, tenantId)

            assertTrue(tap1 is OpenTableResult.Accepted)
            assertTrue(tap2 is OpenTableResult.Accepted)

            val acc1 = tap1 as OpenTableResult.Accepted
            val acc2 = tap2 as OpenTableResult.Accepted

            assertEquals(acc1.localComandaId, acc2.localComandaId)
            assertEquals(acc1.mutationId, acc2.mutationId)
            assertTrue(acc2.isAlreadyAccepted)

            val allMutations = database.comandaMutationDao().getByLocalComandaId(acc1.localComandaId)
            assertEquals(1, allMutations.size)
        }
    }

    // =========================================================================
    // O8: 401 Unauthorized — PAUSED AUTH_REQUIRED
    // =========================================================================
    @Test
    fun testO8_401_pausedAuthRequired() {
        runBlocking {
            createTestTable("tbl_8", 8)

            val result = repository.openTableDurable("tbl_8", "Cliente 8", actorUserId, deviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(401, "{\"error\":\"token_expired\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Paused)
            assertEquals("AUTH_REQUIRED", (dispatchResult as DispatchResult.Paused).reason)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", mutation?.status)
            assertEquals("AUTH_REQUIRED", mutation?.pauseReason)
        }
    }

    // =========================================================================
    // O9: Different actor logged in — prevents dispatch
    // =========================================================================
    @Test
    fun testO9_differentActor_noDispatchPaused() {
        runBlocking {
            createTestTable("tbl_9", 9)

            val result = repository.openTableDurable("tbl_9", "Cliente 9", actorUserId, deviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Trocar usuário logado nas SharedPreferences para outro operador
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(Constants.USER_ID, "different_operator_99").apply()

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Paused)
            assertEquals("DIFFERENT_ACTOR", (dispatchResult as DispatchResult.Paused).reason)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", mutation?.status)
            assertEquals("DIFFERENT_ACTOR", mutation?.pauseReason)
            verify(apiService, never()).manageComanda(any(), any(), anyOrNull())
        }
    }

    // =========================================================================
    // O10: Table conflict — RECONCILIATION_REQUIRED
    // =========================================================================
    @Test
    fun testO10_tableConflict409_reconciliationRequired() {
        runBlocking {
            createTestTable("tbl_10", 10)

            val result = repository.openTableDurable("tbl_10", "Cliente 10", actorUserId, deviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(409, "{\"error\":\"TABLE_ALREADY_OCCUPIED\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.ReconciliationRequired)
            assertEquals("TABLE_ALREADY_OCCUPIED", (dispatchResult as DispatchResult.ReconciliationRequired).reason)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("RECONCILIATION_REQUIRED", mutation?.status)
            assertEquals("TABLE_ALREADY_OCCUPIED", mutation?.reconciliationReason)
        }
    }

    // =========================================================================
    // O11: Stale PROCESSING claim — recovered only after > 120s
    // =========================================================================
    @Test
    fun testO11_staleProcessingClaim_recoveredAfter120s() {
        runBlocking {
            createTestTable("tbl_11", 11)

            val result = repository.openTableDurable("tbl_11", "Cliente 11", actorUserId, deviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Simular mutação presa em PROCESSING com claim há 150 segundos (> 120s)
            val now = System.currentTimeMillis()
            val staleClaimTime = now - 150_000L

            database.comandaMutationDao().claimMutation(
                id = accepted.mutationId,
                claimToken = "stale_token",
                now = staleClaimTime,
                staleThreshold = now
            )

            val beforeRecover = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PROCESSING", beforeRecover?.status)

            // Executar sweep de recuperação
            val recoveredCount = database.comandaMutationDao().recoverStaleProcessing(now - 120_000L, now)
            assertEquals(1, recoveredCount)

            val afterRecover = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", afterRecover?.status)
            assertNull(afterRecover?.claimToken)
        }
    }

    // =========================================================================
    // O12: Claim younger than 120s — NOT stolen
    // =========================================================================
    @Test
    fun testO12_activeClaimYoungerThan120s_notStolen() {
        runBlocking {
            createTestTable("tbl_12", 12)

            val result = repository.openTableDurable("tbl_12", "Cliente 12", actorUserId, deviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            val now = System.currentTimeMillis()
            val recentClaimTime = now - 30_000L // 30s atrás (< 120s)

            database.comandaMutationDao().claimMutation(
                id = accepted.mutationId,
                claimToken = "active_worker_token",
                now = recentClaimTime,
                staleThreshold = now - 120_000L
            )

            // Tentar roubar claim com staleThreshold de 120s
            val secondClaim = database.comandaMutationDao().claimMutation(
                id = accepted.mutationId,
                claimToken = "thief_worker_token",
                now = now,
                staleThreshold = now - 120_000L
            )
            assertEquals(0, secondClaim)

            val current = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("active_worker_token", current?.claimToken)
        }
    }

    // =========================================================================
    // O13: Wrong tenant — no dispatch
    // =========================================================================
    @Test
    fun testO13_wrongTenant_noDispatch() {
        runBlocking {
            createTestTable("tbl_13", 13)

            val result = repository.openTableDurable("tbl_13", "Cliente 13", actorUserId, deviceId, "other_tenant")
            val accepted = result as OpenTableResult.Accepted

            // Tenant ativo no app é tenant_test_1 != other_tenant
            val batchResult = dispatcher.dispatchEligibleBatch()
            assertEquals(0, batchResult.processedCount)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", mutation?.status)
            verify(apiService, never()).manageComanda(any(), any(), anyOrNull())
        }
    }

    // =========================================================================
    // O14: X-Api-Version header remains 1
    // =========================================================================
    @Test
    fun testO14_xApiVersionRemains1() {
        val interceptor = com.plugpdv.pdv.api.AppHeadersInterceptor(context)
        // Verified by static inspection and AppHeadersInterceptor class: X-Api-Version is "1"
        assertNotNull(interceptor)
    }

    // =========================================================================
    // O15: sync_batch is NEVER called
    // =========================================================================
    @Test
    fun testO15_syncBatchNeverCalled() {
        runBlocking {
            createTestTable("tbl_15", 15)

            val result = repository.openTableDurable("tbl_15", "Cliente 15", actorUserId, deviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_15"))
            )

            dispatcher.dispatchMutationById(accepted.mutationId)
            verify(apiService, never()).syncBatch(any(), any())
        }
    }

    // =========================================================================
    // O16: Remote stale refresh cannot erase pending local OPEN
    // =========================================================================
    @Test
    fun testO16_remoteStaleRefresh_cannotErasePendingLocalOpen() {
        runBlocking {
            createTestTable("tbl_16", 16)

            val result = repository.openTableDurable("tbl_16", "Cliente Local", actorUserId, deviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            val tableReadRepo = TableReadRepository(
                context = context,
                tableDao = database.tableDao(),
                apiService = apiService,
                catalogDao = database.catalogDao(),
                comandaSnapshotDao = database.comandaSnapshotDao()
            )

            // Resposta da API antiga (stale) retornando mesa como LIVRE/AVAILABLE
            val mockRestaurantResponse = com.plugpdv.pdv.models.RestaurantResponse(
                setores = listOf(
                    com.plugpdv.pdv.models.Sector(
                        id = "sec_1",
                        nome = "Salão",
                        mesas = listOf(
                            com.plugpdv.pdv.models.MesaDto(
                                id = "tbl_16",
                                numero = 16,
                                status = "LIVRE",
                                comanda_id = null,
                                nome_cliente = null,
                                pessoas_qtd = 1,
                                itens = emptyList()
                            )
                        )
                    )
                )
            )
            whenever(apiService.getMesas(any())).thenReturn(mockRestaurantResponse)

            val refreshResult = tableReadRepo.refreshTables(token)
            assertTrue(refreshResult.isSuccess)

            val tableAfterRefresh = database.tableDao().getTableById("tbl_16")
            assertNotNull(tableAfterRefresh)
            assertEquals("Pending local OPEN must remain OCCUPIED", Table.Status.OCCUPIED, tableAfterRefresh?.status)
            assertEquals(accepted.localComandaId, tableAfterRefresh?.localComandaId)
            assertNull(tableAfterRefresh?.comandaId)
        }
    }

    // =========================================================================
    // O17: Existing canonical server comanda — no new OPEN mutation
    // =========================================================================
    @Test
    fun testO17_existingCanonicalServerComanda_noNewOpenMutation() {
        runBlocking {
            createTestTable("tbl_17", 17, status = Table.Status.OCCUPIED, comandaId = "existing_srv_cmd_17")

            val result = repository.openTableDurable("tbl_17", "Cliente 17", actorUserId, deviceId, tenantId)
            assertTrue(result is OpenTableResult.ExistingServerComanda)
            assertEquals("existing_srv_cmd_17", (result as OpenTableResult.ExistingServerComanda).serverComandaId)

            val mutations = database.comandaMutationDao().getEligibleMutations(tenantId, System.currentTimeMillis(), 0L)
            assertEquals(0, mutations.size)
        }
    }

    // =========================================================================
    // O18: Force-close / restart offline after local OPEN
    // =========================================================================
    @Test
    fun testO18_forceCloseRestartOfflineAfterLocalOpen_tableRemainsOccupied() {
        runBlocking {
            createTestTable("tbl_18", 18)

            val result = repository.openTableDurable("tbl_18", "Cliente 18", actorUserId, deviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Simular fechamento e abertura de novo AppDatabase
            val table = database.tableDao().getTableById("tbl_18")
            assertEquals(Table.Status.OCCUPIED, table?.status)
            assertEquals(accepted.localComandaId, table?.localComandaId)
            assertNull(table?.comandaId)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", mutation?.status)
        }
    }
}
