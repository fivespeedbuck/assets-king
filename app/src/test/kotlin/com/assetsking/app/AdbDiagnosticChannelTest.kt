package com.assetsking.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbDiagnosticChannelTest {
    private val token = "one-time-token"

    @Test
    fun acceptsOnlyExactSnapshotGetWithCurrentToken() {
        assertTrue(acceptsDiagnosticRequest("GET /snapshot?token=$token HTTP/1.1", token))
        assertTrue(acceptsDiagnosticRequest("GET /snapshot?token=$token HTTP/1.0", token))
        assertFalse(acceptsDiagnosticRequest("POST /snapshot?token=$token HTTP/1.1", token))
        assertFalse(acceptsDiagnosticRequest("GET /snapshot?token=wrong HTTP/1.1", token))
        assertFalse(acceptsDiagnosticRequest("GET /other?token=$token HTTP/1.1", token))
        assertFalse(acceptsDiagnosticRequest(null, token))
    }
}
