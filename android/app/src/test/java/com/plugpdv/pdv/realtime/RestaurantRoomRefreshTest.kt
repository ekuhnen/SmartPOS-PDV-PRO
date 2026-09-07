package com.plugpdv.pdv.realtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.models.MesaDto
import com.plugpdv.pdv.models.RestaurantResponse
import com.plugpdv.pdv.models.Sector
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.repository.TableReadRepository
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.TenantBindingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RestaurantRoomRefreshTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var api: PosApiService
    private lateinit var repository: TableReadRepository

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        TenantBindingStore.setActiveTenantId(context, "ownerA")
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(Constants.TOKEN, "tokenA").putString(Constants.USER_ID, "userA").commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        api = mock()
        repository = TableReadRepository(context, db.tableDao(), api, db.catalogDao(), db.comandaSnapshotDao())
        db.tableDao().insert(TableEntity("mesa5", 5, Table.Status.OCCUPIED))
    }

    @After fun close() {
        db.close()
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        TenantBindingStore.clearTenant(context)
    }

    private fun canonical(status: String) = RestaurantResponse(setores = listOf(
        Sector(id = "sector", nome = "Room", mesas = listOf(MesaDto(id = "mesa5", numero = 5, status = status)))
    ))

    @Test fun partialCloseAndFinalCloseFollowCanonicalRoomOccupancy() = runBlocking {
        whenever(api.getMesas(any())).thenReturn(canonical("OCUPADA"), canonical("LIVRE"))
        assertTrue(repository.refreshTables("tokenA").isSuccess)
        assertEquals(Table.Status.OCCUPIED, db.tableDao().getTableById("mesa5")!!.status)
        assertTrue(repository.refreshTables("tokenA").isSuccess)
        assertEquals(Table.Status.AVAILABLE, db.tableDao().getTableById("mesa5")!!.status)
        assertEquals(1, db.tableDao().getAllTables().size)
        verify(api, times(2)).getMesas("Bearer tokenA")
        verifyNoMoreInteractions(api)
    }

    @Test fun invalidResponsePreservesExistingRoomCache() = runBlocking {
        whenever(api.getMesas(any())).thenReturn(RestaurantResponse(setores = null))
        assertTrue(repository.refreshTables("tokenA").isFailure)
        assertEquals(Table.Status.OCCUPIED, db.tableDao().getTableById("mesa5")!!.status)
    }

    @Test fun logoutDuringHttpCannotReplaceRoom() = runBlocking {
        whenever(api.getMesas(any())).thenAnswer {
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().remove(Constants.TOKEN).commit()
            canonical("LIVRE")
        }
        try {
            repository.refreshTables("tokenA")
            fail("Old session response must be rejected")
        } catch (_: CancellationException) { }
        assertEquals(Table.Status.OCCUPIED, db.tableDao().getTableById("mesa5")!!.status)
    }

    @Test fun tenantSwitchDuringHttpCannotReplaceRoom() = runBlocking {
        whenever(api.getMesas(any())).thenAnswer {
            TenantBindingStore.setActiveTenantId(context, "ownerB")
            canonical("LIVRE")
        }
        try {
            repository.refreshTables("tokenA")
            fail("Old tenant response must be rejected")
        } catch (_: CancellationException) { }
        assertEquals(Table.Status.OCCUPIED, db.tableDao().getTableById("mesa5")!!.status)
    }

    @Test fun oldTokenCannotStartReadAfterRotation() = runBlocking {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(Constants.TOKEN, "tokenB").commit()
        try {
            repository.refreshTables("tokenA")
            fail("Old token must be rejected before HTTP")
        } catch (_: CancellationException) { }
        verifyNoInteractions(api)
    }
}
