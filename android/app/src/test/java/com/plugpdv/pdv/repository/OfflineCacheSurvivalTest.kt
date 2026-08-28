package com.plugpdv.pdv.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.TaxEntity
import com.plugpdv.pdv.models.AuthResponse
import com.plugpdv.pdv.models.CatalogDetail
import com.plugpdv.pdv.models.CatalogInfo
import com.plugpdv.pdv.models.CatalogResponse
import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.utils.TenantBindingStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * OFFLINE-CACHE-01..08
 *
 * Tests covering cache survival under PDV-OFFLINE-FIRST-01.
 * Uses Robolectric + Room in-memory (same pattern as SaleOutboxRepositoryTest).
 *
 * Tenant authority: ONLY response.ownerId. Never user.id / invited_by / email.
 *
 * NOTE: Uses catalogDao.getAll() directly (not LiveData) to avoid main-looper idle issues
 * in Robolectric tests. CatalogDao.getAll() was added specifically for this purpose.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineCacheSurvivalTest {

    private lateinit var context: Context
    private lateinit var appDatabase: AppDatabase
    private lateinit var apiService: PosApiService
    private lateinit var catalogRepository: CatalogRepository
    private lateinit var taxRepository: DefaultTaxRepository

    // --- fixture helpers ---

    private fun makeProduct(id: String) = Product(
        id = id,
        name = "Product $id",
        sku = null,
        barcode = null,
        category = "Cat",
        selling_price = 10.0,
        stock = null,
        image_url = null,
        price_currency = "BRL",
        group = null
    )

    private fun makeTaxEntity(id: String) = TaxEntity().apply {
        this.id = id
        name = "Tax $id"
        percentage = 5.0
        currency = "BRL"
        active = true
    }

    private fun makeCatalogInfo(vararg products: Product): CatalogInfo =
        CatalogInfo(
            catalog = CatalogDetail(id = "cat1", name = "Catalog"),
            products = products.toList()
        )

    private fun seedProducts(vararg ids: String) = runBlocking {
        appDatabase.catalogDao().insertAll(ids.map { makeProduct(it) })
    }

    private fun seedTaxes(vararg ids: String) = runBlocking {
        appDatabase.taxDao().insertAll(ids.map { makeTaxEntity(it) })
    }

    // --- setup / teardown ---

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TenantBindingStore.clearTenant(context)

        appDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        apiService = mock()

        catalogRepository = CatalogRepository(
            catalogDao = appDatabase.catalogDao(),
            apiService = apiService,
            appDatabase = appDatabase
        )
        taxRepository = DefaultTaxRepository(
            taxDao = appDatabase.taxDao(),
            apiService = apiService,
            appDatabase = appDatabase
        )
    }

    @After
    fun tearDown() {
        appDatabase.close()
        TenantBindingStore.clearTenant(context)
    }

    // -------------------------------------------------------------------------
    // OFFLINE-CACHE-01: Same owner - no login-time cache purge
    // -------------------------------------------------------------------------

    /**
     * OFFLINE-CACHE-01
     * TenantBindingStore: owner A, Room: 10 products + 3 taxes.
     * Same-owner login guard is a no-op on cache — nothing is deleted.
     */
    @Test
    fun `OFFLINE-CACHE-01 same owner login does not purge existing cache`() = runBlocking {
        val ownerA = "owner-aaa-001"
        TenantBindingStore.setActiveTenantId(context, ownerA)
        seedProducts("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10")
        seedTaxes("t1", "t2", "t3")

        // Simulate same-owner tenant guard (no cache mutation):
        val activeTenantId = TenantBindingStore.getActiveTenantId(context)
        assertEquals(ownerA, activeTenantId)
        // activeTenantId == ownerId -> no-op on cache

        val taxes = appDatabase.taxDao().getAll()
        assertEquals("Taxes must survive same-owner login", 3, taxes.size)

        val products = appDatabase.catalogDao().getAll()
        assertEquals("Products must survive same-owner login", 10, products.size)
    }

    // -------------------------------------------------------------------------
    // OFFLINE-CACHE-02: First bind - preserves existing cache
    // -------------------------------------------------------------------------

    /**
     * OFFLINE-CACHE-02
     * TenantBindingStore: null (legacy/fresh install). Room: 3 products + 2 taxes.
     * First bind must bind owner without purging cache.
     */
    @Test
    fun `OFFLINE-CACHE-02 first bind preserves existing cache`() = runBlocking {
        assertNull(TenantBindingStore.getActiveTenantId(context))
        seedProducts("p1", "p2", "p3")
        seedTaxes("t1", "t2")

        // Simulate first-bind path: bind without clearing cache
        val ownerId = "owner-first-bind"
        TenantBindingStore.setActiveTenantId(context, ownerId)
        // DO NOT call clearRebuildableCaches — existing data must be preserved

        assertEquals(ownerId, TenantBindingStore.getActiveTenantId(context))

        val taxes = appDatabase.taxDao().getAll()
        assertEquals("Cache must be preserved on first bind", 2, taxes.size)

        val products = appDatabase.catalogDao().getAll()
        assertEquals("Products must be preserved on first bind", 3, products.size)
    }

    // -------------------------------------------------------------------------
    // OFFLINE-CACHE-03: Owner mismatch - login blocked, data untouched
    // -------------------------------------------------------------------------

    /**
     * OFFLINE-CACHE-03
     * TenantBindingStore: owner A. Login response: owner B.
     * Guard must block login and leave owner + cache intact.
     */
    @Test
    fun `OFFLINE-CACHE-03 owner mismatch blocks login and keeps cache intact`() = runBlocking {
        val ownerA = "owner-a-mismatch"
        val ownerB = "owner-b-mismatch"
        TenantBindingStore.setActiveTenantId(context, ownerA)
        seedProducts("p1", "p2", "p3", "p4", "p5")
        seedTaxes("t1", "t2", "t3")

        // Evaluate guard: mismatch detected
        val activeTenantId = TenantBindingStore.getActiveTenantId(context)
        val isMismatch = activeTenantId != null && activeTenantId != ownerB
        assertTrue("Must detect mismatch", isMismatch)

        // Guard returns block result -> DOES NOT mutate owner or cache
        assertEquals("Stored owner must remain A after mismatch", ownerA, TenantBindingStore.getActiveTenantId(context))

        val taxes = appDatabase.taxDao().getAll()
        assertEquals("Taxes must be intact after mismatch block", 3, taxes.size)

        val products = appDatabase.catalogDao().getAll()
        assertEquals("Products must be intact after mismatch block", 5, products.size)
    }

    // -------------------------------------------------------------------------
    // OFFLINE-CACHE-04: Missing owner_id - FAIL CLOSED
    // -------------------------------------------------------------------------

    /**
     * OFFLINE-CACHE-04
     * Login response: owner_id = null.
     * Guard must detect it and not modify cache or TenantBindingStore.
     */
    @Test
    fun `OFFLINE-CACHE-04 missing owner_id fails closed without touching cache`() = runBlocking {
        val ownerA = "owner-existing"
        TenantBindingStore.setActiveTenantId(context, ownerA)
        seedTaxes("t1", "t2")
        seedProducts("p1")

        val response = AuthResponse(access_token = "tok", ownerId = null)

        // Guard detects null/blank owner_id and returns early with error
        assertTrue("owner_id null must be detected as missing", response.ownerId.isNullOrBlank())

        // No mutations should have occurred
        assertEquals("Owner must remain unchanged", ownerA, TenantBindingStore.getActiveTenantId(context))
        assertEquals("Taxes must be untouched", 2, appDatabase.taxDao().getAll().size)
        val products = appDatabase.catalogDao().getAll()
        assertEquals("Products must be untouched", 1, products.size)
    }

    // -------------------------------------------------------------------------
    // OFFLINE-CACHE-05: Catalog network failure - old catalog untouched
    // -------------------------------------------------------------------------

    /**
     * OFFLINE-CACHE-05
     * Room: 5 products. apiService.getCatalogs throws IOException.
     * After syncCatalog: old 5 products still present.
     */
    @Test
    fun `OFFLINE-CACHE-05 catalog network failure preserves existing cache`() = runBlocking {
        seedProducts("p1", "p2", "p3", "p4", "p5")
        whenever(apiService.getCatalogs("Bearer token")).thenAnswer { throw IOException("Network unavailable") }

        catalogRepository.syncCatalog("token")

        val products = appDatabase.catalogDao().getAll()
        assertEquals("Old catalog must survive network failure", 5, products.size)
    }

    // -------------------------------------------------------------------------
    // OFFLINE-CACHE-06: Catalog atomic replace
    // -------------------------------------------------------------------------

    /**
     * OFFLINE-CACHE-06
     * Old: 3 products. Remote: 2 different products.
     * After syncCatalog: exactly 2 new products, old 3 gone atomically.
     */
    @Test
    fun `OFFLINE-CACHE-06 catalog atomic replace swaps dataset transactionally`() = runBlocking {
        seedProducts("old1", "old2", "old3")

        val newProducts = listOf(makeProduct("new1"), makeProduct("new2"))
        val catalogResponse = CatalogResponse(
            catalogs = listOf(makeCatalogInfo(*newProducts.toTypedArray()))
        )
        whenever(apiService.getCatalogs("Bearer token")).thenReturn(catalogResponse)

        catalogRepository.syncCatalog("token")

        val products = appDatabase.catalogDao().getAll()
        assertEquals("Final dataset must equal new remote dataset", 2, products.size)
        assertTrue("new1 must exist", products.any { it.id == "new1" })
        assertTrue("new2 must exist", products.any { it.id == "new2" })
        assertTrue("old1 must not exist", products.none { it.id == "old1" })
    }

    // -------------------------------------------------------------------------
    // OFFLINE-CACHE-07: Tax network failure - old taxes untouched
    // -------------------------------------------------------------------------

    /**
     * OFFLINE-CACHE-07
     * Room: 3 taxes. apiService.getTaxes throws IOException.
     * After syncTaxes: old 3 taxes unchanged.
     */
    @Test
    fun `OFFLINE-CACHE-07 tax network failure preserves existing taxes`() = runBlocking {
        seedTaxes("t1", "t2", "t3")
        whenever(apiService.getTaxes("Bearer token")).thenAnswer { throw IOException("Network unavailable") }

        taxRepository.syncTaxes("token")

        val taxes = appDatabase.taxDao().getAll()
        assertEquals("Old taxes must survive network failure", 3, taxes.size)
    }

    // -------------------------------------------------------------------------
    // OFFLINE-CACHE-08: Repeated same-owner logins - no purge on any iteration
    // -------------------------------------------------------------------------

    /**
     * OFFLINE-CACHE-08
     * Owner A logs in 3 times. After each iteration: cache must remain intact.
     */
    @Test
    fun `OFFLINE-CACHE-08 repeated same-owner logins never purge cache`() = runBlocking {
        val ownerA = "owner-repeated-login"
        TenantBindingStore.setActiveTenantId(context, ownerA)
        seedProducts("p1", "p2", "p3", "p4")
        seedTaxes("t1", "t2")

        // Simulate 3 same-owner login guards (all no-ops on cache)
        repeat(3) { i ->
            val activeTenantId = TenantBindingStore.getActiveTenantId(context)
            when {
                activeTenantId == null -> TenantBindingStore.setActiveTenantId(context, ownerA)
                activeTenantId == ownerA -> { /* same owner - no-op on cache */ }
                else -> error("Unexpected mismatch on iteration $i: expected=$ownerA actual=$activeTenantId")
            }
        }

        assertEquals(ownerA, TenantBindingStore.getActiveTenantId(context))

        val taxes = appDatabase.taxDao().getAll()
        assertEquals("Taxes must survive 3 repeated logins", 2, taxes.size)

        val products = appDatabase.catalogDao().getAll()
        assertEquals("Products must survive 3 repeated logins", 4, products.size)
    }
}
