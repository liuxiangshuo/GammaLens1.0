package com.gammalens.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionConfigTest {

    @Test
    fun normalizeInPlace_clamps_threshold_ranges() {
        val cfg = DetectionConfig.defaultInstance().apply {
            riskScoreTriggerHigh = 0.3
            riskScoreReleaseLow = 0.9
            deadTimeMsMin = 300.0
            deadTimeMsMax = 120.0
            poissonCvMin = 2.0
            poissonCvMax = 1.0
            deadTimeDropWindowSize = 1
            deadTimeDropEmaAlpha = 2.0
        }
        cfg.normalizeInPlace()
        assertTrue(cfg.riskScoreReleaseLow <= cfg.riskScoreTriggerHigh)
        assertTrue(cfg.deadTimeMsMax >= cfg.deadTimeMsMin)
        assertTrue(cfg.poissonCvMax >= cfg.poissonCvMin)
        assertTrue(cfg.deadTimeDropWindowSize >= 8)
        assertTrue(cfg.deadTimeDropEmaAlpha in 0.01..1.0)
    }

    @Test
    fun deepCopy_creates_independent_instance() {
        val cfg = DetectionConfig.defaultInstance().apply {
            modelVersion = "v8-r7-a"
            riskCalibrationPoints = listOf(0.0 to 0.0, 0.7 to 0.6, 1.0 to 1.0)
        }
        val copy = cfg.deepCopy()
        assertNotSame(cfg, copy)
        assertEquals(cfg.modelVersion, copy.modelVersion)
        assertEquals(cfg.riskCalibrationPoints, copy.riskCalibrationPoints)
        cfg.modelVersion = "v8-r7-b"
        assertEquals("v8-r7-a", copy.modelVersion)
    }

    @Test
    fun updated_returns_new_normalized_copy() {
        val cfg = DetectionConfig.defaultInstance()
        val next = cfg.updated {
            it.deadTimeMsMin = 400.0
            it.deadTimeMsMax = 120.0
            it.calibrationVersion = 0
        }
        assertNotSame(cfg, next)
        assertEquals(1, next.calibrationVersion)
        assertTrue(next.deadTimeMsMax >= next.deadTimeMsMin)
        assertTrue(cfg.deadTimeMsMin != next.deadTimeMsMin)
    }
}
