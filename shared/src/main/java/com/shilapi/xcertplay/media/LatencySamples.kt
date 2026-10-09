package com.shilapi.xcertplay.media

/** Bounded samples; no allocations on record. Quantiles use the most recent capacity samples. */
internal class LatencySamples(private val capacity: Int = 1024) {
    init { require(capacity > 0) }
    private val micros = LongArray(capacity)
    private var count = 0L
    private var maxMicros = 0L

    @Synchronized fun record(nanos: Long) {
        val value = nanos.coerceAtLeast(0) / 1000
        micros[(count % capacity).toInt()] = value
        count++
        maxMicros = maxOf(maxMicros, value)
    }

    @Synchronized fun takeSummary(label: String): String {
        if (count == 0L) return ""
        val size = minOf(count, capacity.toLong()).toInt()
        val sorted = micros.copyOf(size).apply { sort() }
        fun percentile(p: Int) = sorted[((size * p + 99) / 100 - 1).coerceAtLeast(0)] / 1000
        val summary = " $label p50=${percentile(50)}ms p95=${percentile(95)}ms max=${maxMicros / 1000}ms n=$count"
        count = 0
        maxMicros = 0
        return summary
    }
}
