package com.plugpdv.pdv.di

import com.plugpdv.pdv.repository.DefaultTaxRepository
import com.plugpdv.pdv.repository.TaxRepository
import com.plugpdv.pdv.utils.CurrencyRulesProvider
import com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTaxRepository(impl: DefaultTaxRepository): TaxRepository

    @Binds
    @Singleton
    abstract fun bindCurrencyRulesProvider(impl: DefaultCurrencyRulesProvider): CurrencyRulesProvider
}
