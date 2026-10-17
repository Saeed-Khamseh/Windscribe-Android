package com.windscribe.mobile.ui.scanner

import android.app.Application
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.windscribe.mobile.ui.theme.AndroidTheme
import com.windscribe.vpn.scanner.ScanLocation
import com.windscribe.vpn.scanner.ScanProgress
import com.windscribe.vpn.scanner.ScanResult
import com.windscribe.vpn.scanner.ScanRoute
import com.windscribe.vpn.scanner.ScanSettings
import com.windscribe.vpn.scanner.ScanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ScannerContentTest {
    @get:Rule val compose = createComposeRule()
    private val location = ScanLocation(1, "Netherlands", "NL", "Amsterdam", "Tulip", "nl.example")
    private val settings = ScanSettings(locationIds = listOf(1))
    private val route = ScanRoute(location, "wg", "WireGuard", "443")

    @Test fun `setup can start when a complete plan exists`() {
        var started = false
        compose.setContent {
            AndroidTheme(darkTheme = true) {
                ScannerContent(settings, listOf(location), emptyMap(), ScanProgress(), false, false, onStart = { started = true })
            }
        }
        compose.onNodeWithTag("scanner_primary_action").assertIsEnabled().performClick()
        assertTrue(started)
    }

    @Test fun `empty inventory cannot start a scan in light theme`() {
        compose.setContent {
            AndroidTheme(darkTheme = false) {
                ScannerContent(settings, emptyList(), emptyMap(), ScanProgress(), false, false)
            }
        }
        compose.onNodeWithTag("scanner_primary_action").assertIsNotEnabled()
    }

    @Test fun `live scan exposes Stop but not normal setup actions`() {
        var stopped = false
        compose.setContent {
            AndroidTheme(darkTheme = true) {
                ScannerContent(
                    settings,
                    listOf(location),
                    emptyMap(),
                    ScanProgress(true, route, ScanStep.Measuring, 4),
                    true,
                    false,
                    onStop = { stopped = true },
                )
            }
        }
        compose.onNodeWithText("Start scan").assertDoesNotExist()
        compose.onNodeWithText("Advanced settings").assertDoesNotExist()
        compose.onNodeWithText("Stop scan").performClick()
        assertTrue(stopped)
    }

    @Test fun `results Connect uses the exact tested route`() {
        var selected: ScanRoute? = null
        compose.setContent {
            AndroidTheme(darkTheme = true) {
                ScannerContent(
                    settings,
                    listOf(location),
                    emptyMap(),
                    ScanProgress(total = 1, results = listOf(ScanResult(route, listOf(80, 84)))),
                    false,
                    false,
                    onConnect = { selected = it },
                )
            }
        }
        compose.onNodeWithTag("scanner_primary_action").performScrollTo().performClick()
        assertEquals(route, selected)
    }
}
