package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager

/** Compatibility facade for the old MobileTina pre-warm call; real start still rebuilds with 2.2.6 CoreConfigManager. */
object V2rayConfigManager {
    fun getV2rayConfig(context: Context, guid: String) = CoreConfigManager.getV2rayConfig(context, guid)
}
