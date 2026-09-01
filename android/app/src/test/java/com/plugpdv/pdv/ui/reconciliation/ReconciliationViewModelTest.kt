package com.plugpdv.pdv.ui.reconciliation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.repository.ComandaMutationRepository
import com.plugpdv.pdv.repository.ReconciliationReason
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.KillSwitchManager
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import kotlinx.coroutines.delay
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * OFFLINE-FIRST-04A.2.6B — UI & ViewModel Tests (UI-R1 .. UI-R10)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ReconciliationViewModelTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var workScheduler: ComandaWorkScheduler
    private lateinit var repository: ComandaMutationRepository
    private lateinit var viewModel: ReconciliationViewModel

    private val tenantId = "tenant_ui_recon_1"
    private val actorUserId = "actor_ui_recon_1"
    private val deviceId = "device_ui_pos_1"
    private val token = "mock_token_ui"

    private suspend fun drainLooper() {
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        delay(50)
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    }

    private suspend fun waitUntil(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && (System.currentTimeMillis() - start) < timeoutMs) {
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            delay(20)
        }
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Constants.TOKEN, token)
            .putString(Constants.USER_ID, actorUserId)
            .putString(Constants.OPERATOR_ID, actorUserId)
            .putBoolean(Constants.HAS_MESA, true)
            .apply()

        TenantBindingStore.setActiveTenantId(context, tenantId)
        DeviceIdProvider.clear(context)

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        workScheduler = mock()
        repository = ComandaMutationRepository(
            context = context,
            database = db,
            tableDao = db.tableDao(),
            comandaMutationDao = db.comandaMutationDao(),
            comandaSnapshotDao = db.comandaSnapshotDao(),
            comandaReconciliationLogDao = db.comandaReconciliationLogDao(),
            workScheduler = workScheduler
        )

        viewModel = ReconciliationViewModel(context, repository)
    }

    @After
    fun tearDown() {
        KillSwitchManager.reset()
        db.close()
    }

    private suspend fun createTestTable(id: String = "tbl_ui_1", number: Int = 1): TableEntity {
        val table = TableEntity(
            id = id,
            number = number,
            status = Table.Status.AVAILABLE,
            sectorName = "Salão",
            sectorId = "sec_ui_1",
            customerName = null,
            comandaId = null,
            localComandaId = null,
            peopleCount = 1,
            totalBalance = 0.0,
            paidAmount = 0.0,
            pendingBalance = 0.0,
            updatedAt = System.currentTimeMillis()
        )
        db.tableDao().insert(table)
        return table
    }

    private suspend fun insertMutationWithStatus(
        id: String,
        status: String,
        reason: String? = null,
        mActorUserId: String = actorUserId,
        mDeviceId: String = deviceId,
        mTenantId: String = tenantId
    ): ComandaMutationEntity {
        val now = System.currentTimeMillis()
        val mutation = ComandaMutationEntity(
            id = id,
            operationType = "OPEN_TABLE",
            tenantId = mTenantId,
            actorUserId = mActorUserId,
            deviceId = mDeviceId,
            localComandaId = "loc_cmd_ui_$id",
            tableId = "tbl_ui_1",
            payloadJson = """{"action":"abrir","mesa_id":"tbl_ui_1"}""",
            resolvedPayloadJson = """{"action":"abrir","mesa_id":"tbl_ui_1"}""",
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
    // UI-R1: List shows mutation in RECONCILIATION_REQUIRED
    // =========================================================================
    @Test
    fun uiR1_listShowsReconciliationRequiredMutations() = runBlocking {
        createTestTable()
        val currentDeviceId = DeviceIdProvider.get(context)
        insertMutationWithStatus(
            id = "mut_ui_r1",
            status = "RECONCILIATION_REQUIRED",
            reason = ReconciliationReason.TABLE_ALREADY_OCCUPIED,
            mDeviceId = currentDeviceId
        )

        viewModel.loadReconciliations()
        drainLooper()

        val list = viewModel.reconciliations.value
        assertNotNull(list)
        assertEquals(1, list?.size)
        assertEquals("mut_ui_r1", list?.get(0)?.id)
        assertEquals(1, viewModel.count.value)
    }

    // =========================================================================
    // UI-R2: PENDING, SYNCED, COMPLETED, CANCELLED mutations do NOT appear
    // =========================================================================
    @Test
    fun uiR2_listExcludesNonReconciliationMutations() = runBlocking {
        createTestTable()
        val currentDeviceId = DeviceIdProvider.get(context)

        insertMutationWithStatus("mut_pending", "PENDING", mDeviceId = currentDeviceId)
        insertMutationWithStatus("mut_synced", "SYNCED", mDeviceId = currentDeviceId)
        insertMutationWithStatus("mut_completed", "COMPLETED", mDeviceId = currentDeviceId)
        insertMutationWithStatus("mut_cancelled", "CANCELLED", mDeviceId = currentDeviceId)
        insertMutationWithStatus("mut_recon", "RECONCILIATION_REQUIRED", reason = ReconciliationReason.FORBIDDEN, mDeviceId = currentDeviceId)

        viewModel.loadReconciliations()
        drainLooper()

        val list = viewModel.reconciliations.value
        assertNotNull(list)
        assertEquals("Only RECONCILIATION_REQUIRED should be in the list", 1, list?.size)
        assertEquals("mut_recon", list?.get(0)?.id)
        assertEquals(1, viewModel.count.value)
    }

    // =========================================================================
    // UI-R3: Retry calls R-B repository method once and schedules WorkManager
    // =========================================================================
    @Test
    fun uiR3_retryExecutesResolutionAndSchedulesWorkManager() = runBlocking {
        createTestTable()
        val currentDeviceId = DeviceIdProvider.get(context)
        insertMutationWithStatus("mut_retry", "RECONCILIATION_REQUIRED", reason = ReconciliationReason.MESA_NOT_FOUND, mDeviceId = currentDeviceId)

        viewModel.resolveAsRetry("mut_retry", "Operator clicked retry")
        waitUntil { viewModel.uiState.value != null }

        val state = viewModel.uiState.value
        assertTrue("UI state must be Success", state is ReconciliationUiState.Success)
        assertEquals("RETRY", (state as ReconciliationUiState.Success).action)

        val updated = db.comandaMutationDao().getById("mut_retry")
        assertEquals("Status must be PENDING", "PENDING", updated?.status)

        verify(workScheduler, times(1)).scheduleCommandSync()
    }

    // =========================================================================
    // UI-R4: Double tap / call does not cause duplicate resolutions
    // =========================================================================
    @Test
    fun uiR4_doubleTapDoesNotCauseDuplicateResolutions() = runBlocking {
        createTestTable()
        val currentDeviceId = DeviceIdProvider.get(context)
        insertMutationWithStatus("mut_double_tap", "RECONCILIATION_REQUIRED", reason = ReconciliationReason.TABLE_ALREADY_OCCUPIED, mDeviceId = currentDeviceId)

        // First call
        viewModel.resolveAsCancelled("mut_double_tap")
        waitUntil { viewModel.uiState.value != null }

        val firstState = viewModel.uiState.value
        assertTrue("First call should succeed", firstState is ReconciliationUiState.Success)

        // Second call (simulating double tap before list refreshed)
        viewModel.clearUiState()
        viewModel.resolveAsCancelled("mut_double_tap")
        waitUntil { viewModel.uiState.value != null }

        val secondState = viewModel.uiState.value
        assertTrue("Second call should result in AlreadyResolved", secondState is ReconciliationUiState.AlreadyResolved)

        // Verify only 1 audit log entry was written
        val logs = db.comandaReconciliationLogDao().getByMutationId("mut_double_tap")
        assertEquals(1, logs.size)
    }

    // =========================================================================
    // UI-R5: Cancel action updates status to CANCELLED and writes audit log
    // =========================================================================
    @Test
    fun uiR5_cancelActionTransitionsToCancelledWithAuditLog() = runBlocking {
        createTestTable()
        val currentDeviceId = DeviceIdProvider.get(context)
        insertMutationWithStatus("mut_cancel", "RECONCILIATION_REQUIRED", reason = ReconciliationReason.TABLE_ALREADY_OCCUPIED, mDeviceId = currentDeviceId)

        viewModel.resolveAsCancelled("mut_cancel", "Operator confirmed cancellation")
        waitUntil { viewModel.uiState.value != null }

        val state = viewModel.uiState.value
        assertTrue(state is ReconciliationUiState.Success)

        val mutation = db.comandaMutationDao().getById("mut_cancel")
        assertEquals("CANCELLED", mutation?.status)
        assertNotNull(mutation?.resolvedAt)

        val logs = db.comandaReconciliationLogDao().getByMutationId("mut_cancel")
        assertEquals(1, logs.size)
        assertEquals("CANCELLED", logs[0].resolutionAction)
    }

    // =========================================================================
    // UI-R6: Confirm success action updates status to COMPLETED and writes audit log
    // =========================================================================
    @Test
    fun uiR6_confirmSuccessTransitionsToCompletedWithAuditLog() = runBlocking {
        createTestTable()
        val currentDeviceId = DeviceIdProvider.get(context)
        insertMutationWithStatus("mut_confirm", "RECONCILIATION_REQUIRED", reason = ReconciliationReason.EMPTY_SERVER_ID, mDeviceId = currentDeviceId)

        viewModel.resolveAsCompleted("mut_confirm", "Manually checked on POS")
        waitUntil { viewModel.uiState.value != null }

        val state = viewModel.uiState.value
        assertTrue(state is ReconciliationUiState.Success)

        val mutation = db.comandaMutationDao().getById("mut_confirm")
        assertEquals("COMPLETED", mutation?.status)
        assertNotNull(mutation?.resolvedAt)

        val logs = db.comandaReconciliationLogDao().getByMutationId("mut_confirm")
        assertEquals(1, logs.size)
        assertEquals("COMPLETED", logs[0].resolutionAction)
    }

    // =========================================================================
    // UI-R7: Rejected result shows user feedback and preserves mutation in list
    // =========================================================================
    @Test
    fun uiR7_rejectedResultShowsFeedbackAndPreservesMutation() = runBlocking {
        createTestTable()
        // Insert mutation owned by a DIFFERENT actor
        insertMutationWithStatus(
            id = "mut_other_actor",
            status = "RECONCILIATION_REQUIRED",
            reason = ReconciliationReason.TABLE_ALREADY_OCCUPIED,
            mActorUserId = "OTHER_USER_ID"
        )

        // Attempting to resolve with current user -> should be rejected
        viewModel.resolveAsCancelled("mut_other_actor")
        waitUntil { viewModel.uiState.value != null }

        val state = viewModel.uiState.value
        assertTrue("UI state must be Rejected", state is ReconciliationUiState.Rejected)

        val mutation = db.comandaMutationDao().getById("mut_other_actor")
        assertEquals("Mutation status must remain RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED", mutation?.status)
    }

    // =========================================================================
    // UI-R8: AlreadyResolved updates screen without error
    // =========================================================================
    @Test
    fun uiR8_alreadyResolvedUpdatesScreenWithoutError() = runBlocking {
        createTestTable()
        val currentDeviceId = DeviceIdProvider.get(context)
        insertMutationWithStatus("mut_already_comp", "COMPLETED", mDeviceId = currentDeviceId)

        viewModel.resolveAsCompleted("mut_already_comp")
        waitUntil { viewModel.uiState.value != null }

        val state = viewModel.uiState.value
        assertTrue("UI state must be AlreadyResolved", state is ReconciliationUiState.AlreadyResolved)
    }

    // =========================================================================
    // UI-R9: After resolution, mutation disappears from active list when reloaded
    // =========================================================================
    @Test
    fun uiR9_mutationDisappearsFromActiveListAfterResolution() = runBlocking {
        createTestTable()
        val currentDeviceId = DeviceIdProvider.get(context)
        insertMutationWithStatus("mut_disappear", "RECONCILIATION_REQUIRED", reason = ReconciliationReason.FORBIDDEN, mDeviceId = currentDeviceId)

        viewModel.loadReconciliations()
        waitUntil { viewModel.count.value == 1 }
        assertEquals(1, viewModel.count.value)

        viewModel.resolveAsCancelled("mut_disappear")
        waitUntil { viewModel.count.value == 0 }

        assertEquals("Count must update to 0 after resolution", 0, viewModel.count.value)
        assertEquals(0, viewModel.reconciliations.value?.size)
    }

    // =========================================================================
    // UI-R10: ViewModel recreation maintains consistent state by re-querying Room
    // =========================================================================
    @Test
    fun uiR10_viewModelRecreationMaintainsConsistentStateFromRoom() = runBlocking {
        createTestTable()
        val currentDeviceId = DeviceIdProvider.get(context)
        insertMutationWithStatus("mut_recreate_1", "RECONCILIATION_REQUIRED", reason = ReconciliationReason.TABLE_ALREADY_OCCUPIED, mDeviceId = currentDeviceId)
        insertMutationWithStatus("mut_recreate_2", "RECONCILIATION_REQUIRED", reason = ReconciliationReason.MESA_NOT_FOUND, mDeviceId = currentDeviceId)

        // ViewModel 1 loads
        viewModel.loadReconciliations()
        waitUntil { viewModel.count.value == 2 }
        assertEquals(2, viewModel.count.value)

        // Resolve one mutation
        viewModel.resolveAsCancelled("mut_recreate_1")
        waitUntil { viewModel.count.value == 1 }
        assertEquals(1, viewModel.count.value)

        // Simulate Activity recreate / ViewModel recreate
        val newViewModel = ReconciliationViewModel(context, repository)
        newViewModel.loadReconciliations()
        waitUntil { newViewModel.count.value == 1 }

        assertEquals("New ViewModel instance must reflect Room state (1 item remaining)", 1, newViewModel.count.value)
        assertEquals("mut_recreate_2", newViewModel.reconciliations.value?.get(0)?.id)
    }
}
