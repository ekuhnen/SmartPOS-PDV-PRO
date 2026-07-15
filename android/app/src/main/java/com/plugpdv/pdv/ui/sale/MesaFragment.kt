package com.plugpdv.pdv.ui.sale

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.FragmentMesaBinding
import com.plugpdv.pdv.models.Table
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MesaFragment : Fragment() {
    private var _binding: FragmentMesaBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: MesaViewModel by viewModels()
    private var adapter: TableAdapter? = null
    private var sectorAdapter: SectorAdapter? = null
    private var token: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMesaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        token = arguments?.getString("ACCESS_TOKEN")
        
        setupRecyclerViews()
        observeViewModel()
        
        token?.let { viewModel.fetchTables(it) }
    }

    private fun setupRecyclerViews() {
        // Tables
        adapter = TableAdapter(mutableListOf(), { table ->
            if (table.status == Table.Status.AVAILABLE) {
                showOpenTableDialog(table)
            } else {
                openTableOrder(table)
            }
        }, { table ->
            showTransferDialog(table)
        })
        binding.rvTables.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvTables.adapter = adapter

        // Sectors
        sectorAdapter = SectorAdapter { sector ->
            viewModel.setSelectedSector(sector.id)
            sectorAdapter?.setSelectedSector(sector.id)
        }
        binding.rvSectors.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvSectors.adapter = sectorAdapter
    }

    private fun observeViewModel() {
        viewModel.tables.observe(viewLifecycleOwner) { tables ->
            adapter?.setTables(tables)
        }
        
        viewModel.sectors.observe(viewLifecycleOwner) { sectors ->
            sectorAdapter?.setSectors(sectors)
        }
        
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
        }
        
        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showOpenTableDialog(table: Table) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_open_table, null)
        val etName = dialogView.findViewById<EditText>(R.id.etCustomerName)
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        
        dialogView.findViewById<View>(R.id.btnConfirm).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isNotEmpty()) {
                token?.let { viewModel.openTable(it, table, name) }
                dialog.dismiss()
            } else {
                Toast.makeText(requireContext(), R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showTransferDialog(originTable: Table) {
        val availableTables = viewModel.tables.value?.filter { 
            it.status == Table.Status.AVAILABLE && it.id != originTable.id 
        } ?: emptyList()

        if (availableTables.isEmpty()) {
            Toast.makeText(requireContext(), "Nenhuma mesa livre disponível", Toast.LENGTH_SHORT).show()
            return
        }

        val tableNumbers = availableTables.map { "Mesa ${it.number} (${it.sectorName})" }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_destination)
            .setItems(tableNumbers) { _, which ->
                val destination = availableTables[which]
                token?.let { 
                    viewModel.transferTable(it, originTable, destination)
                    Toast.makeText(requireContext(), R.string.transfer_success, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openTableOrder(table: Table) {
        val intent = Intent(requireActivity(), TableOrderActivity::class.java).apply {
            putExtra("TABLE_NUMBER", table.number)
            putExtra("ACCESS_TOKEN", token)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        token?.let { viewModel.fetchTables(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance(token: String): MesaFragment {
            return MesaFragment().apply {
                arguments = Bundle().apply {
                    putString("ACCESS_TOKEN", token)
                }
            }
        }
    }
}
