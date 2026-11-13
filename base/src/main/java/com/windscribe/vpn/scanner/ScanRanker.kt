package com.windscribe.vpn.scanner

/**
 * Chooses which probed candidates go to verification, and scores what verification found.
 * Pure: no Android, no I/O.
 */
object ScanRanker {
    /**
     * Verification is expensive (a real tunnel, 10 to 20 s), so at most [maxVerifications] go.
     *
     * Tiers, in order: endpoints that answered (fastest first); candidates with no honest probe on
     * an IP where some other probe succeeded; the rest of the unprobeable ones. Endpoints that did
     * not answer are never verified, and neither are probeable ones that were never probed (the
     * time ran out). Inside a tier the pick is round-robin over (node, protocol), so one good node
     * cannot use up the whole budget.
     */
    fun selectForVerification(
        candidates: List<ScanCandidate>,
        probes: Map<ProbeKey, ProbeResult>,
        maxVerifications: Int,
    ): List<ScanCandidate> {
        if (maxVerifications <= 0) return emptyList()
        val reachable = ArrayList<ScanCandidate>()
        val unprobeable = ArrayList<ScanCandidate>()
        for (candidate in candidates) {
            val key = candidate.probeKey()
            if (key.kind == ProbeKind.NONE) {
                unprobeable.add(candidate)
                continue
            }
            when (probes[key]?.reachable) {
                true -> reachable.add(candidate)
                else -> Unit
            }
        }
        reachable.sortBy { probes[it.probeKey()]?.latencyMs ?: Long.MAX_VALUE }

        val goodIps = reachable.mapTo(HashSet()) { it.nodeIp }
        val (onGoodIp, elsewhere) = unprobeable.partition { it.nodeIp in goodIps }

        val picked = ArrayList<ScanCandidate>()
        for (tier in listOf(reachable, onGoodIp, elsewhere)) {
            if (picked.size >= maxVerifications) break
            picked.addAll(roundRobin(tier, maxVerifications - picked.size))
        }
        return picked
    }

    /** One from each (node, protocol) group in turn, groups in order of their first candidate. */
    internal fun roundRobin(
        tier: List<ScanCandidate>,
        limit: Int,
    ): List<ScanCandidate> {
        val groups = LinkedHashMap<Pair<String, String>, ArrayDeque<ScanCandidate>>()
        for (candidate in tier) {
            groups.getOrPut(candidate.nodeHostname to candidate.protocol) { ArrayDeque() }.addLast(candidate)
        }
        val out = ArrayList<ScanCandidate>()
        while (out.size < limit && groups.isNotEmpty()) {
            val iterator = groups.values.iterator()
            while (iterator.hasNext() && out.size < limit) {
                val group = iterator.next()
                out.add(group.removeFirst())
                if (group.isEmpty()) iterator.remove()
            }
        }
        return out
    }

    /**
     * Higher is better. A route that carried a download outranks everything else; among those, lower
     * latency and higher throughput win. A route that connected but moved no traffic scores zero:
     * it is recorded with its reason and never listed as working.
     */
    fun score(
        probe: ProbeResult,
        verify: VerifyResult?,
    ): Double {
        if (verify != null) {
            if (!verify.trafficOk) return 0.0
            val latency = (verify.latencyMs ?: MAX_LATENCY_MS).coerceIn(0, MAX_LATENCY_MS)
            val throughput = (verify.downloadKbps ?: 0L).coerceIn(0, MAX_KBPS)
            val connect = (verify.connectMs ?: MAX_CONNECT_MS).coerceIn(0, MAX_CONNECT_MS)
            return WORKING_BASE +
                LATENCY_POINTS * (1.0 - latency.toDouble() / MAX_LATENCY_MS) +
                THROUGHPUT_POINTS * (throughput.toDouble() / MAX_KBPS) +
                CONNECT_POINTS * (1.0 - connect.toDouble() / MAX_CONNECT_MS)
        }
        // Unverified: only ever below any verified route.
        return when (probe.reachable) {
            true -> UNVERIFIED_REACHABLE
            null -> UNVERIFIED_UNKNOWN
            false -> -1.0
        }
    }

    /** Best first. Ties keep their order. */
    fun rank(results: List<ScanResult>): List<ScanResult> = results.sortedByDescending { it.score }

    private const val WORKING_BASE = 1000.0
    private const val LATENCY_POINTS = 300.0
    private const val THROUGHPUT_POINTS = 300.0
    private const val CONNECT_POINTS = 100.0
    private const val MAX_LATENCY_MS = 2_000L
    private const val MAX_KBPS = 20_000L
    private const val MAX_CONNECT_MS = 20_000L
    private const val UNVERIFIED_REACHABLE = 50.0
    private const val UNVERIFIED_UNKNOWN = 10.0
}
