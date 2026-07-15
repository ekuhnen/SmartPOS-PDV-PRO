package com.plugpdv.pdv.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.plugpdv.pdv.databinding.LayoutCartBottomSheetBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CartBottomSheet : BottomSheetDialogFragment() {
    private var _binding: LayoutCartBottomSheetBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SaleViewModel by activityViewModels()
    private lateinit var adapter: CartAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutCartBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = CartAdapter(mutableListOf(), object : CartAdapter.OnCartItemListener {
            override fun onIncrease(item: SaleViewModel.CartItem) {
                viewModel.updateQuantity(item.product, 1)
            }

            override fun onDecrease(item: SaleViewModel.CartItem) {
                viewModel.updateQuantity(item.product, -1)
            }
        })

        binding.rvCartItems.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCartItems.adapter = adapter

        viewModel.cart.observe(viewLifecycleOwner) { cartItems ->
            adapter.setItems(cartItems ?: emptyList())
            if (cartItems.isNullOrEmpty()) {
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
