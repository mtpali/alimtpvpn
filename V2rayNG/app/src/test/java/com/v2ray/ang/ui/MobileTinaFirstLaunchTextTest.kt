package com.v2ray.ang.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MobileTinaFirstLaunchTextTest {
    @Test
    fun socialNoticeVaultDecryptsExactRequestedText() {
        assertArrayEquals(
            arrayOf(
                "instagram 1 : mobile.tina",
                "instagram 2 : mobile.tina2",
                "instagram 3 : mobile.tinaa"
            ),
            arrayOf(q.a(9), q.a(10), q.a(11))
        )
    }
}
