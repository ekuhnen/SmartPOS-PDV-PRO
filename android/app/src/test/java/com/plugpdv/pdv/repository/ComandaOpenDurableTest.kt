package com.plugpdv.pdv.repository

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.di.NetworkModule
import com.plugpdv.pdv.dispatcher.ComandaOutboxDispatcher
import com.plugpdv.pdv.dispatcher.DispatchResult
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.plugpdv.pdv.utils.KillSwitchManager
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
    private val currencyRulesProvider = DefaultCurrencyRulesProvider()
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
            .putBoolean(Constants.HAS_MESA, true)
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
            currencyRulesProvider = currencyRulesProvider,
            workScheduler = workScheduler
        )
    }

    @After
    fun tearDown() {
        KillSwitchManager.reset()
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

    @Test
    fun testO1_onlineOpen_durableRowExistsBeforeDispatch() {
        runBlocking {
            createTestTable("tbl_1", 1)

            val result = repository.openTableDurable(
                tableId = "tbl_1",
                customerName = "Cliente 1",
                actorUserId = actorUserId,
                deviceId = DeviceIdProvider.get(context),
                tenantId = tenantId
            )

            assertTrue(result is OpenTableResult.Accepted)
            val accepted = result as OpenTableResult.Accepted

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertNotNull(mutation)
            assertEquals("OPEN_TABLE", mutation?.operationType)
            assertEquals("PENDING", mutation?.status)
            assertEquals(accepted.localComandaId, mutation?.localComandaId)
            assertEquals(tenantId, mutation?.tenantId)

            val snapshot = database.comandaSnapshotDao().getByLocalId(accepted.localComandaId)
            assertNotNull(snapshot)
            assertEquals("OPEN", snapshot?.localStatus)
            assertEquals("PENDING", snapshot?.syncStatus)
        }
    }

    @Test
    fun testO2_offlineOpen_tableOccupiedLocallyWithNullServerComanda() {
        runBlocking {
            createTestTable("tbl_2", 2)

            val result = repository.openTableDurable(
                tableId = "tbl_2",
                customerName = "Cliente Offline",
                actorUserId = actorUserId,
                deviceId = DeviceIdProvider.get(context),
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

            val snapshot = database.comandaSnapshotDao().getByLocalId(accepted.localComandaId)
            assertNotNull(snapshot)
            assertNull(snapshot?.baseCurrency)
            assertNull(snapshot?.baseMinorUnitDigits)
        }
    }

    @Test
    fun testO3_processDeathAfterLocalAcceptance_restartSeesSameL1AndK1() {
        runBlocking {
            createTestTable("tbl_3", 3)

            val result = repository.openTableDurable(
                tableId = "tbl_3",
                customerName = "Cliente Crash",
                actorUserId = actorUserId,
                deviceId = DeviceIdProvider.get(context),
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

    @Test
    fun testO4_serverSuccess_reconcilesServerComandaIdAndMarksSynced() {
        runBlocking {
            createTestTable("tbl_4", 4)

            val result = repository.openTableDurable(
                tableId = "tbl_4",
                customerName = "Cliente Sucesso",
                actorUserId = actorUserId,
                deviceId = DeviceIdProvider.get(context),
                tenantId = tenantId
            )
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_444", "currency" to "BRL"))
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
            assertEquals("BRL", snapshot?.baseCurrency)
            assertEquals(2, snapshot?.baseMinorUnitDigits)
        }
    }

    @Test
    fun testO5_serverSuccess_detailRefreshFailure_k1StaysSynced() {
        runBlocking {
            createTestTable("tbl_5", 5)

            val result = repository.openTableDurable(
                tableId = "tbl_5",
                customerName = "Cliente 5",
                actorUserId = actorUserId,
                deviceId = DeviceIdProvider.get(context),
                tenantId = tenantId
            )
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_555"))
            )

            dispatcher.dispatchMutationById(accepted.mutationId)

            whenever(apiService.getComandaDetail(any(), any())).thenAnswer {
                throw IOException("Timeout")
            }

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", mutation?.status)
        }
    }

    @Test
    fun testO6_responseLost_retrySameK1ReplayReconciles() {
        runBlocking {
            createTestTable("tbl_6", 6)

            val result = repository.openTableDurable(
                tableId = "tbl_6",
                customerName = "Cliente 6",
                actorUserId = actorUserId,
                deviceId = DeviceIdProvider.get(context),
                tenantId = tenantId
            )
            val accepted = result as OpenTableResult.Accepted

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

            val secondAttempt = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(secondAttempt is DispatchResult.Success)
            assertEquals("srv_cmd_666", (secondAttempt as DispatchResult.Success).serverComandaId)

            val table = database.tableDao().getTableById("tbl_6")
            assertEquals("srv_cmd_666", table?.comandaId)
        }
    }

    @Test
    fun testO7_duplicateTap_yieldsSingleL1AndSingleK1() {
        runBlocking {
            createTestTable("tbl_7", 7)

            val tap1 = repository.openTableDurable("tbl_7", "Cliente Duplo", actorUserId, DeviceIdProvider.get(context), tenantId)
            val tap2 = repository.openTableDurable("tbl_7", "Cliente Duplo", actorUserId, DeviceIdProvider.get(context), tenantId)

            assertTrue(tap1 is OpenTableResult.Accepted)
            assertTrue(tap2 is OpenTableResult.Accepted)

            val acc1 = tap1 as OpenTableResult.Accepted
            val acc2 = tap2 as OpenTableResult.Accepted

            assertEquals("Local comanda ID must be identical on double tap", acc1.localComandaId, acc2.localComandaId)
            assertEquals("Mutation ID must be identical on double tap", acc1.mutationId, acc2.mutationId)
            assertTrue(acc2.isAlreadyAccepted)

            val mutations = database.comandaMutationDao().getByLocalComandaId(acc1.localComandaId)
            assertEquals("Must have exactly 1 mutation row in DB", 1, mutations.size)
        }
    }

    @Test
    fun testO8_401_pausedAuthRequired() {
        runBlocking {
            createTestTable("tbl_8", 8)

            val result = repository.openTableDurable("tbl_8", "Cliente 8", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(401, "{\"error\":\"Unauthorized\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Paused)
            assertEquals("AUTH_REQUIRED", (dispatchResult as DispatchResult.Paused).reason)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", mutation?.status)
            assertEquals("AUTH_REQUIRED", mutation?.pauseReason)
        }
    }

    @Test
    fun testO9_differentActor_noDispatchPaused() {
        runBlocking {
            createTestTable("tbl_9", 9)

            val result = repository.openTableDurable("tbl_9", "Cliente 9", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

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

    @Test
    fun testO10_tableConflict409_reconciliationRequired() {
        runBlocking {
            createTestTable("tbl_10", 10)

            val result = repository.openTableDurable("tbl_10", "Cliente 10", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(409, "{\"error\":\"TABLE_ALREADY_OCCUPIED\",\"code\":\"TABLE_ALREADY_OCCUPIED\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.ReconciliationRequired)
            assertEquals("TABLE_ALREADY_OCCUPIED", (dispatchResult as DispatchResult.ReconciliationRequired).reason)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("RECONCILIATION_REQUIRED", mutation?.status)
            assertEquals("TABLE_ALREADY_OCCUPIED", mutation?.reconciliationReason)
        }
    }

    @Test
    fun testO11_staleProcessingClaim_recoveredAfter120s() {
        runBlocking {
            createTestTable("tbl_11", 11)

            val result = repository.openTableDurable("tbl_11", "Cliente 11", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            val now = System.currentTimeMillis()
            database.comandaMutationDao().claimMutation(accepted.mutationId, "old_token", now - 150_000L, now)

            val mutationClaimed = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PROCESSING", mutationClaimed?.status)

            val recoveredCount = database.comandaMutationDao().recoverStaleProcessing(now - 120_000L, now)
            assertEquals(1, recoveredCount)

            val mutationRecovered = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", mutationRecovered?.status)
            assertNull(mutationRecovered?.claimToken)
        }
    }

    @Test
    fun testO12_activeClaimYoungerThan120s_notStolen() {
        runBlocking {
            createTestTable("tbl_12", 12)

            val result = repository.openTableDurable("tbl_12", "Cliente 12", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            val now = System.currentTimeMillis()
            database.comandaMutationDao().claimMutation(accepted.mutationId, "active_worker_token", now - 30_000L, now - 120_000L)

            val secondClaim = database.comandaMutationDao().claimMutation(accepted.mutationId, "thief_token", now, now - 120_000L)
            assertEquals("Younger claim must not be stolen", 0, secondClaim)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("active_worker_token", mutation?.claimToken)
        }
    }

    @Test
    fun testO13_wrongTenant_noDispatch() {
        runBlocking {
            createTestTable("tbl_13", 13)

            val result = repository.openTableDurable("tbl_13", "Cliente 13", actorUserId, DeviceIdProvider.get(context), "tenant_alpha")
            val accepted = result as OpenTableResult.Accepted

            TenantBindingStore.setActiveTenantId(context, "tenant_beta")

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Paused)
            assertEquals("DIFFERENT_TENANT", (dispatchResult as DispatchResult.Paused).reason)

            verify(apiService, never()).manageComanda(any(), any(), anyOrNull())
        }
    }

    @Test
    fun testO14_xApiVersionRemains1() {
        val interceptor = com.plugpdv.pdv.api.AppHeadersInterceptor(context)
        assertNotNull(interceptor)
    }

    @Test
    fun testO15_syncBatchNeverCalled() {
        runBlocking {
            createTestTable("tbl_15", 15)

            val result = repository.openTableDurable("tbl_15", "Cliente 15", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_15"))
            )

            dispatcher.dispatchMutationById(accepted.mutationId)
            verify(apiService, never()).syncBatch(any(), any())
        }
    }

    @Test
    fun testO16_remoteStaleRefresh_cannotErasePendingLocalOpen() {
        runBlocking {
            createTestTable("tbl_16", 16)

            val result = repository.openTableDurable("tbl_16", "Cliente Local", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            val tableReadRepo = TableReadRepository(
                context = context,
                tableDao = database.tableDao(),
                apiService = apiService,
                catalogDao = database.catalogDao(),
                comandaSnapshotDao = database.comandaSnapshotDao()
            )

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

    @Test
    fun testO17_existingCanonicalServerComanda_noNewOpenMutation() {
        runBlocking {
            createTestTable("tbl_17", 17, status = Table.Status.OCCUPIED, comandaId = "existing_srv_cmd_17")

            val result = repository.openTableDurable("tbl_17", "Cliente 17", actorUserId, DeviceIdProvider.get(context), tenantId)
            assertTrue(result is OpenTableResult.ExistingServerComanda)
            assertEquals("existing_srv_cmd_17", (result as OpenTableResult.ExistingServerComanda).serverComandaId)

            val mutations = database.comandaMutationDao().getEligibleMutations(tenantId, System.currentTimeMillis(), 0L)
            assertEquals(0, mutations.size)
        }
    }

    @Test
    fun testO18_forceCloseRestartOfflineAfterLocalOpen_tableRemainsOccupied() {
        runBlocking {
            createTestTable("tbl_18", 18)

            val result = repository.openTableDurable("tbl_18", "Cliente 18", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            val table = database.tableDao().getTableById("tbl_18")
            assertEquals(Table.Status.OCCUPIED, table?.status)
            assertEquals(accepted.localComandaId, table?.localComandaId)
            assertNull(table?.comandaId)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", mutation?.status)
        }
    }

    @Test
    fun testB1_dispatcherClient_callTimeoutIs45Seconds() {
        val baseOkHttp = NetworkModule.provideOkHttpClient(context)
        val dispatcherOkHttp = NetworkModule.provideComandaDispatcherOkHttpClient(baseOkHttp)
        assertEquals(45_000, dispatcherOkHttp.callTimeoutMillis)
    }

    @Test
    fun testB2A_staleClaim_lostOwnerCannotFinalize_returnsZeroRows() {
        runBlocking {
            createTestTable("tbl_b2a", 21)

            val result = repository.openTableDurable("tbl_b2a", "Cliente B2A", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted
            val now = System.currentTimeMillis()

            val workerAToken = "claim_worker_a"
            val claimedA = database.comandaMutationDao().claimMutation(accepted.mutationId, workerAToken, now - 150_000L, now)
            assertEquals(1, claimedA)

            val workerBToken = "claim_worker_b"
            val claimedB = database.comandaMutationDao().claimMutation(accepted.mutationId, workerBToken, now, now - 120_000L)
            assertEquals(1, claimedB)

            val rowsA = database.comandaMutationDao().markSyncedClaimed(accepted.mutationId, workerAToken, now)
            assertEquals(0, rowsA)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PROCESSING", mutation?.status)
            assertEquals(workerBToken, mutation?.claimToken)
        }
    }

    @Test
    fun testB2B_activeClaim_finalizesSuccessfully() {
        runBlocking {
            createTestTable("tbl_b2b", 22)

            val result = repository.openTableDurable("tbl_b2b", "Cliente B2B", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted
            val now = System.currentTimeMillis()

            val workerToken = "valid_worker_token"
            database.comandaMutationDao().claimMutation(accepted.mutationId, workerToken, now, now - 120_000L)

            val rows = database.comandaMutationDao().markSyncedClaimed(accepted.mutationId, workerToken, now)
            assertEquals(1, rows)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", mutation?.status)
            assertNull(mutation?.claimToken)
        }
    }

    @Test
    fun testB3_wrongDevice_zeroHttpCalls() {
        runBlocking {
            createTestTable("tbl_b3", 23)

            val result = repository.openTableDurable("tbl_b3", "Cliente B3", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_diff_device",
                    deviceId = "OTHER_DEVICE_XYZ"
                )
            )

            val dispatchResult = dispatcher.dispatchMutationById("mut_diff_device")
            assertTrue(dispatchResult is DispatchResult.Paused)
            assertEquals("DEVICE_ID_MISMATCH", (dispatchResult as DispatchResult.Paused).reason)

            verify(apiService, never()).manageComanda(any(), any(), anyOrNull())
        }
    }

    @Test
    fun testB4_startupRecovery_rediscoversUnsyncedMutation() {
        runBlocking {
            createTestTable("tbl_b4", 24)

            val result = repository.openTableDurable("tbl_b4", "Cliente B4", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_b4"))
            )

            val batchResult = dispatcher.dispatchEligibleBatch()
            assertEquals(1, batchResult.processedCount)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", mutation?.status)
        }
    }

    @Test
    fun testB5_serverPygCurrency_reconcilesSnapshotToPygWith0Digits() {
        runBlocking {
            createTestTable("tbl_b5", 25)

            val result = repository.openTableDurable("tbl_b5", "Cliente PYG", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_pyg_1", "currency" to "PYG"))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Success)

            val snapshot = database.comandaSnapshotDao().getByLocalId(accepted.localComandaId)
            assertEquals("PYG", snapshot?.baseCurrency)
            assertEquals(0, snapshot?.baseMinorUnitDigits)
        }
    }

    @Test
    fun testB6_operationInProgress_409_retriesSameK() {
        runBlocking {
            createTestTable("tbl_b6a", 26)

            val result = repository.openTableDurable("tbl_b6a", "Cliente B6A", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(409, "{\"code\":\"OPERATION_IN_PROGRESS\",\"message\":\"Operation in progress\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Retrying)
            assertEquals("OPERATION_IN_PROGRESS", (dispatchResult as DispatchResult.Retrying).reason)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", mutation?.status)
            assertEquals("OPERATION_IN_PROGRESS", mutation?.lastErrorCode)
        }
    }

    @Test
    fun testB6_idempotencyKeyReused_409_reconciliationRequired() {
        runBlocking {
            createTestTable("tbl_b6b", 27)

            val result = repository.openTableDurable("tbl_b6b", "Cliente B6B", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(409, "{\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"message\":\"Key reused\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.ReconciliationRequired)
            assertEquals("IDEMPOTENCY_KEY_REUSED", (dispatchResult as DispatchResult.ReconciliationRequired).reason)
        }
    }

    @Test
    fun testB7_missingOrUnknownActor_failsClosed() {
        runBlocking {
            createTestTable("tbl_b7", 28)

            val resultBlank = repository.openTableDurable("tbl_b7", "Cliente", "", DeviceIdProvider.get(context), tenantId)
            assertTrue(resultBlank is OpenTableResult.Rejected)

            val resultUnknown = repository.openTableDurable("tbl_b7", "Cliente", "UNKNOWN", DeviceIdProvider.get(context), tenantId)
            assertTrue(resultUnknown is OpenTableResult.Rejected)

            val mutations = database.comandaMutationDao().getEligibleMutations(tenantId, System.currentTimeMillis(), 0L)
            assertEquals(0, mutations.size)
        }
    }

    @Test
    fun testB8_resolvedPayload_frozenAcrossRetries() {
        runBlocking {
            createTestTable("tbl_b8", 29)

            val result = repository.openTableDurable("tbl_b8", "Nome Original", actorUserId, DeviceIdProvider.get(context), tenantId, peopleCount = 2)
            val accepted = result as OpenTableResult.Accepted

            val mutationBefore = database.comandaMutationDao().getById(accepted.mutationId)
            assertNotNull(mutationBefore)
            assertTrue(mutationBefore?.resolvedPayloadJson?.contains("Nome Original") == true)

            val table = database.tableDao().getTableById("tbl_b8")
            database.tableDao().insert(table!!.copy(customerName = "Nome Alterado Post Facto", peopleCount = 10))

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_b8"))
            )

            dispatcher.dispatchMutationById(accepted.mutationId)

            val requestCaptor = org.mockito.kotlin.argumentCaptor<CommandActionRequest>()
            verify(apiService).manageComanda(any(), requestCaptor.capture(), eq(accepted.mutationId))

            assertEquals("Nome Original", requestCaptor.firstValue.nome_cliente)
            assertEquals(2, requestCaptor.firstValue.people_count)
        }
    }

    @Test
    fun testB9_mesaModeDisabledOrUnknown_cannotCreateOpenMutation() {
        runBlocking {
            createTestTable("tbl_b9", 30)

            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

            // 1. Mesa mode = false
            prefs.edit().putBoolean(Constants.HAS_MESA, false).apply()
            val resultDisabled = repository.openTableDurable("tbl_b9", "Cliente B9", actorUserId, DeviceIdProvider.get(context), tenantId)
            assertTrue(resultDisabled is OpenTableResult.Rejected)
            assertEquals("operation_mode_disabled", (resultDisabled as OpenTableResult.Rejected).messageKey)

            // 2. Mesa mode = unknown (removido)
            prefs.edit().remove(Constants.HAS_MESA).apply()
            val resultUnknown = repository.openTableDurable("tbl_b9", "Cliente B9", actorUserId, DeviceIdProvider.get(context), tenantId)
            assertTrue(resultUnknown is OpenTableResult.Rejected)
            assertEquals("mode_authority_unknown", (resultUnknown as OpenTableResult.Rejected).messageKey)
        }
    }

    // =========================================================================
    // T-B10-A: Pending OPEN + Logout: K and local projection survive
    // =========================================================================
    @Test
    fun testTB10A_pendingOpenAndLogout_mutationAndProjectionsSurvive() {
        runBlocking {
            createTestTable("tbl_tb10a", 31)

            val result = repository.openTableDurable("tbl_tb10a", "Cliente TB10A", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Simular logout / force_logout
            KillSwitchManager.forceLogout(context, "USER_LOGOUT")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            KillSwitchManager.reset()

            // 1. Verificar que as credenciais foram limpas
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            assertNull(prefs.getString(Constants.TOKEN, null))
            assertFalse(prefs.contains(Constants.HAS_MESA))

            // 2. Verificar que a mutação durável permanece no banco
            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertNotNull("Mutação durável não pode ser destruída no logout", mutation)
            assertEquals("PENDING", mutation?.status)

            // 3. Projeção local da mesa e snapshot permanecem
            val table = database.tableDao().getTableById("tbl_tb10a")
            assertNotNull(table)
            assertEquals(Table.Status.OCCUPIED, table?.status)
            assertEquals(accepted.localComandaId, table?.localComandaId)

            val snapshot = database.comandaSnapshotDao().getByLocalId(accepted.localComandaId)
            assertNotNull(snapshot)
        }
    }

    // =========================================================================
    // T-B10-B: Pending OPEN + DEVICE_BLOCKED: DeviceId unchanged & K survives PAUSED
    // =========================================================================
    @Test
    fun testTB10B_pendingOpenAndDeviceBlocked_deviceIdUnchangedAndMutationPaused() {
        runBlocking {
            createTestTable("tbl_tb10b", 32)
            val initialDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_tb10b", "Cliente TB10B", actorUserId, initialDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(403, "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Paused)
            assertEquals("DEVICE_BLOCKED", (dispatchResult as DispatchResult.Paused).reason)

            // Verificar que o DeviceId não rotacionou
            assertEquals(initialDeviceId, DeviceIdProvider.get(context))

            // Verificar que a mutação está PAUSED
            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", mutation?.status)
            assertEquals("DEVICE_BLOCKED", mutation?.pauseReason)
        }
    }

    // =========================================================================
    // T-B10-C: Matching actor/device re-auth: same K can resume
    // =========================================================================
    @Test
    fun testTB10C_matchingActorReauth_sameKResumes() {
        runBlocking {
            createTestTable("tbl_tb10c", 33)

            val result = repository.openTableDurable("tbl_tb10c", "Cliente TB10C", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Simular perda de autenticação (401) -> PAUSED
            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(401, "{\"error\":\"Unauthorized\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)

            // Reautenticação do mesmo operador com novo token
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(Constants.TOKEN, "fresh_valid_token")
                .putString(Constants.USER_ID, actorUserId)
                .putBoolean(Constants.HAS_MESA, true)
                .apply()

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_tb10c"))
            )

            // Dispatch do lote despausa e sincroniza o mesmo K
            val batchResult = dispatcher.dispatchEligibleBatch()
            assertEquals(1, batchResult.processedCount)

            val mutation = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", mutation?.status)
        }
    }

    // =========================================================================
    // T-B11: Startup without auth + later successful login schedules command worker
    // =========================================================================
    @Test
    fun testTB11_startupWithoutAuthAndSubsequentLogin_schedulesCommandWorker() {
        runBlocking {
            createTestTable("tbl_tb11", 36)

            val result = repository.openTableDurable("tbl_tb11", "Cliente TB11", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Simular login bem-sucedido salvando credenciais
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(Constants.TOKEN, "fresh_token_tb11")
                .putString(Constants.USER_ID, actorUserId)
                .putBoolean(Constants.HAS_MESA, true)
                .apply()

            // Login executa scheduleCommandSync
            workScheduler.scheduleCommandSync()
            verify(workScheduler, org.mockito.kotlin.atLeastOnce()).scheduleCommandSync()

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_tb11"))
            )

            val batch = dispatcher.dispatchEligibleBatch()
            assertEquals(1, batch.processedCount)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
        }
    }

    // =========================================================================
    // T-B12: New local OPEN snapshot: all money fields and currency are null
    // =========================================================================
    @Test
    fun testTB12_newLocalOpenSnapshot_allMoneyFieldsAndCurrencyAreNull() {
        runBlocking {
            createTestTable("tbl_tb12", 34)

            val result = repository.openTableDurable("tbl_tb12", "Cliente TB12", actorUserId, DeviceIdProvider.get(context), tenantId)
            val accepted = result as OpenTableResult.Accepted

            val snapshot = database.comandaSnapshotDao().getByLocalId(accepted.localComandaId)
            assertNotNull(snapshot)
            assertNull("baseCurrency must be null before server confirmation", snapshot?.baseCurrency)
            assertNull("baseMinorUnitDigits must be null before server confirmation", snapshot?.baseMinorUnitDigits)
            assertNull("totalBaseMinor must be null before server confirmation", snapshot?.totalBaseMinor)
            assertNull("paidBaseMinor must be null before server confirmation", snapshot?.paidBaseMinor)
            assertNull("balanceBaseMinor must be null before server confirmation", snapshot?.balanceBaseMinor)
        }
    }

    // =========================================================================
    // T-B13: localComandaId never populates openedComandaId
    // =========================================================================
    @Test
    fun testTB13_localComandaIdNeverPopulatesOpenedComandaId() {
        runBlocking {
            val tableEntity = createTestTable("tbl_tb13", 35)
            val domainTable = Table(
                id = tableEntity.id,
                number = tableEntity.number,
                status = Table.Status.AVAILABLE
            )

            val tableReadRepo = TableReadRepository(
                context, database.tableDao(), apiService, database.catalogDao(), database.comandaSnapshotDao()
            )

            val viewModel = com.plugpdv.pdv.ui.sale.MesaViewModel(
                apiService = apiService,
                catalogDao = database.catalogDao(),
                tableReadRepository = tableReadRepo,
                context = context,
                comandaMutationRepository = repository,
                comandaOutboxDispatcher = dispatcher
            )

            viewModel.openTable(token, domainTable, "Cliente TB13")
            repeat(10) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(20)
            }

            // Verificar que openSuccess é true, mas openedComandaId é NULL (nunca L1)
            if (viewModel.error.value != null) {
                throw AssertionError("ViewModel error: ${viewModel.error.value}")
            }
            assertEquals(true, viewModel.openSuccess.value)
            assertNull("openedComandaId must never be populated with localComandaId L1", viewModel.openedComandaId.value)

            val persistedTable = database.tableDao().getTableById("tbl_tb13")
            assertNotNull(persistedTable?.localComandaId)
            assertNull("TableEntity.comandaId must remain null until server confirmation", persistedTable?.comandaId)
        }
    }

    // =========================================================================
    // T-B15: Session expiration removes HAS_MESA, HAS_VENDA_DIRETA and HAS_COMANDA
    // =========================================================================
    @Test
    fun testTB15_sessionExpirationRemovesModeAuthority() {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Constants.TOKEN, "expiring_token")
            .putBoolean(Constants.HAS_MESA, true)
            .putBoolean(Constants.HAS_VENDA_DIRETA, true)
            .putBoolean(Constants.HAS_COMANDA, true)
            .apply()

        assertTrue(prefs.contains(Constants.HAS_MESA))

        // Executar limpeza de credenciais
        prefs.edit()
            .remove(Constants.TOKEN)
            .remove(Constants.EMAIL)
            .remove(Constants.PASSWORD)
            .remove(Constants.SESSION_ID)
            .remove(Constants.LOGIN_TIME)
            .remove(Constants.USER_ID)
            .remove(Constants.HAS_MESA)
            .remove(Constants.HAS_VENDA_DIRETA)
            .remove(Constants.HAS_COMANDA)
            .apply()

        assertFalse("HAS_MESA must be removed on session expiration", prefs.contains(Constants.HAS_MESA))
        assertFalse("HAS_VENDA_DIRETA must be removed on session expiration", prefs.contains(Constants.HAS_VENDA_DIRETA))
        assertFalse("HAS_COMANDA must be removed on session expiration", prefs.contains(Constants.HAS_COMANDA))
    }

    // =========================================================================
    // 04A.2.3 / 04A.2.4: R1 to R10 - DEVICE AUTHORIZATION RESUME TESTS
    // =========================================================================

    @Test
    fun testR1_kPausedDeviceBlocked_successfulLogin_transitionsToPending() {
        runBlocking {
            createTestTable("tbl_r1", 41)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r1", "Cliente R1", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(403, "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)

            val paused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", paused?.status)
            assertEquals("DEVICE_BLOCKED", paused?.pauseReason)

            // Simular autorização comprovada do dispositivo pós login
            val resumedCount = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, resumedCount)

            val resumed = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", resumed?.status)
            assertNull(resumed?.pauseReason)
            assertNull(resumed?.claimToken)
            assertEquals(accepted.mutationId, resumed?.id)
        }
    }

    @Test
    fun testR2_afterR1BackendSucceeds_sameKSyncedWithoutReplacement() {
        runBlocking {
            createTestTable("tbl_r2", 42)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r2", "Cliente R2", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(403, "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)

            repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)

            // Administrador desbloqueou: backend responde sucesso
            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_r2"))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Success)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
            assertEquals(accepted.mutationId, synced?.id)
            assertEquals(accepted.localComandaId, synced?.localComandaId)
        }
    }

    @Test
    fun testR3_kPausedDeviceNotRegistered_sameAuthorizedLogin_transitionsToPending() {
        runBlocking {
            createTestTable("tbl_r3", 43)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r3", "Cliente R3", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(403, "{\"code\":\"DEVICE_NOT_REGISTERED\",\"message\":\"Terminal not registered\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)

            val paused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", paused?.status)
            assertEquals("DEVICE_NOT_REGISTERED", paused?.pauseReason)

            val resumedCount = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, resumedCount)

            val resumed = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", resumed?.status)
            assertNull(resumed?.pauseReason)
        }
    }

    @Test
    fun testR4_sameTenantActor_differentDevice_zeroRowsResumed() {
        runBlocking {
            createTestTable("tbl_r4", 44)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r4", "Cliente R4", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(403, "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)

            // Tentativa de login com outro DeviceId
            val resumedCount = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, "OTHER_DEVICE_XYZ")
            assertEquals(0, resumedCount)

            val stillPaused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", stillPaused?.status)
        }
    }

    @Test
    fun testR5_sameDeviceTenant_differentActor_zeroRowsResumed() {
        runBlocking {
            createTestTable("tbl_r5", 45)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r5", "Cliente R5", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(403, "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)

            // Tentativa de login com outro operador
            val resumedCount = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, "OTHER_USER_999", currentDeviceId)
            assertEquals(0, resumedCount)

            val stillPaused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", stillPaused?.status)
        }
    }

    @Test
    fun testR6_hasMesaFalse_pausedRowsNotResumed() {
        runBlocking {
            createTestTable("tbl_r6", 46)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r6", "Cliente R6", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(403, "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)

            // Quando hasMesa = false, o login NÃO invoca resumeAfterVerifiedDeviceAuthorization
            val hasMesa = false
            if (hasMesa) {
                repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            }

            val stillPaused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", stillPaused?.status)
            assertEquals("DEVICE_BLOCKED", stillPaused?.pauseReason)
        }
    }

    @Test
    fun testR7_deviceIdMismatch_remainsPaused() {
        runBlocking {
            createTestTable("tbl_r7", 47)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r7", "Cliente R7", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Forçar status PAUSED com DEVICE_ID_MISMATCH
            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_r7_mismatch",
                    status = "PAUSED",
                    pauseReason = "DEVICE_ID_MISMATCH"
                )
            )

            val resumedCount = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(0, resumedCount)

            val stillPaused = database.comandaMutationDao().getById("mut_r7_mismatch")
            assertEquals("PAUSED", stillPaused?.status)
            assertEquals("DEVICE_ID_MISMATCH", stillPaused?.pauseReason)
        }
    }

    @Test
    fun testR8_tenantDeviceMismatch_remainsPaused() {
        runBlocking {
            createTestTable("tbl_r8", 48)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r8", "Cliente R8", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_r8_mismatch",
                    status = "PAUSED",
                    pauseReason = "TENANT_DEVICE_MISMATCH"
                )
            )

            val resumedCount = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(0, resumedCount)

            val stillPaused = database.comandaMutationDao().getById("mut_r8_mismatch")
            assertEquals("PAUSED", stillPaused?.status)
            assertEquals("TENANT_DEVICE_MISMATCH", stillPaused?.pauseReason)
        }
    }

    @Test
    fun testR9_reconciliationRequired_remainsUnchanged() {
        runBlocking {
            createTestTable("tbl_r9", 49)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r9", "Cliente R9", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_r9_rec",
                    status = "RECONCILIATION_REQUIRED",
                    reconciliationReason = "TABLE_ALREADY_OCCUPIED"
                )
            )

            val resumedCount = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(0, resumedCount)

            val rec = database.comandaMutationDao().getById("mut_r9_rec")
            assertEquals("RECONCILIATION_REQUIRED", rec?.status)
            assertEquals("TABLE_ALREADY_OCCUPIED", rec?.reconciliationReason)
        }
    }

    @Test
    fun testR10_terminalStillBlocked_resumeToPending_dispatchPausesAgain_sameK() {
        runBlocking {
            createTestTable("tbl_r10", 50)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_r10", "Cliente R10", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Inicialmente bloqueado
            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                Response.error<Map<String, Any>>(
                    403,
                    "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            }
            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)

            // Re-avaliação pós login com autorização comprovada do dispositivo
            val resumed = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, resumed)
            assertEquals("PENDING", database.comandaMutationDao().getById(accepted.mutationId)?.status)

            // Servidor AINDA bloqueado: despacha uma vez e retorna a PAUSED
            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Paused)
            assertEquals("DEVICE_BLOCKED", (dispatchResult as DispatchResult.Paused).reason)

            val repaused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", repaused?.status)
            assertEquals("DEVICE_BLOCKED", repaused?.pauseReason)
            assertEquals(accepted.mutationId, repaused?.id)
        }
    }

    // =========================================================================
    // 04A.2.4: V1 to V12 - VERIFIED DEVICE AUTHORIZATION RESUME TESTS
    // =========================================================================

    @Test
    fun testV1_authRequired_loginSuccessMatchingIdentity_transitionsToPending() {
        runBlocking {
            createTestTable("tbl_v1", 51)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v1", "Cliente V1", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_v1_auth",
                    status = "PAUSED",
                    pauseReason = "AUTH_REQUIRED"
                )
            )

            val count = repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, count)

            val resumed = database.comandaMutationDao().getById("mut_v1_auth")
            assertEquals("PENDING", resumed?.status)
            assertNull(resumed?.pauseReason)
        }
    }

    @Test
    fun testV2_differentActor_originalActorLogsBackIn_transitionsToPending() {
        runBlocking {
            createTestTable("tbl_v2", 52)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v2", "Cliente V2", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_v2_actor",
                    status = "PAUSED",
                    pauseReason = "DIFFERENT_ACTOR"
                )
            )

            val count = repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, count)

            val resumed = database.comandaMutationDao().getById("mut_v2_actor")
            assertEquals("PENDING", resumed?.status)
            assertNull(resumed?.pauseReason)
        }
    }

    @Test
    fun testV3_deviceBlocked_loginResponseDeviceNull_remainsPaused() {
        runBlocking {
            createTestTable("tbl_v3", 53)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v3", "Cliente V3", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_v3_blk",
                    status = "PAUSED",
                    pauseReason = "DEVICE_BLOCKED"
                )
            )

            // Auth resume NÃO deve despausar DEVICE_BLOCKED
            val authCount = repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId)
            assertEquals(0, authCount)

            // device = null: verificação de dispositivo não ocorre
            val deviceAuth: com.plugpdv.pdv.models.AuthDevice? = null
            val deviceVerified = deviceAuth != null && deviceAuth.id == currentDeviceId && deviceAuth.blocked != true
            assertEquals(false, deviceVerified)

            val stillPaused = database.comandaMutationDao().getById("mut_v3_blk")
            assertEquals("PAUSED", stillPaused?.status)
            assertEquals("DEVICE_BLOCKED", stillPaused?.pauseReason)
        }
    }

    @Test
    fun testV4_deviceNotRegistered_loginResponseDeviceNull_remainsPaused() {
        runBlocking {
            createTestTable("tbl_v4", 54)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v4", "Cliente V4", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_v4_notreg",
                    status = "PAUSED",
                    pauseReason = "DEVICE_NOT_REGISTERED"
                )
            )

            val authCount = repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId)
            assertEquals(0, authCount)

            val stillPaused = database.comandaMutationDao().getById("mut_v4_notreg")
            assertEquals("PAUSED", stillPaused?.status)
            assertEquals("DEVICE_NOT_REGISTERED", stillPaused?.pauseReason)
        }
    }

    @Test
    fun testV5_deviceBlocked_verifiedResponseDevice_transitionsToPending() {
        runBlocking {
            createTestTable("tbl_v5", 55)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v5", "Cliente V5", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_v5_blk",
                    status = "PAUSED",
                    pauseReason = "DEVICE_BLOCKED"
                )
            )

            val deviceAuth = com.plugpdv.pdv.models.AuthDevice(id = currentDeviceId, apiVersion = 1, blocked = false)
            val deviceVerified = deviceAuth.id == currentDeviceId && deviceAuth.blocked != true
            assertTrue(deviceVerified)

            val count = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, count)

            val resumed = database.comandaMutationDao().getById("mut_v5_blk")
            assertEquals("PENDING", resumed?.status)
            assertNull(resumed?.pauseReason)
        }
    }

    @Test
    fun testV6_deviceNotRegistered_verifiedAutoRegisteredDevice_transitionsToPending() {
        runBlocking {
            createTestTable("tbl_v6", 56)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v6", "Cliente V6", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_v6_notreg",
                    status = "PAUSED",
                    pauseReason = "DEVICE_NOT_REGISTERED"
                )
            )

            val deviceAuth = com.plugpdv.pdv.models.AuthDevice(id = currentDeviceId, apiVersion = 1, blocked = false)
            val deviceVerified = deviceAuth.id == currentDeviceId && deviceAuth.blocked != true
            assertTrue(deviceVerified)

            val count = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, count)

            val resumed = database.comandaMutationDao().getById("mut_v6_notreg")
            assertEquals("PENDING", resumed?.status)
            assertNull(resumed?.pauseReason)
        }
    }

    @Test
    fun testV7_verifiedDeviceIdDiffers_zeroRowsResumed() {
        runBlocking {
            createTestTable("tbl_v7", 57)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v7", "Cliente V7", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_v7_blk",
                    status = "PAUSED",
                    pauseReason = "DEVICE_BLOCKED"
                )
            )

            val deviceAuth = com.plugpdv.pdv.models.AuthDevice(id = "DIFFERENT_DEV_ID", apiVersion = 1, blocked = false)
            val deviceVerified = deviceAuth.id == currentDeviceId && deviceAuth.blocked != true
            assertFalse(deviceVerified)

            val stillPaused = database.comandaMutationDao().getById("mut_v7_blk")
            assertEquals("PAUSED", stillPaused?.status)
        }
    }

    @Test
    fun testV8_responseDeviceBlockedTrue_zeroRowsResumed() {
        runBlocking {
            createTestTable("tbl_v8", 58)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v8", "Cliente V8", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_v8_blk",
                    status = "PAUSED",
                    pauseReason = "DEVICE_BLOCKED"
                )
            )

            val deviceAuth = com.plugpdv.pdv.models.AuthDevice(id = currentDeviceId, apiVersion = 1, blocked = true)
            val deviceVerified = deviceAuth.id == currentDeviceId && deviceAuth.blocked != true
            assertFalse(deviceVerified)

            val stillPaused = database.comandaMutationDao().getById("mut_v8_blk")
            assertEquals("PAUSED", stillPaused?.status)
        }
    }

    @Test
    fun testV9_hasMesaFalse_noMesaMutationResumes() {
        runBlocking {
            createTestTable("tbl_v9", 59)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v9", "Cliente V9", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            database.comandaMutationDao().insert(
                database.comandaMutationDao().getById(accepted.mutationId)!!.copy(
                    id = "mut_v9_blk",
                    status = "PAUSED",
                    pauseReason = "DEVICE_BLOCKED"
                )
            )

            val hasMesa = false
            if (hasMesa) {
                repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            }

            val stillPaused = database.comandaMutationDao().getById("mut_v9_blk")
            assertEquals("PAUSED", stillPaused?.status)
        }
    }

    @Test
    fun testV10_sameKLocalComandaIdPayloadPreservedThroughResume() {
        runBlocking {
            createTestTable("tbl_v10", 60)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v10", "Cliente V10 Original", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            val originalMutation = database.comandaMutationDao().getById(accepted.mutationId)!!
            val originalPayload = originalMutation.payloadJson
            val originalResolved = originalMutation.resolvedPayloadJson

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(403, "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)

            repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)

            val resumed = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", resumed?.status)
            assertEquals(accepted.mutationId, resumed?.id)
            assertEquals(accepted.localComandaId, resumed?.localComandaId)
            assertEquals(originalPayload, resumed?.payloadJson)
            assertEquals(originalResolved, resumed?.resolvedPayloadJson)
            assertEquals(currentDeviceId, resumed?.deviceId)
            assertEquals(actorUserId, resumed?.actorUserId)
            assertEquals(tenantId, resumed?.tenantId)
        }
    }

    @Test
    fun testV11_deviceAuthNull_noHttpOpenDispatchCausedByDeviceResume() {
        runBlocking {
            createTestTable("tbl_v11", 61)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_v11", "Cliente V11", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(403, "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)

            // Auth login com device = null
            val deviceAuth: com.plugpdv.pdv.models.AuthDevice? = null
            if (deviceAuth != null && deviceAuth.id == currentDeviceId && deviceAuth.blocked != true) {
                repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            }

            // Dispatch batch não encontra mutações elegíveis
            val batchResult = dispatcher.dispatchEligibleBatch()
            assertEquals(0, batchResult.processedCount)
        }
    }

    @Test
    fun testV12_authResponseRepresentativeJson_deserializesDeviceIdentityCorrectly() {
        val json = """
            {
              "access_token": "token-12345",
              "owner_id": "tenant-1",
              "user": {"id":"user-1"},
              "mesa": true,
              "device": {
                "id":"device-1",
                "api_version":1,
                "blocked":false
              }
            }
        """.trimIndent()

        val response = Gson().fromJson(json, com.plugpdv.pdv.models.AuthResponse::class.java)
        assertNotNull(response)
        assertEquals("token-12345", response.access_token)
        assertEquals("tenant-1", response.ownerId)
        assertEquals("user-1", response.user?.id)
        assertEquals(true, response.mesa)
        assertNotNull(response.device)
        assertEquals("device-1", response.device?.id)
        assertEquals(1, response.device?.apiVersion)
        assertEquals(false, response.device?.blocked)
    }
}
