package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.CapabilitiesRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

class CapabilitiesTransportContractTest {

    @Test
    fun capabilitiesUsesDeployedTerminalSyncPostSurface() {
        val method = PosApiService::class.java.methods.single { it.name == "getCapabilities" }
        val post = requireNotNull(method.getAnnotation(POST::class.java))

        assertEquals("terminal-sync", post.value)
        assertNull(method.getAnnotation(GET::class.java))
        assertTrue(
            method.parameterAnnotations.flatten().any { annotation ->
                annotation.annotationClass.java == Body::class.java
            }
        )
    }

    @Test
    fun defaultCapabilitiesRequestSerializesExactAction() {
        val json = Gson().toJson(CapabilitiesRequest())
        assertEquals("{\"action\":\"capabilities\"}", json)
    }
}
