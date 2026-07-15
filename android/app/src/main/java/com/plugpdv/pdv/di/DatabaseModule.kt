package com.plugpdv.pdv.di

import android.content.Context
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.CatalogDao
import com.plugpdv.pdv.database.LocalSaleDao
import com.plugpdv.pdv.database.TaxDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    fun provideTaxDao(database: AppDatabase): TaxDao {
        return database.taxDao()
    }

    @Provides
    fun provideCatalogDao(database: AppDatabase): CatalogDao {
        return database.catalogDao()
    }

    @Provides
    fun provideLocalSaleDao(database: AppDatabase): LocalSaleDao {
        return database.localSaleDao()
    }
}
