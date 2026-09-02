package com.v2ray.ang.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.q
import com.v2ray.ang.ui.r
import java.security.MessageDigest

/**
 * Release-only integrity checks for the hardened MobileTina distribution.
 *
 * The checks intentionally use compiled resource IDs rather than resource file names so they
 * continue to work when AAPT/R8 shorten packaged resource paths. The expected artwork digests
 * are the immutable source hashes already enforced by CI. The text digest covers MobileTina's
 * branded/user-facing strings as one ordered payload. The signing-certificate digest is injected
 * by the hardened build workflow before compilation, so a modified/re-signed APK is rejected.
 */
object MobileTinaIntegrityGuard {
    @Volatile
    private var verified = false

    fun verify(context: Context) {
        if (!BuildConfig.MOBILETINA_HARDENED_BUILD || verified) return

        synchronized(this) {
            if (verified) return

            verifyPackageName(context)
            verifyArtwork(context)
            verifyBrandText(context)
            verifySigningCertificate(context)

            verified = true
        }
    }

    private fun verifyPackageName(context: Context) {
        val expected = byteArrayOf(
            68, -122, 19, 123, 79, -105, -105, 60,
            -100, 104, -11, 99, -51, -9, 37, -45,
            -58, -86, 120, -37, -27, 75, 105, -58,
            -84, -65, -15, 23, 58, -47, -82, -7
        )
        requireDigest(sha256(context.packageName.toByteArray(Charsets.UTF_8)), expected)
    }

    private fun verifyArtwork(context: Context) {
        val resources = context.resources
        val protectedArtwork = arrayOf(
            R.drawable.white to byteArrayOf(
                -1, 76, 8, -73, -117, -54, -102, 22, 84, -7, 24, -7, -72, -104, 19, -66,
                -112, -91, -106, 109, 101, -128, 92, -60, 25, -75, -79, 72, 42, 117, -75, -43
            ),
            R.drawable.yellow to byteArrayOf(
                -124, 58, -2, -63, 41, -78, -69, -10, -19, 32, 88, 84, -89, -115, 65, 113,
                -112, 84, -69, 57, 5, -41, 6, -57, 59, -87, -23, 107, 100, 81, -74, -27
            ),
            R.drawable.blue to byteArrayOf(
                -31, 62, -23, 2, 4, 42, -75, -27, 96, -18, 113, 116, 77, -80, 22, -2,
                -94, 64, 79, -101, 93, 108, 75, 102, 76, -35, 5, -15, 24, 123, -4, 117
            ),
            R.drawable.red to byteArrayOf(
                -94, 75, -86, -100, -53, 119, -64, 100, 83, -7, -122, 70, 52, -105, 75, 118,
                -104, 121, -18, -76, 43, -71, -88, 121, 8, -105, 26, -105, 19, -26, -65, 40
            ),
            R.drawable.stop to byteArrayOf(
                -121, -20, 80, 76, -36, 60, 122, -61, 40, 126, 50, 20, 15, 58, 86, 73,
                -33, -115, 33, 98, -25, 49, 46, 117, 42, -121, 72, -52, 80, -125, 28, 46
            ),
            R.drawable.fab to byteArrayOf(
                -35, 66, -58, 2, 50, -60, 94, -121, -53, 0, -101, 31, 65, -107, -97, 120,
                89, 88, -32, -14, 126, 24, 11, -119, -66, 70, -1, -122, 53, -97, -28, -108
            ),
            R.drawable.auto to byteArrayOf(
                5, 86, -105, -113, -42, -21, 122, 32, -79, -30, 61, 53, 90, -41, 12, -18,
                -75, 1, 22, 0, -38, -73, -18, 19, -54, -48, 90, 82, 4, -94, -19, 126
            ),
            R.drawable.nav to byteArrayOf(
                -71, 62, -92, -108, -21, 81, 125, -97, -23, 64, -25, 120, 13, -46, -22, -75,
                -114, -43, -3, 57, -108, -10, -35, -121, 97, 20, -72, -92, -37, 64, 57, -56
            ),
            R.mipmap.ic_launcher_foreground to byteArrayOf(
                69, 48, 114, 90, 12, 20, 121, -114, -62, 101, 115, -115, 53, -24, -80, 64,
                -111, -121, 55, 123, 105, 94, 113, 110, -81, -14, 87, 93, 53, -51, -31, -62
            )
        )

        for ((resourceId, expected) in protectedArtwork) {
            val digest = MessageDigest.getInstance("SHA-256")
            resources.openRawResource(resourceId).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            requireDigest(digest.digest(), expected)
        }
    }

    private fun verifyBrandText(context: Context) {
        val a = intArrayOf(
            R.string.app_name,
            R.string.app_widget_name,
            R.string.app_tile_name,
            R.string.mobiletina_status_connected,
            R.string.mobiletina_status_disconnected,
            R.string.mobiletina_status_connecting,
            R.string.mobiletina_status_failed,
            R.string.mobiletina_testing,
            R.string.mobiletina_no_working_server,
            R.string.mobiletina_ping_unknown,
            R.string.mobiletina_ping_inactive,
            R.string.mobiletina_ping_format,
            R.string.mobiletina_enable_internet,
            R.string.mobiletina_mode_auto,
            R.string.mobiletina_mode_manual,
            R.string.mobiletina_tap_for_ping,
            R.string.mobiletina_manual_smart_connect,
            R.string.mobiletina_manual_smart_connect_with_icon,
            R.string.mobiletina_locate_selected,
            R.string.mobiletina_countdown_format,
            R.string.mobiletina_smart_countdown_format,
            R.string.mobiletina_smart_countdown_short,
            R.string.mobiletina_subscription_status,
            R.string.mobiletina_subscription_used,
            R.string.mobiletina_subscription_days_remaining,
            R.string.mobiletina_subscription_usage_compact,
            R.string.mobiletina_subscription_secret_title,
            R.string.mobiletina_copy_subscription_link,
            R.string.mobiletina_copy_all_configs,
            R.string.mobiletina_close,
            R.string.mobiletina_confirm,
            R.string.mobiletina_delete_vpn,
            R.string.mobiletina_reset_title,
            R.string.mobiletina_reset_message,
            R.string.mobiletina_reset_confirm,
            R.string.mobiletina_reset_done,
            R.string.mobiletina_store_about_title,
            R.string.mobiletina_store_page_title
        )
        val b = intArrayOf(
            R.string.mobiletina_store_address_section,
            R.string.mobiletina_store_address_branch_1,
            R.string.mobiletina_store_address_branch_2,
            R.string.mobiletina_real_delay,
            R.string.title_file_chooser,
            R.string.notification_action_stop_v2ray,
            R.string.per_app_proxy_settings,
            R.string.title_settings
        )

        val digest = MessageDigest.getInstance("SHA-256")
        fun u(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        a.forEach { resourceId ->
            u(
                when (resourceId) {
                    R.string.mobiletina_subscription_days_remaining -> r.a(7)
                    R.string.mobiletina_subscription_secret_title -> r.a(4)
                    R.string.mobiletina_copy_subscription_link -> r.a(5)
                    R.string.mobiletina_close -> r.a(6)
                    else -> context.getString(resourceId)
                }
            )
        }
        intArrayOf(8, 4, 5, 6, 7).forEach { u(q.a(it)) }
        b.forEach { u(context.getString(it)) }

        val expected = byteArrayOf(
            -18, 9, 107, 2, 34, 103, 119, -63,
            -91, 13, -76, 29, 71, -48, 101, -90,
            126, -47, -52, -65, -114, -96, -97, 100,
            105, -19, -93, -116, -10, -56, 35, 70
        )
        requireDigest(digest.digest(), expected)
    }

    private fun verifySigningCertificate(context: Context) {
        val expectedHex = BuildConfig.MOBILETINA_EXPECTED_CERT_SHA256
        if (expectedHex.length != 64) fail()
        val expected = hexToBytes(expectedHex)

        @Suppress("DEPRECATION")
        val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signingInfo = info.signingInfo ?: fail()
            val rawSignatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            rawSignatures?.filterNotNull().orEmpty()
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            ).signatures?.filterNotNull().orEmpty()
        }

        if (signatures.isEmpty() || signatures.none {
                MessageDigest.isEqual(sha256(it.toByteArray()), expected)
            }
        ) {
            fail()
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun requireDigest(actual: ByteArray, expected: ByteArray) {
        if (!MessageDigest.isEqual(actual, expected)) fail()
    }

    private fun hexToBytes(value: String): ByteArray {
        if (value.length % 2 != 0) fail()
        return ByteArray(value.length / 2) { index ->
            val hi = Character.digit(value[index * 2], 16)
            val lo = Character.digit(value[index * 2 + 1], 16)
            if (hi < 0 || lo < 0) fail()
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun fail(): Nothing = throw SecurityException("Application integrity validation failed")
}
