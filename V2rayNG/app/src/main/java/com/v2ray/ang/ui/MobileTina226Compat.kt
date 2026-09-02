package com.v2ray.ang.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MobileTinaSubscriptionUpdateOptimizer
import com.v2ray.ang.service.TProxyService
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin MobileTina bridge over the native v2rayNG 2.2.6 MainViewModel/CoreTestService.
 * No 2.0.15 Core implementation is copied here.
 */
private object MobileTinaRealPingBridge {
    val finished = MutableLiveData<Long>()
    private val generation = AtomicLong(0L)
    private val startedBatchId = AtomicLong(0L)
    private val registered = AtomicBoolean(false)

    fun generation(): Long = generation.get()
    fun startedBatchId(): Long = startedBatchId.get()
    fun prepareSmartBatch() = startedBatchId.set(0L)

    fun ensureRegistered(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getIntExtra("key", 0)) {
                    AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {
                        val content = intent.getSerializableExtra("content")?.toString().orEmpty()
                        if (content.startsWith(TestServiceMessage.SMART_BATCH_STARTED_PREFIX)) {
                            content.removePrefix(TestServiceMessage.SMART_BATCH_STARTED_PREFIX)
                                .toLongOrNull()
                                ?.takeIf { it > 0L }
                                ?.let(startedBatchId::set)
                        }
                    }

                    AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                        val status = intent.getSerializableExtra("content")?.toString().orEmpty()
                        if (status == "0") {
                            val value = generation.incrementAndGet()
                            finished.postValue(value)
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags()
        )
    }
}

val MainViewModel.realPingFinishedAction: MutableLiveData<Long>
    get() {
        MobileTinaRealPingBridge.ensureRegistered(getApplication())
        return MobileTinaRealPingBridge.finished
    }

val MainViewModel.realPingGeneration: Long
    get() {
        MobileTinaRealPingBridge.ensureRegistered(getApplication())
        return MobileTinaRealPingBridge.generation()
    }

val MainViewModel.realPingStartedBatchId: Long
    get() {
        MobileTinaRealPingBridge.ensureRegistered(getApplication())
        return MobileTinaRealPingBridge.startedBatchId()
    }

fun MainViewModel.currentServerGuids(): List<String> {
    val cached = serversCache.map { it.guid }
    if (cached.isNotEmpty()) return cached
    return if (subscriptionId.isBlank()) {
        MmkvManager.decodeAllServerList()
    } else {
        MmkvManager.decodeServerList(subscriptionId)
    }
}

fun MainViewModel.updateEverySubscription() = MobileTinaSubscriptionUpdateOptimizer.updateAll()

/**
 * Starts one isolated Smart Connect Real Ping batch. CoreTestService treats START as
 * authoritative: it invalidates/cancels older workers, clears these exact GUIDs inside the new
 * generation, then echoes batchId. No extra CANCEL/CLEAR round-trip is needed here.
 */
fun MainViewModel.testAllRealPingForSmart(batchId: Long) {
    if (batchId <= 0L) return
    MobileTinaRealPingBridge.ensureRegistered(getApplication())
    MobileTinaRealPingBridge.prepareSmartBatch()

    val guids = currentServerGuids()
    if (guids.isEmpty()) return

    MessageUtil.sendMsg2TestService(
        getApplication(),
        TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = subscriptionId,
            serverGuids = guids,
            batchId = batchId
        )
    )
}

fun MainViewModel.testServerRealPing(guid: String) {
    if (guid.isBlank()) return
    MobileTinaRealPingBridge.ensureRegistered(getApplication())
    MessageUtil.sendMsg2TestService(
        getApplication(),
        TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
    )
    MmkvManager.clearAllTestDelayResults(listOf(guid))
    MessageUtil.sendMsg2TestService(
        getApplication(),
        TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = subscriptionId,
            serverGuids = listOf(guid)
        )
    )
}

fun MainViewModel.cancelRealPing() {
    MessageUtil.sendMsg2TestService(
        getApplication(),
        TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
    )
}

/** Legacy hidden TCP action is intentionally mapped to the supported 2.2.6 Real Ping path. */
fun MainViewModel.testAllTcping() = testAllRealPing()

/** Old UI pre-warm hook; 2.2.6 loads the JNI library in TProxyService's companion initializer. */
fun TProxyService.Companion.preloadNative() = Unit
