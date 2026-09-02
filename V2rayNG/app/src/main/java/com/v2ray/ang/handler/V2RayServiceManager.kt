package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.LogUtil

/**
 * Compatibility facade used by the MobileTina UI while the VPN/Core service runs in a separate
 * Android process.
 *
 * Only the multi-process MMKV flag is treated as "running" here. A previous local 15-second
 * pending-start grace deliberately reported true before the core had actually started. When a
 * foreground-service start failed without a matching broadcast (observed on some Samsung builds),
 * Smart Connect's watchdog therefore believed the VPN was healthy and the yellow FAB could remain
 * stuck indefinitely. MainActivity already tracks pending starts with smartConnecting/
 * manualConnecting, so pretending a pending request is a running core is unnecessary.
 */
object V2RayServiceManager {

    fun startVService(context: Context) {
        CoreServiceManager.startVService(context)
    }

    fun stopVService(context: Context) {
        val app = context.applicationContext

        // Keep the normal in-process command for an orderly shutdown, then also stop every
        // possible concrete service explicitly. A few OEM builds occasionally delay/drop the
        // dynamically registered cross-process broadcast; stopService reaches the actual Android
        // component and its idempotent onDestroy teardown even in that state.
        CoreServiceManager.stopVService(app)
        arrayOf(
            CoreVpnService::class.java,
            CoreProxyOnlyService::class.java,
            CoreRootService::class.java
        ).forEach { serviceClass ->
            runCatching { app.stopService(Intent(app, serviceClass)) }
                .onFailure { error ->
                    LogUtil.w(AppConfig.TAG, "Unable to stop ${serviceClass.simpleName}", error)
                }
        }
    }

    /** Returns only a confirmed service/core state shared by the daemon process. */
    fun isRunning(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.CACHE_SERVICE_RUNNING, false)
}
