package com.windscribe.vpn.scanner

/**
 * How big a custom scan is, before it runs. Pure: the same generator the scan uses, without the
 * probe cap, so the count is what the selection really asks for.
 */
data class ScanEstimate(
    /** Routes the selection asks for, whatever the limits. */
    val candidates: Int,
    /** Distinct endpoints that would need a probe. */
    val probes: Int,
    val limits: ScanLimits,
) {
    /** More endpoints than a scan will probe: the tail of the list is dropped. */
    val exceedsProbeLimit: Boolean get() = probes > limits.maxProbes

    /** Worth warning about: some of what was asked for will not be tried. */
    val isLarge: Boolean get() = exceedsProbeLimit || candidates > limits.maxVerifications * LARGE_FACTOR

    companion object {
        /** Verification is the scarce stage; this many routes per verification slot is a lot. */
        const val LARGE_FACTOR = 50
    }
}

object ScanEstimator {
    fun estimate(
        inventory: ScanInventory,
        request: ScanRequest.Custom,
    ): ScanEstimate {
        val uncapped = request.copy(limits = request.limits.copy(maxProbes = Int.MAX_VALUE))
        val candidates = ScanCandidateGenerator.generate(inventory, uncapped)
        val probes =
            candidates
                .asSequence()
                .filter { it.probeKind != ProbeKind.NONE }
                .map { it.probeKey() }
                .distinct()
                .count()
        return ScanEstimate(candidates.size, probes, request.limits)
    }
}
