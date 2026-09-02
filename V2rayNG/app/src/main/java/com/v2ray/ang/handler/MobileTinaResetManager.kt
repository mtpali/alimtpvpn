package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig

object MobileTinaResetManager {
    fun reset(context: Context, cancelPendingExpiry: Boolean = true) {
        if (cancelPendingExpiry) MobileTinaExpiryManager.cancel(context)
        V2RayServiceManager.stopVService(context)
        MmkvManager.decodeSubscriptions().map { it.guid }.forEach(MmkvManager::removeSubscription)
        MmkvManager.removeServerViaSubid(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        MmkvManager.removeAllServer()
        MmkvManager.encodeSubsList(mutableListOf())
        MmkvManager.setSelectServer("")
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, "")
        MobileTinaSubscriptionMarkerManager.clearExpiredState()
    }
}
