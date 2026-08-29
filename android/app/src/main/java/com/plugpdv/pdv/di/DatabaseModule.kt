package com.plugpdv.pdv.di

import android.content.Context
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.*
import com.plugpdv.pdv.repository.TableReadRepository
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

    @Provides
    fun provideOutboxDao(database: AppDatabase): OutboxDao {
        return database.outboxDao()
    }

    @Provides
    fun providePaymentAttemptDao(database: AppDatabase): PaymentAttemptDao {
        return database.paymentAttemptDao()
    }

    @Provides
    fun provideTableDao(database: AppDatabase): TableDao {
        return database.tableDao()
    }

    @Provides
    fun provideComandaSnapshotDao(database: AppDatabase): ComandaSnapshotDao {
        return database.comandaSnapshotDao()
    }
}
