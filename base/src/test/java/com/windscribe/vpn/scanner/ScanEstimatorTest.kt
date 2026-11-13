package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_IKev2
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_TCP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_UDP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanEstimatorTest {
    private fun city(
        id: Int,
        nodes: Int = 3,
    ) = ScanCity(
        id,
        "City$id",
        (1..nodes).map { ScanNode("c$id-n$it", "10.$id.$it.1", "10.$id.$it.2", "10.$id.$it.3") },
    )

    private fun inventory(cities: Int) =
        ScanInventory(
            cities = (1..cities).map { city(it) },
            portMap = mapOf(PROTO_TCP to listOf(443, 80), PROTO_IKev2 to listOf(500), PROTO_UDP to listOf(443)),
        )

    @Test
    fun `counts routes the selection asks for`() {
        // 2 cities x 2 nodes x (2 tcp ports + 1 ikev2 port) = 12; UDP is not selected.
        val request = ScanRequest.Custom(cityIds = listOf(1, 2), protocols = listOf(PROTO_TCP, PROTO_IKev2), nodesPerCity = 2)
        val estimate = ScanEstimator.estimate(inventory(5), request)
        assertEquals(12, estimate.candidates)
        assertEquals(12, estimate.probes)
        assertFalse(estimate.isLarge)
    }

    @Test
    fun `an empty selection is zero`() {
        val estimate = ScanEstimator.estimate(inventory(3), ScanRequest.Custom(cityIds = emptyList()))
        assertEquals(0, estimate.candidates)
        assertEquals(0, estimate.probes)
    }

    @Test
    fun `unprobeable protocols count as routes but not as probes`() {
        val request = ScanRequest.Custom(cityIds = listOf(1), protocols = listOf(PROTO_UDP), nodesPerCity = 3)
        val estimate = ScanEstimator.estimate(inventory(2), request)
        assertEquals(3, estimate.candidates)
        assertEquals(0, estimate.probes)
    }

    @Test
    fun `the estimate is not capped by the probe limit`() {
        val limits = ScanLimits(maxProbes = 5, maxVerifications = 2, timeBudgetMs = 1000)
        val request = ScanRequest.Custom(cityIds = listOf(1, 2), protocols = listOf(PROTO_TCP), nodesPerCity = 3, limits = limits)
        val estimate = ScanEstimator.estimate(inventory(2), request)
        assertEquals(12, estimate.candidates)
        assertTrue(estimate.exceedsProbeLimit)
        assertTrue(estimate.isLarge)
    }

    @Test
    fun `many routes per verification slot is large even under the probe limit`() {
        val limits = ScanLimits(maxProbes = 10_000, maxVerifications = 2, timeBudgetMs = 1000)
        val request = ScanRequest.Custom(cityIds = (1..20).toList(), protocols = listOf(PROTO_TCP), nodesPerCity = 3, limits = limits)
        val estimate = ScanEstimator.estimate(inventory(20), request)
        assertFalse(estimate.exceedsProbeLimit)
        assertTrue(estimate.isLarge)
    }
}
