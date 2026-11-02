package com.lagdaemon.creationremote.webcam

import org.junit.Assert.*
import org.junit.Test

class PairingAttemptsTest {
    @Test fun unreachableFirstEndpointDoesNotPreventRoutedSecondEndpoint() {
        val attempts=PairingAttempts(listOf("ws://192.168.5.1:8765/signal","ws://10.20.1.8:8765/signal"))
        val first=attempts.next()!!
        assertTrue(attempts.failed(first,PairingAttempts.Failure.UNREACHABLE))
        val second=attempts.next()!!
        assertEquals("ws://10.20.1.8:8765/signal",second.endpoint)
        assertFalse(attempts.failed(first,PairingAttempts.Failure.AUTHENTICATION))
        assertTrue(attempts.isCurrent(second))
        assertTrue(attempts.failed(second,PairingAttempts.Failure.REFUSED))
        assertNull(attempts.next())
        assertTrue(attempts.failureMessage().contains("refused or blocked"))
    }
    @Test fun authenticationAndUnreachableFailuresStayDistinct() {
        for (kind in PairingAttempts.Failure.values()) {
            val attempts=PairingAttempts(listOf("ws://10.0.0.1:8765/signal"))
            assertTrue(attempts.failed(attempts.next()!!,kind));assertNull(attempts.next())
            val message=attempts.failureMessage()
            assertFalse(message.contains("wrong Wi-Fi",true))
            if(kind==PairingAttempts.Failure.AUTHENTICATION)assertTrue(message.contains("authentication"))
            if(kind==PairingAttempts.Failure.UNREACHABLE)assertTrue(message.contains("No reachable"))
        }
    }
}
