package com.gammalens.app.data

/**
 * Current device/camera profile for Calibration tab and export.
 */
data class DeviceProfile(
    val deviceModel: String = "",
    val cameraId: String = "",
    val pairWindowNs: Long = 50_000_000L,
    val flashThreshold: Double = 180.0,
    val areaRangeMin: Double = 2.0,
    val areaRangeMax: Double = 500.0,
    val captureSize: String = "1280x720",
    val fpsTarget: Int = 10,
)
