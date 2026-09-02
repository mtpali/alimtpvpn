package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

class MobileTinaExpiryManagerTest {

    @Test
    fun timestampWithoutOffsetUsesTehranTime() {
        val expected = Instant.parse("2026-09-14T08:30:00Z").toEpochMilli()

        assertEquals(expected, MobileTinaExpiryManager.parseTimestamp("2026-09-14T12:00:00"))
    }

    @Test
    fun explicitOffsetOverridesDefaultTimeZone() {
        val expected = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli()

        assertEquals(expected, MobileTinaExpiryManager.parseTimestamp("2026-09-14T12:00:00Z"))
    }

    @Test
    fun commentIsReadOnlyFromCustomConfigJson() {
        val customJson = """
            {
              "_comment": "2026-09-14T12:00:00",
              "inbounds": [],
              "outbounds": [],
              "routing": {}
            }
        """.trimIndent()
        val unrelatedJson = """{"_comment":"2026-09-14T12:00:00"}"""

        assertEquals(
            Instant.parse("2026-09-14T08:30:00Z").toEpochMilli(),
            MobileTinaExpiryManager.extractTriggerAtMillis(customJson)
        )
        assertNull(MobileTinaExpiryManager.extractTriggerAtMillis(unrelatedJson))
    }

    @Test
    fun customConfigWithoutCommentHasNoDisplayExpiry() {
        val customJson = """
            {
              "inbounds": [],
              "outbounds": [],
              "routing": {}
            }
        """.trimIndent()

        assertNull(MobileTinaExpiryManager.extractTriggerAtMillis(customJson))
    }

    @Test
    fun obfuscatedMarkerDecodesToExpectedPayload() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(MobileTinaExpiryManager.decodeExpiryMarker().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals("9e7999c0f019e0257eba8c974106679edada62fe22ef097a46e35520ef88c587", digest)
    }
}
