package com.v2ray.ang.handler

import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Reads subscription-userinfo and exposes the same successful response body to the
 * MobileTina subscription optimizer so unchanged subscriptions do not need a second GET.
 */
object MobileTinaSubscriptionInfo {
    data class Snapshot(
        val body: String,
        val userInfo: String?
    )

    private const val RECENT_SNAPSHOT_WINDOW_MS = 5_000L
    private val recentSnapshotAt = ConcurrentHashMap<String, Long>()

    fun refreshAll() {
        val now = SystemClock.elapsedRealtime()
        MmkvManager.decodeSubscriptions()
            .filter { it.subscription.enabled && it.subscription.url.isNotBlank() }
            .forEach { cache ->
                val capturedAt = recentSnapshotAt[cache.guid] ?: 0L
                if (capturedAt > 0L && now - capturedAt <= RECENT_SNAPSHOT_WINDOW_MS) {
                    return@forEach
                }
                runCatching { refreshOne(cache.guid, cache.subscription) }
            }
    }

    fun refresh(subscriptionId: String) {
        val item = MmkvManager.decodeSubscription(subscriptionId) ?: return
        if (!item.enabled || item.url.isBlank()) return
        runCatching { refreshOne(subscriptionId, item) }
    }

    /**
     * Fetches the subscription once, preserving the existing local-proxy -> direct fallback.
     * The response body is used for change detection and subscription-userinfo is applied
     * immediately so MainActivity.refreshAll() can skip its otherwise duplicate request.
     */
    fun captureSnapshot(guid: String, item: SubscriptionItem): Snapshot? {
        val proxyPort = SettingsManager.getHttpPort()
        val snapshot = if (proxyPort > 0) {
            fetchSnapshot(item, proxyPort) ?: fetchSnapshot(item, 0)
        } else {
            fetchSnapshot(item, 0)
        } ?: return null

        applyUserInfo(guid, item, snapshot.userInfo)
        MobileTinaExpiryManager.updateDisplayExpiryFromPayload(guid, snapshot.body)
        recentSnapshotAt[guid] = SystemClock.elapsedRealtime()
        return snapshot
    }

    private fun refreshOne(guid: String, item: SubscriptionItem) {
        captureSnapshot(guid, item)
    }

    /**
     * Applies header metadata only when it actually changed, avoiding an unnecessary MMKV
     * subscription write on every resume when traffic/expiry values are identical.
     */
    private fun applyUserInfo(guid: String, item: SubscriptionItem, raw: String?) {
        if (raw.isNullOrBlank()) return

        val fields = raw.split(';').mapNotNull { segment ->
            val i = segment.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val key = segment.substring(0, i).trim().lowercase()
            val value = segment.substring(i + 1).trim().toLongOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()

        val upload = fields["upload"]?.takeIf { it >= 0L }
        val download = fields["download"]?.takeIf { it >= 0L }
        val total = fields["total"]?.takeIf { it > 0L }
        val expire = fields["expire"]?.takeIf { it > 0L }

        val changed = item.trafficUploadBytes != upload ||
                item.trafficDownloadBytes != download ||
                item.trafficTotalBytes != total ||
                item.expireEpochSeconds != expire

        item.trafficUploadBytes = upload
        item.trafficDownloadBytes = download
        item.trafficTotalBytes = total
        item.expireEpochSeconds = expire

        if (changed) {
            MmkvManager.encodeSubscription(guid, item)
        }
    }

    private fun fetchSnapshot(item: SubscriptionItem, httpPort: Int): Snapshot? {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (httpPort > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, httpPort)))
        }
        val request = Request.Builder()
            .url(item.url)
            .get()
            .header("User-Agent", item.userAgent?.takeIf { it.isNotBlank() } ?: "v2rayNG/${BuildConfig.VERSION_NAME}")
            .header("Connection", "close")
            .build()

        return runCatching {
            builder.build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    Snapshot(
                        body = response.body?.string().orEmpty(),
                        userInfo = response.header("subscription-userinfo")
                    )
                }
            }
        }.getOrNull()
    }
}
