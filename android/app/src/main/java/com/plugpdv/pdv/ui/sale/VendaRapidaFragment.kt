package com.plugpdv.pdv.ui.sale

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.FragmentVendaRapidaBinding
import com.plugpdv.pdv.ui.cashier.CashierActivity
import com.plugpdv.pdv.utils.CurrencyManager
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VendaRapidaFragment : Fragment() {
    private var _binding: FragmentVendaRapidaBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SaleViewModel by activityViewModels()
    private lateinit var adapter: ProductAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var cartAdapter: CartAdapter
    private var token: String? = null
    private var isCartExpanded = false
    private var scanner: com.plugpdv.pdv.hardware.Scanner? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVendaRapidaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        token = arguments?.getString("ACCESS_TOKEN")

        setupRecyclerViews()
        setupCartSection()
        setupSearch()
        observeViewModel()

        if (token != null && (viewModel.products.value.isNullOrEmpty())) {
            viewModel.loadCatalog(token!!)
        }

        binding.btnCheckout.setOnClickListener {
            val cartItems = viewModel.cart.value
            if (!cartItems.isNullOrEmpty()) {
                val intent = Intent(requireActivity(), CheckoutActivity::class.java).apply {
                    putExtra("ACCESS_TOKEN", token)
                    putExtra("TOTAL", viewModel.total.value)
                    putExtra("CART_ITEMS", ArrayList(cartItems))
                }
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), R.string.cart_empty, Toast.LENGTH_SHORT).show()
            }
        }



        binding.fabCurrency.setOnClickListener {
            (activity as? com.plugpdv.pdv.ui.BaseActivity)?.showCurrencySelector {
                val currentTotal = viewModel.total.value
                if (currentTotal != null) {
                    binding.tvTotal.text = CurrencyManager.getInstance().format(currentTotal)
                }
                adapter.notifyDataSetChanged()
                cartAdapter.notifyDataSetChanged()
            }
        }

        binding.btnViewCart.setOnClickListener {
            if (!viewModel.cart.value.isNullOrEmpty()) {
                CartBottomSheet().show(requireActivity().supportFragmentManager, "CartBottomSheet")
            } else {
                Toast.makeText(requireContext(), "Carrinho está vazio.", Toast.LENGTH_SHORT).show()
            }
        }

        setupHardwareScanner()
    }

    private fun setupHardwareScanner() {
        scanner = com.plugpdv.pdv.hardware.HardwareFactory.getScanner(requireContext())
    }

    override fun onResume() {
        super.onResume()
        scanner?.startScan(object : com.plugpdv.pdv.hardware.ScanCallback {
            override fun onScanResult(result: String) {
                activity?.runOnUiThread {
                    val product = viewModel.findProductByBarcode(result)
                    if (product != null) {
                        viewModel.addToCart(product)
                        Toast.makeText(requireContext(), getString(R.string.product_added, product.name), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Produto não encontrado: $result", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onScanFailed(error: String) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Erro no scanner: $error", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        scanner?.stopScan()
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupRecyclerViews() {
        adapter = ProductAdapter { product ->
            viewModel.addToCart(product)
            Toast.makeText(requireContext(), getString(R.string.product_added, product.name), Toast.LENGTH_SHORT).show()
        }
        binding.rvProducts.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvProducts.adapter = adapter

        categoryAdapter = CategoryAdapter { category ->
            viewModel.setSelectedCategory(category)
        }
        binding.rvCategories.adapter = categoryAdapter
    }

    private fun setupCartSection() {
        cartAdapter = CartAdapter(mutableListOf(), object : CartAdapter.OnCartItemListener {
            override fun onIncrease(item: SaleViewModel.CartItem) {
                viewModel.updateQuantity(item.product, 1)
            }

            override fun onDecrease(item: SaleViewModel.CartItem) {
                viewModel.updateQuantity(item.product, -1)
            }
        })
        binding.rvCartItems.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.rvCartItems.adapter = cartAdapter

        binding.cartHeader.setOnClickListener { toggleCartSection() }
        binding.btnToggleCart.setOnClickListener { toggleCartSection() }
        binding.llTotalContainer.setOnClickListener {
            if (!isCartExpanded) {
                toggleCartSection()
            }
        }
    }

    private fun toggleCartSection() {
        isCartExpanded = !isCartExpanded
        val targetPercent = if (isCartExpanded) 0.6f else 0.9f
        
        val animator = android.animation.ValueAnimator.ofFloat(binding.guideline.layoutParams.let { it as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams }.guidePercent, targetPercent)
        animator.addUpdateListener { valueAnimator ->
            val params = binding.guideline.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.guidePercent = valueAnimator.animatedValue as Float
            binding.guideline.layoutParams = params
        }
        animator.duration = 300
        animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        animator.start()

        binding.btnToggleCart.animate().rotation(if (isCartExpanded) 0f else 180f).setDuration(300).start()
    }

    private fun observeViewModel() {
        val cm = CurrencyManager.getInstance()
        
        viewModel.total.observe(viewLifecycleOwner) { total ->
            binding.tvTotal.text = cm.format(total ?: 0.0)
        }

        viewModel.cart.observe(viewLifecycleOwner) { cartItems ->
            cartAdapter.setItems(cartItems ?: emptyList())
            val qtdItems = cartItems?.sumOf { it.quantity } ?: 0
            binding.tvCartCount.text = "$qtdItems ${if (qtdItems == 1) "item" else "itens"}"
        }

        viewModel.products.observe(viewLifecycleOwner) { products ->
            adapter.submitList(products)
        }

        viewModel.categories.observe(viewLifecycleOwner) { categories ->
            categoryAdapter.setCategories(categories)
        }

        viewModel.selectedCategory.observe(viewLifecycleOwner) { category ->
            categoryAdapter.setSelectedCategory(category)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            updateLoading(loading)
        }
    }

    private fun updateLoading(loading: Boolean) {
        binding.loadingLayout.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance(token: String): VendaRapidaFragment {
            return VendaRapidaFragment().apply {
                arguments = Bundle().apply {
                    putString("ACCESS_TOKEN", token)
                }
            }
        }
    }
}
