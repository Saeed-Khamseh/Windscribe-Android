package com.windscribe.mobile.ui.scanner

import PreferencesNavBar
import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.windscribe.mobile.ui.common.PreferenceBackground
import com.windscribe.mobile.ui.helper.MultiDevicePreview
import com.windscribe.mobile.ui.helper.PreviewWithNav
import com.windscribe.mobile.ui.nav.LocalNavController
import com.windscribe.mobile.ui.theme.AppColors
import com.windscribe.mobile.ui.theme.colorForTheme
import com.windscribe.mobile.ui.theme.font14
import com.windscribe.mobile.ui.theme.font16
import com.windscribe.mobile.ui.theme.font24
import com.windscribe.mobile.ui.theme.preferencesBackgroundColor
import com.windscribe.mobile.ui.theme.preferencesSubtitleColor
import com.windscribe.mobile.ui.theme.primaryTextColor
import com.windscribe.vpn.R
import com.windscribe.vpn.scanner.ScanLocation
import com.windscribe.vpn.scanner.ScanProgress
import com.windscribe.vpn.scanner.ScanProtocol
import com.windscribe.vpn.scanner.ScanResult
import com.windscribe.vpn.scanner.ScanRoute
import com.windscribe.vpn.scanner.ScanSettings
import com.windscribe.vpn.scanner.ScanStep
import com.windscribe.vpn.scanner.buildScanPlan
import com.windscribe.vpn.scanner.rankedResults
import com.windscribe.vpn.scanner.scanCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun NetworkScannerScreen(viewModel: ScannerViewModel) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val settings by viewModel.settings.collectAsState()
    val locations by viewModel.locations.collectAsState()
    val ports by viewModel.ports.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val network by viewModel.network.collectAsState()
    val active by viewModel.active.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    var permissionError by remember { mutableStateOf<String?>(null) }
    var pendingRoute by remember { mutableStateOf<ScanRoute?>(null) }
    var requestStart by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    val vpnPermissionMessage = stringResource(R.string.scanner_vpn_permission)
    val notificationPermissionMessage = stringResource(R.string.scanner_notification_permission)
    val shareTitle = stringResource(R.string.scanner_share_report)
    val vpnPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) viewModel.start(context, pendingRoute) else permissionError = vpnPermissionMessage
        }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestStart = true else permissionError = notificationPermissionMessage
        }
    LaunchedEffect(requestStart) {
        if (!requestStart) return@LaunchedEffect
        requestStart = false
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            (
                Build.VERSION.SDK_INT >= 26 &&
                    context
                        .getSystemService(NotificationManager::class.java)
                        ?.getNotificationChannel("network_scanner")
                        ?.importance == NotificationManager.IMPORTANCE_NONE
            )
        ) {
            permissionError = notificationPermissionMessage
        } else {
            val intent = VpnService.prepare(context)
            if (intent == null) viewModel.start(context, pendingRoute) else vpnPermission.launch(intent)
        }
    }
    LaunchedEffect(export) {
        if (!export) return@LaunchedEffect
        export = false
        try {
            val uri =
                withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "network-scan.csv")
                    file.writeText(scanCsv(progress.results))
                    FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                }
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    shareTitle,
                ),
            )
        } catch (exception: Exception) {
            permissionError = exception.message
        }
    }
    val navController = LocalNavController.current
    BackHandler(active) { (context as? Activity)?.moveTaskToBack(true) }
    ScannerContent(
        settings,
        locations,
        ports,
        progress,
        active,
        loading,
        networkLabel = network?.name,
        error = permissionError ?: error,
        showNotificationSettings = permissionError == notificationPermissionMessage,
        onBack = { navController.popBackStack() },
        onUpdate = viewModel::update,
        onStart = { confirm = true },
        onStop = { viewModel.stop(context) },
        onMinimize = { (context as? Activity)?.moveTaskToBack(true) },
        onConnect = {
            pendingRoute = it
            requestStart = true
        },
        onExport = { export = true },
        onDismissError = {
            permissionError = null
            viewModel.clearError()
        },
        onNotificationSettings = {
            val intent =
                if (Build.VERSION.SDK_INT >= 26) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                }
            context.startActivity(intent)
        },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Label(stringResource(R.string.scanner_confirm_title), bold = true) },
            text = { Label(stringResource(R.string.scanner_confirm_message), secondary = true) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    pendingRoute = null
                    requestStart = true
                }) { Text(stringResource(R.string.scanner_start), color = AppColors.mediumBlue) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.scanner_cancel), color = AppColors.mediumBlue) }
            },
            containerColor = sheetColor(),
        )
    }
}

@Composable
fun ScannerContent(
    settings: ScanSettings,
    locations: List<ScanLocation>,
    ports: Map<String, List<String>>,
    progress: ScanProgress,
    active: Boolean,
    loading: Boolean,
    networkLabel: String? = null,
    error: String? = null,
    showNotificationSettings: Boolean = false,
    onBack: () -> Unit = {},
    onUpdate: ((ScanSettings) -> ScanSettings) -> Unit = {},
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onMinimize: () -> Unit = {},
    onConnect: (ScanRoute) -> Unit = {},
    onExport: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
) {
    var showResults by rememberSaveable { mutableStateOf(progress.results.isNotEmpty()) }
    var picker by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var protocolPorts by remember { mutableStateOf<ScanProtocol?>(null) }
    LaunchedEffect(active, progress.results.size) { if (!active && progress.results.isNotEmpty()) showResults = true }
    PreferenceBackground {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).navigationBarsPadding()) {
            if (active) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Radar, null, tint = scanAccent(), modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Label(stringResource(R.string.scanner_scanning), bold = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = onMinimize) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.scanner_minimize),
                            tint = MaterialTheme.colorScheme.primaryTextColor,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(16.dp))
                PreferencesNavBar(stringResource(if (showResults) R.string.scanner_results else R.string.scanner_title), onBack)
                Spacer(Modifier.height(20.dp))
            }
            if (error != null) {
                ScannerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(error, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismissError) {
                            Icon(
                                Icons.Default.Close,
                                stringResource(R.string.scanner_done),
                                tint = MaterialTheme.colorScheme.primaryTextColor,
                            )
                        }
                    }
                    if (showNotificationSettings) {
                        TextButton(onClick = onNotificationSettings) {
                            Text(stringResource(R.string.scanner_notification_permission), color = AppColors.mediumBlue)
                        }
                    }
                }
            }
            when {
                loading ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = scanAccent())
                    }
                active -> ScanLive(progress, onStop, modifier = Modifier.weight(1f))
                showResults ->
                    ScanResults(progress, onConnect, onExport, {
                        onUpdate { it.copy(resume = false) }
                        showResults = false
                    }, Modifier.weight(1f))
                else -> {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Label(stringResource(R.string.scanner_subtitle), secondary = true)
                        ScannerCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Wifi, null, tint = AppColors.mediumBlue)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Label(networkLabel ?: stringResource(R.string.scanner_current_network), bold = true)
                                    Label(stringResource(R.string.scanner_network_scope), secondary = true)
                                }
                            }
                        }
                        ScannerCard {
                            ActionRow(
                                Icons.Default.Flag,
                                stringResource(R.string.scanner_locations),
                                stringResource(
                                    R.string.scanner_locations_count,
                                    settings.locationIds.count { id ->
                                        locations.any {
                                            it.id ==
                                                id
                                        }
                                    },
                                ),
                                { picker = true },
                            )
                        }
                        ScannerCard {
                            SectionTitle(stringResource(R.string.scanner_protocols))
                            settings.protocols.forEach { protocol ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        protocol.enabled,
                                        onCheckedChange = { checked ->
                                            onUpdate {
                                                it.copy(
                                                    protocols =
                                                        it.protocols.map { p ->
                                                            if (p.id ==
                                                                protocol.id
                                                            ) {
                                                                p.copy(enabled = checked)
                                                            } else {
                                                                p
                                                            }
                                                        },
                                                )
                                            }
                                        },
                                        colors =
                                            CheckboxDefaults.colors(
                                                checkedColor = AppColors.mintGreen,
                                                checkmarkColor = AppColors.deepBlue,
                                            ),
                                    )
                                    Column(
                                        Modifier
                                            .weight(
                                                1f,
                                            ).clip(RoundedCornerShape(8.dp))
                                            .clickable { protocolPorts = protocol }
                                            .padding(vertical = 8.dp),
                                    ) {
                                        Label(protocol.name, bold = true)
                                        Label(protocol.ports.joinToString(", "), secondary = true)
                                    }
                                    IconButton(onClick = {
                                        protocolPorts = protocol
                                    }) {
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            stringResource(R.string.scanner_select_ports),
                                            tint = MaterialTheme.colorScheme.preferencesSubtitleColor,
                                        )
                                    }
                                }
                            }
                        }
                        ScannerCard {
                            SectionTitle(stringResource(R.string.scanner_options))
                            ToggleRow(
                                stringResource(R.string.scanner_first_success),
                                stringResource(R.string.scanner_first_success_detail),
                                settings.stopOnSuccess,
                            ) { checked ->
                                onUpdate {
                                    it.copy(stopOnSuccess = checked)
                                }
                            }
                            Divider()
                            ToggleRow(
                                stringResource(R.string.scanner_resume),
                                stringResource(R.string.scanner_resume_detail),
                                settings.resume,
                            ) { checked ->
                                onUpdate {
                                    it.copy(resume = checked)
                                }
                            }
                        }
                        ScannerCard {
                            ActionRow(
                                Icons.Default.Tune,
                                stringResource(
                                    R.string.scanner_advanced,
                                ),
                                "${settings.pingCount} pings · ${settings.connectionTimeout}s",
                                {
                                    advanced =
                                        true
                                },
                            )
                        }
                        if (progress.results.isNotEmpty()) {
                            TextButton(onClick = {
                                showResults = true
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.scanner_back_results),
                                    color = AppColors.mediumBlue,
                                )
                            }
                        }
                        Label(stringResource(R.string.scanner_warning), secondary = true)
                        if (locations.isEmpty()) Label(stringResource(R.string.scanner_empty_locations), secondary = true)
                        Spacer(Modifier.height(4.dp))
                    }
                    Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Label(
                            stringResource(
                                R.string.scanner_plan,
                                locations.count {
                                    it.id in settings.locationIds
                                },
                                buildScanPlan(settings, locations).size,
                            ),
                            secondary = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        ScanButton(
                            stringResource(R.string.scanner_start),
                            Icons.Default.PlayArrow,
                            enabled = settings.isValid() && buildScanPlan(settings, locations).isNotEmpty(),
                            onClick = onStart,
                        )
                    }
                }
            }
        }
    }
    if (!active &&
        picker
    ) {
        LocationPicker(locations, settings.locationIds, { picker = false }) { selected ->
            onUpdate { it.copy(locationIds = selected) }
            picker =
                false
        }
    }
    if (!active && advanced) {
        AdvancedSettings(settings, { advanced = false }) { updated ->
            onUpdate { updated }
            advanced = false
        }
    }
    if (!active) {
        protocolPorts?.let { protocol ->
            PortPicker(protocol, ports[protocol.id].orEmpty(), { protocolPorts = null }) { selected ->
                onUpdate { it.copy(protocols = it.protocols.map { p -> if (p.id == protocol.id) p.copy(ports = selected) else p }) }
                protocolPorts = null
            }
        }
    }
}

@Composable
private fun ScanLive(
    progress: ScanProgress,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScannerCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = scanAccent(), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Label(stringResource(R.string.scanner_lock), bold = true)
                }
                Label(stringResource(R.string.scanner_lock_detail), secondary = true)
            }
            ScannerCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(stringResource(R.string.scanner_progress_count, progress.completed, progress.total), bold = true)
                    Label("${if (progress.total == 0) 0 else progress.completed * 100 / progress.total}%", secondary = true)
                }
                LinearProgressIndicator(
                    progress = {
                        if (progress.total ==
                            0
                        ) {
                            0f
                        } else {
                            progress.completed.toFloat() / progress.total
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(
                                6.dp,
                            ).clip(
                                CircleShape,
                            ),
                    color = scanAccent(),
                    trackColor = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = .1f),
                )
            }
            ScannerCard {
                SectionTitle(stringResource(R.string.scanner_testing_now), accent = true)
                progress.route?.let {
                    Label(it.location.label, bold = true)
                    Label("${it.location.nickname} · ${it.protocolName} · ${it.port}", secondary = true)
                }
                if (progress.step == ScanStep.Stopping) {
                    Label(stringResource(R.string.scanner_stopping))
                } else {
                    val steps =
                        listOf(
                            ScanStep.Disconnecting to R.string.scanner_step_disconnect,
                            ScanStep.Connecting to R.string.scanner_step_connect,
                            ScanStep.Settling to R.string.scanner_step_settle,
                            ScanStep.Measuring to R.string.scanner_step_measure,
                            ScanStep.Saving to R.string.scanner_step_save,
                        )
                    steps.forEach { (step, title) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            when {
                                step.ordinal < progress.step.ordinal ->
                                    Icon(
                                        Icons.Default.Check,
                                        null,
                                        tint = scanAccent(),
                                        modifier = Modifier.size(20.dp),
                                    )
                                step == progress.step ->
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        color = AppColors.cyberBlue,
                                        strokeWidth = 2.dp,
                                    )
                                else ->
                                    Box(
                                        Modifier.size(18.dp).border(1.dp, MaterialTheme.colorScheme.preferencesSubtitleColor, CircleShape),
                                    )
                            }
                            Spacer(Modifier.width(12.dp))
                            Label(stringResource(title), secondary = step.ordinal > progress.step.ordinal)
                        }
                    }
                }
            }
            ScannerCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(stringResource(R.string.scanner_working), progress.results.count { it.working }.toString(), scanAccent())
                    Stat(stringResource(R.string.scanner_failed), progress.results.count { !it.working }.toString(), AppColors.red)
                    Stat(
                        stringResource(R.string.scanner_best_ping),
                        progress.best?.averagePing?.let { "$it ms" } ?: "—",
                        AppColors.cyberBlue,
                    )
                }
            }
            progress.best?.let { best ->
                ScannerCard {
                    SectionTitle(stringResource(R.string.scanner_best_so_far), true)
                    RouteSummary(best)
                }
            }
        }
        OutlinedButton(
            onClick = onStop,
            enabled = progress.step != ScanStep.Stopping,
            modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 4.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                stringResource(R.string.scanner_stop),
                color = MaterialTheme.colorScheme.primaryTextColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ScanResults(
    progress: ScanProgress,
    connect: (ScanRoute) -> Unit,
    export: () -> Unit,
    again: () -> Unit,
    modifier: Modifier,
) {
    var all by rememberSaveable { mutableStateOf(false) }
    val ranked = remember(progress.results) { rankedResults(progress.results) }
    val displayed = if (all) ranked + progress.results.filterNot { it.working } else ranked
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Text(
                stringResource(R.string.scanner_results_summary, progress.completed, ranked.size),
                style = font24,
                color = MaterialTheme.colorScheme.primaryTextColor,
            )
            progress.message?.let {
                Spacer(Modifier.height(8.dp))
                Label(it, secondary = true)
            }
        }
        item {
            ranked.firstOrNull()?.let { best ->
                ScannerCard(accent = true) {
                    SectionTitle(stringResource(R.string.scanner_best_route), true)
                    RouteSummary(best)
                    Text(
                        best.averagePing?.let {
                            stringResource(R.string.scanner_ping_ms, it)
                        } ?: "—",
                        style =
                            font24.copy(
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        color = MaterialTheme.colorScheme.primaryTextColor,
                    )
                    Label(stringResource(R.string.scanner_loss, best.packetLoss), secondary = true)
                    ScanButton(stringResource(R.string.scanner_connect), Icons.Default.PlayArrow, onClick = { connect(best.route) })
                }
            }
                ?: ScannerCard {
                    SectionTitle(stringResource(R.string.scanner_no_results))
                    Label(stringResource(R.string.scanner_no_results_detail), secondary = true)
                }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    !all,
                    { all = false },
                    label = { Label("${stringResource(R.string.scanner_working)} (${ranked.size})") },
                    colors = chipColors(),
                )
                FilterChip(
                    all,
                    { all = true },
                    label = { Label("${stringResource(R.string.scanner_all)} (${progress.completed})") },
                    colors = chipColors(),
                )
            }
        }
        items(displayed, key = { it.route.key }) { result ->
            ScannerCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RouteSummary(result)
                        result.failure?.let { Label(it, secondary = true) }
                    }
                    if (result.working) {
                        IconButton(onClick = {
                            connect(result.route)
                        }) { Icon(Icons.Default.ChevronRight, stringResource(R.string.scanner_connect), tint = AppColors.mediumBlue) }
                    }
                }
            }
        }
        item {
            ScannerCard {
                ActionRow(Icons.Default.Download, stringResource(R.string.scanner_export), onClick = export)
                Divider()
                ActionRow(Icons.Default.Refresh, stringResource(R.string.scanner_again), onClick = again)
            }
        }
    }
}

@Composable
private fun RouteSummary(result: ScanResult) {
    Label(result.route.location.label, bold = true)
    Label("${result.route.location.nickname} · ${result.route.protocolName} · ${result.route.port}", secondary = true)
    result.averagePing?.let {
        Label(
            "${stringResource(R.string.scanner_ping_ms, it)} · ${stringResource(R.string.scanner_loss, result.packetLoss)}",
            secondary = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPicker(
    locations: List<ScanLocation>,
    ids: List<Int>,
    close: () -> Unit,
    save: (List<Int>) -> Unit,
) {
    var selected by remember { mutableStateOf(ids.toSet()) }
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = locations.filter { "${it.label} ${it.nickname} ${it.hostname}".contains(search.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = close, containerColor = sheetColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            SectionTitle(stringResource(R.string.scanner_choose_locations))
            OutlinedTextField(search, {
                search = it
            }, singleLine = true, leadingIcon = {
                Icon(Icons.Default.Search, null)
            }, label = {
                Text(
                    stringResource(R.string.scanner_search_locations),
                )
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(selected.size.toString(), bold = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    selected = selected + filtered.map { it.id }
                }) { Text(stringResource(R.string.scanner_select_all), color = AppColors.mediumBlue) }
                TextButton(onClick = {
                    selected = selected - filtered.map { it.id }.toSet()
                }) { Text(stringResource(R.string.scanner_clear), color = AppColors.mediumBlue) }
            }
            LazyColumn(Modifier.weight(1f, fill = false).height(360.dp)) {
                if (filtered.isEmpty()) item { Label(stringResource(R.string.scanner_empty_search), secondary = true) }
                filtered.groupBy { it.country }.forEach { (country, cities) ->
                    item(key = "country:$country") {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val keys = cities.map { it.id }.toSet()
                                selected = if (selected.containsAll(keys)) selected - keys else selected + keys
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(cities.all { it.id in selected }, onCheckedChange = null, colors = scanCheckboxColors())
                            Label(country, bold = true)
                        }
                    }
                    items(cities, key = { it.id }) { city ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selected =
                                        if (city.id in
                                            selected
                                        ) {
                                            selected - city.id
                                        } else {
                                            selected + city.id
                                        }
                                }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(city.id in selected, onCheckedChange = null, colors = scanCheckboxColors())
                            Column(Modifier.weight(1f)) {
                                Label(city.city)
                                Label("${city.nickname} · ${city.hostname}", secondary = true)
                            }
                        }
                    }
                }
            }
            ScanButton(stringResource(R.string.scanner_done), enabled = selected.isNotEmpty(), onClick = { save(selected.toList()) })
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PortPicker(
    protocol: ScanProtocol,
    available: List<String>,
    close: () -> Unit,
    save: (List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(protocol.ports.toSet()) }
    val choices = (available + protocol.ports).distinct()
    AlertDialog(
        onDismissRequest = close,
        containerColor = sheetColor(),
        title = { Label(protocol.name, bold = true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { port ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected =
                                if (port in
                                    selected
                                ) {
                                    selected - port
                                } else {
                                    selected + port
                                }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(port in selected, onCheckedChange = null, colors = scanCheckboxColors())
                        Label(port)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                save(choices.filter { it in selected })
            }, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.scanner_done), color = AppColors.mediumBlue) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.scanner_cancel), color = AppColors.mediumBlue) } },
    )
}

@Composable
private fun AdvancedSettings(
    settings: ScanSettings,
    close: () -> Unit,
    save: (ScanSettings) -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(settings.pingTarget) }
    var count by rememberSaveable { mutableStateOf(settings.pingCount.toString()) }
    var timeout by rememberSaveable { mutableStateOf(settings.connectionTimeout.toString()) }
    var settle by rememberSaveable { mutableStateOf(settings.settleDelay.toString()) }
    var protocols by remember { mutableStateOf(settings.protocols) }
    var invalid by remember { mutableStateOf(false) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val dragThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    Dialog(onDismissRequest = close) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(22.dp),
                ).background(sheetColor())
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle(stringResource(R.string.scanner_advanced))
            SettingField(stringResource(R.string.scanner_ping_target), target, { target = it })
            SettingField(stringResource(R.string.scanner_ping_count), count, { count = it }, true)
            SettingField(stringResource(R.string.scanner_connect_timeout), timeout, { timeout = it }, true)
            SettingField(stringResource(R.string.scanner_settle_delay), settle, { settle = it }, true)
            SectionTitle(stringResource(R.string.scanner_priority))
            Label(stringResource(R.string.scanner_priority_detail), secondary = true)
            protocols.forEachIndexed { index, protocol ->
                key(protocol.id) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .zIndex(if (dragged == protocol.id) 1f else 0f)
                                .graphicsLayer { translationY = if (dragged == protocol.id) dragOffset else 0f }
                                .pointerInput(protocol.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragged = protocol.id
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            dragged = null
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            dragged = null
                                            dragOffset = 0f
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val current = protocols.indexOfFirst { it.id == protocol.id }
                                        val offset =
                                            when {
                                                dragOffset > dragThreshold && current < protocols.lastIndex -> 1
                                                dragOffset < -dragThreshold && current > 0 -> -1
                                                else -> 0
                                            }
                                        if (offset != 0) {
                                            protocols = protocols.toMutableList().apply { add(current + offset, removeAt(current)) }
                                            dragOffset -= offset * dragThreshold
                                        }
                                    }
                                },
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            stringResource(R.string.scanner_drag),
                            tint = MaterialTheme.colorScheme.preferencesSubtitleColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Label(protocol.name, modifier = Modifier.weight(1f))

                        fun move(offset: Int) {
                            protocols = protocols.toMutableList().apply { add(index + offset, removeAt(index)) }
                        }
                        IconButton(onClick = { move(-1) }, enabled = index > 0) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                stringResource(R.string.scanner_move_up),
                                tint =
                                    if (index >
                                        0
                                    ) {
                                        AppColors.mediumBlue
                                    } else {
                                        Color.Gray
                                    },
                            )
                        }
                        IconButton(onClick = { move(1) }, enabled = index < protocols.lastIndex) {
                            Icon(
                                Icons.Default.ArrowDownward,
                                stringResource(R.string.scanner_move_down),
                                tint =
                                    if (index <
                                        protocols.lastIndex
                                    ) {
                                        AppColors.mediumBlue
                                    } else {
                                        Color.Gray
                                    },
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(invalid) { Label(stringResource(R.string.scanner_invalid_settings)) }
            ScanButton(stringResource(R.string.scanner_save), onClick = {
                val updated =
                    settings.copy(
                        pingTarget = target.trim(),
                        pingCount = count.toIntOrNull() ?: 0,
                        connectionTimeout = timeout.toIntOrNull() ?: 0,
                        settleDelay = settle.toIntOrNull() ?: -1,
                        protocols = protocols,
                    )
                // Location/protocol selection may be empty while editing advanced options.
                if (updated.copy(locationIds = listOf(1), protocols = listOf(ScanProtocol("wg", "WireGuard", listOf("443")))).isValid()) {
                    save(updated)
                } else {
                    invalid =
                        true
                }
            })
            TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scanner_cancel), color = AppColors.mediumBlue)
            }
        }
    }
}

@Composable
private fun SettingField(
    title: String,
    value: String,
    change: (String) -> Unit,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value,
        change,
        label = { Text(title) },
        singleLine = true,
        colors = scannerFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ScannerCard(
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(sheetColor())
            .then(if (accent) Modifier.border(1.dp, AppColors.mintGreen.copy(alpha = .45f), shape) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun sheetColor(): Color =
    androidx.compose.ui.graphics
        .lerp(MaterialTheme.colorScheme.preferencesBackgroundColor, MaterialTheme.colorScheme.primaryTextColor, .06f)

@Composable
private fun Label(
    text: String,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    bold: Boolean = false,
) {
    Text(
        text,
        modifier = modifier,
        style = if (secondary) font14 else font16,
        color = if (secondary) MaterialTheme.colorScheme.preferencesSubtitleColor else MaterialTheme.colorScheme.primaryTextColor,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun SectionTitle(
    title: String,
    accent: Boolean = false,
) {
    Text(
        title,
        style = font14,
        fontWeight = FontWeight.SemiBold,
        color = if (accent) scanAccent() else MaterialTheme.colorScheme.primaryTextColor,
        letterSpacing = .6.sp,
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primaryTextColor, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Label(title, bold = true)
            detail?.let { Label(it, secondary = true) }
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.preferencesSubtitleColor)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label(title)
            Label(detail, secondary = true)
        }
        Switch(
            checked,
            onCheckedChange = change,
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.mintGreen, checkedThumbColor = AppColors.deepBlue),
        )
    }
}

@Composable
private fun Stat(
    title: String,
    value: String,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Label(title, secondary = true)
        Text(value, style = font24, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = .07f))

@Composable
private fun ScanButton(
    title: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp).testTag("scanner_primary_action"),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.mintGreen, contentColor = AppColors.deepBlue),
    ) {
        icon?.let {
            Icon(it, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = font16, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun scanCheckboxColors() = CheckboxDefaults.colors(checkedColor = AppColors.mintGreen, checkmarkColor = AppColors.deepBlue)

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.mediumBlue.copy(alpha = .18f))

@MultiDevicePreview
@Composable
private fun ScannerSetupPreview() =
    PreviewWithNav {
        ScannerContent(
            ScanSettings(locationIds = listOf(1)),
            listOf(ScanLocation(1, "Netherlands", "NL", "Amsterdam", "Tulip", "nl.example")),
            emptyMap(),
            ScanProgress(),
            false,
            false,
        )
    }

@MultiDevicePreview
@Composable
private fun ScannerLivePreview() =
    PreviewWithNav {
        val route = ScanRoute(ScanLocation(1, "Netherlands", "NL", "Amsterdam", "Tulip", "nl.example"), "wg", "WireGuard", "443")
        ScannerContent(
            ScanSettings(),
            emptyList(),
            emptyMap(),
            ScanProgress(true, route, ScanStep.Measuring, 30, listOf(ScanResult(route, listOf(84, 86, 82)))),
            true,
            false,
        )
    }

@MultiDevicePreview
@Composable
private fun ScannerResultsPreview() =
    PreviewWithNav {
        val route = ScanRoute(ScanLocation(1, "Netherlands", "NL", "Amsterdam", "Tulip", "nl.example"), "wg", "WireGuard", "443")
        ScannerContent(
            ScanSettings(),
            emptyList(),
            emptyMap(),
            ScanProgress(total = 1, results = listOf(ScanResult(route, listOf(84, 86, 82)))),
            false,
            false,
        )
    }

@Composable
private fun scanAccent(): Color = MaterialTheme.colorScheme.colorForTheme(AppColors.mintGreen, Color(0xFF007D43))

@Composable
private fun scannerFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.primaryTextColor,
        unfocusedTextColor = MaterialTheme.colorScheme.primaryTextColor,
        focusedBorderColor = AppColors.mediumBlue,
        focusedLabelColor = AppColors.mediumBlue,
        cursorColor = AppColors.mediumBlue,
        unfocusedBorderColor = MaterialTheme.colorScheme.preferencesSubtitleColor,
    )
