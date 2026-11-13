package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_TCP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStateExtTest {
    private val candidate = ScanCandidate(1, "City", "host", "10.0.0.1", PROTO_TCP, 443)

    private fun result(
        working: Boolean,
        score: Double,
    ) = ScanResult(
        candidate,
        ProbeResult(true, 10),
        VerifyResult(connected = true, trafficOk = working),
        score,
        0L,
        "net",
    )

    @Test
    fun `running and terminal states are told apart`() {
        assertTrue(ScanState.Probing(1, 2).isRunning())
        assertTrue(ScanState.Verifying(1, 2, candidate).isRunning())
        assertFalse(ScanState.Idle.isRunning())
        assertFalse(ScanState.Finished(emptyList()).isRunning())

        assertTrue(ScanState.Finished(emptyList()).isTerminal())
        assertTrue(ScanState.Cancelled(emptyList()).isTerminal())
        assertTrue(ScanState.Failed("x").isTerminal())
        assertFalse(ScanState.Idle.isTerminal())
        assertFalse(ScanState.Probing(0, 0).isTerminal())
    }

    @Test
    fun `working keeps only routes that carried traffic and their order`() {
        val results = listOf(result(true, 3.0), result(false, 2.0), result(true, 1.0))
        assertEquals(listOf(3.0, 1.0), results.working().map { it.score })
    }
}
