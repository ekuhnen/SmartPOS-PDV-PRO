package com.plugpdv.pdv.ui.sale

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import com.google.android.material.tabs.TabLayoutMediator
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ActivityDirectSaleBinding
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.ui.auth.AuthViewModel
import com.plugpdv.pdv.ui.auth.LoginResult
import com.plugpdv.pdv.ui.cashier.CashierActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DirectSaleActivity : BaseActivity() {
    private lateinit var binding: ActivityDirectSaleBinding
    private val saleViewModel: SaleViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDirectSaleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        token = intent.getStringExtra("ACCESS_TOKEN")

        if (intent.getBooleanExtra("CLEAR_CART", false)) {
            saleViewModel.clearCart()
        }

        setupViewPager()

        binding.btnGlobalCashier.setOnClickListener {
            val intent = Intent(this, CashierActivity::class.java).apply {
                putExtra("ACCESS_TOKEN", token)
            }
            startActivity(intent)
        }
    }

    private fun setupViewPager() {
        val prefs = getSharedPreferences(com.plugpdv.pdv.utils.Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val hasMesa = prefs.getBoolean(com.plugpdv.pdv.utils.Constants.HAS_MESA, false)
        val hasVendaDireta = prefs.getBoolean(com.plugpdv.pdv.utils.Constants.HAS_VENDA_DIRETA, true)
        val hasComanda = prefs.getBoolean(com.plugpdv.pdv.utils.Constants.HAS_COMANDA, false)

        val fragments = mutableListOf<androidx.fragment.app.Fragment>()
        val titles = mutableListOf<String>()

        if (hasMesa) {
            fragments.add(MesaFragment.newInstance(token ?: ""))
            titles.add(getString(R.string.tab_mesa))
        }

        if (hasVendaDireta) {
            fragments.add(VendaRapidaFragment.newInstance(token ?: ""))
            titles.add(getString(R.string.tab_venda_rapida))
        }

        if (hasComanda) {
            fragments.add(ComandaFragment.newInstance(token ?: ""))
            titles.add(getString(R.string.tab_comanda))
        }

        // Fallback se nada estiver liberado
        if (fragments.isEmpty()) {
            fragments.add(VendaRapidaFragment.newInstance(token ?: ""))
            titles.add(getString(R.string.tab_venda_rapida))
        }

        val adapter = SalePagerAdapter(this, fragments)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()

        val vendaRapidaIndex = titles.indexOf(getString(R.string.tab_venda_rapida))
        if (vendaRapidaIndex != -1) {
            binding.viewPager.setCurrentItem(vendaRapidaIndex, false)
        } else {
            binding.viewPager.setCurrentItem(0, false)
        }
    }

    fun switchToTab(position: Int) {
        binding.viewPager.setCurrentItem(position, true)
    }
}
