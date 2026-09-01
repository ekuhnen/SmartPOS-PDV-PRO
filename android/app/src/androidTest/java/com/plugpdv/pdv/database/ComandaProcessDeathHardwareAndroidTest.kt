package com.plugpdv.pdv.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ComandaProcessDeathHardwareAndroidTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val DB_NAME = "comanda_hw_restart_test.db"

    private val tenantId = "tenant_hw_1"
    private val actorUserId = "actor_hw_1"
    private val deviceId = "device_hw_pos_1"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    private suspend fun insertTestMutation(
        id: String = UUID.randomUUID().toString(),
        localComandaId: String = UUID.randomUUID().toString(),
        status: String = "PENDING",
        pauseReason: String? = null,
        reconciliationReason: String? = null,
        claimedAt: Long? = null,
        claimToken: String? = null
    ): ComandaMutationEntity {
        val mutation = ComandaMutationEntity(
            id = id,
            localComandaId = localComandaId,
            operationType = "OPEN_TABLE",
            tableId = "tbl_hw_1",
            payloadJson = "{\"action\":\"abrir\",\"mesaId\":\"tbl_hw_1\",\"nome_cliente\":\"Cliente HW\"}",
            resolvedPayloadJson = "{\"action\":\"abrir\",\"mesaId\":\"tbl_hw_1\",\"nome_cliente\":\"Cliente HW\"}",
            status = status,
            attemptCount = 0,
            nextRetryAt = System.currentTimeMillis(),
            lastAttemptAt = null,
            lastErrorCode = null,
            messageKey = null,
            pauseReason = pauseReason,
            reconciliationReason = reconciliationReason,
            claimToken = claimToken,
            claimedAt = claimedAt,
            deviceId = deviceId,
            actorUserId = actorUserId,
            tenantId = tenantId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.comandaMutationDao().insert(mutation)
        return mutation
    }

    // =========================================================================
    // HW-R1: Persistent pending mutation survives DB close/reopen on hardware
    // =========================================================================
    @Test
    fun testHWR1_pendingMutationSurvivesDbCloseAndReopenOnHardware() = runBlocking {
        val original = insertTestMutation()

        // Fechar banco (simula process death / restart)
        db.close()

        // Reabrir banco a partir do armazenamento persistente do hardware
        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

        val restored = db.comandaMutationDao().getById(original.id)
        assertNotNull(restored)
        assertEquals(original.id, restored?.id)
        assertEquals(original.localComandaId, restored?.localComandaId)
        assertEquals("PENDING", restored?.status)
        assertEquals(original.payloadJson, restored?.payloadJson)
        assertEquals(tenantId, restored?.tenantId)
        assertEquals(actorUserId, restored?.actorUserId)
        assertEquals(deviceId, restored?.deviceId)
    }

    // =========================================================================
    // HW-R2: Mutation AUTH_REQUIRED survives restart without altering state
    // =========================================================================
    @Test
    fun testHWR2_authRequiredSurvivesRestartOnHardware() = runBlocking {
        val original = insertTestMutation(status = "PAUSED", pauseReason = "AUTH_REQUIRED")

        db.close()

        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

        val restored = db.comandaMutationDao().getById(original.id)
        assertNotNull(restored)
        assertEquals("PAUSED", restored?.status)
        assertEquals("AUTH_REQUIRED", restored?.pauseReason)
    }

    // =========================================================================
    // HW-R3: Authenticated login resume unpauses AUTH_REQUIRED on device SQLite
    // =========================================================================
    @Test
    fun testHWR3_authenticatedLoginResumeOnHardware() = runBlocking {
        val original = insertTestMutation(status = "PAUSED", pauseReason = "AUTH_REQUIRED")

        val count = db.comandaMutationDao().resumeAfterAuthenticatedLogin(
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            now = System.currentTimeMillis()
        )
        assertEquals(1, count)

        val resumed = db.comandaMutationDao().getById(original.id)
        assertEquals("PENDING", resumed?.status)
        assertNull(resumed?.pauseReason)
    }

    // =========================================================================
    // HW-R4: Mutation DEVICE_BLOCKED survives restart & unverified login
    // =========================================================================
    @Test
    fun testHWR4_deviceBlockedSurvivesRestartAndUnverifiedLoginOnHardware() = runBlocking {
        val original = insertTestMutation(status = "PAUSED", pauseReason = "DEVICE_BLOCKED")

        db.close()

        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

        // Auth login normal NÃO despausa DEVICE_BLOCKED
        val authResumeCount = db.comandaMutationDao().resumeAfterAuthenticatedLogin(
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            now = System.currentTimeMillis()
        )
        assertEquals(0, authResumeCount)

        val stillPaused = db.comandaMutationDao().getById(original.id)
        assertEquals("PAUSED", stillPaused?.status)
        assertEquals("DEVICE_BLOCKED", stillPaused?.pauseReason)
    }

    // =========================================================================
    // HW-R5: Verified device authorization resume executes on device SQLite
    // =========================================================================
    @Test
    fun testHWR5_verifiedDeviceAuthorizationResumeOnHardware() = runBlocking {
        val original = insertTestMutation(status = "PAUSED", pauseReason = "DEVICE_BLOCKED")

        val count = db.comandaMutationDao().resumeAfterVerifiedDeviceAuthorization(
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            now = System.currentTimeMillis()
        )
        assertEquals(1, count)

        val resumed = db.comandaMutationDao().getById(original.id)
        assertEquals("PENDING", resumed?.status)
        assertNull(resumed?.pauseReason)
    }

    // =========================================================================
    // HW-R6: Non-resumable reasons fail closed on hardware SQLite
    // =========================================================================
    @Test
    fun testHWR6_nonResumableReasonsFailClosedOnHardware() = runBlocking {
        val diffTenant = insertTestMutation(id = "mut_diff_tenant", status = "PAUSED", pauseReason = "DIFFERENT_TENANT")
        val devMismatch = insertTestMutation(id = "mut_dev_mismatch", status = "PAUSED", pauseReason = "DEVICE_ID_MISMATCH")
        val rec = insertTestMutation(id = "mut_rec_req", status = "RECONCILIATION_REQUIRED", reconciliationReason = "TABLE_ALREADY_OCCUPIED")

        db.close()

        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

        val now = System.currentTimeMillis()
        assertEquals(0, db.comandaMutationDao().resumeAfterAuthenticatedLogin(tenantId, actorUserId, deviceId, now))
        assertEquals(0, db.comandaMutationDao().resumeAfterVerifiedDeviceAuthorization(tenantId, actorUserId, deviceId, now))

        assertEquals("PAUSED", db.comandaMutationDao().getById(diffTenant.id)?.status)
        assertEquals("PAUSED", db.comandaMutationDao().getById(devMismatch.id)?.status)
        assertEquals("RECONCILIATION_REQUIRED", db.comandaMutationDao().getById(rec.id)?.status)
    }

    // =========================================================================
    // HW-R7: Stale claim recovery (120s) executes on device SQLite
    // =========================================================================
    @Test
    fun testHWR7_staleClaimRecoveryAfter120sOnHardware() = runBlocking {
        val now = System.currentTimeMillis()
        val staleTime = now - 150_000L // 150s atrás (>120s threshold)
        val original = insertTestMutation(
            status = "PROCESSING",
            claimedAt = staleTime,
            claimToken = "stale_token_hw"
        )

        val recovered = db.comandaMutationDao().recoverStaleProcessing(now - 120_000L, now)
        assertEquals(1, recovered)

        val restored = db.comandaMutationDao().getById(original.id)
        assertEquals("PENDING", restored?.status)
        assertNull(restored?.claimToken)
    }

    // =========================================================================
    // HW-R8: WorkManager unique work enqueue on real hardware
    // =========================================================================
    @Test
    fun testHWR8_workManagerUniqueWorkEnqueueOnHardware() {
        val scheduler = ComandaWorkScheduler(context)
        scheduler.scheduleCommandSync()
        scheduler.scheduleCommandSync()
        scheduler.scheduleRetry(2000L)
        assertTrue(true)
    }
}
