package com.plugpdv.pdv.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductCommercialRevisionGsonTest {

    private val gson = Gson()

    @Test
    fun testProductDeserializationFromBackendCatalogJsonWithCommercialRevision() {
        val json = """
            {
              "id": "product-1",
              "name": "Produto Teste",
              "selling_price": 25.0,
              "commercial_revision": "rev_backend_123"
            }
        """.trimIndent()

        val product = gson.fromJson(json, Product::class.java)

        assertNotNull(product)
        assertEquals("product-1", product.id)
        assertEquals("Produto Teste", product.name)
        assertEquals(25.0, product.selling_price ?: 0.0, 0.0001)
        assertEquals("rev_backend_123", product.commercialRevision)
    }

    @Test
    fun testProductDeserializationWithoutCommercialRevisionYieldsNull() {
        val json = """
            {
              "id": "product-2",
              "name": "Produto Sem Revisao",
              "selling_price": 10.0
            }
        """.trimIndent()

        val product = gson.fromJson(json, Product::class.java)

        assertNotNull(product)
        assertEquals("product-2", product.id)
        assertNull(product.commercialRevision)
    }

    @Test
    fun testProductSerializationOutputsCommercialRevisionInSnakeCase() {
        val product = Product(
            id = "prod-3",
            name = "Burger",
            selling_price = 30.0,
            commercialRevision = "rev_xyz_789"
        )

        val json = gson.toJson(product)
        assertTrue("JSON serialized output must contain commercial_revision", json.contains("\"commercial_revision\":\"rev_xyz_789\""))
    }
}
