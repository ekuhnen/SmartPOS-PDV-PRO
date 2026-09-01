package com.plugpdv.pdv.ui.reconciliation

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.databinding.ActivityReconciliationListBinding
import com.plugpdv.pdv.databinding.DialogReconciliationDetailBinding
import com.plugpdv.pdv.repository.ReconciliationReason
import com.plugpdv.pdv.ui.BaseActivity
import dagger.hilt.android.AndroidEntryPoint
import java.util.Date

@AndroidEntryPoint
class ReconciliationListActivity : BaseActivity() {

    private lateinit var binding: ActivityReconciliationListBinding
    private val viewModel: ReconciliationViewModel by viewModels()
    private lateinit var adapter: ReconciliationAdapter
    private var detailDialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReconciliationListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadReconciliations()
    }

    private fun setupRecyclerView() {
        adapter = ReconciliationAdapter(emptyList()) { mutation ->
            showDetailDialog(mutation)
        }
        binding.rvReconciliations.layoutManager = LinearLayoutManager(this)
        binding.rvReconciliations.adapter = adapter
    }

    private fun observeViewModel() {
        viewModel.reconciliations.observe(this) { list ->
            adapter.updateItems(list)
            if (list.isEmpty()) {
                binding.emptyStateContainer.visibility = View.VISIBLE
                binding.rvReconciliations.visibility = View.GONE
            } else {
                binding.emptyStateContainer.visibility = View.GONE
                binding.rvReconciliations.visibility = View.VISIBLE
            }
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.uiState.observe(this) { state ->
            state?.let {
                detailDialog?.dismiss()
                detailDialog = null

                val toastMessage = when (it) {
                    is ReconciliationUiState.Success -> it.message
                    is ReconciliationUiState.Rejected -> it.reason
                    is ReconciliationUiState.AlreadyResolved -> it.message
                }
                Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
                viewModel.clearUiState()
            }
        }
    }

    private fun showDetailDialog(mutation: ComandaMutationEntity) {
        val dialogBinding = DialogReconciliationDetailBinding.inflate(LayoutInflater.from(this))
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogBinding.root)
        detailDialog = dialog

        dialogBinding.tvDetailTarget.text = "Mesa ${mutation.tableId.removePrefix("tbl_")}"
        dialogBinding.tvDetailOperation.text = "Operação: ${ReconciliationReasonMapper.toHumanOperationType(mutation.operationType)}"
        val dateFormatted = DateFormat.format("dd/MM/yyyy, HH:mm", Date(mutation.createdAt)).toString()
        dialogBinding.tvDetailTimestamp.text = "Tentativa: $dateFormatted"
        dialogBinding.tvDetailExplanation.text = ReconciliationReasonMapper.toHumanMessage(mutation.reconciliationReason ?: mutation.lastErrorCode)

        val reason = (mutation.reconciliationReason ?: mutation.lastErrorCode).orEmpty().uppercase()

        // Contextual action visibility matrix (Section 7)
        val isAmbiguous = reason in setOf(
            ReconciliationReason.EMPTY_SERVER_ID,
            ReconciliationReason.IDEMPOTENCY_KEY_REUSED,
            "AMBIGUOUS_REMOTE_RESULT"
        )

        // R-A: Confirm Remote Success is only exposed when outcome is ambiguous
        dialogBinding.btnConfirmSuccess.visibility = if (isAmbiguous) View.VISIBLE else View.GONE

        // R-B: Retry is available unless entity is permanently closed/invalid where retry cannot succeed
        val isPermanentlyClosed = reason in setOf(
            ReconciliationReason.MESA_INACTIVE,
            "REMOTE_ENTITY_ALREADY_CLOSED"
        )
        dialogBinding.btnRetry.visibility = if (!isPermanentlyClosed) View.VISIBLE else View.GONE

        // R-C: Cancel is always available
        dialogBinding.btnCancelOperation.visibility = View.VISIBLE

        // Button Click Handlers with Confirmation & Loading / Double-tap prevention (Section 8 & 11)
        dialogBinding.btnConfirmSuccess.setOnClickListener {
            showConfirmSuccessDialog(mutation, dialogBinding)
        }

        dialogBinding.btnRetry.setOnClickListener {
            dialogBinding.btnConfirmSuccess.isEnabled = false
            dialogBinding.btnRetry.isEnabled = false
            dialogBinding.btnCancelOperation.isEnabled = false
            viewModel.resolveAsRetry(mutation.id)
        }

        dialogBinding.btnCancelOperation.setOnClickListener {
            showConfirmCancelDialog(mutation, dialogBinding)
        }

        dialog.show()
    }

    private fun showConfirmSuccessDialog(
        mutation: ComandaMutationEntity,
        dialogBinding: DialogReconciliationDetailBinding
    ) {
        AlertDialog.Builder(this)
            .setTitle("Confirmar Conclusão")
            .setMessage("Esta operação será considerada concluída e não será enviada novamente ao servidor.")
            .setPositiveButton("Confirmar") { _, _ ->
                dialogBinding.btnConfirmSuccess.isEnabled = false
                dialogBinding.btnRetry.isEnabled = false
                dialogBinding.btnCancelOperation.isEnabled = false
                viewModel.resolveAsCompleted(mutation.id)
            }
            .setNegativeButton("Voltar", null)
            .show()
    }

    private fun showConfirmCancelDialog(
        mutation: ComandaMutationEntity,
        dialogBinding: DialogReconciliationDetailBinding
    ) {
        AlertDialog.Builder(this)
            .setTitle("Cancelar Operação")
            .setMessage("Esta operação será cancelada e não será mais enviada ao servidor.")
            .setPositiveButton("Confirmar Cancelamento") { _, _ ->
                dialogBinding.btnConfirmSuccess.isEnabled = false
                dialogBinding.btnRetry.isEnabled = false
                dialogBinding.btnCancelOperation.isEnabled = false
                viewModel.resolveAsCancelled(mutation.id)
            }
            .setNegativeButton("Voltar", null)
            .show()
    }
}
