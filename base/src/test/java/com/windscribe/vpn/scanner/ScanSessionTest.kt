package com.windscribe.vpn.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ScanSessionTest {
    @Test fun `normal controls cannot take over an active scan`() {
        val session = ScanSession()
        val normal = UUID.randomUUID()
        val scanner = UUID.randomUUID()
        assertTrue(session.allows(normal))
        assertTrue(session.acquire())
        assertFalse(session.acquire())
        assertFalse(session.allows(normal))
        session.authorize(scanner)
        assertTrue(session.allows(scanner))
        session.release()
        assertTrue(session.allows(normal))
        assertFalse(session.allows(scanner))
    }

    @Test fun `late launches from previous attempts are rejected`() {
        val session = ScanSession()
        session.acquire()
        val previous = UUID.randomUUID()
        session.authorize(previous)
        session.authorize(UUID.randomUUID())
        assertFalse(session.allows(previous))
        session.release()
        session.acquire()
        assertFalse(session.allows(previous))
    }
}
