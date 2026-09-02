package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.util.Utils

object MobileTinaHiddenShareManager {
    fun copyAllConfigs(context: Context, subscriptionId: String): Int {
        val contents = MmkvManager.decodeServerList(subscriptionId).mapNotNull { guid -> exportConfig(guid) }
        if (contents.isEmpty()) return 0
        Utils.setClipboard(context, contents.joinToString("\n\n"))
        return contents.size
    }

    fun exportConfig(guid: String): String? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        return when (profile.configType) {
            EConfigType.VMESS -> profile.configType.protocolScheme + VmessFmt.toUri(profile)
            EConfigType.SHADOWSOCKS -> profile.configType.protocolScheme + ShadowsocksFmt.toUri(profile)
            EConfigType.SOCKS -> profile.configType.protocolScheme + SocksFmt.toUri(profile)
            EConfigType.VLESS -> profile.configType.protocolScheme + VlessFmt.toUri(profile)
            EConfigType.TROJAN -> profile.configType.protocolScheme + TrojanFmt.toUri(profile)
            EConfigType.WIREGUARD -> profile.configType.protocolScheme + WireguardFmt.toUri(profile)
            EConfigType.HYSTERIA2 -> profile.configType.protocolScheme + Hysteria2Fmt.toUri(profile)
            EConfigType.CUSTOM -> MmkvManager.decodeServerRaw(guid)
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }
}
