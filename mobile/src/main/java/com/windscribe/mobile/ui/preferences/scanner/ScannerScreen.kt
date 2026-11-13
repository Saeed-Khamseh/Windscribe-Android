package com.windscribe.mobile.ui.preferences.scanner

import PreferencesNavBar
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.windscribe.mobile.R
import com.windscribe.mobile.ui.common.NextButton
import com.windscribe.mobile.ui.common.PreferenceBackground
import com.windscribe.mobile.ui.common.ScreenDescription
import com.windscribe.mobile.ui.helper.MultiDevicePreview
import com.windscribe.mobile.ui.helper.PreviewWithNav
import com.windscribe.mobile.ui.helper.hapticClickable
import com.windscribe.mobile.ui.nav.LocalNavController
import com.windscribe.mobile.ui.nav.Screen
import com.windscribe.mobile.ui.theme.AppColors
import com.windscribe.mobile.ui.theme.font12
import com.windscribe.mobile.ui.theme.font14
import com.windscribe.mobile.ui.theme.font16
import com.windscribe.mobile.ui.theme.preferencesSubtitleColor
import com.windscribe.mobile.ui.theme.primaryTextColor
import com.windscribe.vpn.apppreference.PreferencesKeyConstants
import com.windscribe.vpn.backend.Util
import com.windscribe.vpn.scanner.ScanCandidate
import com.windscribe.vpn.scanner.ScanCandidateGenerator
import com.windscribe.vpn.scanner.ScanEstimate
import com.windscribe.vpn.scanner.ScanLimits
import com.windscribe.vpn.scanner.ScanResult
import com.windscribe.vpn.scanner.ScanState
import com.windscribe.vpn.scanner.ScanTweak
import com.windscribe.vpn.scanner.VerifyResult
import com.windscribe.vpn.scanner.working
import java.util.Locale
import com.windscribe.vpn.R as BaseR

/** What the user asked to start, kept while the permissions it needs are asked for. */
private enum class PendingScan { Quick, Custom }

class ScannerActions(
    val onQuickScan: () -> Unit = {},
    val onCustomScan: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onScanAgain: () -> Unit = {},
    val onRouteClick: (ScanResult) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onCityToggle: (Int) -> Unit = {},
    val onClearCities: () -> Unit = {},
    val onProtocolToggle: (String) -> Unit = {},
    val onTweakToggle: (CustomTweak) -> Unit = {},
    val onTlsServerNameChange: (String) -> Unit = {},
    val onNodesPerCityChange: (Int) -> Unit = {},
)

/**
 * Stateful entry point: owns the [ScannerViewModel], the permissions a scan needs and the side
 * effects, then delegates rendering to [ScannerContent].
 */
@Composable
fun ScannerScreen(viewModel: ScannerViewModel = hiltViewModel<ScannerViewModelImpl>()) {
    val state by viewModel.state.collectAsState()
    val custom by viewModel.custom.collectAsState()
    val context = LocalContext.current
    val navController = LocalNavController.current
    var pending by remember { mutableStateOf<PendingScan?>(null) }

    fun begin(scan: PendingScan) {
        when (scan) {
            PendingScan.Quick -> viewModel.startQuickScan()
            PendingScan.Custom -> viewModel.startCustomScan()
        }
    }

    // Asked last. A scan runs and reports through the service whatever the answer: without the
    // permission only the notification is missing.
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pending?.let(::begin)
            pending = null
        }

    fun askNotificationsThenBegin(scan: PendingScan) {
        val needsAsk =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            pending = scan
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            begin(scan)
        }
    }

    // Every verification builds a real tunnel, which needs the system's VPN permission. Without it
    // the permission dialog would pop up in the middle of the scan.
    val vpnPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val scan = pending
            if (result.resultCode == Activity.RESULT_OK && scan != null) {
                askNotificationsThenBegin(scan)
            } else {
                pending = null
            }
        }

    fun startScan(scan: PendingScan) {
        val vpnPermission = VpnService.prepare(context)
        if (vpnPermission != null) {
            pending = scan
            vpnPermissionLauncher.launch(vpnPermission)
        } else {
            askNotificationsThenBegin(scan)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ScannerEvent.GoHome -> {
                    if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
                        navController.navigate(Screen.Home.route) { launchSingleTop = true }
                    }
                }

                is ScannerEvent.Message -> Toast.makeText(context, event.resId, Toast.LENGTH_LONG).show()
            }
        }
    }

    ScannerContent(
        state = state,
        custom = custom,
        actions =
            ScannerActions(
                onQuickScan = { startScan(PendingScan.Quick) },
                onCustomScan = { startScan(PendingScan.Custom) },
                onStop = viewModel::stopScan,
                onScanAgain = viewModel::scanAgain,
                onRouteClick = viewModel::onRouteClicked,
                onQueryChange = viewModel::onQueryChanged,
                onCityToggle = viewModel::onCityToggled,
                onClearCities = viewModel::onClearCities,
                onProtocolToggle = viewModel::onProtocolToggled,
                onTweakToggle = viewModel::onTweakToggled,
                onTlsServerNameChange = viewModel::onTlsServerNameChanged,
                onNodesPerCityChange = viewModel::onNodesPerCityChanged,
            ),
    )
}

/** Stateless scanner UI: renders identically in the app and in `@Preview`. */
@Composable
fun ScannerContent(
    state: ScannerUiState,
    custom: CustomScanState,
    actions: ScannerActions,
) {
    val navController = LocalNavController.current
    PreferenceBackground {
        Column(modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp)) {
            PreferencesNavBar(stringResource(BaseR.string.scan_title)) {
                navController.popBackStack()
            }
            Spacer(modifier = Modifier.height(20.dp))
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState()),
            ) {
                when (state.phase) {
                    ScannerPhase.Setup -> SetupContent(state, custom, actions)
                    ScannerPhase.Running -> RunningContent(state, actions)
                    ScannerPhase.Results -> ResultsContent(state, actions)
                }
            }
        }
    }
}

// ---- Setup

@Composable
private fun SetupContent(
    state: ScannerUiState,
    custom: CustomScanState,
    actions: ScannerActions,
) {
    ScreenDescription(stringResource(BaseR.string.scan_description))
    Spacer(modifier = Modifier.height(16.dp))
    VpnNotice()
    when (val banner = state.banner) {
        is ScannerBanner.Failed -> {
            Spacer(modifier = Modifier.height(16.dp))
            ScreenDescription(stringResource(BaseR.string.scan_last_failed, banner.reason))
        }

        ScannerBanner.Stopped -> {
            Spacer(modifier = Modifier.height(16.dp))
            ScreenDescription(stringResource(BaseR.string.scan_last_stopped))
        }

        null -> Unit
    }
    Spacer(modifier = Modifier.height(16.dp))
    QuickScanCard(actions.onQuickScan)
    if (state.saved.isNotEmpty()) {
        Spacer(modifier = Modifier.height(24.dp))
        RouteList(
            title = stringResource(BaseR.string.scan_results_saved_title),
            results = state.saved,
            showAge = true,
            onClick = actions.onRouteClick,
        )
    }
    Spacer(modifier = Modifier.height(24.dp))
    CustomScanSection(custom, actions)
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun VpnNotice() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, AppColors.yellow.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_warning_icon),
                contentDescription = null,
                tint = AppColors.yellow,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(BaseR.string.scan_vpn_notice_title),
                style = font16.copy(fontWeight = FontWeight.Medium, textAlign = TextAlign.Start),
                color = MaterialTheme.colorScheme.primaryTextColor,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(BaseR.string.scan_vpn_notice),
            style = font14.copy(fontWeight = FontWeight.Normal, textAlign = TextAlign.Start),
            color = MaterialTheme.colorScheme.preferencesSubtitleColor,
        )
    }
}

@Composable
private fun QuickScanCard(onStart: () -> Unit) {
    NextButton(text = stringResource(BaseR.string.scan_quick), enabled = true, onClick = onStart)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(BaseR.string.scan_quick_description),
        style = font12.copy(textAlign = TextAlign.Start),
        color = MaterialTheme.colorScheme.preferencesSubtitleColor,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomScanSection(
    custom: CustomScanState,
    actions: ScannerActions,
) {
    SectionTitle(stringResource(BaseR.string.scan_custom_section))
    Spacer(modifier = Modifier.height(12.dp))

    // Cities
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(BaseR.string.scan_cities),
            style = font14.copy(textAlign = TextAlign.Start),
            color = MaterialTheme.colorScheme.primaryTextColor,
            modifier = Modifier.weight(1f),
        )
        if (custom.selectedCityIds.isNotEmpty()) {
            Text(
                text = stringResource(BaseR.string.scan_cities_selected, custom.selectedCityIds.size),
                style = font12,
                color = MaterialTheme.colorScheme.preferencesSubtitleColor,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(BaseR.string.scan_cities_clear),
                style = font12.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primaryTextColor,
                modifier = Modifier.hapticClickable { actions.onClearCities() },
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    ScannerTextField(
        value = custom.query,
        hint = stringResource(BaseR.string.scan_cities_search_hint),
        onValueChange = actions.onQueryChange,
    )
    Spacer(modifier = Modifier.height(8.dp))
    CityList(custom, actions.onCityToggle)

    // Protocols
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(BaseR.string.scan_protocols),
        style = font14.copy(textAlign = TextAlign.Start),
        color = MaterialTheme.colorScheme.primaryTextColor,
    )
    Text(
        text = stringResource(BaseR.string.scan_protocols_hint),
        style = font12.copy(textAlign = TextAlign.Start),
        color = MaterialTheme.colorScheme.preferencesSubtitleColor,
    )
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScanCandidateGenerator.PROTOCOL_ORDER.forEach { protocol ->
            Chip(Util.getProtocolLabel(protocol), protocol in custom.protocols) { actions.onProtocolToggle(protocol) }
        }
    }

    // Tweaks
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(BaseR.string.scan_tweaks),
        style = font14.copy(textAlign = TextAlign.Start),
        color = MaterialTheme.colorScheme.primaryTextColor,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        TweakRow(BaseR.string.scan_tweak_protocol_tweaks, CustomTweak.ProtocolTweaks in custom.tweaks, true) {
            actions.onTweakToggle(CustomTweak.ProtocolTweaks)
        }
        TweakRow(BaseR.string.scan_tweak_tls_padding, CustomTweak.ExtraTlsPadding in custom.tweaks, true) {
            actions.onTweakToggle(CustomTweak.ExtraTlsPadding)
        }
        TweakRow(BaseR.string.scan_tweak_amnezia, CustomTweak.AmneziaWg in custom.tweaks, custom.amneziaAvailable) {
            actions.onTweakToggle(CustomTweak.AmneziaWg)
        }
        TweakRow(BaseR.string.scan_tweak_alt_routing, CustomTweak.AlternateRouting in custom.tweaks, true) {
            actions.onTweakToggle(CustomTweak.AlternateRouting)
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    ScannerTextField(
        value = custom.tlsServerName,
        hint = stringResource(BaseR.string.scan_tweak_sni_hint),
        onValueChange = actions.onTlsServerNameChange,
    )

    // Nodes per city
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(BaseR.string.scan_nodes_per_city),
        style = font14.copy(textAlign = TextAlign.Start),
        color = MaterialTheme.colorScheme.primaryTextColor,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CustomScanState.NODES_PER_CITY_CHOICES.forEach { nodes ->
            Chip(nodes.toString(), nodes == custom.nodesPerCity) { actions.onNodesPerCityChange(nodes) }
        }
    }

    // Estimate and start
    Spacer(modifier = Modifier.height(16.dp))
    Estimate(custom)
    Spacer(modifier = Modifier.height(12.dp))
    NextButton(
        text = stringResource(BaseR.string.scan_custom_start),
        enabled = custom.selectedCityIds.isNotEmpty() && (custom.estimate?.candidates ?: 1) > 0,
        onClick = actions.onCustomScan,
    )
}

@Composable
private fun Estimate(custom: CustomScanState) {
    val estimate = custom.estimate
    if (custom.selectedCityIds.isEmpty() || estimate == null) {
        Text(
            text = stringResource(BaseR.string.scan_estimate_pick_cities),
            style = font14.copy(fontWeight = FontWeight.Normal, textAlign = TextAlign.Start),
            color = MaterialTheme.colorScheme.preferencesSubtitleColor,
        )
        return
    }
    Text(
        text = stringResource(BaseR.string.scan_estimate, estimate.candidates),
        style = font14.copy(textAlign = TextAlign.Start),
        color = MaterialTheme.colorScheme.primaryTextColor,
    )
    if (estimate.isLarge) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                painter = painterResource(R.drawable.ic_warning_icon),
                contentDescription = null,
                tint = AppColors.yellow,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(BaseR.string.scan_estimate_large, estimate.limits.maxProbes, estimate.limits.maxVerifications),
                style = font12.copy(textAlign = TextAlign.Start),
                color = MaterialTheme.colorScheme.preferencesSubtitleColor,
            )
        }
    }
}

@Composable
private fun CityList(
    custom: CustomScanState,
    onToggle: (Int) -> Unit,
) {
    val cities = custom.visibleCities
    when {
        custom.loading -> {
            Box(
                modifier = Modifier.fillMaxWidth().height(CITY_LIST_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primaryTextColor)
            }
        }

        custom.cities.isEmpty() -> ScreenDescription(stringResource(BaseR.string.scan_cities_unavailable))
        cities.isEmpty() -> ScreenDescription(stringResource(BaseR.string.scan_cities_none))
        else -> {
            // Bounded height, so it can sit inside the screen's own scroll.
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(CITY_LIST_HEIGHT)
                        .background(MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
            ) {
                items(cities, key = { it.id }) { city ->
                    CityRow(city, city.id in custom.selectedCityIds) { onToggle(city.id) }
                }
            }
        }
    }
}

private val CITY_LIST_HEIGHT = 240.dp

@Composable
private fun CityRow(
    city: ScannerCity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .hapticClickable(onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = city.name,
            style = font14.copy(fontWeight = FontWeight.Normal, textAlign = TextAlign.Start),
            color = MaterialTheme.colorScheme.primaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (city.isFavourite) {
            Icon(
                painter = painterResource(R.drawable.ic_star),
                contentDescription = null,
                tint = AppColors.yellow,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Check(selected)
    }
}

@Composable
private fun Check(selected: Boolean) {
    Icon(
        painter = painterResource(R.drawable.ic_check),
        contentDescription = null,
        tint = if (selected) AppColors.neonGreen else MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.15f),
        modifier = Modifier.size(16.dp),
    )
}

@Composable
private fun TweakRow(
    labelRes: Int,
    selected: Boolean,
    available: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.primaryTextColor.copy(alpha = if (available) 0.05f else 0.02f),
                    RoundedCornerShape(8.dp),
                ).then(if (available) Modifier.hapticClickable(onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = font14.copy(fontWeight = FontWeight.Normal, textAlign = TextAlign.Start),
            color = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = if (available) 1f else 0.4f),
            modifier = Modifier.weight(1f),
        )
        Check(selected && available)
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = font14.copy(fontWeight = FontWeight.Medium),
        color = if (selected) AppColors.deepBlue else MaterialTheme.colorScheme.primaryTextColor,
        modifier =
            Modifier
                .background(
                    if (selected) AppColors.neonGreen else MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.05f),
                    RoundedCornerShape(20.dp),
                ).hapticClickable(onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ScannerTextField(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
) {
    TextField(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = {
            Text(
                hint,
                style = font14,
                color = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.6f),
            )
        },
        textStyle = font14.copy(textAlign = TextAlign.Start),
        colors =
            TextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.primaryTextColor,
                unfocusedTextColor = MaterialTheme.colorScheme.primaryTextColor,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primaryTextColor,
            ),
    )
}

// ---- Running

@Composable
private fun RunningContent(
    state: ScannerUiState,
    actions: ScannerActions,
) {
    val progress = state.progress
    VpnNotice()
    Spacer(modifier = Modifier.height(24.dp))
    when (progress) {
        is ScanState.Probing -> {
            StageHeader(stringResource(BaseR.string.scan_stage_probing))
            Spacer(modifier = Modifier.height(12.dp))
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.neonGreen,
                    trackColor = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.1f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Caption(stringResource(BaseR.string.scan_progress_count, progress.done, progress.total))
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.neonGreen,
                    trackColor = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.1f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Caption(stringResource(BaseR.string.scan_preparing))
            }
        }

        is ScanState.Verifying -> {
            StageHeader(stringResource(BaseR.string.scan_stage_verifying))
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (progress.index - 1).coerceAtLeast(0).toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
                color = AppColors.neonGreen,
                trackColor = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.1f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Caption(stringResource(BaseR.string.scan_progress_count, progress.index, progress.total))
            Spacer(modifier = Modifier.height(16.dp))
            Caption(stringResource(BaseR.string.scan_testing_now))
            Text(
                text = describe(progress.current),
                style = font16.copy(fontWeight = FontWeight.Medium, textAlign = TextAlign.Start),
                color = MaterialTheme.colorScheme.primaryTextColor,
            )
        }

        else -> StageHeader(stringResource(BaseR.string.scan_preparing))
    }
    val working = state.found.working()
    if (working.isNotEmpty()) {
        Spacer(modifier = Modifier.height(24.dp))
        RouteList(
            title = stringResource(BaseR.string.scan_found_so_far),
            results = working,
            showAge = false,
            onClick = actions.onRouteClick,
        )
    }
    Spacer(modifier = Modifier.height(24.dp))
    NextButton(
        text = stringResource(if (state.stopping) BaseR.string.scan_stopping else BaseR.string.scan_stop),
        enabled = !state.stopping,
        onClick = actions.onStop,
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun StageHeader(text: String) {
    Text(
        text = text,
        style = font16.copy(fontWeight = FontWeight.Medium, textAlign = TextAlign.Start),
        color = MaterialTheme.colorScheme.primaryTextColor,
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = font12.copy(textAlign = TextAlign.Start),
        color = MaterialTheme.colorScheme.preferencesSubtitleColor,
    )
}

// ---- Results

@Composable
private fun ResultsContent(
    state: ScannerUiState,
    actions: ScannerActions,
) {
    val working = state.found.working()
    RouteList(
        title = stringResource(if (state.resultsStopped) BaseR.string.scan_results_stopped_title else BaseR.string.scan_results_title),
        results = state.found,
        showAge = false,
        onClick = actions.onRouteClick,
    )
    if (working.isEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        ScreenDescription(stringResource(BaseR.string.scan_results_none))
    }
    Spacer(modifier = Modifier.height(24.dp))
    NextButton(text = stringResource(BaseR.string.scan_scan_again), enabled = true, onClick = actions.onScanAgain)
    Spacer(modifier = Modifier.height(16.dp))
}

/** Working routes first, ranked, each tappable; the failed ones collapsed below with their reason. */
@Composable
private fun RouteList(
    title: String,
    results: List<ScanResult>,
    showAge: Boolean,
    onClick: (ScanResult) -> Unit,
) {
    val (working, failed) = results.partition { it.isWorking }
    var failedExpanded by rememberSaveable { mutableStateOf(false) }
    SectionTitle(title)
    if (working.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Caption(stringResource(BaseR.string.scan_results_tap_to_connect))
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            working.forEach { RouteRow(it, showAge, onClick) }
        }
    }
    if (failed.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().hapticClickable { failedExpanded = !failedExpanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(BaseR.string.scan_failed_routes, failed.size),
                style = font14.copy(textAlign = TextAlign.Start),
                color = MaterialTheme.colorScheme.preferencesSubtitleColor,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.arrow_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.preferencesSubtitleColor,
                modifier = Modifier.size(14.dp),
            )
        }
        if (failedExpanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                failed.forEach { FailedRow(it) }
            }
        }
    }
}

@Composable
private fun RouteRow(
    result: ScanResult,
    showAge: Boolean,
    onClick: (ScanResult) -> Unit,
) {
    val candidate = result.candidate
    val verify = result.verify
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, AppColors.neonGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .hapticClickable { onClick(result) }
                .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = candidate.cityName,
                style = font16.copy(fontWeight = FontWeight.Medium, textAlign = TextAlign.Start),
                color = MaterialTheme.colorScheme.primaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = shortNode(candidate),
                style = font12,
                color = MaterialTheme.colorScheme.preferencesSubtitleColor,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = routeLine(candidate),
            style = font14.copy(fontWeight = FontWeight.Normal, textAlign = TextAlign.Start),
            color = MaterialTheme.colorScheme.primaryTextColor,
        )
        val measured = measuredLine(verify)
        if (measured.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Caption(measured)
        }
        if (showAge) {
            Caption(stringResource(BaseR.string.scan_scanned_ago, ageOf(result.timestampMs)))
        }
    }
}

@Composable
private fun FailedRow(result: ScanResult) {
    val candidate = result.candidate
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                .padding(12.dp),
    ) {
        Text(
            text = "${candidate.cityName} ${shortNode(candidate)}",
            style = font14.copy(fontWeight = FontWeight.Normal, textAlign = TextAlign.Start),
            color = MaterialTheme.colorScheme.primaryTextColor.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Caption(routeLine(candidate))
        Caption(result.verify?.error ?: result.probe.detail)
    }
}

// ---- Shared pieces

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = font16.copy(fontWeight = FontWeight.Medium, textAlign = TextAlign.Start),
        color = MaterialTheme.colorScheme.primaryTextColor,
    )
}

/** "us-central-099" from "us-central-099.example.net". */
private fun shortNode(candidate: ScanCandidate): String = candidate.nodeHostname.substringBefore('.')

@Composable
private fun routeLine(candidate: ScanCandidate): String {
    val base = "${Util.getProtocolLabel(candidate.protocol)}:${candidate.port}"
    val tweak = tweakLabel(candidate.tweak) ?: return base
    return "$base, $tweak"
}

@Composable
private fun tweakLabel(tweak: ScanTweak): String? =
    when (tweak) {
        ScanTweak.None -> null
        ScanTweak.ProtocolTweaks -> stringResource(BaseR.string.scan_tweak_protocol_tweaks).substringBefore(" (")
        is ScanTweak.AmneziaWg -> stringResource(BaseR.string.scan_tweak_amnezia).substringBefore(" (") + " " + tweak.presetId
        ScanTweak.ExtraTlsPadding -> stringResource(BaseR.string.scan_tweak_tls_padding).substringBefore(" (")
        is ScanTweak.TlsServerName -> tweak.sni
        ScanTweak.AlternateRouting -> stringResource(BaseR.string.scan_tweak_alt_routing).substringBefore(" (")
    }

@Composable
private fun measuredLine(verify: VerifyResult?): String {
    if (verify == null) return ""
    val parts = ArrayList<String>()
    verify.latencyMs?.let { parts.add(stringResource(BaseR.string.scan_latency_ms, it.toInt())) }
    verify.downloadKbps?.let {
        parts.add(stringResource(BaseR.string.scan_speed_mbps, String.format(Locale.getDefault(), "%.1f", it / 1000.0)))
    }
    return parts.joinToString("  |  ")
}

@Composable
private fun describe(candidate: ScanCandidate): String = "${candidate.cityName} ${shortNode(candidate)}  ${routeLine(candidate)}"

private fun ageOf(timestampMs: Long): String =
    DateUtils
        .getRelativeTimeSpanString(timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString()

// ---- Preview

private class ScannerStateProvider : PreviewParameterProvider<ScannerUiState> {
    private val candidate =
        ScanCandidate(1, "Toronto", "ca-001.example.net", "10.0.0.1", PreferencesKeyConstants.PROTO_STEALTH, 443, ScanTweak.ExtraTlsPadding)
    private val working =
        ScanResult(
            candidate,
            com.windscribe.vpn.scanner
                .ProbeResult(true, 80),
            VerifyResult(connected = true, trafficOk = true, latencyMs = 120, downloadKbps = 8400),
            1500.0,
            System.currentTimeMillis() - 3_600_000,
            "Home",
        )
    private val failed =
        ScanResult(
            candidate.copy(protocol = PreferencesKeyConstants.PROTO_WIRE_GUARD, port = 51820, tweak = ScanTweak.None),
            com.windscribe.vpn.scanner
                .ProbeResult(null),
            VerifyResult(connected = false, error = "handshake timed out"),
            0.0,
            System.currentTimeMillis() - 3_600_000,
            "Home",
        )

    override val values =
        sequenceOf(
            ScannerUiState(saved = listOf(working, failed)),
            ScannerUiState(ScannerPhase.Running, progress = ScanState.Verifying(4, 20, candidate), found = listOf(working, failed)),
            ScannerUiState(ScannerPhase.Results, found = listOf(working, failed)),
        )
}

@Composable
@MultiDevicePreview
private fun ScannerContentPreview(
    @PreviewParameter(ScannerStateProvider::class) state: ScannerUiState,
) {
    PreviewWithNav {
        ScannerContent(
            state = state,
            custom =
                CustomScanState(
                    loading = false,
                    cities = listOf(ScannerCity(1, "Toronto", 3, true), ScannerCity(2, "Istanbul", 2, false)),
                    selectedCityIds = setOf(1),
                    estimate = ScanEstimate(candidates = 3200, probes = 2900, limits = ScanLimits.CUSTOM),
                ),
            actions = ScannerActions(),
        )
    }
}
