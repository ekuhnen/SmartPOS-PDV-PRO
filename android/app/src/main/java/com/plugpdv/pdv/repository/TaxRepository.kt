package com.plugpdv.pdv.repository

import android.util.Log
import androidx.lifecycle.LiveData
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.TaxDao
import com.plugpdv.pdv.database.TaxEntity
import javax.inject.Inject
import javax.inject.Singleton

interface TaxRepository {
    fun getActiveTaxesLiveData(): LiveData<List<TaxEntity>>
    suspend fun syncTaxes(token: String)
    suspend fun getAll(): List<TaxEntity>
    suspend fun insertAll(taxes: List<TaxEntity>)
    suspend fun deleteAll()
}

@Singleton
class DefaultTaxRepository @Inject constructor(
    private val taxDao: TaxDao,
    private val apiService: PosApiService
) : TaxRepository {

    override fun getActiveTaxesLiveData(): LiveData<List<TaxEntity>> {
        return taxDao.activeTaxesLiveData
    }

    override suspend fun syncTaxes(token: String) {
        try {
            val response = apiService.getTaxes("Bearer $token")
            val rates = response.taxes ?: emptyList()
            val entities = rates.map { rate ->
                TaxEntity().apply {
                    id = rate.id
                    name = rate.name
                    percentage = rate.percentage
                    currency = rate.currency
                    active = rate.active
                }
            }
            taxDao.deleteAll()
            taxDao.insertAll(entities)
            Log.d("TaxRepository", "Taxas sincronizadas com sucesso: ${entities.size} registros.")
        } catch (e: Exception) {
            Log.e("TaxRepository", "Erro ao sincronizar taxas: ${e.message}")
        }
    }

    override suspend fun getAll(): List<TaxEntity> {
        return taxDao.getAll()
    }

    override suspend fun insertAll(taxes: List<TaxEntity>) {
        taxDao.insertAll(taxes)
    }

    override suspend fun deleteAll() {
        taxDao.deleteAll()
    }
}
