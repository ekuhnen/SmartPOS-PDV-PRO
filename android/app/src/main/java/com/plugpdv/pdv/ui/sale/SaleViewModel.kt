package com.plugpdv.pdv.ui.sale

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.repository.CatalogRepository
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SaleViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _cart = MutableLiveData<MutableList<CartItem>>(mutableListOf())
    val cart: LiveData<MutableList<CartItem>> = _cart

    val allProducts: LiveData<List<Product>> = catalogRepository.allProducts
    
    private val _filteredProducts = MutableLiveData<List<Product>>(emptyList())
    val products: LiveData<List<Product>> = _filteredProducts

    private val _categories = MutableLiveData<List<String>>(emptyList())
    val categories: LiveData<List<String>> = _categories

    private val _total = MutableLiveData(0.0)
    val total: LiveData<Double> = _total

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _selectedCategory = MutableLiveData("")
    val selectedCategory: LiveData<String> = _selectedCategory

    private val _searchQuery = MutableLiveData("")

    init {
        allProducts.observeForever { products ->
            products?.let {
                val categoryList = it.mapNotNull { p -> p.category }.distinct()
                _categories.value = categoryList
                applyFilter()
            }
        }
    }

    fun loadCatalog(token: String) {
        viewModelScope.launch {
            _isLoading.value = true
            retryIO { catalogRepository.syncCatalog(token) }
            _isLoading.value = false
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilter()
    }

    fun setSelectedCategory(category: String) {
        _selectedCategory.value = category
        applyFilter()
    }

    private fun applyFilter() {
        val all = allProducts.value ?: return
        val query = _searchQuery.value?.lowercase()?.trim() ?: ""
        val category = _selectedCategory.value ?: ""

        val filtered = all.filter { p ->
            val pName = p.name?.lowercase() ?: ""
            val matchesSearch = pName.contains(query) || (p.sku?.lowercase()?.contains(query) == true)
            val matchesCategory = category.isEmpty() || category == "Todos" || category == p.category
            matchesSearch && matchesCategory
        }
        _filteredProducts.value = filtered
    }

    fun addToCart(product: Product) {
        val currentCart = _cart.value ?: mutableListOf()
        val existingItem = currentCart.find { it.product.id == product.id }
        
        if (existingItem != null) {
            existingItem.quantity++
        } else {
            currentCart.add(CartItem(product, 1))
        }
        
        _cart.value = currentCart
        calculateTotal()
    }

    fun removeFromCart(product: Product) {
        val currentCart = _cart.value ?: return
        currentCart.removeAll { it.product.id == product.id }
        _cart.value = currentCart
        calculateTotal()
    }

    fun updateQuantity(product: Product, delta: Int) {
        val currentCart = _cart.value ?: return
        val item = currentCart.find { it.product.id == product.id }
        
        item?.let {
            it.quantity += delta
            if (it.quantity <= 0) {
                currentCart.remove(it)
            }
        }
        
        _cart.value = currentCart
        calculateTotal()
    }

    fun clearCart() {
        _cart.value = mutableListOf()
        calculateTotal()
    }

    private fun calculateTotal() {
        _total.value = _cart.value?.sumOf { (it.product.selling_price ?: 0.0) * it.quantity } ?: 0.0
    }

    fun findProductByBarcode(barcode: String): Product? {
        return allProducts.value?.find { it.sku == barcode }
    }

    data class CartItem(val product: Product, var quantity: Int) : java.io.Serializable
}
