package com.v2ray.ang.handler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.receiver.MobileTinaExpiryReceiver
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Schedules expiry markers using internet time rather than the phone wall clock.
 *
 * The HTTPS Date header is sampled before an alarm is scheduled. The resulting delay is
 * then measured with Android's monotonic elapsed-realtime clock, so manually changing the
 * device date or time cannot move an already scheduled expiry. After a reboot (or when the
 * alarm fires), internet time is sampled again before the subscription is retired.
 */
object MobileTinaExpiryManager {
    const val ACTION_EXPIRE = "com.v2ray.mobiletina.action.CONFIG_EXPIRE"
    const val ACTION_DATA_CHANGED = "com.v2ray.mobiletina.action.DATA_CHANGED"
    const val EXTRA_SUBSCRIPTION_ID = "mobiletina_expiry_subscription_id"

    private const val PREFS_NAME = "mobiletina_config_expiry"
    private const val KEY_TRIGGER_PREFIX = "trigger_at_millis_"
    private const val LEGACY_KEY_TRIGGER = "trigger_at_millis"
    private const val KEY_PENDING_SUBSCRIPTIONS = "MOBILETINA_EXPIRY_PENDING_SUBSCRIPTIONS"
    private const val WORK_INPUT_SUBSCRIPTION_ID = "subscription_id"
    private const val VERIFY_WORK_PREFIX = "mobiletina_expiry_verify_"
    private const val FALLBACK_WORK_PREFIX = "mobiletina_expiry_fallback_"
    private const val MIN_VALID_NETWORK_TIME = 1_704_067_200_000L // 2024-01-01 UTC
    private const val MAX_VALID_NETWORK_TIME = 4_102_444_800_000L // 2100-01-01 UTC
    private const val VPN_STOP_TIMEOUT_MILLIS = 15_000L
    private const val VPN_STOP_POLL_MILLIS = 100L
    private val DEFAULT_TIME_ZONE: ZoneId = ZoneId.of("Asia/Tehran")

    private val NETWORK_TIME_SOURCES = arrayOf(
        "https://api.github.com/zen",
        "https://www.gstatic.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace"
    )

    // The retirement URI is deliberately not stored as a plain string in the APK. This is
    // obfuscation (not unbreakable encryption): any secret shipped inside a client APK can
    // ultimately be recovered by a determined reverse engineer.
    private val EXPIRY_MARKER_CIPHER = intArrayOf(
        41, 42, 7, 108, 85, 251, 207, 172, 237, 42, 44, 62, 20, 248, 203, 174,
        238, 109, 81, 86, 1, 244, 180, 171, 151, 31, 72, 58, 122, 225, 221, 218,
        251, 128, 0, 95, 35, 99, 241, 198, 198, 149, 105, 46, 57, 12, 140, 170,
        175, 244, 109, 82, 36, 1, 245, 183, 171, 152, 100, 74, 59, 125, 225, 222,
        162, 179, 129, 3, 95, 36, 24, 241, 199, 201, 148, 106, 44, 63, 13, 143,
        168, 176, 140, 20, 83, 35, 0, 246, 182, 165, 153, 29, 74, 60, 124, 227,
        223, 164, 194
    )

    /** Schedule an expiry found in a manually imported custom JSON configuration. */
    fun scheduleFromImportedText(context: Context, configText: String?, subscriptionId: String) {
        if (subscriptionId.isBlank()) return
        val trigger = extractTriggerAtMillis(configText) ?: return
        persistDisplayExpiry(subscriptionId, trigger)
        persistAndVerify(context.applicationContext, subscriptionId, trigger)
    }

    /**
     * Keep a remote subscription's timer in sync with its latest replacement payload.
     * A valid custom JSON without `_comment` is treated as a renewed, non-expiring payload.
     */
    fun syncFromSubscriptionPayload(context: Context, configText: String?, subscriptionId: String) {
        if (subscriptionId.isBlank()) return
        val root = parseCustomConfigRoot(configText) ?: return
        val trigger = extractTriggerAtMillis(root)
        persistDisplayExpiry(subscriptionId, trigger)
        if (trigger == null) {
            cancelForSubscription(context.applicationContext, subscriptionId)
        } else {
            persistAndVerify(context.applicationContext, subscriptionId, trigger)
        }
    }

    fun recoverPending(context: Context) {
        val app = context.applicationContext
        migrateLegacyEntry(app)
        pendingSubscriptionIds().forEach { subscriptionId ->
            enqueueVerification(app, subscriptionId, ExistingWorkPolicy.KEEP)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val subscriptionIds = pendingSubscriptionIds()
        subscriptionIds.forEach { subscriptionId ->
            MmkvManager.encodeSettings(triggerKey(subscriptionId), 0L)
        }
        MmkvManager.encodeSettings(KEY_PENDING_SUBSCRIPTIONS, mutableSetOf<String>())
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        subscriptionIds.forEach { cancelScheduled(app, it) }
    }

    fun cancelForSubscription(context: Context, subscriptionId: String) {
        if (subscriptionId.isBlank()) return
        val app = context.applicationContext
        val hadPendingTrigger = readTrigger(subscriptionId) > 0L
        removePendingTrigger(subscriptionId)
        if (hadPendingTrigger) cancelScheduled(app, subscriptionId)
    }

    fun requestOnlineVerification(context: Context, subscriptionId: String) {
        if (subscriptionId.isBlank()) return
        enqueueVerification(context.applicationContext, subscriptionId, ExistingWorkPolicy.REPLACE)
    }

    @Synchronized
    private fun persistAndVerify(context: Context, subscriptionId: String, trigger: Long) {
        val pending = pendingSubscriptionIds().toMutableSet()
        pending.add(subscriptionId)
        MmkvManager.encodeSettings(triggerKey(subscriptionId), trigger)
        MmkvManager.encodeSettings(KEY_PENDING_SUBSCRIPTIONS, pending)
        enqueueVerification(context, subscriptionId, ExistingWorkPolicy.REPLACE)
    }

    private suspend fun verifyWithNetworkTime(context: Context, subscriptionId: String): Boolean {
        val trigger = readTrigger(subscriptionId)
        if (trigger <= 0L) return true

        if (!subscriptionStillExists(subscriptionId)) {
            cancelForSubscription(context, subscriptionId)
            return true
        }

        val networkNow = fetchNetworkEpochMillis() ?: return false
        if (networkNow < trigger) {
            scheduleFromTrustedDelay(context, subscriptionId, trigger - networkNow)
            return true
        }

        if (!stopVpnBeforeExpiry(context)) {
            LogUtil.w(AppConfig.TAG, "Expiry is due but VPN did not stop; verification will retry")
            return false
        }

        if (!claimExpiry(subscriptionId, trigger)) return true
        // Do not cancel the currently running verification work before it imports the
        // marker; only its alarm/fallback siblings need to be removed here.
        cancelAlarmAndFallback(context, subscriptionId)

        // Import into the original group. Marker processing removes that group and all of
        // its normal profiles, then keeps this marker in the disabled display-only group.
        AngConfigManager.importBatchConfig(decodeExpiryMarker(), subscriptionId, true)
        context.sendBroadcast(Intent(ACTION_DATA_CHANGED).setPackage(context.packageName))
        return true
    }

    /**
     * Stops the daemon before its selected profile can be removed. The running flag is
     * cleared as shutdown starts, while CACHE_SERVICE_STOP_COMPLETED acknowledges the
     * matching request only after service teardown (and the TUN close in VPN mode).
     */
    private suspend fun stopVpnBeforeExpiry(context: Context): Boolean {
        val wasRunning = MmkvManager.decodeSettingsBool(AppConfig.CACHE_SERVICE_RUNNING, false)
        val stopRequest = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        MmkvManager.encodeSettings(AppConfig.CACHE_SERVICE_STOP_REQUEST, stopRequest)

        // Sending stop is harmless when no service exists and also covers a very small
        // race where the daemon started before its running flag became visible.
        CoreServiceManager.stopVService(context.applicationContext)
        if (!wasRunning) {
            delay(350L)
            if (!MmkvManager.decodeSettingsBool(AppConfig.CACHE_SERVICE_RUNNING, false)) return true
            // The service appeared during the race window; make sure its receiver sees
            // the stop command and then wait for the normal acknowledgement below.
            CoreServiceManager.stopVService(context.applicationContext)
        }

        val deadline = SystemClock.elapsedRealtime() + VPN_STOP_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val running = MmkvManager.decodeSettingsBool(AppConfig.CACHE_SERVICE_RUNNING, false)
            val completedNow = MmkvManager.decodeSettingsLong(AppConfig.CACHE_SERVICE_STOP_COMPLETED, 0L)
            if (!running && completedNow == stopRequest) return true
            delay(VPN_STOP_POLL_MILLIS)
        }

        // Keep the expiry record intact. WorkManager will retry and send the stop command
        // again instead of deleting configs while the VPN may still be using them.
        CoreServiceManager.stopVService(context.applicationContext)
        return false
    }

    @Synchronized
    private fun claimExpiry(subscriptionId: String, expectedTrigger: Long): Boolean {
        if (readTrigger(subscriptionId) != expectedTrigger) return false
        removePendingTrigger(subscriptionId)
        return true
    }

    private fun subscriptionStillExists(subscriptionId: String): Boolean {
        return if (subscriptionId == AppConfig.DEFAULT_SUBSCRIPTION_ID) {
            MmkvManager.decodeServerList(subscriptionId).isNotEmpty()
        } else {
            MmkvManager.decodeSubscription(subscriptionId) != null
        }
    }

    private fun scheduleFromTrustedDelay(context: Context, subscriptionId: String, delayMillis: Long) {
        val delay = delayMillis.coerceAtLeast(1L)
        cancelAlarmAndFallback(context, subscriptionId)

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = expiryPendingIntent(context, subscriptionId)
        val elapsedTrigger = SystemClock.elapsedRealtime() + delay
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
        }

        val fallback = verificationRequest(subscriptionId, delay)
        WorkManager.getInstance(context).enqueueUniqueWork(
            fallbackWorkName(subscriptionId),
            ExistingWorkPolicy.REPLACE,
            fallback
        )
    }

    private fun enqueueVerification(
        context: Context,
        subscriptionId: String,
        policy: ExistingWorkPolicy
    ) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            verifyWorkName(subscriptionId),
            policy,
            verificationRequest(subscriptionId, 0L)
        )
    }

    private fun verificationRequest(subscriptionId: String, delayMillis: Long): androidx.work.OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<ExpiryVerificationWorker>()
            .setInputData(workDataOf(WORK_INPUT_SUBSCRIPTION_ID to subscriptionId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
        // On Android 12+ this maps to an expedited JobScheduler job. Older Android
        // versions would require a foreground notification for expedited WorkManager
        // jobs, so they retain the ordinary zero-delay path.
        if (delayMillis == 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return builder.build()
    }

    private fun cancelScheduled(context: Context, subscriptionId: String) {
        cancelAlarmAndFallback(context, subscriptionId)
        WorkManager.getInstance(context).cancelUniqueWork(verifyWorkName(subscriptionId))
    }

    private fun cancelAlarmAndFallback(context: Context, subscriptionId: String) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(expiryPendingIntent(context, subscriptionId))
        WorkManager.getInstance(context).cancelUniqueWork(fallbackWorkName(subscriptionId))
    }

    private fun expiryPendingIntent(context: Context, subscriptionId: String): PendingIntent {
        val intent = Intent(context, MobileTinaExpiryReceiver::class.java)
            .setAction(ACTION_EXPIRE)
            .setData(Uri.parse("mobiletina://config-expiry/${Uri.encode(subscriptionId)}"))
            .putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
        return PendingIntent.getBroadcast(
            context,
            requestCode(subscriptionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pendingSubscriptionIds(): List<String> =
        MmkvManager.decodeSettingsStringSet(KEY_PENDING_SUBSCRIPTIONS)
            .orEmpty()
            .filter { subscriptionId ->
                subscriptionId.isNotBlank() && readTrigger(subscriptionId) > 0L
            }

    private fun migrateLegacyEntry(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacyTrigger = prefs.getLong(LEGACY_KEY_TRIGGER, 0L)
        if (legacyTrigger <= 0L) return

        val target = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID).orEmpty()
        val editor = prefs.edit().remove(LEGACY_KEY_TRIGGER)
        if (target.isNotBlank() && subscriptionStillExists(target)) {
            val pending = pendingSubscriptionIds().toMutableSet()
            pending.add(target)
            MmkvManager.encodeSettings(triggerKey(target), legacyTrigger)
            MmkvManager.encodeSettings(KEY_PENDING_SUBSCRIPTIONS, pending)
        }
        editor.commit()
    }

    private fun readTrigger(subscriptionId: String): Long =
        MmkvManager.decodeSettingsLong(triggerKey(subscriptionId), 0L)

    @Synchronized
    private fun removePendingTrigger(subscriptionId: String) {
        MmkvManager.encodeSettings(triggerKey(subscriptionId), 0L)
        val pending = MmkvManager.decodeSettingsStringSet(KEY_PENDING_SUBSCRIPTIONS).orEmpty().toMutableSet()
        if (pending.remove(subscriptionId)) {
            MmkvManager.encodeSettings(KEY_PENDING_SUBSCRIPTIONS, pending)
        }
    }

    private fun triggerKey(subscriptionId: String) = KEY_TRIGGER_PREFIX + subscriptionId

    private fun verifyWorkName(subscriptionId: String) = VERIFY_WORK_PREFIX + stableToken(subscriptionId)

    private fun fallbackWorkName(subscriptionId: String) = FALLBACK_WORK_PREFIX + stableToken(subscriptionId)

    private fun stableToken(subscriptionId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(subscriptionId.toByteArray(StandardCharsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private fun requestCode(subscriptionId: String): Int = subscriptionId.hashCode() and Int.MAX_VALUE

    internal fun extractTriggerAtMillis(configText: String?): Long? =
        parseCustomConfigRoot(configText)?.let(::extractTriggerAtMillis)

    private fun extractTriggerAtMillis(root: JsonObject): Long? {
        val comment = root.get("_comment") ?: return null
        if (!comment.isJsonPrimitive || !comment.asJsonPrimitive.isString) return null
        return parseTimestamp(comment.asString.trim())
    }

    internal fun updateDisplayExpiryFromPayload(subscriptionId: String, configText: String?) {
        val root = parseCustomConfigRoot(configText) ?: return
        persistDisplayExpiry(subscriptionId, extractTriggerAtMillis(root))
    }

    private fun persistDisplayExpiry(subscriptionId: String, triggerAtMillis: Long?) {
        val item = MmkvManager.decodeSubscription(subscriptionId) ?: return
        val epochSeconds = triggerAtMillis?.takeIf { it > 0L }?.div(1_000L)
        if (item.commentExpireEpochSeconds == epochSeconds) return
        item.commentExpireEpochSeconds = epochSeconds
        MmkvManager.encodeSubscription(subscriptionId, item)
    }

    private fun parseCustomConfigRoot(configText: String?): JsonObject? {
        val text = configText?.trim().orEmpty()
        if (!text.startsWith('{')) return null
        return try {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return null
            root.asJsonObject.takeIf {
                it.has("inbounds") && it.has("outbounds") && it.has("routing")
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseTimestamp(value: String): Long? {
        runCatching { return Instant.parse(value).toEpochMilli() }
        runCatching {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }
        runCatching {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }
        return runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(DEFAULT_TIME_ZONE)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private suspend fun fetchNetworkEpochMillis(): Long? = withContext(Dispatchers.IO) {
        NETWORK_TIME_SOURCES.firstNotNullOfOrNull { source ->
            fetchNetworkEpochMillis(source)
        }
    }

    private fun fetchNetworkEpochMillis(source: String): Long? {
        val separator = if ('?' in source) '&' else '?'
        val url = URL("$source${separator}mobiletina_clock=${SystemClock.elapsedRealtime()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 7_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("User-Agent", "MobileTina-Android")
            setRequestProperty("Connection", "close")
        }

        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val responseCode = connection.responseCode
            val completedAt = SystemClock.elapsedRealtime()
            val serverEpoch = connection.getHeaderFieldDate("Date", -1L)
            if (responseCode !in 200..399 || serverEpoch !in MIN_VALID_NETWORK_TIME..MAX_VALID_NETWORK_TIME) {
                null
            } else {
                LogUtil.i(AppConfig.TAG, "Internet time verified with ${url.host}")
                serverEpoch + ((completedAt - startedAt).coerceAtLeast(0L) / 2L)
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Internet time source failed: ${url.host}: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    internal fun decodeExpiryMarker(): String {
        val plain = ByteArray(EXPIRY_MARKER_CIPHER.size) { index ->
            val key = 0x5A xor ((index * 31) and 0xFF)
            (EXPIRY_MARKER_CIPHER[index] xor key).toByte()
        }
        return String(plain, StandardCharsets.UTF_8)
    }

    class ExpiryVerificationWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val subscriptionId = inputData.getString(WORK_INPUT_SUBSCRIPTION_ID).orEmpty()
            if (subscriptionId.isBlank()) return Result.failure()
            return if (verifyWithNetworkTime(applicationContext, subscriptionId)) {
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}
