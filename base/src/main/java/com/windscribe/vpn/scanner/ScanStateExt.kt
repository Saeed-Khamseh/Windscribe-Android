package com.windscribe.vpn.scanner

/** Probing or verifying: a scan is under way and owns the connect path. */
fun ScanState.isRunning(): Boolean = this is ScanState.Probing || this is ScanState.Verifying

/** Finished, stopped or failed: the scan has cleaned up and its outcome is final. */
fun ScanState.isTerminal(): Boolean = this is ScanState.Finished || this is ScanState.Cancelled || this is ScanState.Failed

/** The routes that carried a download, best first. */
fun List<ScanResult>.working(): List<ScanResult> = filter { it.isWorking }
