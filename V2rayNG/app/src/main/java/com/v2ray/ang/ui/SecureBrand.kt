package com.v2ray.ang.ui

import com.v2ray.ang.BuildConfig
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Runtime-only vault for the subscription brand label. */
internal object w {
    private val a = intArrayOf(78, 121, 137, 167, 178, 182, 25, 62, 32, 49, 20, 252, 86, 213, 148, 27, 228, 12, 28, 133, 220, 103, 242, 130, 43, 127, 50, 79, 115, 187, 72, 116)
    private val b = intArrayOf(221, 105, 97, 120, 140, 208, 128, 239, 196, 75, 60, 58, 193, 157, 219, 241, 94, 34, 212, 122, 90, 108, 41, 90, 250, 28, 213, 220, 107, 48, 16, 67)
    private val c = intArrayOf(0, 12, 88, 92, 85, 160, 187, 126, 19, 242, 99, 120, 112, 87, 49, 1, 56, 28, 216, 190, 63, 174, 228, 127, 71, 77, 52, 151, 28, 150, 209, 50)
    private val d = intArrayOf(202, 95, 107, 179, 0, 91, 74, 206, 244, 182, 81, 22, 31, 193, 175, 219, 198, 186, 78, 244, 235, 81, 79, 69, 98, 252, 110, 127, 94, 189, 7, 222)

    private val e = intArrayOf(155, 37, 190, 132, 61, 151, 23, 159, 105, 143, 167, 22, 66, 133, 12, 223, 220, 9, 129, 31, 182, 149, 69, 252, 76, 99, 184, 35, 229, 226, 217, 31, 41, 170, 34, 83, 173, 134, 233, 159, 59, 241, 239, 3, 117, 244, 143, 228, 111, 152, 171)
    private val f = intArrayOf(97, 3, 88, 22, 80, 161, 204, 90, 146, 83, 130, 32, 9, 145, 184, 161, 122, 253, 190, 129, 76, 224, 208, 96, 207, 14, 7, 219, 80, 157, 72, 245, 146, 143, 177, 173, 173, 216, 148, 153, 73, 121, 207, 49, 21, 109, 61, 96, 35, 34, 197)
    private val g = intArrayOf(116, 180, 81, 89, 24, 38, 130, 167, 91, 224, 241, 64, 131, 97, 196, 117, 206, 47, 250, 178, 213, 217, 3, 219, 146, 32, 47, 14, 12, 4, 69, 20, 138, 39, 196, 176, 113, 107, 67, 94, 78, 196, 142, 52, 193, 74, 185, 69, 251, 210, 163)
    private val h = intArrayOf(109, 116, 50, 50, 54, 193, 87, 14)

    fun a(): String {
        val seed = ByteArray(32) { i ->
            (
                a[i] xor
                    b[(i * 7 + 3) and 31] xor
                    c[(i * 11 + 5) and 31] xor
                    d[(i * 13 + 9) and 31] xor
                    ((i * 37 + 0xa7) and 0xff)
                ).toByte()
        }
        val appId = BuildConfig.APPLICATION_ID.toByteArray(Charsets.UTF_8)
        val prk = try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(seed, "HmacSHA256"))
                doFinal(appId)
            }
        } finally {
            seed.fill(0)
            appId.fill(0)
        }

        val version = BuildConfig.VERSION_NAME.toByteArray(Charsets.UTF_8)
        val key = try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(prk, "HmacSHA256"))
                update(version)
                update(byteArrayOf(0x53, 0x2d, 0x71, 0xb4.toByte(), 0x19, 0x8c.toByte(), 0xe3.toByte()))
                doFinal()
            }
        } finally {
            prk.fill(0)
            version.fill(0)
        }

        check(e.size == f.size && f.size == g.size && e.size > 28)
        val packed = ByteArray(e.size) { i ->
            (
                e[i] xor
                    f[i] xor
                    g[i] xor
                    ((0x5d + i * 29 + ((i * i * 7) and 0xff)) and 0xff)
                ).toByte()
        }
        val aad = ByteArray(h.size) { h[it].toByte() }

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, packed.copyOfRange(0, 12))
            )
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(packed.copyOfRange(12, packed.size))
            try {
                String(plain, Charsets.UTF_8)
            } finally {
                plain.fill(0)
            }
        } finally {
            key.fill(0)
            packed.fill(0)
            aad.fill(0)
        }
    }
}
