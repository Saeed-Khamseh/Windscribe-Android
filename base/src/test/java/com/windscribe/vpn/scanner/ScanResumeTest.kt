package com.windscribe.vpn.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanResumeTest {
    private val settings = ScanSettings(locationIds = listOf(1))
    private val location = ScanLocation(1, "Netherlands", "NL", "Amsterdam", "Tulip", "nl.example")
    private val plan = buildScanPlan(settings, listOf(location))
    private val checkpoint = ScanCheckpoint(settings, "account:wifi", plan, emptyList(), settings)

    @Test fun `unchanged settings on the same network resume`() {
        assertTrue(canResumeScan(checkpoint, settings, plan, "account:wifi"))
        assertTrue(canResumeScan(checkpoint, settings.copy(stopOnSuccess = true), plan, "account:wifi"))
    }

    @Test fun `measurement settings changes invalidate completed results`() {
        for (changed in listOf(
            settings.copy(pingTarget = "8.8.8.8"),
            settings.copy(pingCount = 10),
            settings.copy(connectionTimeout = 30),
            settings.copy(settleDelay = 5),
        )) {
            // Saving editable settings must not overwrite the settings used for prior results.
            assertFalse(canResumeScan(checkpoint.copy(settings = changed), changed, plan, "account:wifi"))
        }
    }

    @Test fun `different network account server or explicit fresh scan never resumes`() {
        assertFalse(canResumeScan(checkpoint, settings, plan, "account:cellular"))
        assertFalse(canResumeScan(checkpoint, settings, plan, "other-account:wifi"))
        assertFalse(
            canResumeScan(checkpoint, settings, buildScanPlan(settings, listOf(location.copy(hostname = "new.example"))), "account:wifi"),
        )
        assertFalse(canResumeScan(checkpoint, settings.copy(resume = false), plan, "account:wifi"))
    }
}
