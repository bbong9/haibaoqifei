package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.Locale

/** Privacy-safe metadata from the latest RouteGuidanceUpdate, without retaining source strings. */
class RouteGuidanceDiagnostics {
    enum class GuidanceApp { AMAP, APPLE_MAPS, BAIDU, GOOGLE, OTHER }

    data class Snapshot(
        val app: GuidanceApp? = null,
        val parameterIds: List<Int> = emptyList(),
        val laneIndex: Int? = null,
        val laneTotal: Int? = null,
        val laneShowing: Int? = null,
        val sourceSupports: Int? = null,
    ) {
        private fun params(): String = parameterIds.joinToString(",").ifEmpty { "-" }
        private fun details(): String = "laneShowing=${laneShowing ?: "-"} laneTotal=${laneTotal ?: "-"} " +
            "laneIndex=${laneIndex ?: "-"} sourceSupports=${sourceSupports ?: "-"}"

        fun logLine(): String = "Route guidance: app=${app ?: "-"} params=${params()} ${details()}"
        fun reportLine(): String = "guidanceApp=${app ?: "-"} guidanceParams=${params()} ${details()}"
    }

    private var latest: Snapshot? = null

    fun snapshot(): Snapshot? = latest

    /** Returns a loggable change only on the first frame or an app/parameter-set change. */
    fun accept(frame: Iap2Frame): Snapshot? {
        if (frame.messageId != ROUTE_GUIDANCE_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        val source = runCatching { body.optionalString(SOURCE) }.getOrNull()
        val next = Snapshot(
            app = classify(source),
            parameterIds = body.list().map { it.id }.distinct().sorted(),
            laneIndex = runCatching { body.optionalU16(LANE_INDEX) }.getOrNull(),
            laneTotal = runCatching { body.optionalU16(LANE_TOTAL) }.getOrNull(),
            laneShowing = runCatching { body.optionalU8(LANE_SHOWING) }.getOrNull(),
            sourceSupports = runCatching { body.optionalU8(SOURCE_SUPPORTS) }.getOrNull(),
        )
        val previous = latest
        latest = next
        return next.takeIf { previous == null || previous.app != next.app || previous.parameterIds != next.parameterIds }
    }

    private fun classify(value: String?): GuidanceApp? {
        val source = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = source.lowercase(Locale.ROOT)
        return when {
            source.contains("高德") || "amap" in lower || "autonavi" in lower -> GuidanceApp.AMAP
            lower == "maps" || source == "地图" || source == "地圖" -> GuidanceApp.APPLE_MAPS
            source.contains("百度") || "baidu" in lower -> GuidanceApp.BAIDU
            "waze" in lower || "google" in lower -> GuidanceApp.GOOGLE
            else -> GuidanceApp.OTHER
        }
    }

    private companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val LANE_INDEX = 0x10
        const val LANE_TOTAL = 0x11
        const val LANE_SHOWING = 0x12
        const val SOURCE = 0x13
        const val SOURCE_SUPPORTS = 0x14
    }
}
