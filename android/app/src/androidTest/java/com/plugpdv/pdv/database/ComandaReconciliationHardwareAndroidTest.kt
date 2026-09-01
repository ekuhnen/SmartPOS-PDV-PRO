package com.plugpdv.pdv.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.plugpdv.pdv.repository.ComandaMutationRepository
import com.plugpdv.pdv.repository.ReconciliationResult
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * OFFLINE-FIRST-04A.2.6 — Hardware Reconciliation Tests
 *
 * HW-C1: Resolution persists across database close/re-open (simulates process death).
 * HW-C2: Audit log is immutable — duplicate log id must fail with exception.
 * HW-C3: getUnresolvedCount correctly excludes COMPLETED and CANCELLED after resolution.
 * HW-C4: resolveAsRetry transitions back to PENDING on real SQLite.
 * HW-C5: R-D (replace) atomicity — new mutation exists with new K' on real SQLite storage.
 * HW-C6: Wrong device is rejected on real SQLite, no audit log written.
 */
@RunWith(AndroidJUnit4::class)
class ComandaReconciliationHardwareAndroidTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: ComandaMutationRepository

    private val DB_NAME = "comanda_hw_reconciliation_test.db"
    private val tenantId = "tenant_hw_recon_1"
    private val actorUserId = "actor_hw_recon_1"
    private val deviceId = "device_hw_recon_pos_1"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        // Use real ComandaWorkScheduler — HW tests only verify Room state transitions,
        // not WorkManager scheduling. WorkManager may silently fail in test context, which is acceptable.
        val workScheduler = ComandaWorkScheduler(context)
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

    // =========================================================================
    // HELPERS
    // =========================================================================

    private suspend fun insertMutationWithStatus(
        id: String,
        status: String,
        reconciliationReason: String? = null
    ): ComandaMutationEntity {
        val now = System.currentTimeMillis()
        val mutation = ComandaMutationEntity(
            id = id,
            operationType = "OPEN_TABLE",
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            localComandaId = "loc_cmd_hw_${id}",
            tableId = "tbl_hw_recon_1",
            payloadJson = """{"action":"abrir","mesa_id":"tbl_hw_recon_1"}""",
            resolvedPayloadJson = """{"action":"abrir","mesa_id":"tbl_hw_recon_1"}""",
            createdAt = now,
            updatedAt = now,
            attemptCount = 3,
            lastAttemptAt = now,
            nextRetryAt = now,
            status = status,
            reconciliationReason = reconciliationReason,
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
    // HW-C1: Resolution persists across process death (DB close + re-open)
    // =========================================================================

    @Test
    fun hwC1_completedResolutionPersistsAfterDatabaseReopen() = runBlocking {
        val mutationId = "mut_hw_c1_${UUID.randomUUID()}"
        insertMutationWithStatus(mutationId, "RECONCILIATION_REQUIRED", "TABLE_ALREADY_OCCUPIED")

        val result = repository.resolveReconciliationAsCompleted(
            mutationId = mutationId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            notes = "HW-C1 test"
        )
        assertTrue("Resolution must succeed", result is ReconciliationResult.Success)

        // Close the database (simulates process death)
        db.close()

        // Re-open (simulates process restart)
        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

        // Verify mutation state is durable across restart
        val mutation = db.comandaMutationDao().getById(mutationId)
        assertNotNull("Mutation must survive process death", mutation)
        assertEquals("COMPLETED status must survive process death", "COMPLETED", mutation?.status)
        assertNotNull("resolvedAt must survive process death", mutation?.resolvedAt)

        // Verify audit log is durable
        val logs = db.comandaReconciliationLogDao().getByMutationId(mutationId)
        assertEquals("Audit log must survive process death", 1, logs.size)
        assertEquals("COMPLETED", logs[0].resolutionAction)
    }

    // =========================================================================
    // HW-C2: Audit log is immutable — duplicate id must fail
    // =========================================================================

    @Test
    fun hwC2_auditLogIsImmutable_duplicateIdFails() = runBlocking {
        val logId = "log_hw_c2_immutable"
        val now = System.currentTimeMillis()
        val logEntry = ComandaReconciliationLogEntity(
            id = logId,
            mutationId = "mut_hw_c2",
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            previousState = "RECONCILIATION_REQUIRED",
            resolutionAction = "COMPLETED",
            resultState = "COMPLETED",
            reconciliationReason = null,
            lastHttpStatus = null,
            notes = null,
            resolvedAt = now
        )
        db.comandaReconciliationLogDao().insert(logEntry)

        var threw = false
        try {
            // Attempt to insert the same log id — must throw SQLiteConstraintException
            db.comandaReconciliationLogDao().insert(logEntry)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("Duplicate log id must throw — audit log is immutable", threw)
    }

    // =========================================================================
    // HW-C3: getUnresolvedCount excludes COMPLETED and CANCELLED on real SQLite
    // =========================================================================

    @Test
    fun hwC3_getUnresolvedCountExcludesFinalizedStates() = runBlocking {
        val mutCompleted = "mut_hw_c3_completed_${UUID.randomUUID()}"
        val mutCancelled = "mut_hw_c3_cancelled_${UUID.randomUUID()}"
        val mutPending = "mut_hw_c3_pending_${UUID.randomUUID()}"

        insertMutationWithStatus(mutCompleted, "RECONCILIATION_REQUIRED")
        insertMutationWithStatus(mutCancelled, "RECONCILIATION_REQUIRED")
        insertMutationWithStatus(mutPending, "PENDING")

        repository.resolveReconciliationAsCompleted(
            mutationId = mutCompleted, actorUserId = actorUserId,
            deviceId = deviceId, tenantId = tenantId
        )
        repository.resolveReconciliationAsCancelled(
            mutationId = mutCancelled, actorUserId = actorUserId,
            deviceId = deviceId, tenantId = tenantId
        )

        val unresolvedCount = db.comandaMutationDao().getUnresolvedCount()
        assertEquals("Only PENDING should count as unresolved", 1, unresolvedCount)
    }

    // =========================================================================
    // HW-C4: resolveAsRetry transitions back to PENDING on real SQLite
    // =========================================================================

    @Test
    fun hwC4_retryResolutionTransitionsToPendingOnRealSQLite() = runBlocking {
        val mutationId = "mut_hw_c4_${UUID.randomUUID()}"
        insertMutationWithStatus(mutationId, "RECONCILIATION_REQUIRED", "MESA_NOT_FOUND")

        val result = repository.resolveReconciliationAsRetry(
            mutationId = mutationId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            notes = "Table re-created"
        )

        assertTrue("Retry resolution must succeed", result is ReconciliationResult.Success)

        val mutation = db.comandaMutationDao().getById(mutationId)
        assertEquals("Status must be PENDING", "PENDING", mutation?.status)
        assertNull("reconciliationReason must be cleared", mutation?.reconciliationReason)
        assertNull("lastErrorCode must be cleared", mutation?.lastErrorCode)
        assertNull("resolvedAt must be null after retry (not finalized)", mutation?.resolvedAt)
        assertEquals("Mutation id (K) must be preserved", mutationId, mutation?.id)

        // Eligible for dispatch
        val now = System.currentTimeMillis()
        val eligible = db.comandaMutationDao().getEligibleMutations(
            tenantId = tenantId,
            now = now,
            staleThreshold = now - 120_000L
        )
        assertTrue("Retried mutation must appear in eligible list", eligible.any { it.id == mutationId })

        val logs = db.comandaReconciliationLogDao().getByMutationId(mutationId)
        assertEquals("Exactly one audit log for retry", 1, logs.size)
        assertEquals("RETRY", logs[0].resolutionAction)
        assertEquals("PENDING", logs[0].resultState)
    }

    // =========================================================================
    // HW-C5: R-D (replace) atomicity on real SQLite storage
    // =========================================================================

    @Test
    fun hwC5_replacedResolutionIsAtomicOnRealSQLite() = runBlocking {
        val originalId = "mut_hw_c5_original_${UUID.randomUUID()}"
        insertMutationWithStatus(originalId, "RECONCILIATION_REQUIRED", "FORBIDDEN")

        val newPayload = """{"action":"abrir","mesa_id":"tbl_hw_recon_1","nome_cliente":"Novo HW Cliente"}"""
        val result = repository.resolveReconciliationAsReplaced(
            originalMutationId = originalId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            newPayloadJson = newPayload
        )

        assertTrue("Replace resolution must succeed", result is ReconciliationResult.Success)
        val successResult = result as ReconciliationResult.Success
        val newMutationId = successResult.resultState.removePrefix("CANCELLED+NEW:")

        // Original must be CANCELLED
        val original = db.comandaMutationDao().getById(originalId)
        assertEquals("CANCELLED", original?.status)
        assertNotNull(original?.resolvedAt)

        // New mutation with different K must exist
        val newMutation = db.comandaMutationDao().getById(newMutationId)
        assertNotNull("New mutation must exist on real SQLite", newMutation)
        assertNotEquals(originalId, newMutation?.id)
        assertEquals("PENDING", newMutation?.status)
        assertEquals(newPayload, newMutation?.payloadJson)

        // Audit log for original
        val logs = db.comandaReconciliationLogDao().getByMutationId(originalId)
        assertEquals(1, logs.size)
        assertEquals("REPLACED", logs[0].resolutionAction)
    }

    // =========================================================================
    // HW-C6: Wrong device rejected on real SQLite — no audit log written
    // =========================================================================

    @Test
    fun hwC6_wrongDeviceIsRejectedAndNoAuditLogWritten() = runBlocking {
        val mutationId = "mut_hw_c6_${UUID.randomUUID()}"
        insertMutationWithStatus(mutationId, "RECONCILIATION_REQUIRED", "TABLE_ALREADY_OCCUPIED")

        val result = repository.resolveReconciliationAsCompleted(
            mutationId = mutationId,
            actorUserId = actorUserId,
            deviceId = "WRONG_DEVICE_ID",
            tenantId = tenantId
        )

        assertTrue("Wrong device must be Rejected", result is ReconciliationResult.Rejected)

        val mutation = db.comandaMutationDao().getById(mutationId)
        assertEquals("Status must remain RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED", mutation?.status)
        assertNull("resolvedAt must remain null", mutation?.resolvedAt)

        val logs = db.comandaReconciliationLogDao().getByMutationId(mutationId)
        assertEquals("No audit log must be written on rejection", 0, logs.size)
    }
}
