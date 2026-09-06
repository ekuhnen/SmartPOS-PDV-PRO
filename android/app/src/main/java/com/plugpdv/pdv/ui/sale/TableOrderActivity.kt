package com.plugpdv.pdv.ui.sale

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.util.Log
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
import dagger.hilt.android.AndroidEntryPoint
import java.math.BigDecimal

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
    private var mesaOpenedAtElapsed = 0L
    private var firstVisibleMetricLogged = false
    private var firstVisibleMetricArmed = false
    private var cachedAdapterMetricLogged = false
    private var listVisibleMetricLogged = false
    private var loadingGoneMetricLogged = false
    private var firstChildMetricLogged = false
    private var recyclerLayoutMetricLogged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTableOrderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mesaOpenedAtElapsed = android.os.SystemClock.elapsedRealtime()
        binding.tvMesaLoadingState.visibility = View.VISIBLE
        binding.tvMesaLoadingState.text = getString(R.string.table_loading)
        Log.d("PERF_MESA", "initial_loading_visible_ms=0")

        val tableId = intent.getStringExtra("TABLE_ID")
        val tableNumber = intent.getIntExtra("TABLE_NUMBER", 0)
        val sectorId = intent.getStringExtra("SECTOR_ID")
        token = intent.getStringExtra("ACCESS_TOKEN")

        if (token == null || (tableId.isNullOrEmpty() && tableNumber <= 0)) {
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.let {
            it.title = "Mesa $tableNumber"
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

        saleViewModel.loadCatalog(token!!)
        tableOrderViewModel.init(tableId, tableNumber, sectorId, token!!)

        binding.btnUpdateTable.setOnClickListener {
            table?.calculateTotal()
            tableOrderViewModel.enviarCozinha {
                hasUnsavedChanges = false
                Toast.makeText(this, R.string.table_updated, Toast.LENGTH_SHORT).show()
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
            TableCheckoutBottomSheet.newInstance(tableId, tableNumber, token!!).show(supportFragmentManager, "checkout")
        }
    }

    override fun onResume() {
        super.onResume()
        // Revalidate both the Mesa detail and its separate item-allocation overlay
        // whenever checkout returns. Never rely on the previous in-memory map.
        if (::binding.isInitialized) tableOrderViewModel.syncTable()
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
        val currentTable = table ?: return super.onOptionsItemSelected(item)
        return when (item.itemId) {
            R.id.action_history -> {
                TableHistoryBottomSheet.newInstance(currentTable.id, currentTable.number).show(supportFragmentManager, "history")
                true
            }
            R.id.action_close_account -> {
                TableCheckoutBottomSheet.newInstance(currentTable.id, currentTable.number, token!!).show(supportFragmentManager, "checkout")
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
        orderAdapter = TableOrderItemAdapter(mutableListOf()) { item ->
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
        tableOrderViewModel.table.observe(this) { resolvedTable ->
            this.table = resolvedTable
            if (resolvedTable != null) {
                supportActionBar?.let {
                    var title = if (!resolvedTable.sectorName.isNullOrEmpty()) "Mesa ${resolvedTable.number} (${resolvedTable.sectorName})" else "Mesa ${resolvedTable.number}"
                    if (!resolvedTable.customerName.isNullOrEmpty() && resolvedTable.customerName != "null") {
                        title += " - ${resolvedTable.customerName}"
                    }
                    it.title = title
                }
            }
            updateUI()
        }

        tableOrderViewModel.isLoading.observe(this) { loading -> updateLoading(loading) }
        tableOrderViewModel.isRefreshing.observe(this) { updateMesaLoadingState() }
        tableOrderViewModel.readProvenance.observe(this) { updateMesaLoadingState() }
        tableOrderViewModel.error.observe(this) { error ->
            error?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }
        tableOrderViewModel.refreshWarning.observe(this) { warning ->
            warning?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            updateMesaLoadingState()
        }
        tableOrderViewModel.accountingSummary.observe(this) { _ ->
            updateUI()
        }
        tableOrderViewModel.sessionExpired.observe(this) { expired ->
            if (expired == true) {
                com.plugpdv.pdv.utils.KillSwitchManager.forceLogout(
                    applicationContext,
                    "Sessão expirada. Faça login novamente."
                )
            }
        }
    }

    private fun updateLoading(loading: Boolean?) {
        // Mesa opening and simple mutations never use a full-screen blocker.
        binding.loadingLayout.loadingOverlay.visibility = View.GONE
        updateMesaLoadingState()
    }

    private fun updateMesaLoadingState() {
        val hasItems = table?.items?.isNotEmpty() == true
        val refreshing = tableOrderViewModel.isRefreshing.value == true
        val warning = tableOrderViewModel.refreshWarning.value
        binding.tvMesaLoadingState.visibility = if (!hasItems || refreshing || warning != null) View.VISIBLE else View.GONE
        binding.tvMesaLoadingState.text = when {
            warning != null && !hasItems -> getString(R.string.table_refresh_failed)
            refreshing && hasItems -> getString(R.string.table_refreshing)
            warning != null -> getString(R.string.table_refresh_failed)
            else -> getString(R.string.table_loading)
        }
        if (hasItems) {
            binding.rvOrderItems.visibility = View.VISIBLE
            val elapsed = android.os.SystemClock.elapsedRealtime() - mesaOpenedAtElapsed
            if (!listVisibleMetricLogged && binding.rvOrderItems.visibility == View.VISIBLE) {
                listVisibleMetricLogged = true
                Log.d("PERF_MESA", "list_visibility_visible_ms=$elapsed")
                Log.d("PERF_MESA", "recycler_visibility=VISIBLE")
                Log.d("PERF_MESA", "recycler_height=${binding.rvOrderItems.height}")
                Log.d("PERF_MESA", "recycler_child_count=${binding.rvOrderItems.childCount}")
            }
            if (!loadingGoneMetricLogged) {
                loadingGoneMetricLogged = true
                Log.d("PERF_MESA", "loading_visibility_gone_ms=$elapsed")
            }
        }
    }

    fun updateUI() {
        val renderStart = android.os.SystemClock.elapsedRealtime()
        val currentTable = table
        val summary = tableOrderViewModel.accountingSummary.value
        if (summary != null) {
            val cm = CurrencyManager.getInstance()
            val displayCurrency = cm.selectedCurrency
            fun formatMinor(value: Long): String {
                val decimal = com.plugpdv.pdv.utils.ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(value, summary.baseMinorUnitDigits)
                return if (displayCurrency.equals(summary.baseCurrency, ignoreCase = true)) {
                    cm.formatExplicit(decimal.toDouble(), summary.baseCurrency)
                } else {
                    cm.quoteBaseAmount(BigDecimal.valueOf(decimal.toDouble()), summary.baseCurrency, displayCurrency)
                        .getOrNull()?.let { quote -> cm.formatExplicit(quote.transactionAmount.toDouble(), quote.transactionCurrency) }
                        ?: cm.formatExplicit(decimal.toDouble(), summary.baseCurrency)
                }
            }
            binding.tvTotal.text = formatMinor(summary.balanceBaseMinor)
            binding.tvComandaTotal.text = getString(R.string.comanda_total) + ": " + formatMinor(summary.totalBaseMinor)
            binding.tvComandaPaid.text = getString(R.string.comanda_paid) + ": " + formatMinor(summary.paidBaseMinor)
        } else {
            binding.tvTotal.text = CurrencyManager.getInstance().format(currentTable?.calculateTotal() ?: 0.0)
            binding.tvComandaTotal.text = ""
            binding.tvComandaPaid.text = ""
        }
        currentTable?.items?.let { items ->
            orderAdapter.setItems(items)
            Log.d("PERF_MESA", "adapter_submit_ms=${android.os.SystemClock.elapsedRealtime() - renderStart}")
            if (items.isNotEmpty() && !cachedAdapterMetricLogged) {
                cachedAdapterMetricLogged = true
                Log.d("PERF_MESA", "cached_adapter_nonempty_ms=${android.os.SystemClock.elapsedRealtime() - mesaOpenedAtElapsed}")
                Log.d("PERF_MESA", "recycler_visibility=${if (binding.rvOrderItems.visibility == View.VISIBLE) "VISIBLE" else if (binding.rvOrderItems.visibility == View.INVISIBLE) "INVISIBLE" else "GONE"}")
                Log.d("PERF_MESA", "recycler_height=${binding.rvOrderItems.height}")
                Log.d("PERF_MESA", "recycler_child_count=${binding.rvOrderItems.childCount}")
            }
            updateMesaLoadingState()
            if (items.isNotEmpty() && !firstVisibleMetricLogged && !firstVisibleMetricArmed) {
                firstVisibleMetricArmed = true
                binding.rvOrderItems.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (binding.rvOrderItems.childCount > 0 && binding.rvOrderItems.visibility == View.VISIBLE && binding.rvOrderItems.height > 0) {
                            if (!firstChildMetricLogged && binding.rvOrderItems.visibility == View.VISIBLE && binding.rvOrderItems.height > 0) {
                                firstChildMetricLogged = true
                                Log.d("PERF_MESA", "first_child_attached_ms=${android.os.SystemClock.elapsedRealtime() - mesaOpenedAtElapsed}")
                            }
                            if (!recyclerLayoutMetricLogged && binding.rvOrderItems.childCount > 0 && binding.rvOrderItems.height > 0) {
                                recyclerLayoutMetricLogged = true
                                Log.d("PERF_MESA", "recycler_layout_nonempty_ms=${android.os.SystemClock.elapsedRealtime() - mesaOpenedAtElapsed}")
                            }
                            firstVisibleMetricLogged = true
                            val elapsed = android.os.SystemClock.elapsedRealtime() - mesaOpenedAtElapsed
                            Log.d("PERF_MESA", "first_item_visible_ms=$elapsed")
                            Log.d("PERF_MESA", "first_visible_item_count=${binding.rvOrderItems.childCount}")
                            binding.rvOrderItems.viewTreeObserver.removeOnPreDrawListener(this)
                        }
                        return true
                    }
                })
            }
        }
        Log.d("PERF_MESA", "render_ms=${android.os.SystemClock.elapsedRealtime() - renderStart}")
    }

    private fun showItemOptions(item: TableItem) {
        val options = arrayOf(
            getString(R.string.add_observation),
            getString(R.string.remove_item),
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
            .setTitle(getString(R.string.remove_product, item.product.name))
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
                .setTitle(R.string.attention)
                .setMessage(R.string.leave_without_sending_order)
                .setPositiveButton(R.string.yes) { _, _ ->
                    finish()
                }
                .setNegativeButton(R.string.no, null)
                .show()
        } else {
            finish()
        }
    }
}
