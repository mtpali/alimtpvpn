package com.v2ray.ang.dto

import java.io.Serializable

data class TestServiceMessage(
    val key: Int,
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val batchId: Long = 0L
) : Serializable {
    companion object {
        const val SMART_BATCH_STARTED_PREFIX = "mobiletina-smart-batch-start:"
    }
}
