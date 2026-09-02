package com.v2ray.ang.util

object MobileTinaImportNormalizer {
    private val base64Payload = Regex("^[A-Za-z0-9+/=_\\-\\r\\n]+$")

    fun normalize(input: String?): String? {
        if (input == null) return null
        val trimmed = input.trim()
        if (!trimmed.startsWith('#')) return input
        val candidate = trimmed.drop(1).trimStart()
        if (candidate.length < 8 || !base64Payload.matches(candidate)) return input
        return candidate
    }
}
