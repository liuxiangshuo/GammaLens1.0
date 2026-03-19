package com.gammalens.app.data

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class DeviceCalibrationProfile(
    val key: String,
    val darkFieldOffset: Double = 0.0,
    val hotPixelGain: Double = 1.0,
    val tempCompensationCoeff: Double = 0.02,
    val version: Int = 1,
    val tempNoiseCurve: Map<String, Double> = emptyMap(),
    val hotPixelMapByTemp: Map<String, List<Pair<Int, Int>>> = emptyMap(),
)

data class CalibrationUpdateResult(
    val profile: DeviceCalibrationProfile,
    val deltaOffset: Double,
    val deltaHotPixelGain: Double,
    val rollbackSuggested: Boolean,
    val temperatureBucket: String,
    val frozenByTemperature: Boolean,
)

class DeviceCalibrationRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("device_calibration", Context.MODE_PRIVATE)

    fun loadOrDefault(): DeviceCalibrationProfile {
        val key = Build.MODEL ?: "unknown"
        return loadByKeyOrDefault(key)
    }

    fun loadByKeyOrDefault(key: String): DeviceCalibrationProfile {
        val raw = prefs.getString(key, null) ?: return DeviceCalibrationProfile(key = key)
        return runCatching {
            val obj = JSONObject(raw)
            DeviceCalibrationProfile(
                key = key,
                darkFieldOffset = obj.optDouble("darkFieldOffset", 0.0),
                hotPixelGain = obj.optDouble("hotPixelGain", 1.0),
                tempCompensationCoeff = obj.optDouble("tempCompensationCoeff", 0.02),
                version = obj.optInt("version", 1),
                tempNoiseCurve = parseTempNoiseCurve(obj.optJSONObject("tempNoiseCurve")),
                hotPixelMapByTemp = parseHotPixelMap(obj.optJSONObject("hotPixelMapByTemp")),
            )
        }.getOrDefault(DeviceCalibrationProfile(key = key))
    }

    fun save(profile: DeviceCalibrationProfile) {
        val obj = JSONObject().apply {
            put("darkFieldOffset", profile.darkFieldOffset)
            put("hotPixelGain", profile.hotPixelGain)
            put("tempCompensationCoeff", profile.tempCompensationCoeff)
            put("version", profile.version)
            put("tempNoiseCurve", JSONObject(profile.tempNoiseCurve))
            put("hotPixelMapByTemp", encodeHotPixelMap(profile.hotPixelMapByTemp))
        }
        prefs.edit().putString(profile.key, obj.toString()).apply()
    }

    fun smoothUpdateFromDarkSession(
        key: String,
        darkFieldMean: Double,
        hotPixelSuppressedCount: Int,
        tempC: Double?,
        alpha: Double,
        stepMaxOffset: Double,
        stepMaxHotGain: Double,
        medianWindow: Int,
        freezeTempLow: Double,
        freezeTempHigh: Double,
    ): CalibrationUpdateResult {
        val current = loadByKeyOrDefault(key)
        val bucket = tempBucket(tempC)
        val tempValue = tempC ?: 25.0
        if (tempValue < freezeTempLow || tempValue > freezeTempHigh) {
            return CalibrationUpdateResult(
                profile = current,
                deltaOffset = 0.0,
                deltaHotPixelGain = 0.0,
                rollbackSuggested = false,
                temperatureBucket = bucket,
                frozenByTemperature = true,
            )
        }
        val safeAlpha = alpha.coerceIn(0.01, 0.4)
        val targetOffset = (darkFieldMean - 28.0).coerceIn(-8.0, 12.0)
        val targetHotPixelGain = (1.0 + hotPixelSuppressedCount.toDouble() / 3500.0).coerceIn(0.75, 2.2)
        val robustOffset = applyMedianGuard(targetOffset, current.tempNoiseCurve.values.toList(), medianWindow)
        val updatedCurve = mergeTempNoiseCurve(current.tempNoiseCurve, tempC, darkFieldMean, safeAlpha)
        val rawOffset = smooth(current.darkFieldOffset, robustOffset, safeAlpha)
        val rawHotGain = smooth(current.hotPixelGain, targetHotPixelGain, safeAlpha)
        val limitedOffset = limitStep(current.darkFieldOffset, rawOffset, stepMaxOffset)
        val limitedHotGain = limitStep(current.hotPixelGain, rawHotGain, stepMaxHotGain)
        val updated = current.copy(
            key = key,
            darkFieldOffset = limitedOffset,
            hotPixelGain = limitedHotGain,
            version = current.version + 1,
            tempNoiseCurve = updatedCurve,
        )
        save(updated)
        val deltaOffset = updated.darkFieldOffset - current.darkFieldOffset
        val deltaHot = updated.hotPixelGain - current.hotPixelGain
        val rollbackSuggested = kotlin.math.abs(deltaOffset) > stepMaxOffset * 0.9 ||
            kotlin.math.abs(deltaHot) > stepMaxHotGain * 0.9
        return CalibrationUpdateResult(
            profile = updated,
            deltaOffset = deltaOffset,
            deltaHotPixelGain = deltaHot,
            rollbackSuggested = rollbackSuggested,
            temperatureBucket = bucket,
            frozenByTemperature = false,
        )
    }

    private fun smooth(oldValue: Double, newValue: Double, alpha: Double): Double {
        return oldValue * (1.0 - alpha) + newValue * alpha
    }

    private fun parseTempNoiseCurve(raw: JSONObject?): Map<String, Double> {
        if (raw == null) return emptyMap()
        val out = mutableMapOf<String, Double>()
        val keys = raw.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = raw.optDouble(k, 0.0)
        }
        return out
    }

    private fun parseHotPixelMap(raw: JSONObject?): Map<String, List<Pair<Int, Int>>> {
        if (raw == null) return emptyMap()
        val out = mutableMapOf<String, List<Pair<Int, Int>>>()
        val keys = raw.keys()
        while (keys.hasNext()) {
            val bucket = keys.next()
            val arr = raw.optJSONArray(bucket) ?: continue
            val pts = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val x = p.optInt("x", -1)
                val y = p.optInt("y", -1)
                if (x >= 0 && y >= 0) pts.add(x to y)
            }
            out[bucket] = pts
        }
        return out
    }

    private fun encodeHotPixelMap(map: Map<String, List<Pair<Int, Int>>>): JSONObject {
        val obj = JSONObject()
        for ((bucket, points) in map) {
            val arr = JSONArray()
            for ((x, y) in points) {
                arr.put(JSONObject().apply {
                    put("x", x)
                    put("y", y)
                })
            }
            obj.put(bucket, arr)
        }
        return obj
    }

    private fun mergeTempNoiseCurve(
        oldCurve: Map<String, Double>,
        tempC: Double?,
        noise: Double,
        alpha: Double,
    ): Map<String, Double> {
        val bucket = tempBucket(tempC)
        val oldValue = oldCurve[bucket] ?: noise
        val newValue = smooth(oldValue, noise, alpha)
        return oldCurve.toMutableMap().apply { put(bucket, newValue) }
    }

    private fun applyMedianGuard(target: Double, refs: List<Double>, medianWindow: Int): Double {
        if (refs.isEmpty()) return target
        val sorted = refs.sorted()
        val n = medianWindow.coerceAtLeast(1).coerceAtMost(sorted.size)
        val slice = sorted.takeLast(n)
        val median = if (slice.size % 2 == 1) slice[slice.size / 2] else {
            (slice[slice.size / 2 - 1] + slice[slice.size / 2]) / 2.0
        }
        return 0.7 * target + 0.3 * median
    }

    private fun limitStep(oldValue: Double, newValue: Double, maxStep: Double): Double {
        val delta = (newValue - oldValue).coerceIn(-maxStep, maxStep)
        return oldValue + delta
    }

    private fun tempBucket(tempC: Double?): String {
        val v = (tempC ?: 25.0).coerceIn(-10.0, 80.0)
        val lo = (kotlin.math.floor(v / 5.0) * 5.0).toInt()
        val hi = lo + 4
        return "${lo}_${hi}"
    }

    fun getHotPixelMapForTemperature(key: String, tempC: Double?): Pair<String, List<Pair<Int, Int>>> {
        val profile = loadByKeyOrDefault(key)
        val bucket = tempBucket(tempC)
        return bucket to (profile.hotPixelMapByTemp[bucket] ?: emptyList())
    }

    fun mergeHotPixelMapFromSession(
        key: String,
        tempC: Double?,
        points: List<Pair<Int, Int>>,
        minOccurrences: Int,
        maxPoints: Int,
    ): DeviceCalibrationProfile {
        if (points.isEmpty()) return loadByKeyOrDefault(key)
        val profile = loadByKeyOrDefault(key)
        val bucket = tempBucket(tempC)
        val oldPoints = profile.hotPixelMapByTemp[bucket] ?: emptyList()
        val freq = mutableMapOf<Pair<Int, Int>, Int>()
        for (pt in oldPoints) freq[pt] = (freq[pt] ?: 0) + minOccurrences
        for (pt in points) freq[pt] = (freq[pt] ?: 0) + 1
        val merged = freq.entries
            .asSequence()
            .filter { it.value >= minOccurrences }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(maxPoints.coerceAtLeast(16))
            .toList()
        val updated = profile.copy(
            version = profile.version + 1,
            hotPixelMapByTemp = profile.hotPixelMapByTemp.toMutableMap().apply { put(bucket, merged) },
        )
        save(updated)
        return updated
    }
}

