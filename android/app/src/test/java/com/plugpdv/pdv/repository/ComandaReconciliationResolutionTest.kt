package com.plugpdv.pdv.repository

import android.content.Context
import androidx.room.Room
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.KillSwitchManager
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * OFFLINE-FIRST-04A.2.6 — Durable Reconciliation Resolution Tests
 *
 * RC1: RECONCILIATION_REQUIRED does NOT auto-resume via new login (AUTH_REQUIRED resume path).
 * RC2: RECONCILIATION_REQUIRED does NOT auto-resume via verified device auth.
 * RC3: RECONCILIATION_REQUIRED does NOT auto-resume via lease reclaim on app restart.
 * RC4: RECONCILIATION_REQUIRED does NOT auto-resume via stale PROCESSING recovery.
 * RC5: R-A — resolveAsCompleted transitions to COMPLETED, writes audit log.
 * RC6: R-B — resolveAsRetry transitions to PENDING with same Idempotency-Key K.
 * RC7: R-C — resolveAsCancelled transitions to CANCELLED, record preserved.
 * RC8: R-D — resolveAsReplaced: original → CANCELLED, new mutation with new K' inserted.
 * RC9: Wrong tenant → Rejected, mutation unchanged.
 * RC10: Wrong actor → Rejected, mutation unchanged.
 * RC11: Double resolution (idempotent call) → AlreadyResolved, no state change.
 * RC12: Resolution called on non-RECONCILIATION_REQUIRED state → Rejected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ComandaReconciliationResolutionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var workScheduler: ComandaWorkScheduler
    private lateinit var repository: ComandaMutationRepository

    private val tenantId = "tenant_reconciliation_1"
    private val actorUserId = "user_operator_reconciliation"
    private val deviceId = "device_pos_reconciliation"
    private val token = "mock_valid_token_recon"

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

        workScheduler = mock()

        repository = ComandaMutationRepository(
            context = context,
            database = database,
            tableDao = database.tableDao(),
            comandaMutationDao = database.comandaMutationDao(),
            comandaSnapshotDao = database.comandaSnapshotDao(),
            comandaReconciliationLogDao = database.comandaReconciliationLogDao(),
            workScheduler = workScheduler
        )
    }

    @After
    fun tearDown() {
        KillSwitchManager.reset()
        database.close()
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private suspend fun createTestTable(id: String = "tbl_rc_1", number: Int = 1): TableEntity {
        val table = TableEntity(
            id = id,
            number = number,
            status = Table.Status.AVAILABLE,
            sectorName = "Salão",
            sectorId = "sec_rc_1",
            customerName = null,
            comandaId = null,
            localComandaId = null,
            peopleCount = 1,
            totalBalance = 0.0,
            paidAmount = 0.0,
            pendingBalance = 0.0,
            updatedAt = System.currentTimeMillis()
        )
        database.tableDao().insert(table)
        return table
    }

    /**
     * Inserts a mutation directly into the DB with a specified status for testing.
     */
    private suspend fun insertMutationWithStatus(
        id: String,
        tableId: String = "tbl_rc_1",
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
            localComandaId = "loc_cmd_rc_${id}",
            tableId = tableId,
            payloadJson = """{"action":"abrir","mesa_id":"$tableId"}""",
            resolvedPayloadJson = """{"action":"abrir","mesa_id":"$tableId"}""",
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
        database.comandaMutationDao().insert(mutation)
        return mutation
    }

    // =========================================================================
    // RC1 — RECONCILIATION_REQUIRED does NOT auto-resume via new login
    // =========================================================================

    @Test
    fun `RC1 - reconciliationRequired does not resume via resumeAfterAuthenticatedLogin`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc1",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.TABLE_ALREADY_OCCUPIED
        )

        // Simulate a new login: should NOT resume RECONCILIATION_REQUIRED mutations
        val resumed = repository.resumeAfterAuthenticatedLogin(
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId
        )
        assertEquals("Login resume must return 0 for RECONCILIATION_REQUIRED", 0, resumed)

        val mutation = database.comandaMutationDao().getById("mut_rc1")
        assertEquals("Status must remain RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED", mutation?.status)
    }

    // =========================================================================
    // RC2 — RECONCILIATION_REQUIRED does NOT auto-resume via verified device auth
    // =========================================================================

    @Test
    fun `RC2 - reconciliationRequired does not resume via resumeAfterVerifiedDeviceAuthorization`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc2",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.FORBIDDEN
        )

        val resumed = repository.resumeAfterVerifiedDeviceAuthorization(
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId
        )
        assertEquals("Device auth resume must return 0 for RECONCILIATION_REQUIRED", 0, resumed)

        val mutation = database.comandaMutationDao().getById("mut_rc2")
        assertEquals("Status must remain RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED", mutation?.status)
    }

    // =========================================================================
    // RC3 — RECONCILIATION_REQUIRED does NOT auto-resume via app restart
    //         (i.e., getEligibleMutations must not include it)
    // =========================================================================

    @Test
    fun `RC3 - reconciliationRequired is not returned in getEligibleMutations`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc3",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.MESA_NOT_FOUND
        )

        val now = System.currentTimeMillis()
        val eligible = database.comandaMutationDao().getEligibleMutations(
            tenantId = tenantId,
            now = now,
            staleThreshold = now - 120_000L
        )

        assertTrue(
            "RECONCILIATION_REQUIRED mutation must NOT appear in eligible list",
            eligible.none { it.id == "mut_rc3" }
        )
    }

    // =========================================================================
    // RC4 — RECONCILIATION_REQUIRED does NOT auto-resume via stale PROCESSING recovery
    // =========================================================================

    @Test
    fun `RC4 - reconciliationRequired is not affected by recoverStaleProcessing`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc4",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.TABLE_ALREADY_OCCUPIED
        )

        val now = System.currentTimeMillis()
        val recovered = database.comandaMutationDao().recoverStaleProcessing(
            staleThreshold = now + 999_999L, // way in the future: would recover anything PROCESSING
            now = now
        )
        assertEquals("Stale processing recovery must return 0 for non-PROCESSING rows", 0, recovered)

        val mutation = database.comandaMutationDao().getById("mut_rc4")
        assertEquals("Status must remain RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED", mutation?.status)
    }

    // =========================================================================
    // RC5 — R-A: Confirm Remote Success → COMPLETED
    // =========================================================================

    @Test
    fun `RC5 - resolveAsCompleted transitions to COMPLETED and writes audit log`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc5",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.EMPTY_SERVER_ID
        )

        val result = repository.resolveReconciliationAsCompleted(
            mutationId = "mut_rc5",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            notes = "Manually verified on POS terminal"
        )

        assertTrue("Result must be Success", result is ReconciliationResult.Success)
        assertEquals("COMPLETED", (result as ReconciliationResult.Success).resultState)

        val mutation = database.comandaMutationDao().getById("mut_rc5")
        assertEquals("Status must be COMPLETED", "COMPLETED", mutation?.status)
        assertNotNull("resolvedAt must be set", mutation?.resolvedAt)

        val logs = database.comandaReconciliationLogDao().getByMutationId("mut_rc5")
        assertEquals("Exactly one audit log entry", 1, logs.size)
        assertEquals("COMPLETED", logs[0].resolutionAction)
        assertEquals("RECONCILIATION_REQUIRED", logs[0].previousState)
        assertEquals("COMPLETED", logs[0].resultState)
        assertEquals(tenantId, logs[0].tenantId)
        assertEquals(actorUserId, logs[0].actorUserId)
        assertEquals(deviceId, logs[0].deviceId)
        assertEquals("Manually verified on POS terminal", logs[0].notes)
    }

    @Test
    fun `RC5b - getUnresolvedCount excludes COMPLETED mutations`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(id = "mut_rc5b", status = "RECONCILIATION_REQUIRED")

        repository.resolveReconciliationAsCompleted(
            mutationId = "mut_rc5b",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )

        val unresolvedCount = database.comandaMutationDao().getUnresolvedCount()
        assertEquals("Unresolved count must be 0 after COMPLETED", 0, unresolvedCount)
    }

    // =========================================================================
    // RC6 — R-B: Retry → PENDING with same Idempotency-Key K
    // =========================================================================

    @Test
    fun `RC6 - resolveAsRetry transitions to PENDING preserving original mutation id`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc6_original_K",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.MESA_NOT_FOUND
        )

        val result = repository.resolveReconciliationAsRetry(
            mutationId = "mut_rc6_original_K",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            notes = "Table was re-created on server"
        )

        assertTrue("Result must be Success", result is ReconciliationResult.Success)
        assertEquals("PENDING", (result as ReconciliationResult.Success).resultState)

        val mutation = database.comandaMutationDao().getById("mut_rc6_original_K")
        assertNotNull("Original mutation must still exist", mutation)
        assertEquals("Status must be PENDING", "PENDING", mutation?.status)
        assertNull("reconciliationReason must be cleared", mutation?.reconciliationReason)
        assertNull("lastErrorCode must be cleared", mutation?.lastErrorCode)
        assertNull("claimToken must be cleared", mutation?.claimToken)
        assertNull("resolvedAt must be null (not a terminal state)", mutation?.resolvedAt)
        // Idempotency-Key K unchanged
        assertEquals("Mutation id (K) must be preserved", "mut_rc6_original_K", mutation?.id)

        val logs = database.comandaReconciliationLogDao().getByMutationId("mut_rc6_original_K")
        assertEquals("Exactly one audit log entry", 1, logs.size)
        assertEquals("RETRY", logs[0].resolutionAction)
        assertEquals("PENDING", logs[0].resultState)

        // WorkManager must have been scheduled for retry
        verify(workScheduler, times(1)).scheduleCommandSync()
    }

    // =========================================================================
    // RC7 — R-C: Cancel → CANCELLED, record preserved
    // =========================================================================

    @Test
    fun `RC7 - resolveAsCancelled transitions to CANCELLED and record is preserved`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc7",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.TABLE_ALREADY_OCCUPIED
        )

        val result = repository.resolveReconciliationAsCancelled(
            mutationId = "mut_rc7",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            notes = "Operator acknowledged duplicate"
        )

        assertTrue("Result must be Success", result is ReconciliationResult.Success)
        assertEquals("CANCELLED", (result as ReconciliationResult.Success).resultState)

        val mutation = database.comandaMutationDao().getById("mut_rc7")
        assertNotNull("Record must be preserved (not deleted)", mutation)
        assertEquals("Status must be CANCELLED", "CANCELLED", mutation?.status)
        assertNotNull("resolvedAt must be set", mutation?.resolvedAt)

        val logs = database.comandaReconciliationLogDao().getByMutationId("mut_rc7")
        assertEquals("Exactly one audit log entry", 1, logs.size)
        assertEquals("CANCELLED", logs[0].resolutionAction)
        assertEquals("CANCELLED", logs[0].resultState)
    }

    @Test
    fun `RC7b - getUnresolvedCount excludes CANCELLED mutations`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(id = "mut_rc7b", status = "RECONCILIATION_REQUIRED")

        repository.resolveReconciliationAsCancelled(
            mutationId = "mut_rc7b",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )

        val unresolvedCount = database.comandaMutationDao().getUnresolvedCount()
        assertEquals("Unresolved count must be 0 after CANCELLED", 0, unresolvedCount)
    }

    // =========================================================================
    // RC8 — R-D: Replace → original CANCELLED + new mutation with new K'
    // =========================================================================

    @Test
    fun `RC8 - resolveAsReplaced cancels original and inserts new mutation with new K`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc8_original",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.FORBIDDEN
        )

        val newPayload = """{"action":"abrir","mesa_id":"tbl_rc_1","nome_cliente":"Novo Cliente"}"""

        val result = repository.resolveReconciliationAsReplaced(
            originalMutationId = "mut_rc8_original",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId,
            newPayloadJson = newPayload,
            notes = "Customer corrected"
        )

        assertTrue("Result must be Success", result is ReconciliationResult.Success)
        val successResult = result as ReconciliationResult.Success
        assertTrue("Result state should mention new mutation", successResult.resultState.startsWith("CANCELLED+NEW:"))
        val newMutationId = successResult.resultState.removePrefix("CANCELLED+NEW:")

        // Original must be CANCELLED
        val originalMutation = database.comandaMutationDao().getById("mut_rc8_original")
        assertNotNull("Original must be preserved", originalMutation)
        assertEquals("Original must be CANCELLED", "CANCELLED", originalMutation?.status)
        assertNotNull("Original resolvedAt must be set", originalMutation?.resolvedAt)

        // New mutation must exist with new K', correct status, and same identity triplet
        val newMutation = database.comandaMutationDao().getById(newMutationId)
        assertNotNull("New mutation must exist", newMutation)
        assertNotEquals("New mutation must have a different id (K')", "mut_rc8_original", newMutation?.id)
        assertEquals("New mutation status must be PENDING", "PENDING", newMutation?.status)
        assertEquals("New mutation tenantId must match", tenantId, newMutation?.tenantId)
        assertEquals("New mutation actorUserId must match", actorUserId, newMutation?.actorUserId)
        assertEquals("New mutation deviceId must match", deviceId, newMutation?.deviceId)
        assertEquals("New payload preserved", newPayload, newMutation?.payloadJson)
        assertEquals("attemptCount reset to 0", 0, newMutation?.attemptCount)

        // Audit log for original cancellation
        val logs = database.comandaReconciliationLogDao().getByMutationId("mut_rc8_original")
        assertEquals("Exactly one audit log for original", 1, logs.size)
        assertEquals("REPLACED", logs[0].resolutionAction)
        assertEquals("CANCELLED", logs[0].resultState)
    }

    // =========================================================================
    // RC9 — Wrong tenant → Rejected
    // =========================================================================

    @Test
    fun `RC9 - wrong tenant is rejected and mutation is unchanged`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc9",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.FORBIDDEN
        )

        val result = repository.resolveReconciliationAsCompleted(
            mutationId = "mut_rc9",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = "WRONG_TENANT"
        )

        assertTrue("Must be Rejected for wrong tenant", result is ReconciliationResult.Rejected)

        val mutation = database.comandaMutationDao().getById("mut_rc9")
        assertEquals("Status must remain RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED", mutation?.status)

        val logs = database.comandaReconciliationLogDao().getByMutationId("mut_rc9")
        assertEquals("No audit log written on rejected resolution", 0, logs.size)
    }

    // =========================================================================
    // RC10 — Wrong actor → Rejected
    // =========================================================================

    @Test
    fun `RC10 - wrong actor is rejected and mutation is unchanged`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(
            id = "mut_rc10",
            status = "RECONCILIATION_REQUIRED",
            reconciliationReason = ReconciliationReason.MESA_NOT_FOUND
        )

        val result = repository.resolveReconciliationAsCancelled(
            mutationId = "mut_rc10",
            actorUserId = "DIFFERENT_USER",
            deviceId = deviceId,
            tenantId = tenantId
        )

        assertTrue("Must be Rejected for wrong actor", result is ReconciliationResult.Rejected)

        val mutation = database.comandaMutationDao().getById("mut_rc10")
        assertEquals("Status must remain RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED", mutation?.status)

        val logs = database.comandaReconciliationLogDao().getByMutationId("mut_rc10")
        assertEquals("No audit log written on rejected resolution", 0, logs.size)
    }

    // =========================================================================
    // RC11 — Double resolution → AlreadyResolved (idempotent)
    // =========================================================================

    @Test
    fun `RC11 - double resolution after COMPLETED returns AlreadyResolved`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(id = "mut_rc11", status = "RECONCILIATION_REQUIRED")

        // First call — should succeed
        val first = repository.resolveReconciliationAsCompleted(
            mutationId = "mut_rc11",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )
        assertTrue("First call must succeed", first is ReconciliationResult.Success)

        // Second call — must be idempotent
        val second = repository.resolveReconciliationAsCompleted(
            mutationId = "mut_rc11",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )
        assertTrue("Second call must return AlreadyResolved", second is ReconciliationResult.AlreadyResolved)

        // Exactly one audit log entry written total
        val logs = database.comandaReconciliationLogDao().getByMutationId("mut_rc11")
        assertEquals("Only one audit log entry despite two calls", 1, logs.size)
    }

    @Test
    fun `RC11b - double resolution after CANCELLED returns AlreadyResolved`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(id = "mut_rc11b", status = "RECONCILIATION_REQUIRED")

        repository.resolveReconciliationAsCancelled(
            mutationId = "mut_rc11b",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )

        val second = repository.resolveReconciliationAsCompleted(
            mutationId = "mut_rc11b",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )
        assertTrue("Second call on CANCELLED must return AlreadyResolved", second is ReconciliationResult.AlreadyResolved)
    }

    // =========================================================================
    // RC12 — Resolution on non-RECONCILIATION_REQUIRED state → Rejected
    // =========================================================================

    @Test
    fun `RC12a - resolution on PENDING state returns Rejected`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(id = "mut_rc12a", status = "PENDING")

        val result = repository.resolveReconciliationAsCompleted(
            mutationId = "mut_rc12a",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )

        assertTrue("Must be Rejected for PENDING state", result is ReconciliationResult.Rejected)
        val mutation = database.comandaMutationDao().getById("mut_rc12a")
        assertEquals("Status must remain PENDING", "PENDING", mutation?.status)
    }

    @Test
    fun `RC12b - resolution on PAUSED state returns Rejected`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(id = "mut_rc12b", status = "PAUSED")

        val result = repository.resolveReconciliationAsCancelled(
            mutationId = "mut_rc12b",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )

        assertTrue("Must be Rejected for PAUSED state", result is ReconciliationResult.Rejected)
        val mutation = database.comandaMutationDao().getById("mut_rc12b")
        assertEquals("Status must remain PAUSED", "PAUSED", mutation?.status)
    }

    @Test
    fun `RC12c - resolution on SYNCED state returns AlreadyResolved`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(id = "mut_rc12c", status = "SYNCED")

        val result = repository.resolveReconciliationAsCompleted(
            mutationId = "mut_rc12c",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )

        assertTrue("SYNCED must return AlreadyResolved", result is ReconciliationResult.AlreadyResolved)
    }

    @Test
    fun `RC12d - resolution on non-existent mutation returns Rejected`() = runBlocking {
        val result = repository.resolveReconciliationAsCompleted(
            mutationId = "mut_does_not_exist",
            actorUserId = actorUserId,
            deviceId = deviceId,
            tenantId = tenantId
        )

        assertTrue("Non-existent mutation must return Rejected", result is ReconciliationResult.Rejected)
    }

    // =========================================================================
    // Extra: getReconciliationRequired scoping
    // =========================================================================

    @Test
    fun `getReconciliationRequired returns only RECONCILIATION_REQUIRED for given tenant`() = runBlocking {
        createTestTable()
        insertMutationWithStatus(id = "mut_recon_a", status = "RECONCILIATION_REQUIRED")
        insertMutationWithStatus(id = "mut_pending_b", status = "PENDING")
        insertMutationWithStatus(id = "mut_synced_c", status = "SYNCED")

        val reconciliationList = database.comandaMutationDao().getReconciliationRequired(tenantId)
        assertEquals("Should return exactly 1 RECONCILIATION_REQUIRED mutation", 1, reconciliationList.size)
        assertEquals("mut_recon_a", reconciliationList[0].id)
    }

    @Test
    fun `getReconciliationRequired is scoped to tenant`() = runBlocking {
        createTestTable()
        // This mutation belongs to our tenant
        insertMutationWithStatus(id = "mut_our_tenant", status = "RECONCILIATION_REQUIRED")

        // Insert a mutation for a different tenant directly
        val now = System.currentTimeMillis()
        val otherTenantMutation = ComandaMutationEntity(
            id = "mut_other_tenant",
            operationType = "OPEN_TABLE",
            tenantId = "OTHER_TENANT",
            actorUserId = actorUserId,
            deviceId = deviceId,
            localComandaId = "loc_cmd_other",
            tableId = "tbl_rc_1",
            payloadJson = "{}",
            createdAt = now,
            updatedAt = now,
            attemptCount = 0,
            nextRetryAt = now,
            status = "RECONCILIATION_REQUIRED"
        )
        database.comandaMutationDao().insert(otherTenantMutation)

        val reconciliationList = database.comandaMutationDao().getReconciliationRequired(tenantId)
        assertEquals("Should only return mutations for our tenant", 1, reconciliationList.size)
        assertEquals("mut_our_tenant", reconciliationList[0].id)
    }
}
