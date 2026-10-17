package com.windscribe.vpn.scanner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanEngineTest {
    private val location = ScanLocation(1, "Netherlands", "NL", "Amsterdam", "Tulip", "nl.example")
    private val settings =
        ScanSettings(
            locationIds = listOf(1),
            protocols = listOf(ScanProtocol("wg", "WireGuard", listOf("443", "51820"))),
            pingCount = 2,
            settleDelay = 0,
        )
    private val plan = buildScanPlan(settings, listOf(location))

    private class Driver : ScanDriver {
        var key = "wifi"
        var connected = false
        var connections = mutableListOf<ScanRoute>()
        var disconnects = 0
        var samples = ArrayDeque(listOf(80, -1, 40, 60))
        var connectAction: suspend () -> Boolean = { true }
        var pingAction: (suspend () -> Int)? = null

        override fun networkKey() = key

        override suspend fun disconnect() {
            connected = false
            disconnects++
        }

        override suspend fun connect(route: ScanRoute): Boolean {
            connections.add(route)
            connected = connectAction()
            return connected
        }

        override fun isRouteConnected() = connected

        override suspend fun ping(target: String): Int = pingAction?.invoke() ?: samples.removeFirst()
    }

    @Test fun `tests routes sequentially and ranks loss before latency`() =
        runTest {
            val driver = Driver()
            val checkpoints = mutableListOf<List<ScanResult>>()
            val updates = mutableListOf<ScanProgress>()
            val results = ScanEngine(driver).run(settings, plan, emptyList(), "wifi", { checkpoints.add(it) }, { updates.add(it) })
            assertEquals(listOf("443", "51820"), driver.connections.map { it.port })
            assertEquals(50, results[0].packetLoss)
            assertEquals(80, results[0].averagePing)
            assertEquals(50, rankedResults(results)[0].averagePing)
            assertEquals(listOf(1, 2), checkpoints.map { it.size })
            assertEquals(3, driver.disconnects)
            assertFalse(driver.connected)
            assertTrue(updates.any { it.step == ScanStep.Measuring })
        }

    @Test fun `resume skips completed combinations but not untested routes`() =
        runTest {
            val driver = Driver()
            val completed = ScanResult(plan.first(), listOf(100, 100))
            val results = ScanEngine(driver).run(settings, plan, listOf(completed), "wifi", {}, {})
            assertEquals(listOf(plan.last()), driver.connections)
            assertEquals(2, results.size)
            assertEquals(completed, results.first())
        }

    @Test fun `first success stops early and leaves the working tunnel connected`() =
        runTest {
            val driver = Driver()
            val results = ScanEngine(driver).run(settings.copy(stopOnSuccess = true), plan, emptyList(), "wifi", {}, {})
            assertEquals(1, results.size)
            assertEquals(1, driver.connections.size)
            assertTrue(driver.connected)
            assertEquals(1, driver.disconnects)
        }

    @Test fun `timeout records a failed route and continues without sampling it`() =
        runTest {
            val driver = Driver()
            var attempts = 0
            driver.connectAction = { if (++attempts == 1) awaitCancellation() else true }
            val results = ScanEngine(driver).run(settings.copy(connectionTimeout = 3), plan, emptyList(), "wifi", {}, {})
            assertFalse(results.first().working)
            assertTrue(results.first().samples.isEmpty())
            assertTrue(results.last().working)
            assertFalse(driver.connected)
        }

    @Test fun `Stop during a connection cancels the attempt and disconnects`() =
        runTest {
            val driver = Driver()
            val started = CompletableDeferred<Unit>()
            driver.connectAction = {
                started.complete(Unit)
                awaitCancellation()
            }
            val checkpoints = mutableListOf<List<ScanResult>>()
            val job = launch { ScanEngine(driver).run(settings, plan, emptyList(), "wifi", { checkpoints.add(it) }, {}) }
            started.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(2, driver.disconnects)
            assertFalse(driver.connected)
            assertTrue(checkpoints.isEmpty())
        }

    @Test fun `Stop during measurement does not save a partial result`() =
        runTest {
            val driver = Driver()
            val measuring = CompletableDeferred<Unit>()
            driver.pingAction = {
                measuring.complete(Unit)
                awaitCancellation()
            }
            val checkpoints = mutableListOf<List<ScanResult>>()
            val job = launch { ScanEngine(driver).run(settings, plan, emptyList(), "wifi", { checkpoints.add(it) }, {}) }
            measuring.await()
            job.cancel()
            job.join()
            assertFalse(driver.connected)
            assertTrue(checkpoints.isEmpty())
        }

    @Test fun `network change aborts rather than mixing network results`() =
        runTest {
            val driver = Driver()
            driver.pingAction = {
                driver.key = "cellular"
                10
            }
            var failure: Exception? = null
            val checkpoints = mutableListOf<List<ScanResult>>()
            try {
                ScanEngine(driver).run(settings, plan, emptyList(), "wifi", { checkpoints.add(it) }, {})
            } catch (
                error: Exception,
            ) {
                failure = error
            }
            assertTrue(failure is IllegalStateException)
            assertTrue(checkpoints.isEmpty())
            assertFalse(driver.connected)
        }

    @Test fun `VPN loss during a ping cannot become a working result`() =
        runTest {
            val driver = Driver()
            driver.pingAction = {
                driver.connected = false
                10
            }
            val results = ScanEngine(driver).run(settings, plan.take(1), emptyList(), "wifi", {}, {})
            assertFalse(results.single().working)
            assertTrue(results.single().samples.isEmpty())
        }

    @Test fun `blocked ICMP produces a failed test with real packet loss`() =
        runTest {
            val driver = Driver()
            driver.pingAction = { -1 }
            val result = ScanEngine(driver).run(settings, plan.take(1), emptyList(), "wifi", {}, {}).single()
            assertFalse(result.working)
            assertEquals(100, result.packetLoss)
            assertEquals(listOf(-1, -1), result.samples)
        }

    @Test fun `disconnect failure prevents connecting another route`() =
        runTest {
            val driver =
                object : ScanDriver {
                    var connects = 0

                    override fun networkKey() = "wifi"

                    override suspend fun disconnect() {
                        error("teardown failed")
                    }

                    override suspend fun connect(route: ScanRoute): Boolean {
                        connects++
                        return true
                    }

                    override fun isRouteConnected() = true

                    override suspend fun ping(target: String) = 10
                }
            runCatching { ScanEngine(driver).run(settings, plan, emptyList(), "wifi", {}, {}) }
            assertEquals(0, driver.connects)
        }

    @Test fun `plan respects protocol priority selected cities and unique ports`() {
        val selected =
            settings.copy(
                protocols =
                    listOf(
                        ScanProtocol("tcp", "TCP", listOf("443", "443")),
                        ScanProtocol("wg", "WireGuard", listOf("443")),
                        ScanProtocol("udp", "UDP", listOf("53"), false),
                    ),
            )
        assertEquals(listOf("tcp", "wg"), buildScanPlan(selected, listOf(location, location.copy(id = 2))).map { it.protocol })
    }

    @Test fun `advanced settings reject invalid targets ports and limits`() {
        assertTrue(settings.isValid())
        assertFalse(settings.copy(pingTarget = "https://example.com").isValid())
        assertFalse(settings.copy(pingTarget = "1.1.1.1;echo test").isValid())
        assertFalse(settings.copy(pingCount = 0).isValid())
        assertFalse(settings.copy(connectionTimeout = 301).isValid())
        assertFalse(settings.copy(settleDelay = -1).isValid())
        assertFalse(settings.copy(protocols = listOf(ScanProtocol("wg", "WG", listOf("65536")))).isValid())
    }

    @Test fun `CSV quotes commas newlines and spreadsheet formulas`() {
        val route = plan.first().copy(location = location.copy(country = "=SUM(1,2)", city = "City, \"quoted\"\nname"))
        val csv = scanCsv(listOf(ScanResult(route, listOf(80, -1))))
        assertTrue(csv.contains("\"'=SUM(1,2)\""))
        assertTrue(csv.contains("\"City, \"\"quoted\"\"\nname\""))
        assertTrue(csv.contains("\"80\",\"50\""))
    }
}
