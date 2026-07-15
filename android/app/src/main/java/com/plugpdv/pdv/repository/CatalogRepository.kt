package com.plugpdv.pdv.repository

import android.util.Log
import androidx.lifecycle.LiveData
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.CatalogDao
import com.plugpdv.pdv.models.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepository @Inject constructor(
    private val catalogDao: CatalogDao,
    private val apiService: PosApiService
) {
    val allProducts: LiveData<List<Product>> = catalogDao.allProductsLiveData

    suspend fun syncCatalog(token: String) {
        try {
            val response = apiService.getCatalogs("Bearer $token")
            val catalogs = response.catalogs ?: emptyList()
            val productsList = catalogs.flatMap { it.products ?: emptyList() }

            withContext(Dispatchers.IO) {
                catalogDao.deleteAll()
                catalogDao.insertAll(productsList)
            }
        } catch (e: Exception) {
            Log.e("CatalogRepository", "Failed to sync catalog", e)
        }
    }
}
