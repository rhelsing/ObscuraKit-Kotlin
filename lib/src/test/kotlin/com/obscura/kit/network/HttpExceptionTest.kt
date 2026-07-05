package com.obscura.kit.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * HttpException is what every APIClient call throws on a non-2xx response;
 * callers branch on statusCode (e.g. 401 -> refresh, 409 -> device mismatch).
 * Pin that the code and body survive and that it stays an IOException so
 * OkHttp-style try/catch paths keep working.
 */
class HttpExceptionTest {

    @Test
    fun `exposes status code and body`() {
        val e = HttpException(404, "not found")
        assertEquals(404, e.statusCode)
        assertEquals("not found", e.body)
    }

    @Test
    fun `message encodes the status code`() {
        assertEquals("HTTP 500", HttpException(500, "").message)
    }

    @Test
    fun `can be handled as an IOException`() {
        // Assignment compiles only if HttpException is an IOException — this
        // is what lets callers catch it alongside network IO failures.
        val thrown: IOException = HttpException(401, "unauthorized")
        assertEquals(401, (thrown as HttpException).statusCode)
    }
}
