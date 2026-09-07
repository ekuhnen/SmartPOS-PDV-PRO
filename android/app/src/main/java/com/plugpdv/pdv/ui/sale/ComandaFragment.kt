package com.plugpdv.pdv.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.journeyapps.barcodescanner.ScanContract
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import com.journeyapps.barcodescanner.ScanOptions
import com.plugpdv.pdv.databinding.FragmentComandaBinding
import com.plugpdv.pdv.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import com.plugpdv.pdv.repository.RestaurantOpsRepository
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ComandaFragment : Fragment() {
    @Inject lateinit var restaurantOpsRepository: RestaurantOpsRepository
    private var _binding: FragmentComandaBinding? = null
    private val binding get() = _binding!!
    private var token: String? = null
    private val viewModel: CommandViewModel by viewModels()

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { openCommand(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentComandaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        token = arguments?.getString("ACCESS_TOKEN")

        binding.btnScanQR.setOnClickListener { startScanner() }
        binding.btnSearchCode.setOnClickListener { searchCommand() }
        
        binding.etCommandCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                searchCommand()
                true
            } else false
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.comanda.observe(viewLifecycleOwner) { comanda ->
            comanda?.let { openCommand(it.id) }
        }

        viewModel.notFound.observe(viewLifecycleOwner) { code ->
            code?.let { 
                showOpenConfirmation(it)
                viewModel.clearNotFound()
            }
        }

        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            results ?: return@observe
            viewModel.clearSearchResults()
            if (results.firstOrNull()?.searchResultCount?.let { it > results.size } == true) {
                Toast.makeText(requireContext(), R.string.mesa_comandas_refresh_failed, Toast.LENGTH_LONG).show()
                return@observe
            }
            if (binding.etCommandCode.text.toString().trim().toIntOrNull() != null && results.any { it.physicalMesaId != null }) {
                expandMesaSearch(results)
                return@observe
            }
            when (results.size) {
                0 -> showOpenConfirmation(binding.etCommandCode.text.toString().trim())
                1 -> openDiscovery(results.single())
                else -> ComandaSelectorDialog.show(requireContext(), null, results) { openDiscovery(it) }
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
        }

        viewModel.openFinished.observe(viewLifecycleOwner) { code ->
            code?.let { 
                openCommand(it)
                viewModel.clearOpenFinished()
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.btnSearchCode.isEnabled = !loading
            binding.btnScanQR.isEnabled = !loading
        }
    }

    private fun startScanner() {
        val options = ScanOptions().apply {
            setPrompt("Aponte para o QR Code da Comanda")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        barcodeLauncher.launch(options)
    }

    private fun searchCommand() {
        val code = binding.etCommandCode.text.toString().trim()
        if (code.isEmpty()) {
            binding.tilCode.error = "Digite o código"
            return
        }
        binding.tilCode.error = null
        token?.let { viewModel.searchOperational(it, code) }
    }

    private fun openCommand(code: String) {
        val intent = Intent(requireActivity(), CommandOrderActivity::class.java).apply {
            putExtra("COMMAND_CODE", code)
            putExtra("ACCESS_TOKEN", token)
        }
        startActivity(intent)
    }

    private fun openDiscovery(item: com.plugpdv.pdv.models.ComandaDiscoveryItem) {
        val mesaId = item.physicalMesaId
        val auth = token
        if (mesaId != null && auth != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                restaurantOpsRepository.discoverComandasForMesa(auth, mesaId).fold(
                    onSuccess = { discovery ->
                        if (discovery.comandas.any { it.comandaId == item.comandaId }) openDiscoveryDirect(item)
                        else Toast.makeText(requireContext(), R.string.comanda_not_open, Toast.LENGTH_LONG).show()
                    },
                    onFailure = { Toast.makeText(requireContext(), R.string.mesa_comandas_refresh_failed, Toast.LENGTH_LONG).show() }
                )
            }
        } else openDiscoveryDirect(item)
    }

    private fun openDiscoveryDirect(item: com.plugpdv.pdv.models.ComandaDiscoveryItem) {
        startActivity(Intent(requireActivity(), CommandOrderActivity::class.java).apply {
            putExtra("COMMAND_CODE", item.comandaId)
            putExtra("PHYSICAL_TABLE_ID", item.physicalMesaId)
            putExtra("PHYSICAL_TABLE_NUMBER", item.mesaNumero ?: 0)
            putExtra("PHYSICAL_SECTOR_ID", item.setor)
            putExtra("ACCESS_TOKEN", token)
        })
    }

    private fun expandMesaSearch(results: List<com.plugpdv.pdv.models.ComandaDiscoveryItem>) {
        val auth = token ?: return
        val mesaIds = results.mapNotNull { it.physicalMesaId }.distinct()
        viewLifecycleOwner.lifecycleScope.launch {
            val expanded = mesaIds.flatMap { id -> restaurantOpsRepository.discoverComandasForMesa(auth, id).getOrNull()?.comandas.orEmpty() }
                .distinctBy { it.comandaId }
            when (expanded.size) {
                0 -> showOpenConfirmation(binding.etCommandCode.text.toString().trim())
                1 -> openDiscovery(expanded.single())
                else -> ComandaSelectorDialog.show(requireContext(), null, expanded) { openDiscovery(it) }
            }
        }
    }

    private fun showOpenConfirmation(code: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_open_comanda, null)
        val etNickname = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etNickname)
        etNickname.setText(code)

        AlertDialog.Builder(requireContext())
                .setTitle(R.string.comanda_not_found)
            .setView(dialogView)
                .setPositiveButton(R.string.open_comanda) { _, _ ->
                val nickname = etNickname.text.toString().trim()
                token?.let { viewModel.openComanda(it, code, nickname) }
            }
                .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance(token: String): ComandaFragment {
            return ComandaFragment().apply {
                arguments = Bundle().apply {
                    putString("ACCESS_TOKEN", token)
                }
            }
        }
    }
}
