package com.windscribe.vpn.scanner

import android.content.Context
import com.windscribe.vpn.Windscribe
import com.windscribe.vpn.apppreference.PreferencesHelper
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/** The tweak-related preferences, as one comparable value. */
data class TweakPrefs(
    val protocolTweaksMode: String,
    val unblockWgParam: String?,
    val extraTlsPadding: Boolean,
    val tlsServerName: String,
    val serverRoutingMode: String,
)

/** Reads, writes and applies the real preferences. A seam so the logic below can be tested. */
interface TweakPreferences {
    fun read(): TweakPrefs

    fun write(prefs: TweakPrefs)

    fun apply(tweak: ScanTweak)
}

/** Where the baseline and what was applied survive a restart. */
interface BaselineStore {
    var baseline: TweakPrefs?
    var applied: TweakPrefs?
    var appliedKey: String?
}

enum class BaselineAction { Nothing, Restore, ClearOnly }

/** Pure: what to do with a baseline when a connect does not use a saved route. */
object TweakBaselineLogic {
    fun reconcile(
        baseline: TweakPrefs?,
        applied: TweakPrefs?,
        current: TweakPrefs,
    ): BaselineAction =
        when {
            baseline == null -> BaselineAction.Nothing
            // Still what the scanner set: the user has not touched them, so give them back.
            applied == current -> BaselineAction.Restore
            else -> BaselineAction.ClearOnly
        }
}

/**
 * Applies a saved route's tweak without drifting the user's settings. Before the first auto-applied
 * tweak a baseline of the user's values is saved; when a later connect uses no saved route the
 * baseline is put back, unless the user edited the tweaks in the meantime (then it is only dropped).
 */
class TweakBaseline(
    private val preferences: TweakPreferences,
    private val store: BaselineStore,
) {
    private val logger = LoggerFactory.getLogger(SCANNER_LOG)

    @Synchronized
    fun applyRouteTweak(tweak: ScanTweak) {
        val current = preferences.read()
        // Same tweak still in force and untouched: nothing to do.
        if (store.baseline != null && store.applied == current && store.appliedKey == tweak.key) return
        reconcileLocked()
        if (tweak == ScanTweak.None) return
        store.baseline = preferences.read()
        preferences.apply(tweak)
        store.applied = preferences.read()
        store.appliedKey = tweak.key
        logger.info("Applied saved-route tweak {}; baseline saved.", tweak.key)
    }

    /** For a connect that uses no saved route. */
    @Synchronized
    fun reconcile() = reconcileLocked()

    private fun reconcileLocked() {
        val action = TweakBaselineLogic.reconcile(store.baseline, store.applied, preferences.read())
        if (action == BaselineAction.Restore) {
            store.baseline?.let { preferences.write(it) }
            logger.info("Restored the tweak preferences saved before the last saved route.")
        } else if (action == BaselineAction.ClearOnly) {
            logger.info("Tweak preferences were edited since the saved route; keeping them, dropping the baseline.")
        }
        if (action != BaselineAction.Nothing) clear()
    }

    /** The user made the tweaks theirs (tapped a route in the scanner), or nothing is pending. */
    @Synchronized
    fun clear() {
        store.baseline = null
        store.applied = null
        store.appliedKey = null
    }
}

@Singleton
class PrefsTweakPreferences
    @Inject
    constructor(
        private val prefs: PreferencesHelper,
    ) : TweakPreferences {
        override fun read() =
            TweakPrefs(
                prefs.protocolTweaksMode,
                prefs.selectedUnblockWgParam,
                prefs.extraTlsPaddingEnabled,
                prefs.tlsServerName,
                prefs.serverRoutingMode,
            )

        // Each write only when it differs, so unset values stay unset and no flow fires.
        override fun write(prefs: TweakPrefs) {
            if (this.prefs.protocolTweaksMode != prefs.protocolTweaksMode) this.prefs.protocolTweaksMode = prefs.protocolTweaksMode
            if (this.prefs.selectedUnblockWgParam != prefs.unblockWgParam) this.prefs.selectedUnblockWgParam = prefs.unblockWgParam
            if (this.prefs.extraTlsPaddingEnabled != prefs.extraTlsPadding) this.prefs.extraTlsPaddingEnabled = prefs.extraTlsPadding
            if (this.prefs.tlsServerName != prefs.tlsServerName) this.prefs.tlsServerName = prefs.tlsServerName
            if (this.prefs.serverRoutingMode != prefs.serverRoutingMode) this.prefs.serverRoutingMode = prefs.serverRoutingMode
        }

        override fun apply(tweak: ScanTweak) = ScanTweakApplier.apply(prefs, tweak)
    }

/** The scanner's own SharedPreferences file, not `PreferencesHelper`. */
@Singleton
class ScannerSettings
    @Inject
    constructor(
        app: Windscribe,
    ) : BaselineStore {
        private val file = app.getSharedPreferences("scanner_settings", Context.MODE_PRIVATE)
        private val _trySavedFirst = kotlinx.coroutines.flow.MutableStateFlow(file.getBoolean(K_TRY_FIRST, true))

        /** "Try saved routes first", default on. */
        val trySavedFirst: kotlinx.coroutines.flow.StateFlow<Boolean> = _trySavedFirst

        fun setTrySavedFirst(value: Boolean) {
            file.edit().putBoolean(K_TRY_FIRST, value).apply()
            _trySavedFirst.value = value
        }

        override var baseline: TweakPrefs?
            get() = read("b_")
            set(value) = write("b_", value)
        override var applied: TweakPrefs?
            get() = read("a_")
            set(value) = write("a_", value)
        override var appliedKey: String?
            get() = file.getString(K_APPLIED_KEY, null)
            set(value) {
                file.edit().putString(K_APPLIED_KEY, value).commit()
            }

        private fun read(p: String): TweakPrefs? {
            if (!file.getBoolean(p + "set", false)) return null
            return TweakPrefs(
                file.getString(p + "mode", null) ?: return null,
                file.getString(p + "wg", null),
                file.getBoolean(p + "pad", false),
                file.getString(p + "sni", "") ?: "",
                file.getString(p + "route", null) ?: return null,
            )
        }

        // commit(): a baseline must be on disk before the tweak it protects is applied.
        private fun write(
            p: String,
            v: TweakPrefs?,
        ) {
            val e = file.edit()
            if (v == null) {
                e
                    .remove(p + "set")
                    .remove(p + "mode")
                    .remove(p + "wg")
                    .remove(p + "pad")
                    .remove(p + "sni")
                    .remove(p + "route")
            } else {
                e
                    .putBoolean(p + "set", true)
                    .putString(p + "mode", v.protocolTweaksMode)
                    .putString(p + "wg", v.unblockWgParam)
                    .putBoolean(p + "pad", v.extraTlsPadding)
                    .putString(p + "sni", v.tlsServerName)
                    .putString(p + "route", v.serverRoutingMode)
            }
            e.commit()
        }

        private companion object {
            const val K_TRY_FIRST = "try_saved_first"
            const val K_APPLIED_KEY = "applied_key"
        }
    }
