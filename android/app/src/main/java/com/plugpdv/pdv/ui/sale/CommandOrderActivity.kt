package com.plugpdv.pdv.ui.sale

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ActivityCommandOrderBinding
import com.plugpdv.pdv.models.TableItem
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.utils.CurrencyManager
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CommandOrderActivity : BaseActivity() {
    private lateinit var binding: ActivityCommandOrderBinding
    private val saleViewModel: SaleViewModel by viewModels()
    private val commandViewModel: CommandViewModel by viewModels()
    
    private lateinit var productAdapter: ProductAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var orderAdapter: TableOrderItemAdapter
    
    private var commandCode: String? = null
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommandOrderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        commandCode = intent.getStringExtra("COMMAND_CODE")
        token = intent.getStringExtra("ACCESS_TOKEN")

        if (commandCode == null || token == null) {
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.let {
            it.title = "Comanda $commandCode"
            it.setDisplayHomeAsUpEnabled(true)
            binding.toolbar.setNavigationOnClickListener { finish() }
        }

        setupRecyclerViews()
        setupSearch()
        observeViewModels()

        token?.let {
            saleViewModel.loadCatalog(it)
            commandViewModel.fetchComanda(it, commandCode!!)
        }

        binding.btnAction.text = "ATUALIZAR COMANDA"
        binding.btnAction.setOnClickListener {
            finish()
        }

        binding.btnToggleOrder.setOnClickListener { toggleOrderSection() }
        binding.orderHeader.setOnClickListener { toggleOrderSection() }
    }

    private var isOrderExpanded = true
    private fun toggleOrderSection() {
        isOrderExpanded = !isOrderExpanded
        val targetPercent = if (isOrderExpanded) 0.6f else 0.9f
        val currentPercent = (binding.guideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).guidePercent
        
        android.animation.ValueAnimator.ofFloat(currentPercent, targetPercent).apply {
            duration = 300
            addUpdateListener { animator ->
                val lp = binding.guideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                lp.guidePercent = animator.animatedValue as Float
                binding.guideline.layoutParams = lp
            }
            start()
        }

        binding.btnToggleOrder.animate().rotation(if (isOrderExpanded) 0f else 180f).setDuration(300).start()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_table_order, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_close_account -> {
                val currentComanda = commandViewModel.comanda.value
                val currentItems = commandViewModel.items.value ?: emptyList()
                if (currentComanda != null && token != null && commandCode != null) {
                    val fakeTableId = commandCode.hashCode()
                    val fakeTable = com.plugpdv.pdv.models.Table(
                        id = currentComanda.id,
                        number = fakeTableId,
                        comandaId = currentComanda.id,
                        customerName = "",
                        total = currentComanda.total,
                        items = currentItems.toMutableList(),
                        status = com.plugpdv.pdv.models.Table.Status.OCCUPIED
                    )
                    com.plugpdv.pdv.utils.TableManager.updateTable(fakeTable)
                    com.plugpdv.pdv.ui.sale.TableCheckoutBottomSheet.newInstance(fakeTable.id, fakeTableId, token!!).show(supportFragmentManager, "checkout")
                } else {
                    Toast.makeText(this, "Aguarde o carregamento", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                saleViewModel.setSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupRecyclerViews() {
        productAdapter = ProductAdapter { product ->
            token?.let { commandViewModel.addItemToComanda(it, commandCode!!, product, 1, null) }
        }
        binding.rvProducts.layoutManager = GridLayoutManager(this, 3)
        binding.rvProducts.adapter = productAdapter

        categoryAdapter = CategoryAdapter { category ->
            saleViewModel.setSelectedCategory(category)
        }
        binding.rvCategories.adapter = categoryAdapter

        orderAdapter = TableOrderItemAdapter(mutableListOf()) { item ->
            if (!item.removed) {
                showItemOptions(item)
            }
        }
        binding.rvOrderItems.layoutManager = LinearLayoutManager(this)
        binding.rvOrderItems.adapter = orderAdapter
    }

    private fun observeViewModels() {
        saleViewModel.products.observe(this) { products -> productAdapter.submitList(products) }
        saleViewModel.categories.observe(this) { categories -> categoryAdapter.setCategories(categories) }
        saleViewModel.selectedCategory.observe(this) { category -> categoryAdapter.setSelectedCategory(category) }
        
        commandViewModel.items.observe(this) { items ->
            orderAdapter.setItems(items)
            updateTotal()
        }
        
        commandViewModel.isLoading.observe(this) { loading ->
            binding.loadingLayout.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        }
        
        commandViewModel.error.observe(this) { error ->
            error?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }
        
        commandViewModel.comanda.observe(this) { comanda ->
            comanda?.let {
                commandCode = it.id
                val displayName = it.nomeCliente ?: it.numero?.toString() ?: it.id
                supportActionBar?.title = "Comanda $displayName"
            }
        }
    }

    private fun updateTotal() {
        val total = commandViewModel.comanda.value?.total ?: 0.0
        binding.tvTotal.text = CurrencyManager.getInstance().format(total)
    }

    private fun showItemOptions(item: TableItem) {
        val options = arrayOf(
            getString(R.string.add_observation),
            "Remover Item",
            getString(R.string.cancel)
        )

        AlertDialog.Builder(this)
            .setTitle(item.product.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showObservationDialog(item)
                    1 -> showRemovalDialog(item)
                }
            }
            .show()
    }

    private fun showObservationDialog(item: TableItem) {
        val etObs = EditText(this).apply {
            setText(item.observation)
            hint = getString(R.string.observation_label)
        }
        
        AlertDialog.Builder(this)
            .setTitle(R.string.add_observation)
            .setView(etObs)
            .setPositiveButton(R.string.confirm) { _, _ ->
                token?.let {
                    commandViewModel.updateItemObservation(it, commandCode!!, item, etObs.text.toString())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRemovalDialog(item: TableItem) {
        val etReason = EditText(this).apply {
            hint = getString(R.string.removal_reason)
        }

        AlertDialog.Builder(this)
            .setTitle("Remover ${item.product.name}")
            .setView(etReason)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val reason = etReason.text.toString().trim()
                if (reason.isEmpty()) {
                    Toast.makeText(this, "O motivo é obrigatório para remover.", Toast.LENGTH_SHORT).show()
                } else {
                    token?.let {
                        commandViewModel.removeItemFromComanda(it, commandCode!!, item, reason)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    // Define a public updateUI method so TableCheckoutBottomSheet can refresh this Activity
    fun updateUI() {
        token?.let {
            if (commandCode != null) {
                commandViewModel.fetchComanda(it, commandCode!!)
            }
        }
    }
}
