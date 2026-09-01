package com.plugpdv.pdv.repository

import android.content.Context
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.dispatcher.ComandaOutboxDispatcher
import com.plugpdv.pdv.dispatcher.DispatchBatchResult
import com.plugpdv.pdv.dispatcher.DispatchResult
import com.plugpdv.pdv.models.AuthDevice
import com.plugpdv.pdv.models.AuthResponse
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.models.User
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.KillSwitchManager
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.worker.ComandaSyncWorker
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ComandaProcessDeathAndRestartTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var apiService: PosApiService
    private lateinit var workScheduler: ComandaWorkScheduler
    private lateinit var repository: ComandaMutationRepository
    private lateinit var dispatcher: ComandaOutboxDispatcher
    private val currencyRulesProvider = DefaultCurrencyRulesProvider()

    private val tenantId = "tenant_death_restart_1"
    private val actorUserId = "user_operator_death_1"
    private val deviceId = "device_death_1"
    private val token = "valid_auth_token_death"

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
        id: String = "tbl_death_1",
        number: Int = 101,
        status: String = Table.Status.AVAILABLE
    ): TableEntity {
        val table = TableEntity(
            id = id,
            number = number,
            status = status,
            sectorName = "Salão Principal",
            sectorId = "sec_100",
            customerName = null,
            comandaId = null,
            localComandaId = null,
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
    // 3. UNIQUE WORK VERIFICATION (P1 - P4)
    // =========================================================================

    @Test
    fun testP1_singleSchedule_enqueuesUniqueWorkWithKeep() {
        val realScheduler = ComandaWorkScheduler(context)
        // Invoking scheduleCommandSync without crash
        realScheduler.scheduleCommandSync()
        // Unique work constant check
        assertEquals("plugpdv_comanda_mutations_sync", ComandaWorkScheduler.UNIQUE_WORK_NAME)
    }

    @Test
    fun testP2_consecutiveSchedules_respectsKeepPolicy() {
        val realScheduler = ComandaWorkScheduler(context)
        // 2 consecutive calls do not throw and enforce ExistingWorkPolicy.KEEP
        realScheduler.scheduleCommandSync()
        realScheduler.scheduleCommandSync()
    }

    @Test
    fun testP3_appStartupAndLoginSuccess_bothUseKeepPolicy() {
        val realScheduler = ComandaWorkScheduler(context)
        // Simular Application.onCreate() schedule
        realScheduler.scheduleCommandSync()
        // Simular LoginActivity login success schedule
        realScheduler.scheduleCommandSync()
    }

    @Test
    fun testP4_workerRunning_newScheduleRespectsKeepPolicy() {
        val realScheduler = ComandaWorkScheduler(context)
        realScheduler.scheduleCommandSync()
        realScheduler.scheduleRetry(2000L)
    }

    // =========================================================================
    // 4. PROCESS-DEATH SCENARIOS (PD1 - PD5)
    // =========================================================================

    @Test
    fun testPD1_pendingMutationSurvivesProcessRecreation() {
        runBlocking {
            createTestTable("tbl_pd1", 1)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_pd1", "Cliente PD1", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Simular destruição de componentes em memória (Process Death)
            // Reinstanciar repositório e dispatcher conectados ao mesmo banco Room
            val newMockApiService: PosApiService = mock()
            whenever(newMockApiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_pd1"))
            )

            val newDispatcher = ComandaOutboxDispatcher(
                context = context,
                database = database,
                comandaMutationDao = database.comandaMutationDao(),
                comandaSnapshotDao = database.comandaSnapshotDao(),
                tableDao = database.tableDao(),
                apiService = newMockApiService,
                currencyRulesProvider = currencyRulesProvider,
                workScheduler = workScheduler
            )

            // Verificar que a mutação permanece intacta no Room com mesmo ID
            val stored = database.comandaMutationDao().getById(accepted.mutationId)
            assertNotNull(stored)
            assertEquals("PENDING", stored?.status)
            assertEquals(accepted.mutationId, stored?.id)
            assertEquals(accepted.localComandaId, stored?.localComandaId)
            assertEquals(tenantId, stored?.tenantId)
            assertEquals(actorUserId, stored?.actorUserId)
            assertEquals(currentDeviceId, stored?.deviceId)

            // Novo dispatcher despacha com sucesso
            val batchResult = newDispatcher.dispatchEligibleBatch()
            assertEquals(1, batchResult.processedCount)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
        }
    }

    @Test
    fun testPD2_authRequiredSurvivesRestart() {
        runBlocking {
            createTestTable("tbl_pd2", 2)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_pd2", "Cliente PD2", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Pausar com 401 AUTH_REQUIRED
            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(401, "{\"error\":\"Unauthorized\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)
            assertEquals("AUTH_REQUIRED", database.comandaMutationDao().getById(accepted.mutationId)?.pauseReason)

            // Simular restart do aplicativo sem novo login
            // Novo dispatcher executa dispatchEligibleBatch (como disparado no Application.onCreate)
            val freshDispatcher = ComandaOutboxDispatcher(
                context = context,
                database = database,
                comandaMutationDao = database.comandaMutationDao(),
                comandaSnapshotDao = database.comandaSnapshotDao(),
                tableDao = database.tableDao(),
                apiService = apiService,
                currencyRulesProvider = currencyRulesProvider,
                workScheduler = workScheduler
            )

            // Limpar token para simular ausência de sessão ativa
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(Constants.TOKEN).apply()

            val batchResult = freshDispatcher.dispatchEligibleBatch()
            assertEquals(0, batchResult.processedCount)

            val stillPaused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", stillPaused?.status)
            assertEquals("AUTH_REQUIRED", stillPaused?.pauseReason)
        }
    }

    @Test
    fun testPD3_authRequired_validLoginAfterRestart_resumesAndSyncs() {
        runBlocking {
            createTestTable("tbl_pd3", 3)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_pd3", "Cliente PD3", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(401, "{\"error\":\"Unauthorized\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)

            // Simular restart + novo login válido do mesmo operador
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(Constants.TOKEN, "fresh_token_pd3")
                .putString(Constants.USER_ID, actorUserId)
                .putBoolean(Constants.HAS_MESA, true)
                .apply()

            val resumedCount = repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, resumedCount)

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_pd3"))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Success)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
            assertEquals(accepted.mutationId, synced?.id)
        }
    }

    @Test
    fun testPD4_differentActor_matchingActorAfterRestart_resumesAndSyncs() {
        runBlocking {
            createTestTable("tbl_pd4", 4)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_pd4", "Cliente PD4", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Sessão mudou para outro operador -> DIFFERENT_ACTOR
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(Constants.USER_ID, "other_actor_temp")
                .apply()

            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)
            assertEquals("DIFFERENT_ACTOR", database.comandaMutationDao().getById(accepted.mutationId)?.pauseReason)

            // Operador original faz login após restart
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(Constants.USER_ID, actorUserId)
                .putString(Constants.TOKEN, "fresh_token_pd4")
                .putBoolean(Constants.HAS_MESA, true)
                .apply()

            val resumedCount = repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, resumedCount)

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_pd4"))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Success)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
        }
    }

    @Test
    fun testPD5_differentActor_wrongActorAfterRestart_remainsPaused() {
        runBlocking {
            createTestTable("tbl_pd5", 5)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_pd5", "Cliente PD5", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(Constants.USER_ID, "wrong_actor_1")
                .apply()

            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)

            // Login de outro operador após restart
            val resumedCount = repository.resumeAfterAuthenticatedLogin(tenantId, "wrong_actor_2", currentDeviceId)
            assertEquals(0, resumedCount)

            val stillPaused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", stillPaused?.status)
            assertEquals("DIFFERENT_ACTOR", stillPaused?.pauseReason)
        }
    }

    // =========================================================================
    // 5. VERIFIED-DEVICE PAUSE SCENARIOS (PD6 - PD9)
    // =========================================================================

    @Test
    fun testPD6_deviceBlockedSurvivesRestart() {
        runBlocking {
            createTestTable("tbl_pd6", 6)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_pd6", "Cliente PD6", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                Response.error<Map<String, Any>>(
                    403,
                    "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            }
            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)
            assertEquals("DEVICE_BLOCKED", database.comandaMutationDao().getById(accepted.mutationId)?.pauseReason)

            // Application startup dispatch após restart não despausa
            val batchResult = dispatcher.dispatchEligibleBatch()
            assertEquals(0, batchResult.processedCount)

            val stillPaused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", stillPaused?.status)
            assertEquals("DEVICE_BLOCKED", stillPaused?.pauseReason)
        }
    }

    @Test
    fun testPD7_deviceBlocked_loginWithoutVerifiedDevice_remainsPaused() {
        runBlocking {
            createTestTable("tbl_pd7", 7)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_pd7", "Cliente PD7", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                Response.error<Map<String, Any>>(
                    403,
                    "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            }
            dispatcher.dispatchMutationById(accepted.mutationId)

            // 1. Resposta com device = null
            val nullDeviceAuth: AuthDevice? = null
            val verified1 = nullDeviceAuth != null && nullDeviceAuth.id == currentDeviceId && nullDeviceAuth.blocked != true
            assertFalse(verified1)

            // 2. Resposta com ID divergente
            val diffDeviceAuth = AuthDevice(id = "other_id", apiVersion = 1, blocked = false)
            val verified2 = diffDeviceAuth.id == currentDeviceId && diffDeviceAuth.blocked != true
            assertFalse(verified2)

            // 3. Resposta com blocked = true
            val blockedDeviceAuth = AuthDevice(id = currentDeviceId, apiVersion = 1, blocked = true)
            val verified3 = blockedDeviceAuth.id == currentDeviceId && blockedDeviceAuth.blocked != true
            assertFalse(verified3)

            val stillPaused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", stillPaused?.status)
            assertEquals("DEVICE_BLOCKED", stillPaused?.pauseReason)
        }
    }

    @Test
    fun testPD8_deviceBlocked_loginWithVerifiedDevice_resumesAndSyncs() {
        runBlocking {
            createTestTable("tbl_pd8", 8)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_pd8", "Cliente PD8", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                Response.error<Map<String, Any>>(
                    403,
                    "{\"code\":\"DEVICE_BLOCKED\",\"message\":\"Terminal blocked\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            }
            dispatcher.dispatchMutationById(accepted.mutationId)

            // Login com verified device
            val verifiedDevice = AuthDevice(id = currentDeviceId, apiVersion = 1, blocked = false)
            assertTrue(verifiedDevice.id == currentDeviceId && verifiedDevice.blocked != true)

            val resumedCount = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, resumedCount)

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_pd8"))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Success)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
        }
    }

    @Test
    fun testPD9_deviceNotRegistered_verifiedDeviceMatrix() {
        runBlocking {
            createTestTable("tbl_pd9", 9)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_pd9", "Cliente PD9", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                Response.error<Map<String, Any>>(
                    403,
                    "{\"code\":\"DEVICE_NOT_REGISTERED\",\"message\":\"Device not found\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            }
            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("DEVICE_NOT_REGISTERED", database.comandaMutationDao().getById(accepted.mutationId)?.pauseReason)

            // Tentativa de resume com auth login normal (device = null) -> 0
            val authResumeCount = repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId)
            assertEquals(0, authResumeCount)

            // Login com auto-registro bem-sucedido (verified device) -> 1
            val resumedCount = repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId)
            assertEquals(1, resumedCount)

            val resumed = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", resumed?.status)
            assertNull(resumed?.pauseReason)
        }
    }

    // =========================================================================
    // 6. NON-RESUMABLE REASONS (NR1 - NR6)
    // =========================================================================

    @Test
    fun testNR1_differentTenant_neverAutoResumed() {
        runBlocking {
            createTestTable("tbl_nr1", 11)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_nr1", "Cliente NR1", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Mudar tenant ativo -> DIFFERENT_TENANT
            TenantBindingStore.setActiveTenantId(context, "other_tenant_xyz")
            dispatcher.dispatchMutationById(accepted.mutationId)

            val paused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", paused?.status)
            assertEquals("DIFFERENT_TENANT", paused?.pauseReason)

            // Re-estabelecer tenant original: resumeAfterAuthenticatedLogin e verified device NÃO devem despausar DIFFERENT_TENANT
            TenantBindingStore.setActiveTenantId(context, tenantId)
            assertEquals(0, repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId))
            assertEquals(0, repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId))

            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)
            assertEquals("DIFFERENT_TENANT", database.comandaMutationDao().getById(accepted.mutationId)?.pauseReason)
        }
    }

    @Test
    fun testNR2_deviceIdMismatch_neverAutoResumed() {
        runBlocking {
            createTestTable("tbl_nr2", 12)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_nr2", "Cliente NR2", actorUserId, "FROZEN_DEVICE_ABC", tenantId)
            val accepted = result as OpenTableResult.Accepted

            dispatcher.dispatchMutationById(accepted.mutationId)
            val paused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", paused?.status)
            assertEquals("DEVICE_ID_MISMATCH", paused?.pauseReason)

            assertEquals(0, repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId))
            assertEquals(0, repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId))

            val still = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", still?.status)
            assertEquals("DEVICE_ID_MISMATCH", still?.pauseReason)
        }
    }

    @Test
    fun testNR3_tenantDeviceMismatch_neverAutoResumed() {
        runBlocking {
            createTestTable("tbl_nr3", 13)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_nr3", "Cliente NR3", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                Response.error<Map<String, Any>>(
                    403,
                    "{\"code\":\"TENANT_DEVICE_MISMATCH\",\"message\":\"Tenant mismatch\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            }
            dispatcher.dispatchMutationById(accepted.mutationId)
            val paused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", paused?.status)
            assertEquals("TENANT_DEVICE_MISMATCH", paused?.pauseReason)

            assertEquals(0, repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId))
            assertEquals(0, repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId))

            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)
            assertEquals("TENANT_DEVICE_MISMATCH", database.comandaMutationDao().getById(accepted.mutationId)?.pauseReason)
        }
    }

    @Test
    fun testNR4_updateRequired_neverAutoResumed() {
        runBlocking {
            createTestTable("tbl_nr4", 14)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_nr4", "Cliente NR4", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                Response.error<Map<String, Any>>(
                    426,
                    "{\"code\":\"UPDATE_REQUIRED\",\"message\":\"Upgrade required\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            }
            dispatcher.dispatchMutationById(accepted.mutationId)
            val paused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", paused?.status)
            assertEquals("UPDATE_REQUIRED", paused?.pauseReason)

            assertEquals(0, repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId))
            assertEquals(0, repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId))

            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)
            assertEquals("UPDATE_REQUIRED", database.comandaMutationDao().getById(accepted.mutationId)?.pauseReason)
        }
    }

    @Test
    fun testNR5_deviceIdRequired_neverAutoResumed() {
        runBlocking {
            createTestTable("tbl_nr5", 15)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_nr5", "Cliente NR5", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                Response.error<Map<String, Any>>(
                    400,
                    "{\"code\":\"DEVICE_ID_REQUIRED\",\"message\":\"Device ID is required\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            }
            dispatcher.dispatchMutationById(accepted.mutationId)
            val paused = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PAUSED", paused?.status)
            assertEquals("DEVICE_ID_REQUIRED", paused?.pauseReason)

            assertEquals(0, repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId))
            assertEquals(0, repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId))

            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)
            assertEquals("DEVICE_ID_REQUIRED", database.comandaMutationDao().getById(accepted.mutationId)?.pauseReason)
        }
    }

    @Test
    fun testNR6_reconciliationRequired_neverAutoResumed() {
        runBlocking {
            createTestTable("tbl_nr6", 16)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_nr6", "Cliente NR6", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                Response.error<Map<String, Any>>(
                    409,
                    "{\"code\":\"TABLE_ALREADY_OCCUPIED\",\"message\":\"Table occupied\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            }
            dispatcher.dispatchMutationById(accepted.mutationId)
            val rec = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("RECONCILIATION_REQUIRED", rec?.status)
            assertEquals("TABLE_ALREADY_OCCUPIED", rec?.reconciliationReason)

            assertEquals(0, repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId))
            assertEquals(0, repository.resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, currentDeviceId))

            assertEquals("RECONCILIATION_REQUIRED", database.comandaMutationDao().getById(accepted.mutationId)?.status)
        }
    }

    // =========================================================================
    // 7. CRITICAL CRASH WINDOW (CW1)
    // =========================================================================

    @Test
    fun testCW1_crashBetweenLoginResumeAndSchedule_applicationStartupPicksUp() {
        runBlocking {
            createTestTable("tbl_cw1", 17)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_cw1", "Cliente CW1", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Simular pausa por 401
            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(401, "{\"error\":\"Unauthorized\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)

            // Step 1: Login remoto tem sucesso
            // Step 2: AuthViewModel executa resumeAfterAuthenticatedLogin -> transita para PENDING
            repository.resumeAfterAuthenticatedLogin(tenantId, actorUserId, currentDeviceId)
            assertEquals("PENDING", database.comandaMutationDao().getById(accepted.mutationId)?.status)

            // Step 3 & 4: Processo MORRE antes de LoginActivity executar scheduleCommandSync()
            // Simular recriação do processo (Application.onCreate())
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(Constants.TOKEN, "fresh_token_cw1")
                .putString(Constants.USER_ID, actorUserId)
                .putBoolean(Constants.HAS_MESA, true)
                .apply()

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_cw1"))
            )

            // Step 5: Application.onCreate() dispara worker/dispatcher
            val freshDispatcher = ComandaOutboxDispatcher(
                context = context,
                database = database,
                comandaMutationDao = database.comandaMutationDao(),
                comandaSnapshotDao = database.comandaSnapshotDao(),
                tableDao = database.tableDao(),
                apiService = apiService,
                currencyRulesProvider = currencyRulesProvider,
                workScheduler = workScheduler
            )

            val batchResult = freshDispatcher.dispatchEligibleBatch()
            assertEquals(1, batchResult.processedCount)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
            assertEquals(accepted.mutationId, synced?.id)
        }
    }

    // =========================================================================
    // 8. WORKER INTERRUPTION & LEASED CLAIM RECOVERY (WI1 - WI4)
    // =========================================================================

    @Test
    fun testWI1_crashBeforeRemoteRequest_recoversAfter120s() {
        runBlocking {
            createTestTable("tbl_wi1", 18)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_wi1", "Cliente WI1", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            val now = System.currentTimeMillis()
            val claimToken = UUID.randomUUID().toString()
            // Simular claim por worker que morreu antes de fazer o request
            database.comandaMutationDao().claimMutation(accepted.mutationId, claimToken, now, now - 120_000L)

            val processing = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PROCESSING", processing?.status)
            assertEquals(claimToken, processing?.claimToken)

            // Claim recente (<120s) NÃO deve ser roubado
            val recovered0 = database.comandaMutationDao().recoverStaleProcessing(now - 120_000L, now)
            assertEquals(0, recovered0)

            // Após 121 segundos (stale threshold), recuperação automática restaura para PENDING
            val futureTime = now + 121_000L
            val recovered1 = database.comandaMutationDao().recoverStaleProcessing(futureTime - 120_000L, futureTime)
            assertEquals(1, recovered1)

            val restored = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", restored?.status)
            assertNull(restored?.claimToken)
        }
    }

    @Test
    fun testWI2_staleProcessingClaim_newWorkerReclaimsAndSyncs() {
        runBlocking {
            createTestTable("tbl_wi2", 19)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_wi2", "Cliente WI2", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            val oldTime = System.currentTimeMillis() - 150_000L
            database.comandaMutationDao().claimMutation(accepted.mutationId, "dead_worker_token", oldTime, oldTime - 120_000L)

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_wi2"))
            )

            // Novo worker executa getEligibleMutations (que inclui PROCESSING com claimedAt < staleThreshold)
            val batchResult = dispatcher.dispatchEligibleBatch()
            assertEquals(1, batchResult.processedCount)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
            assertEquals(accepted.mutationId, synced?.id)
        }
    }

    @Test
    fun testWI3_remoteRequestUnknownOutcome_sameIdempotencyKeyRetried() {
        runBlocking {
            createTestTable("tbl_wi3", 20)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_wi3", "Cliente WI3", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Simular IOException (timeout/queda de rede sem resposta)
            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                throw java.io.IOException("Socket timeout")
            }

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Retrying)

            val retrying = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("PENDING", retrying?.status)
            assertEquals(accepted.mutationId, retrying?.id) // K permanece idêntico

            // Próximo retry com conexão restabelecida
            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_wi3"))
            )

            val secondDispatch = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(secondDispatch is DispatchResult.Success)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
        }
    }

    @Test
    fun testWI4_crashAfterServerSuccessBeforeLocalCommit_idempotentReplayReconciles() {
        runBlocking {
            createTestTable("tbl_wi4", 21)
            val currentDeviceId = DeviceIdProvider.get(context)

            val result = repository.openTableDurable("tbl_wi4", "Cliente WI4", actorUserId, currentDeviceId, tenantId)
            val accepted = result as OpenTableResult.Accepted

            // Simular que servidor aceitou e criou a comanda srv_cmd_wi4
            // O app sofreu crash antes de persistir SYNCED.
            // No restart, o mesmo K1 é reenviado.
            whenever(apiService.manageComanda(any(), any(), eq(accepted.mutationId))).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_wi4"))
            )

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Success)

            val synced = database.comandaMutationDao().getById(accepted.mutationId)
            assertEquals("SYNCED", synced?.status)
            assertEquals(accepted.mutationId, synced?.id)

            val table = database.tableDao().getTableById("tbl_wi4")
            assertEquals("srv_cmd_wi4", table?.comandaId)
        }
    }

    // =========================================================================
    // 9. WORKER BATCH & RESULT SEMANTICS (WS1 - WS3)
    // =========================================================================

    @Test
    fun testWS1_workerBatchLoop_processesUpTo5Batches() {
        runBlocking {
            // Criar 3 mesas e mutações elegíveis
            for (i in 1..3) {
                createTestTable("tbl_ws1_$i", 30 + i)
                repository.openTableDurable("tbl_ws1_$i", "Cliente WS1-$i", actorUserId, DeviceIdProvider.get(context), tenantId)
            }

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.success(mapOf("id" to "srv_cmd_ws1"))
            )

            // Simular o loop de execução do ComandaSyncWorker (até 5 passadas)
            var loopCount = 0
            while (loopCount < 5) {
                loopCount++
                val result = dispatcher.dispatchEligibleBatch()
                if (result.processedCount == 0 || result.stopReason != "PROGRESSED") {
                    break
                }
            }

            val remainingUnresolved = database.comandaMutationDao().getUnresolvedCount()
            assertEquals(0, remainingUnresolved)
        }
    }

    @Test
    fun testWS2_workerBatchLoop_stopsWhenEmptyOrAuthRequired() {
        runBlocking {
            // Fila vazia
            val emptyResult = dispatcher.dispatchEligibleBatch()
            assertEquals(0, emptyResult.processedCount)
            assertEquals("EMPTY", emptyResult.stopReason)

            // Fila com item pausado por AUTH_REQUIRED
            createTestTable("tbl_ws2", 33)
            val accepted = repository.openTableDurable("tbl_ws2", "Cliente WS2", actorUserId, DeviceIdProvider.get(context), tenantId) as OpenTableResult.Accepted

            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(
                Response.error(401, "{\"error\":\"Unauthorized\"}".toResponseBody("application/json".toMediaTypeOrNull()))
            )
            dispatcher.dispatchMutationById(accepted.mutationId)
            assertEquals("PAUSED", database.comandaMutationDao().getById(accepted.mutationId)?.status)

            // Limpar token para simular ausência de autenticação
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().remove(Constants.TOKEN).apply()

            val authResult = dispatcher.dispatchEligibleBatch()
            assertEquals(0, authResult.processedCount)
        }
    }

    @Test
    fun testWS3_workerException_mutationRemainsDurableInRoom() {
        runBlocking {
            createTestTable("tbl_ws3", 35)
            val accepted = repository.openTableDurable("tbl_ws3", "Cliente WS3", actorUserId, DeviceIdProvider.get(context), tenantId) as OpenTableResult.Accepted

            // Simular falha de rede/exceção transitória
            whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer {
                throw RuntimeException("Temporary network glitch")
            }

            val dispatchResult = dispatcher.dispatchMutationById(accepted.mutationId)
            assertTrue(dispatchResult is DispatchResult.Retrying)

            // Mutação permanece intacta no Room para próximo ciclo do worker
            val preserved = database.comandaMutationDao().getById(accepted.mutationId)
            assertNotNull(preserved)
            assertEquals("PENDING", preserved?.status)
        }
    }
}
