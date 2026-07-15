package com.plugpdv.pdv.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.plugpdv.pdv.database.TaxEntity
import com.plugpdv.pdv.databinding.LayoutCheckoutItemsSheetBinding

class CheckoutItemsBottomSheet : BottomSheetDialogFragment() {
    private var cartItems: MutableList<SaleViewModel.CartItem> = mutableListOf()
    private var activeTaxes: List<TaxEntity> = emptyList()
    private var listener: ((List<SaleViewModel.CartItem>) -> Unit)? = null
    private var _binding: LayoutCheckoutItemsSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutCheckoutItemsSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvCheckoutItems.layoutManager = LinearLayoutManager(context)
        val adapter = CheckoutProductAdapter(cartItems, activeTaxes) { updatedItems ->
            listener?.invoke(updatedItems)
        }
        binding.rvCheckoutItems.adapter = adapter

        binding.btnConfirm.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            items: ArrayList<SaleViewModel.CartItem>,
            taxes: ArrayList<TaxEntity>,
            listener: (List<SaleViewModel.CartItem>) -> Unit
        ): CheckoutItemsBottomSheet {
            return CheckoutItemsBottomSheet().apply {
                this.cartItems = items.toMutableList()
                this.activeTaxes = taxes
                this.listener = listener
            }
        }
    }
}
