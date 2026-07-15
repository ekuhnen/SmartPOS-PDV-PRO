package com.plugpdv.pdv.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.plugpdv.pdv.models.Product

@Database(
    entities = [TaxEntity::class, Product::class, LocalSaleEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taxDao(): TaxDao
    abstract fun catalogDao(): CatalogDao
    abstract fun localSaleDao(): LocalSaleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smartpos_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
