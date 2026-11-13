package com.windscribe.vpn.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TweakBaselineTest {
    private val user = TweakPrefs("auto", null, false, "", "auto")

    private class Fake(
        var current: TweakPrefs,
    ) : TweakPreferences,
        BaselineStore {
        override var baseline: TweakPrefs? = null
        override var applied: TweakPrefs? = null
        override var appliedKey: String? = null

        override fun read() = current

        override fun write(prefs: TweakPrefs) {
            current = prefs
        }

        override fun apply(tweak: ScanTweak) {
            current =
                when (tweak) {
                    ScanTweak.None -> current
                    is ScanTweak.TlsServerName -> current.copy(tlsServerName = tweak.sni)
                    else -> current.copy(protocolTweaksMode = "manual", extraTlsPadding = true)
                }
        }
    }

    @Test
    fun `logic restores only when the tweaks are still what was applied`() {
        val applied = user.copy(protocolTweaksMode = "manual")
        assertEquals(BaselineAction.Nothing, TweakBaselineLogic.reconcile(null, null, user))
        assertEquals(BaselineAction.Restore, TweakBaselineLogic.reconcile(user, applied, applied))
        assertEquals(BaselineAction.ClearOnly, TweakBaselineLogic.reconcile(user, applied, applied.copy(tlsServerName = "x")))
    }

    @Test
    fun `applying saves the baseline first, and a later plain connect restores it`() {
        val f = Fake(user)
        val b = TweakBaseline(f, f)
        b.applyRouteTweak(ScanTweak.ExtraTlsPadding)
        assertEquals(user, f.baseline)
        assertEquals("manual", f.current.protocolTweaksMode)
        b.reconcile()
        assertEquals(user, f.current)
        assertNull(f.baseline)
        assertNull(f.applied)
    }

    @Test
    fun `a user edit after applying is kept and the baseline is dropped`() {
        val f = Fake(user)
        val b = TweakBaseline(f, f)
        b.applyRouteTweak(ScanTweak.ExtraTlsPadding)
        val edited = f.current.copy(serverRoutingMode = "alternate")
        f.current = edited
        b.reconcile()
        assertEquals(edited, f.current)
        assertNull(f.baseline)
    }

    @Test
    fun `the same tweak again keeps the original baseline`() {
        val f = Fake(user)
        val b = TweakBaseline(f, f)
        b.applyRouteTweak(ScanTweak.ExtraTlsPadding)
        b.applyRouteTweak(ScanTweak.ExtraTlsPadding)
        assertEquals(user, f.baseline)
    }

    @Test
    fun `switching to another tweak restores first so the new baseline is the user's`() {
        val f = Fake(user)
        val b = TweakBaseline(f, f)
        b.applyRouteTweak(ScanTweak.ExtraTlsPadding)
        b.applyRouteTweak(ScanTweak.TlsServerName("front.example"))
        assertEquals(user, f.baseline)
        assertEquals("front.example", f.current.tlsServerName)
        assertEquals("auto", f.current.protocolTweaksMode)
    }

    @Test
    fun `a route with no tweak restores a previous one and saves nothing`() {
        val f = Fake(user)
        val b = TweakBaseline(f, f)
        b.applyRouteTweak(ScanTweak.ExtraTlsPadding)
        b.applyRouteTweak(ScanTweak.None)
        assertEquals(user, f.current)
        assertNull(f.baseline)
    }

    @Test
    fun `clear forgets everything without touching the preferences`() {
        val f = Fake(user)
        val b = TweakBaseline(f, f)
        b.applyRouteTweak(ScanTweak.ExtraTlsPadding)
        val applied = f.current
        b.clear()
        b.reconcile()
        assertEquals(applied, f.current)
        assertNull(f.appliedKey)
    }
}
