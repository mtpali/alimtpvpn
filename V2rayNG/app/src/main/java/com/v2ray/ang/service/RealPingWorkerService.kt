package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onEvent: (RealPingEvent) -> Unit = {},
    maxConcurrency: Int? = null
) {
    private val job = SupervisorJob()
    private val concurrency = minOf(
        SettingsManager.getRealPingConcurrency(),
        maxConcurrency ?: Int.MAX_VALUE
    ).coerceAtLeast(1)
    private val dispatcher = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))
    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    onEvent(RealPingEvent.Result(guid, startRealPing(guid)))
                } catch (e: CancellationException) {
                    // Cancellation is not a failed ping. Propagate it so a superseded Smart
                    // Connect attempt cannot publish an artificial -1 result into a new batch.
                    throw e
                } catch (_: Throwable) {
                    onEvent(RealPingEvent.Result(guid, -1L))
                } finally {
                    val left = runningCount.decrementAndGet()
                    val count = totalCount.decrementAndGet()
                    onEvent(RealPingEvent.Progress("$left / $count"))
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onEvent(RealPingEvent.Finish("0"))
            } catch (_: CancellationException) {
                onEvent(RealPingEvent.Finish("-1"))
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
        }
    }

    private fun startRealPing(guid: String): Long {
        val config = MmkvManager.decodeServerConfig(guid) ?: return -1L

        // Do not reject a server before the real outbound test. TCP pre-checks can
        // produce false negatives on mobile networks, Reality, CDN and proxy paths.
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return -1L
        }

        // Some mobile networks/devices intermittently fail the primary generate_204 endpoint
        // even though the proxy server itself is healthy. Match the connected-core delay logic:
        // try the configured/primary endpoint first, then the secondary endpoint before marking
        // a server dead. This prevents Smart Connect from turning red on a false-negative batch.
        val primaryUrl = SettingsManager.getDelayTestUrl()
        val primary = CoreNativeManager.measureOutboundDelay(configResult.content, primaryUrl)
        if (primary >= 0L) return primary

        val secondaryUrl = SettingsManager.getDelayTestUrl(true)
        if (secondaryUrl == primaryUrl) return primary
        return CoreNativeManager.measureOutboundDelay(configResult.content, secondaryUrl)
    }
}
