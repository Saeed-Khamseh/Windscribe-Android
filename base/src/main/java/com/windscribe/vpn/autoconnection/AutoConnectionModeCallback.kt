package com.windscribe.vpn.autoconnection

interface AutoConnectionModeCallback {
    fun onCancel()

    fun onProtocolSelect(protocolInformation: ProtocolInformation) {}

    fun onSendLogClicked() {}

    fun onContactSupportClick() {}

    fun onSetAsPreferredClicked() {}

    fun onSwitchToAutoMode() {}

    /** The user chose to scan for working servers instead of giving up. */
    fun onScanClicked() {}
}
