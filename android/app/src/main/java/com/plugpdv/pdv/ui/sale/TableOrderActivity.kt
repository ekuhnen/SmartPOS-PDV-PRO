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
import com.plugpdv.pdv.databinding.ActivityTableOrderBinding
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.models.TableItem
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.TableManager
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TableOrderActivity : BaseActivity() {
    private lateinit var binding: ActivityTableOrderBinding
    private val saleViewModel: SaleViewModel by viewModels()
    private val tableOrderViewModel: TableOrderViewModel by viewModels()
    
    private var hasUnsavedChanges = false
    
    private lateinit var productAdapter: ProductAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var orderAdapter: TableOrderItemAdapter
    
    private var table: Table? = null
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTableOrderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val tableId = intent.getStringExtra("TABLE_ID")
        val tableNumber = intent.getIntExtra("TABLE_NUMBER", 0)
        val sectorId = intent.getStringExtra("SECTOR_ID")
        token = intent.getStringExtra("ACCESS_TOKEN")
        table = TableManager.getTable(tableId, tableNumber, sectorId)

        if (table == null || token == null) {
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.let {
            var title = if (!table!!.sectorName.isNullOrEmpty()) "Mesa ${table!!.number} (${table!!.sectorName})" else "Mesa ${table!!.number}"
            if (!table!!.customerName.isNullOrEmpty() && table!!.customerName != "null") {
                title += " - ${table!!.customerName}"
            }
            it.title = title
            it.setDisplayHomeAsUpEnabled(true)
            binding.toolbar.setNavigationOnClickListener { attemptToExit() }
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                attemptToExit()
            }
        })

        setupRecyclerViews()
        setupSearch()
        observeViewModels()

        token?.let {
            saleViewModel.loadCatalog(it)
            tableOrderViewModel.init(table!!, it)
        }

        binding.btnUpdateTable.setOnClickListener {
            table?.calculateTotal()
            tableOrderViewModel.enviarCozinha {
                hasUnsavedChanges = false
                Toast.makeText(this, "Mesa atualizada", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.fabCurrency.setOnClickListener {
            showCurrencySelector {
                updateUI()
            }
        }

        binding.btnToggleOrder.setOnClickListener {
            toggleOrderSection()
        }

        binding.orderHeader.setOnClickListener {
            toggleOrderSection()
        }

        binding.llTotalContainer.setOnClickListener {
            if (!isOrderExpanded) {
                toggleOrderSection()
            }
        }

        if (intent.getBooleanExtra("AUTO_CHECKOUT", false)) {
            TableCheckoutBottomSheet.newInstance(table!!.id, table!!.number, token!!).show(supportFragmentManager, "checkout")
        }
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
            R.id.action_history -> {
                TableHistoryBottomSheet.newInstance(table!!.id, table!!.number).show(supportFragmentManager, "history")
                true
            }
            R.id.action_close_account -> {
                TableCheckoutBottomSheet.newInstance(table!!.id, table!!.number, token!!).show(supportFragmentManager, "checkout")
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
        // Catalog Products
        productAdapter = ProductAdapter { product ->
            tableOrderViewModel.addItem(product)
            hasUnsavedChanges = true
        }
        binding.rvProducts.layoutManager = GridLayoutManager(this, 3)
        binding.rvProducts.adapter = productAdapter

        // Catalog Categories
        categoryAdapter = CategoryAdapter { category ->
            saleViewModel.setSelectedCategory(category)
        }
        binding.rvCategories.adapter = categoryAdapter

        // Table Items
        orderAdapter = TableOrderItemAdapter(table?.items ?: mutableListOf()) { item ->
            if (!item.removed) {
                showItemOptions(item)
            }
        }
        binding.rvOrderItems.layoutManager = LinearLayoutManager(this)
        binding.rvOrderItems.adapter = orderAdapter
    }

    private fun observeViewModels() {
        // SaleViewModel (Catalog)
        saleViewModel.products.observe(this) { products -> productAdapter.submitList(products) }
        saleViewModel.categories.observe(this) { categories -> categoryAdapter.setCategories(categories) }
        saleViewModel.selectedCategory.observe(this) { category -> categoryAdapter.setSelectedCategory(category) }
        saleViewModel.isLoading.observe(this) { loading -> updateLoading(loading) }

        // TableOrderViewModel (Table logic)
        tableOrderViewModel.table.observe(this) { _ -> updateUI() }
        tableOrderViewModel.isLoading.observe(this) { loading -> updateLoading(loading) }
        tableOrderViewModel.error.observe(this) { error ->
            error?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun updateLoading(loading: Boolean) {
        binding.loadingLayout.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
    }

    fun updateUI() {
        binding.tvTotal.text = CurrencyManager.getInstance().format(table?.calculateTotal() ?: 0.0)
        orderAdapter.notifyDataSetChanged()
        productAdapter.notifyDataSetChanged()
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
                item.observation = etObs.text.toString()
                hasUnsavedChanges = true
                updateUI()
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
                    Toast.makeText(this, R.string.reason_required, Toast.LENGTH_SHORT).show()
                } else {
                    tableOrderViewModel.removeItem(item, reason)
                    hasUnsavedChanges = true
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun attemptToExit() {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("Atenção")
                .setMessage("Deseja voltar sem ter enviado o pedido da mesa realmente?")
                .setPositiveButton("Sim") { _, _ ->
                    finish()
                }
                .setNegativeButton("Não", null)
                .show()
        } else {
            finish()
        }
    }
}
