package com.obsidianscout.utils

import io.ktor.server.application.*
import io.ktor.util.*
import java.util.Locale

val ServerTimingMetricsKey = AttributeKey<MutableList<ServerTimingMetric>>("ServerTimingMetrics")

data class ServerTimingMetric(
    val name: String,
    val durationMs: Double,
    val desc: String? = null
)

fun ApplicationCall.addServerTimingMetric(name: String, durationMs: Double, desc: String? = null) {
    val key = ServerTimingMetricsKey
    val metrics = attributes.getOrNull(key) ?: run {
        val list = mutableListOf<ServerTimingMetric>()
        attributes.put(key, list)
        list
    }
    metrics.add(ServerTimingMetric(name, durationMs, desc))
}

inline fun <T> ApplicationCall.measure(name: String, desc: String? = null, block: () -> T): T {
    val start = System.nanoTime()
    try {
        return block()
    } finally {
        val durationMs = (System.nanoTime() - start) / 1_000_000.0
        addServerTimingMetric(name, durationMs, desc)
    }
}

suspend inline fun <T> ApplicationCall.measureSuspend(name: String, desc: String? = null, crossinline block: suspend () -> T): T {
    val start = System.nanoTime()
    try {
        return block()
    } finally {
        val durationMs = (System.nanoTime() - start) / 1_000_000.0
        addServerTimingMetric(name, durationMs, desc)
    }
}

val ServerTimingPlugin = createApplicationPlugin(name = "ServerTimingPlugin") {
    val startTimeKey = AttributeKey<Long>("ServerTimingStartTime")
    
    onCall { call ->
        call.attributes.put(startTimeKey, System.nanoTime())
    }
    
    onCallRespond { call ->
        val start = call.attributes.getOrNull(startTimeKey)
        if (start != null) {
            val totalDurMs = (System.nanoTime() - start) / 1_000_000.0
            call.addServerTimingMetric("route", totalDurMs, "Route Handler")
        }
        
        val metrics = call.attributes.getOrNull(ServerTimingMetricsKey)
        if (metrics != null && metrics.isNotEmpty()) {
            val headerValue = metrics.joinToString(", ") { metric ->
                val durStr = ";dur=${"%.2f".format(Locale.US, metric.durationMs)}"
                val descStr = if (metric.desc != null) ";desc=\"${metric.desc}\"" else ""
                "${metric.name}$durStr$descStr"
            }
            call.response.headers.append("Server-Timing", headerValue)
        }
    }
}
