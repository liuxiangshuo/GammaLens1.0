package com.gammalens.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameProcessorAlgorithmsTest {

    @Test
    fun evaluatePoisson_startupGuard_blocks_before_warmup() {
        val decision = FrameProcessorAlgorithms.evaluatePoisson(
            timestamps = listOf(1000L, 1500L, 2200L),
            poissonEnabled = true,
            poissonMinEvents = 4,
            poissonCvMin = 0.5,
            poissonCvMax = 1.8,
            poissonConfidenceSampleTarget = 10,
            poissonConfidenceMinWeight = 0.35,
            startupGuardEnabled = true,
            startupMinEvents = 6,
        )
        assertFalse(decision.warmupReady)
        assertFalse(decision.pass)
        assertEquals(0.0, decision.confidence, 1e-6)
        assertEquals(3, decision.sampleCount)
    }

    @Test
    fun evaluatePoisson_cv_in_range_passes() {
        val decision = FrameProcessorAlgorithms.evaluatePoisson(
            timestamps = listOf(0L, 100L, 250L, 330L, 520L, 640L),
            poissonEnabled = true,
            poissonMinEvents = 4,
            poissonCvMin = 0.2,
            poissonCvMax = 2.0,
            poissonConfidenceSampleTarget = 10,
            poissonConfidenceMinWeight = 0.35,
            startupGuardEnabled = true,
            startupMinEvents = 4,
        )
        assertTrue(decision.warmupReady)
        assertTrue(decision.pass)
        assertTrue(decision.confidence > 0.1)
        assertTrue(decision.cv >= 0.2)
        assertTrue(decision.rawCv >= 0.2)
    }

    @Test
    fun computeStackAdaptive_respects_bounds() {
        val result = FrameProcessorAlgorithms.computeStackAdaptive(
            depthBase = 5,
            thresholdBase = 0.4,
            adaptiveEnabled = true,
            depthMin = 3,
            depthMax = 6,
            noiseQuantile = 100.0,
            adaptiveDiffMin = 8.0,
            adaptiveDiffMax = 52.0,
            motionDx = 40.0,
            motionDy = 40.0,
            motionCompMaxPx = 36.0,
            noiseBoost = 0.18,
            motionBoost = 0.08,
        )
        assertTrue(result.depth in 3..6)
        assertTrue(result.threshold in 0.15..0.95)
    }

    @Test
    fun computeStackAdaptive_disabled_keeps_base() {
        val result = FrameProcessorAlgorithms.computeStackAdaptive(
            depthBase = 5,
            thresholdBase = 0.4,
            adaptiveEnabled = false,
            depthMin = 3,
            depthMax = 8,
            noiseQuantile = 12.0,
            adaptiveDiffMin = 8.0,
            adaptiveDiffMax = 52.0,
            motionDx = 0.0,
            motionDy = 0.0,
            motionCompMaxPx = 36.0,
            noiseBoost = 0.18,
            motionBoost = 0.08,
        )
        assertEquals(5, result.depth)
        assertEquals(0.4, result.threshold, 1e-6)
    }

    @Test
    fun computePoissonConfidence_peak_near_one() {
        val nearOne = FrameProcessorAlgorithms.computePoissonConfidence(1.0, 0.5, 1.8)
        val farAway = FrameProcessorAlgorithms.computePoissonConfidence(1.8, 0.5, 1.8)
        assertTrue(nearOne > farAway)
    }

    @Test
    fun computePoissonConfidence_penalizes_small_samples() {
        val lowSamples = FrameProcessorAlgorithms.computePoissonConfidence(
            cv = 1.0,
            cvMin = 0.5,
            cvMax = 1.8,
            sampleCount = 4,
            sampleTarget = 10,
            minWeight = 0.35,
        )
        val enoughSamples = FrameProcessorAlgorithms.computePoissonConfidence(
            cv = 1.0,
            cvMin = 0.5,
            cvMax = 1.8,
            sampleCount = 20,
            sampleTarget = 10,
            minWeight = 0.35,
        )
        assertTrue(enoughSamples > lowSamples)
    }

    @Test
    fun computeTempSlopeBoost_increases_with_slope() {
        val low = FrameProcessorAlgorithms.computeTempSlopeBoost(0.01, 0.03, 2.0)
        val high = FrameProcessorAlgorithms.computeTempSlopeBoost(0.03, 0.03, 2.0)
        assertTrue(high >= low)
    }

    @Test
    fun computeRiskScore_monotonic_with_poisson() {
        val low = FrameProcessorAlgorithms.computeRiskScore(
            poissonConfidence = 0.2,
            cusumScore = 2.5,
            cusumEnabled = true,
            cusumPass = true,
            pulseDensity = 0.8,
            neighborhoodConsistency = 0.8,
            trackStability = 0.7,
            qualityScore = 0.7,
            classifierProbability = 0.7,
            warmupReady = true,
            warmupPenalty = 0.2,
            weightPoisson = 0.35,
            weightCusum = 0.25,
            weightStability = 0.20,
            weightQuality = 0.20,
        )
        val high = FrameProcessorAlgorithms.computeRiskScore(
            poissonConfidence = 0.8,
            cusumScore = 2.5,
            cusumEnabled = true,
            cusumPass = true,
            pulseDensity = 0.8,
            neighborhoodConsistency = 0.8,
            trackStability = 0.7,
            qualityScore = 0.7,
            classifierProbability = 0.7,
            warmupReady = true,
            warmupPenalty = 0.2,
            weightPoisson = 0.35,
            weightCusum = 0.25,
            weightStability = 0.20,
            weightQuality = 0.20,
        )
        assertTrue(high > low)
    }

    @Test
    fun applyHysteresis_reduces_boundary_toggle() {
        val on = FrameProcessorAlgorithms.applyHysteresis(false, 0.70, 0.68, 0.52)
        val stayOn = FrameProcessorAlgorithms.applyHysteresis(on, 0.60, 0.68, 0.52)
        val off = FrameProcessorAlgorithms.applyHysteresis(stayOn, 0.50, 0.68, 0.52)
        assertTrue(on)
        assertTrue(stayOn)
        assertFalse(off)
    }

    @Test
    fun deadTime_acceptPulseWithDeadTime_behaves() {
        assertTrue(FrameProcessorAlgorithms.acceptPulseWithDeadTime(0L, 1000L, 120.0))
        assertFalse(FrameProcessorAlgorithms.acceptPulseWithDeadTime(1000L, 1080L, 120.0))
        assertTrue(FrameProcessorAlgorithms.acceptPulseWithDeadTime(1000L, 1130L, 120.0))
    }

    @Test
    fun computeDeadTimeMs_feedback_reduces_when_drop_ratio_high() {
        val highDrop = FrameProcessorAlgorithms.computeDeadTimeMs(
            noiseQuantile = 40.0,
            tempSlopeCPerSec = 0.02,
            adaptiveDiffMin = 8.0,
            adaptiveDiffMax = 52.0,
            deadTimeMsMin = 70.0,
            deadTimeMsMax = 220.0,
            deadTimeNoiseScale = 0.55,
            deadTimeTempScale = 0.45,
            tempSlopeThresholdCPerSec = 0.03,
            dropRatio = 0.35,
            dropTarget = 0.16,
            dropFeedbackGain = 0.35,
        )
        val lowDrop = FrameProcessorAlgorithms.computeDeadTimeMs(
            noiseQuantile = 40.0,
            tempSlopeCPerSec = 0.02,
            adaptiveDiffMin = 8.0,
            adaptiveDiffMax = 52.0,
            deadTimeMsMin = 70.0,
            deadTimeMsMax = 220.0,
            deadTimeNoiseScale = 0.55,
            deadTimeTempScale = 0.45,
            tempSlopeThresholdCPerSec = 0.03,
            dropRatio = 0.05,
            dropTarget = 0.16,
            dropFeedbackGain = 0.35,
        )
        assertTrue(highDrop <= lowDrop)
    }

    @Test
    fun evaluatePoisson_deadTime_correction_improves_confidence() {
        val noCorrection = FrameProcessorAlgorithms.evaluatePoisson(
            timestamps = listOf(0L, 200L, 410L, 620L, 830L, 1040L),
            poissonEnabled = true,
            poissonMinEvents = 4,
            poissonCvMin = 0.5,
            poissonCvMax = 1.6,
            poissonConfidenceSampleTarget = 10,
            poissonConfidenceMinWeight = 0.35,
            deadTimeMs = 0.0,
            startupGuardEnabled = true,
            startupMinEvents = 4,
        )
        val corrected = FrameProcessorAlgorithms.evaluatePoisson(
            timestamps = listOf(0L, 200L, 410L, 620L, 830L, 1040L),
            poissonEnabled = true,
            poissonMinEvents = 4,
            poissonCvMin = 0.5,
            poissonCvMax = 1.6,
            poissonConfidenceSampleTarget = 10,
            poissonConfidenceMinWeight = 0.35,
            deadTimeMs = 60.0,
            startupGuardEnabled = true,
            startupMinEvents = 4,
        )
        assertTrue(corrected.confidence >= noCorrection.confidence)
        assertTrue(corrected.cv >= corrected.rawCv)
    }

    @Test
    fun evaluatePoisson_exposes_raw_and_corrected_cv() {
        val decision = FrameProcessorAlgorithms.evaluatePoisson(
            timestamps = listOf(0L, 160L, 340L, 510L, 700L, 880L, 1070L),
            poissonEnabled = true,
            poissonMinEvents = 4,
            poissonCvMin = 0.5,
            poissonCvMax = 1.8,
            poissonConfidenceSampleTarget = 10,
            poissonConfidenceMinWeight = 0.35,
            deadTimeMs = 50.0,
            startupGuardEnabled = true,
            startupMinEvents = 4,
        )
        assertTrue(decision.rawCv > 0.0)
        assertTrue(decision.cv > 0.0)
    }

    @Test
    fun computeDeadTimeMs_stays_in_bounds_extreme_inputs() {
        val low = FrameProcessorAlgorithms.computeDeadTimeMs(
            noiseQuantile = -10.0,
            tempSlopeCPerSec = 0.0,
            adaptiveDiffMin = 8.0,
            adaptiveDiffMax = 52.0,
            deadTimeMsMin = 70.0,
            deadTimeMsMax = 220.0,
            deadTimeNoiseScale = 0.55,
            deadTimeTempScale = 0.45,
            tempSlopeThresholdCPerSec = 0.03,
            dropRatio = 0.0,
            dropTarget = 0.16,
            dropFeedbackGain = 0.35,
        )
        val high = FrameProcessorAlgorithms.computeDeadTimeMs(
            noiseQuantile = 1000.0,
            tempSlopeCPerSec = 3.0,
            adaptiveDiffMin = 8.0,
            adaptiveDiffMax = 52.0,
            deadTimeMsMin = 70.0,
            deadTimeMsMax = 220.0,
            deadTimeNoiseScale = 0.55,
            deadTimeTempScale = 0.45,
            tempSlopeThresholdCPerSec = 0.03,
            dropRatio = 1.0,
            dropTarget = 0.16,
            dropFeedbackGain = 0.35,
        )
        assertTrue(low in 70.0..220.0)
        assertTrue(high in 70.0..220.0)
    }

    @Test
    fun computeTempSlopeBoost_startup_gain_higher_than_steady() {
        val startup = FrameProcessorAlgorithms.computeTempSlopeBoost(0.02, 0.03, 2.0, phaseGain = 1.6)
        val steady = FrameProcessorAlgorithms.computeTempSlopeBoost(0.02, 0.03, 2.0, phaseGain = 1.0)
        assertTrue(startup > steady)
    }

    @Test
    fun applyRiskCalibration_interpolates_between_points() {
        val calibrated = FrameProcessorAlgorithms.applyRiskCalibration(
            score = 0.6,
            enabled = true,
            points = listOf(0.0 to 0.0, 0.5 to 0.4, 1.0 to 1.0),
        )
        assertTrue(calibrated > 0.4)
        assertTrue(calibrated < 1.0)
    }
}
