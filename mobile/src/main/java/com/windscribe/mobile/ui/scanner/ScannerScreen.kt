package com.windscribe.mobile.ui.scanner

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.windscribe.mobile.ui.nav.LocalNavController

/** Scanner settings are separate from Windscribe's normal connection preferences. */
data class ScannerSettings(
    val connectTimeoutSeconds: Int = 25,
    val verifyTimeoutSeconds: Int = 2,
    val stopOnFirstSuccess: Boolean = true,
    val wireGuard: Boolean = true,
    val openVpnTcp: Boolean = true,
    val stealth: Boolean = true,
    val wsTunnel: Boolean = true,
) {
    init {
        require(connectTimeoutSeconds in 3..300)
        require(verifyTimeoutSeconds in 1..20)
    }
}

private object ScannerSettingsStore {
    private const val FILE = "autoscribe_scanner_settings"
    fun load(context: Context): ScannerSettings {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return ScannerSettings(
            connectTimeoutSeconds = p.getInt("connect_timeout", 25).coerceIn(3, 300),
            verifyTimeoutSeconds = p.getInt("verify_timeout", 2).coerceIn(1, 20),
            stopOnFirstSuccess = p.getBoolean("stop_first", true),
            wireGuard = p.getBoolean("wireguard", true),
            openVpnTcp = p.getBoolean("openvpn_tcp", true),
            stealth = p.getBoolean("stealth", true),
            wsTunnel = p.getBoolean("wstunnel", true),
        )
    }
    fun save(context: Context, settings: ScannerSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("connect_timeout", settings.connectTimeoutSeconds)
            .putInt("verify_timeout", settings.verifyTimeoutSeconds)
            .putBoolean("stop_first", settings.stopOnFirstSuccess)
            .putBoolean("wireguard", settings.wireGuard)
            .putBoolean("openvpn_tcp", settings.openVpnTcp)
            .putBoolean("stealth", settings.stealth)
            .putBoolean("wstunnel", settings.wsTunnel)
            .apply()
    }
}

@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    val navigation = LocalNavController.current
    var settings by remember { mutableStateOf(ScannerSettingsStore.load(context)) }
    fun update(next: ScannerSettings) {
        settings = next
        ScannerSettingsStore.save(context, next)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AutoScribe · Scanner setup", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text("Scan routes without changing the normal Windscribe connection preferences.")
        HorizontalDivider()
        Text("Protocol selection")
        @Composable fun protocolToggle(title: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title)
                Checkbox(checked = enabled, onCheckedChange = onToggle)
            }
        }
        protocolToggle("WireGuard", settings.wireGuard) { update(settings.copy(wireGuard = it)) }
        protocolToggle("OpenVPN TCP", settings.openVpnTcp) { update(settings.copy(openVpnTcp = it)) }
        protocolToggle("Stealth", settings.stealth) { update(settings.copy(stealth = it)) }
        protocolToggle("WStunnel", settings.wsTunnel) { update(settings.copy(wsTunnel = it)) }
        HorizontalDivider()
        Text("Connection timeout: ${settings.connectTimeoutSeconds} seconds")
        Slider(
            value = settings.connectTimeoutSeconds.toFloat(),
            onValueChange = { update(settings.copy(connectTimeoutSeconds = it.toInt().coerceIn(3, 300))) },
            valueRange = 3f..60f,
            steps = 56,
        )
        Text("Verification timeout: ${settings.verifyTimeoutSeconds} seconds")
        Slider(
            value = settings.verifyTimeoutSeconds.toFloat(),
            onValueChange = { update(settings.copy(verifyTimeoutSeconds = it.toInt().coerceIn(1, 20))) },
            valueRange = 1f..20f,
            steps = 18,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Stop on first verified success")
            Switch(checked = settings.stopOnFirstSuccess, onCheckedChange = { update(settings.copy(stopOnFirstSuccess = it)) })
        }
        HorizontalDivider()
        Text("Scanner engine not enabled yet. Connection controls remain unchanged until exclusive VPN ownership is enforced across all connection entry points.")
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Start scanning · coming next") }
        OutlinedButton(onClick = { navigation.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("Back to Windscribe") }
    }
}
