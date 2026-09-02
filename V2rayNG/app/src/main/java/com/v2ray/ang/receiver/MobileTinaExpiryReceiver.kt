package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.MobileTinaSessionLimiter
import com.v2ray.ang.handler.MobileTinaExpiryManager

class MobileTinaExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            MobileTinaExpiryManager.ACTION_EXPIRE -> {
                val subscriptionId = intent.getStringExtra(MobileTinaExpiryManager.EXTRA_SUBSCRIPTION_ID).orEmpty()
                MobileTinaExpiryManager.requestOnlineVerification(context.applicationContext, subscriptionId)
            }
            MobileTinaSessionLimiter.ACTION_SESSION_LIMIT -> {
                CoreServiceManager.stopVService(context.applicationContext)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> MobileTinaExpiryManager.recoverPending(context.applicationContext)
        }
    }
}
