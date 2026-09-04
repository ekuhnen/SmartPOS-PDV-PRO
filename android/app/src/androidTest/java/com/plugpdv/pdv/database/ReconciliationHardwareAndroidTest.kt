package com.plugpdv.pdv.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.plugpdv.pdv.repository.ComandaMutationRepository
import com.plugpdv.pdv.repository.ReconciliationReason
import com.plugpdv.pdv.repository.ReconciliationResult
import com.plugpdv.pdv.ui.reconciliation.ReconciliationReasonMapper
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * OFFLINE-FIRST-04A.2.6B — Hardware Smoke Tests (HW-UI1 .. HW-UI6)
 *
 * HW-UI1: Open list with a real reconciliation mutation.
 * HW-UI2: Execute Retry (R-B) on real SQLite storage.
 * HW-UI3: Execute Cancel (R-C) on real SQLite storage.
 * HW-UI4: Execute Confirm Remote Success (R-A) on real SQLite storage.
 * HW-UI5: Close/re-open screen after resolution.
 * HW-UI6: Restart application/database with reconciliation still pending.
 */
@RunWith(AndroidJUnit4::class)
class ReconciliationHardwareAndroidTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: ComandaMutationRepository
    private lateinit var workScheduler: ComandaWorkScheduler

    private val DB_NAME = "comanda_hw_ui_recon_test.db"
    private val tenantId = "tenant_hw_ui_1"
    private val actorUserId = "actor_hw_ui_1"
    private lateinit var deviceId: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Constants.TOKEN, "mock_hw_token")
            .putString(Constants.USER_ID, actorUserId)
            .putString(Constants.OPERATOR_ID, actorUserId)
            .putBoolean(Constants.HAS_MESA, true)
            .apply()

        TenantBindingStore.setActiveTenantId(context, tenantId)
        deviceId = DeviceIdProvider.get(context)

        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

        workScheduler = ComandaWorkScheduler(context)
        repository = ComandaMutationRepository(
            context = context,
            database = db,
            tableDao = db.tableDao(),
            comandaMutationDao = db.comandaMutationDao(),
            comandaSnapshotDao = db.comandaSnapshotDao(),
            comandaReconciliationLogDao = db.comandaReconciliationLogDao(),
            workScheduler = workScheduler
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    private suspend fun insertTestMutation(
        id: String = "mut_hw_ui_${UUID.randomUUID()}",
        status: String = "RECONCILIATION_REQUIRED",
        reason: String = ReconciliationReason.TABLE_ALREADY_OCCUPIED
    ): ComandaMutationEntity {
        val now = System.currentTimeMillis()
        val mutation = ComandaMutationEntity(
            id = id,
            operationType = "OPEN_TABLE",
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            localComandaId = "loc_cmd_hw_ui_${id}",
            tableId = "tbl_hw_ui_1",
            payloadJson = """{"action":"abrir","mesa_id":"tbl_hw_ui_1"}""",
            resolvedPayloadJson = """{"action":"abrir","mesa_id":"tbl_hw_ui_1"}""",
            createdAt = now,
            updatedAt = now,
            attemptCount = 3,
            lastAttemptAt = now,
            nextRetryAt = now,
            status = status,
            reconciliationReason = reason,
            pauseReason = null,
            claimToken = null,
            claimedAt = null,
            lastErrorCode = null,
            messageKey = null,
            resolvedAt = null
        )
        db.comandaMutationDao().insert(mutation)
        return mutation
    }

    // =========================================================================
    // HW-UI1: Open list with a real reconciliation mutation
    // =========================================================================
    @Test
    fun hwUi1_openListWithRealReconciliationMutation() = runBlocking {
        insertTestMutation("mut_hw_ui1", "RECONCILIATION_REQUIRED", ReconciliationReason.TABLE_ALREADY_OCCUPIED)

        val authorityMutations = repository.getReconciliationRequiredForAuthority(tenantId, actorUserId, deviceId)
        assertEquals("Must return 1 mutation for authority", 1, authorityMutations.size)
        assertEquals("mut_hw_ui1", authorityMutations[0].id)

        val humanMsg = context.getString(ReconciliationReasonMapper.messageRes(authorityMutations[0].reconciliationReason))
        assertEquals("Esta mesa já está ocupada por outra operação.", humanMsg)
    }

    // =========================================================================
    // HW-UI2: Execute Retry (R-B) on real SQLite storage
    // =========================================================================
    @Test
    fun hwUi2_executeRetryOnRealSqliteStorage() = runBlocking {
        insertTestMutation("mut_hw_ui2", "RECONCILIATION_REQUIRED", ReconciliationReason.MESA_NOT_FOUND)

        val result = repository.resolveReconciliationAsRetry(
            mutationId = "mut_hw_ui2",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            notes = "HW-UI2 retry test"
        )
        assertTrue("Retry resolution must succeed", result is ReconciliationResult.Success)

        val mutation = db.comandaMutationDao().getById("mut_hw_ui2")
        assertEquals("Status must transition to PENDING", "PENDING", mutation?.status)
        assertNull("reconciliationReason must be cleared", mutation?.reconciliationReason)

        val activeList = repository.getReconciliationRequiredForAuthority(tenantId, actorUserId, deviceId)
        assertTrue("Mutation must no longer be in RECONCILIATION_REQUIRED", activeList.none { it.id == "mut_hw_ui2" })
    }

    // =========================================================================
    // HW-UI3: Execute Cancel (R-C) on real SQLite storage
    // =========================================================================
    @Test
    fun hwUi3_executeCancelOnRealSqliteStorage() = runBlocking {
        insertTestMutation("mut_hw_ui3", "RECONCILIATION_REQUIRED", ReconciliationReason.TABLE_ALREADY_OCCUPIED)

        val result = repository.resolveReconciliationAsCancelled(
            mutationId = "mut_hw_ui3",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            notes = "HW-UI3 cancel test"
        )
        assertTrue("Cancel resolution must succeed", result is ReconciliationResult.Success)

        val mutation = db.comandaMutationDao().getById("mut_hw_ui3")
        assertEquals("Status must transition to CANCELLED", "CANCELLED", mutation?.status)
        assertNotNull("resolvedAt timestamp must be set", mutation?.resolvedAt)

        val logs = db.comandaReconciliationLogDao().getByMutationId("mut_hw_ui3")
        assertEquals(1, logs.size)
        assertEquals("CANCELLED", logs[0].resolutionAction)
    }

    // =========================================================================
    // HW-UI4: Execute Confirm Remote Success (R-A) on real SQLite storage
    // =========================================================================
    @Test
    fun hwUi4_executeConfirmSuccessOnRealSqliteStorage() = runBlocking {
        insertTestMutation("mut_hw_ui4", "RECONCILIATION_REQUIRED", ReconciliationReason.EMPTY_SERVER_ID)

        val result = repository.resolveReconciliationAsCompleted(
            mutationId = "mut_hw_ui4",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            notes = "HW-UI4 confirm success test"
        )
        assertTrue("Confirm success must succeed", result is ReconciliationResult.Success)

        val mutation = db.comandaMutationDao().getById("mut_hw_ui4")
        assertEquals("Status must transition to COMPLETED", "COMPLETED", mutation?.status)
        assertNotNull("resolvedAt timestamp must be set", mutation?.resolvedAt)

        val logs = db.comandaReconciliationLogDao().getByMutationId("mut_hw_ui4")
        assertEquals(1, logs.size)
        assertEquals("COMPLETED", logs[0].resolutionAction)
    }

    // =========================================================================
    // HW-UI5: Close/re-open screen after resolution
    // =========================================================================
    @Test
    fun hwUi5_screenReopenReflectsResolvedStateOnRealSqlite() = runBlocking {
        insertTestMutation("mut_hw_ui5", "RECONCILIATION_REQUIRED", ReconciliationReason.FORBIDDEN)

        // Resolve
        repository.resolveReconciliationAsCancelled("mut_hw_ui5", actorUserId, deviceId, tenantId)

        // Simulate closing/re-opening screen: re-query authority count
        val count = repository.getReconciliationCountForAuthority(tenantId, actorUserId, deviceId)
        assertEquals("Active reconciliation count must be 0 after resolution", 0, count)
    }

    // =========================================================================
    // HW-UI6: Restart application/database with reconciliation still pending
    // =========================================================================
    @Test
    fun hwUi6_applicationRestartPreservesUnresolvedReconciliation() = runBlocking {
        insertTestMutation("mut_hw_ui6", "RECONCILIATION_REQUIRED", ReconciliationReason.TABLE_ALREADY_OCCUPIED)

        // Simulate application process restart / database re-open
        db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        repository = ComandaMutationRepository(
            context = context,
            database = db,
            tableDao = db.tableDao(),
            comandaMutationDao = db.comandaMutationDao(),
            comandaSnapshotDao = db.comandaSnapshotDao(),
            comandaReconciliationLogDao = db.comandaReconciliationLogDao(),
            workScheduler = workScheduler
        )

        val authorityMutations = repository.getReconciliationRequiredForAuthority(tenantId, actorUserId, deviceId)
        assertEquals("Pending reconciliation must survive process restart", 1, authorityMutations.size)
        assertEquals("mut_hw_ui6", authorityMutations[0].id)
    }
}
