package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Fast path for MobileTina subscription refreshes.
 *
 * The upstream parser replaces all profiles in a subscription after every successful fetch.
 * That is correct when the payload changed, but causes unnecessary MMKV writes, new profile
 * GUIDs and UI work when the server returned exactly the same subscription again.
 *
 * This optimizer performs one lightweight fetch first and remembers two fingerprints after a
 * successful full update:
 * 1) the remote payload + parser-affecting local filter;
 * 2) the exact locally stored profile list.
 *
 * Only when BOTH still match do we skip the destructive parse/write path. Expiry payloads,
 * subscription-userinfo and lastUpdated are still refreshed on every successful fast-path fetch.
 */
object MobileTinaSubscriptionUpdateOptimizer {
    private const val REMOTE_HASH_PREFIX = "MOBILETINA_SUB_REMOTE_HASH_V1_"
    private const val LOCAL_HASH_PREFIX = "MOBILETINA_SUB_LOCAL_HASH_V1_"

    fun updateAll(): SubscriptionUpdateResult {
        // Preserve the existing immediate retirement behavior before considering a fast path.
        // A retirement mutates the visible server/subscription list, so surface one changed
        // config to MainActivity to force the normal tab/list rebuild.
        val retiredBeforeFetch = MobileTinaSubscriptionMarkerManager.processExistingMarkers()
        val initialResult = if (retiredBeforeFetch) {
            SubscriptionUpdateResult(configCount = 1, successCount = 1)
        } else {
            SubscriptionUpdateResult()
        }

        return MmkvManager.decodeSubscriptions().toList()
            .fold(initialResult) { total, cache ->
                total + updateOne(cache)
            }
    }

    private fun updateOne(cache: SubscriptionCache): SubscriptionUpdateResult {
        val item = cache.subscription

        // Keep the existing manager authoritative for local marker groups, disabled
        // subscriptions and malformed URLs.
        if (!item.enabled || item.url.isBlank()) {
            return MobileTinaSubscriptionMarkerManager.update(cache)
        }

        val normalizedUrl = runCatching { HttpUtil.toIdnUrl(item.url) }.getOrNull()
            ?: return MobileTinaSubscriptionMarkerManager.update(cache)
        if (!Utils.isValidUrl(normalizedUrl) ||
            (!item.allowInsecureUrl && !Utils.isValidSubUrl(normalizedUrl))
        ) {
            return MobileTinaSubscriptionMarkerManager.update(cache)
        }

        val snapshot = MobileTinaSubscriptionInfo.captureSnapshot(cache.guid, item)
            ?: return MobileTinaSubscriptionMarkerManager.update(cache)
        if (snapshot.body.isBlank()) {
            // Empty content may be a temporary proxy/network problem. Fall back to the
            // established updater so its direct-network fallback and error semantics remain.
            return MobileTinaSubscriptionMarkerManager.update(cache)
        }

        val remoteHash = remoteFingerprint(snapshot.body, item.filter)
        val localHash = localFingerprint(cache.guid)
        val savedRemoteHash = MmkvManager.decodeSettingsString(REMOTE_HASH_PREFIX + cache.guid)
        val savedLocalHash = MmkvManager.decodeSettingsString(LOCAL_HASH_PREFIX + cache.guid)

        if (localHash.isNotBlank() &&
            remoteHash == savedRemoteHash &&
            localHash == savedLocalHash
        ) {
            // Even with unchanged configs these side effects must remain current.
            MobileTinaExpiryManager.syncFromSubscriptionPayload(
                AngApplication.application,
                snapshot.body,
                cache.guid
            )
            item.lastUpdated = System.currentTimeMillis()
            MmkvManager.encodeSubscription(cache.guid, item)

            LogUtil.i(
                AppConfig.TAG,
                "Subscription unchanged; skipped profile rewrite for ${cache.guid}"
            )
            // configCount deliberately stays zero: no profile was added, removed or rewritten.
            // MainActivity uses this to skip its expensive tab/list rebuild after auto refresh.
            return SubscriptionUpdateResult(successCount = 1)
        }

        // First run, changed remote content, changed filter, or locally modified profiles:
        // use the existing updater unchanged. It remains the source of truth for parsing,
        // selection preservation and retirement-marker handling.
        val result = MobileTinaSubscriptionMarkerManager.update(cache)
        if (result.successCount > 0) {
            val updatedSubscription = MmkvManager.decodeSubscription(cache.guid)
            if (updatedSubscription != null && updatedSubscription.url.isNotBlank()) {
                val updatedLocalHash = localFingerprint(cache.guid)
                if (updatedLocalHash.isNotBlank()) {
                    MmkvManager.encodeSettings(REMOTE_HASH_PREFIX + cache.guid, remoteHash)
                    MmkvManager.encodeSettings(LOCAL_HASH_PREFIX + cache.guid, updatedLocalHash)
                }
            }
        }
        return result
    }

    private fun remoteFingerprint(body: String, filter: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateDigest(digest, "mobiletina-subscription-v1")
        // Changing the subscription filter must force a reparse even if the remote body is
        // byte-for-byte identical.
        updateDigest(digest, filter.orEmpty())
        updateDigest(digest, body)
        return digest.toHex()
    }

    private fun localFingerprint(subscriptionId: String): String {
        val keys = MmkvManager.decodeServerList(subscriptionId)
        if (keys.isEmpty()) return ""

        val digest = MessageDigest.getInstance("SHA-256")
        updateDigest(digest, "mobiletina-local-profiles-v1")
        keys.forEach { key ->
            updateDigest(digest, key)
            val profile = MmkvManager.decodeServerConfig(key)
            updateDigest(digest, profile?.let { JsonUtil.toJson(it) }.orEmpty())
            // Custom configs can keep important source material outside ProfileItem.
            updateDigest(digest, MmkvManager.decodeServerRaw(key).orEmpty())
        }
        return digest.toHex()
    }

    private fun updateDigest(digest: MessageDigest, value: String) {
        digest.update(value.toByteArray(StandardCharsets.UTF_8))
        // Explicit separator prevents ambiguous concatenations such as ["ab", "c"] vs
        // ["a", "bc"].
        digest.update(0.toByte())
    }

    private fun MessageDigest.toHex(): String = digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
