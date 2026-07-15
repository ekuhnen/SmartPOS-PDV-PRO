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

@AndroidEntryPoint
class ComandaFragment : Fragment() {
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
        token?.let { viewModel.fetchComanda(it, code) }
    }

    private fun openCommand(code: String) {
        val intent = Intent(requireActivity(), CommandOrderActivity::class.java).apply {
            putExtra("COMMAND_CODE", code)
            putExtra("ACCESS_TOKEN", token)
        }
        startActivity(intent)
    }

    private fun showOpenConfirmation(code: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_open_comanda, null)
        val etNickname = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etNickname)
        etNickname.setText(code)

        AlertDialog.Builder(requireContext())
            .setTitle("Comanda não encontrada")
            .setView(dialogView)
            .setPositiveButton("Abrir Comanda") { _, _ ->
                val nickname = etNickname.text.toString().trim()
                token?.let { viewModel.openComanda(it, code, nickname) }
            }
            .setNegativeButton("Cancelar", null)
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
