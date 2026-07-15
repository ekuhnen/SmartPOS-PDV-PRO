package com.plugpdv.pdv.repository

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.TaxDao
import com.plugpdv.pdv.database.TaxEntity
import com.plugpdv.pdv.utils.ServiceFeeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaxRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taxDao: TaxDao,
    private val apiService: PosApiService
) {
    fun getActiveTaxesLiveData(): LiveData<List<TaxEntity>> {
        return taxDao.activeTaxesLiveData
    }

    suspend fun syncTaxes(token: String) {
        try {
            val response = apiService.getTaxes("Bearer $token")
            
            // Save Service Fee Config
            ServiceFeeManager.saveConfig(context, response.serviceFee)

            val apiTaxes = response.taxes
            if (apiTaxes != null) {
                val entities = apiTaxes.map { rate ->
                    TaxEntity().apply {
                        id = rate.id
                        name = rate.name
                        percentage = rate.percentage
                        currency = rate.currency
                        active = rate.active
                    }
                }
                
                withContext(Dispatchers.IO) {
                    taxDao.deleteAll()
                    taxDao.insertAll(entities)
                }
            }
        } catch (e: Exception) {
            Log.e("TaxRepository", "Failed to sync taxes", e)
        }
    }
}
