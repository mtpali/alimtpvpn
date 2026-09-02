package com.v2ray.ang.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileTinaHiddenAccessTest {
    @Test
    fun manualRevealRejectsRawGitHubSubscriptions() {
        assertFalse(r.b("https://raw.githubusercontent.com/owner/repository/main/subscription.txt"))
        assertFalse(r.b("  HTTPS://RAW.GITHUBUSERCONTENT.COM/owner/repository/main/subscription.txt  "))
    }

    @Test
    fun manualRevealAllowsOtherNonEmptySubscriptions() {
        assertTrue(r.b("https://example.com/subscription"))
        assertTrue(r.b("https://github.com/owner/repository"))
        assertFalse(r.b("   "))
    }
}
