package com.gammalens.app.camera

import kotlin.math.sqrt

internal data class StackAdaptiveResult(
    val depth: Int,
    val threshold: Double,
)

internal data class PoissonDecision(
    val cv: Double,
    val rawCv: Double,
    val pass: Boolean,
    val confidence: Double,
    val warmupReady: Boolean,
    val sampleCount: Int,
)

internal object FrameProcessorAlgorithms {
    fun computeRiskScore(
        poissonConfidence: Double,
        cusumScore: Double,
        cusumEnabled: Boolean,
        cusumPass: Boolean,
        pulseDensity: Double,
        neighborhoodConsistency: Double,
        trackStability: Double,
        qualityScore: Double,
        classifierProbability: Double,
        warmupReady: Boolean,
        warmupPenalty: Double,
        weightPoisson: Double,
        weightCusum: Double,
        weightStability: Double,
        weightQuality: Double,
    ): Double {
        val poissonPart = poissonConfidence.coerceIn(0.0, 1.0)
        val cusumPart = if (!cusumEnabled) {
            1.0
        } else {
            val scoreNorm = ((cusumScore + 1.0) / 4.0).coerceIn(0.0, 1.0)
            val passBoost = if (cusumPass) 0.15 else 0.0
            (scoreNorm + passBoost).coerceIn(0.0, 1.0)
        }
        val stabilityPart = (
            0.40 * pulseDensity.coerceIn(0.0, 1.0) +
                0.35 * neighborhoodConsistency.coerceIn(0.0, 1.0) +
                0.25 * trackStability.coerceIn(0.0, 1.0)
            ).coerceIn(0.0, 1.0)
        val qualityPart = (
            0.6 * qualityScore.coerceIn(0.0, 1.0) +
                0.4 * classifierProbability.coerceIn(0.0, 1.0)
            ).coerceIn(0.0, 1.0)
        val wPoisson = weightPoisson.coerceAtLeast(0.0)
        val wCusum = weightCusum.coerceAtLeast(0.0)
        val wStability = weightStability.coerceAtLeast(0.0)
        val wQuality = weightQuality.coerceAtLeast(0.0)
        val wSum = (wPoisson + wCusum + wStability + wQuality).coerceAtLeast(1e-6)
        val raw = (
            wPoisson * poissonPart +
                wCusum * cusumPart +
                wStability * stabilityPart +
                wQuality * qualityPart
            ) / wSum
        val penaltyFactor = if (!warmupReady) (1.0 - warmupPenalty.coerceIn(0.0, 0.6)) else 1.0
        return (raw * penaltyFactor).coerceIn(0.0, 1.0)
    }

    fun applyHysteresis(lastState: Boolean, score: Double, triggerHigh: Double, releaseLow: Double): Boolean {
        val high = triggerHigh.coerceIn(0.0, 1.0)
        val low = releaseLow.coerceIn(0.0, high)
        return if (lastState) {
            score >= low
        } else {
            score >= high
        }
    }

    fun applyRiskCalibration(
        score: Double,
        enabled: Boolean,
        points: List<Pair<Double, Double>>,
    ): Double {
        val s = score.coerceIn(0.0, 1.0)
        if (!enabled || points.size < 2) return s
        val sorted = points
            .map { it.first.coerceIn(0.0, 1.0) to it.second.coerceIn(0.0, 1.0) }
            .sortedBy { it.first }
        if (s <= sorted.first().first) return sorted.first().second
        if (s >= sorted.last().first) return sorted.last().second
        for (i in 1 until sorted.size) {
            val left = sorted[i - 1]
            val right = sorted[i]
            if (s <= right.first) {
                val dx = (right.first - left.first).coerceAtLeast(1e-6)
                val t = ((s - left.first) / dx).coerceIn(0.0, 1.0)
                return (left.second + t * (right.second - left.second)).coerceIn(0.0, 1.0)
            }
        }
        return s
    }

    fun computeDeadTimeMs(
        noiseQuantile: Double,
        tempSlopeCPerSec: Double,
        adaptiveDiffMin: Double,
        adaptiveDiffMax: Double,
        deadTimeMsMin: Double,
        deadTimeMsMax: Double,
        deadTimeNoiseScale: Double,
        deadTimeTempScale: Double,
        tempSlopeThresholdCPerSec: Double,
        dropRatio: Double = 0.0,
        dropTarget: Double = 0.16,
        dropFeedbackGain: Double = 0.35,
    ): Double {
        val minMs = deadTimeMsMin.coerceAtLeast(1.0)
        val maxMs = maxOf(minMs, deadTimeMsMax)
        val noiseSpan = (adaptiveDiffMax - adaptiveDiffMin).coerceAtLeast(1.0)
        val noiseNorm = ((noiseQuantile - adaptiveDiffMin) / noiseSpan).coerceIn(0.0, 1.0)
        val tempNorm = (kotlin.math.abs(tempSlopeCPerSec) / tempSlopeThresholdCPerSec.coerceAtLeast(1e-4)).coerceIn(0.0, 1.0)
        val blend = (
            deadTimeNoiseScale.coerceAtLeast(0.0) * noiseNorm +
                deadTimeTempScale.coerceAtLeast(0.0) * tempNorm
            ).coerceIn(0.0, 1.0)
        val base = minMs + (maxMs - minMs) * blend
        val dropErr = (dropRatio - dropTarget).coerceIn(-0.5, 0.5)
        val feedbackScale = (1.0 - dropFeedbackGain.coerceAtLeast(0.0) * dropErr).coerceIn(0.75, 1.35)
        return (base * feedbackScale).coerceIn(minMs, maxMs)
    }

    fun acceptPulseWithDeadTime(lastAcceptedTimestampMs: Long, nowMs: Long, deadTimeMs: Double): Boolean {
        if (lastAcceptedTimestampMs <= 0L) return true
        return (nowMs - lastAcceptedTimestampMs).toDouble() >= deadTimeMs.coerceAtLeast(0.0)
    }

    fun computeStackAdaptive(
        depthBase: Int,
        thresholdBase: Double,
        adaptiveEnabled: Boolean,
        depthMin: Int,
        depthMax: Int,
        noiseQuantile: Double,
        adaptiveDiffMin: Double,
        adaptiveDiffMax: Double,
        motionDx: Double,
        motionDy: Double,
        motionCompMaxPx: Double,
        noiseBoost: Double,
        motionBoost: Double,
    ): StackAdaptiveResult {
        val minDepth = depthMin.coerceAtLeast(1)
        val maxDepth = maxOf(minDepth, depthMax)
        val baseDepth = depthBase.coerceAtLeast(1)
        if (!adaptiveEnabled) {
            return StackAdaptiveResult(
                depth = baseDepth.coerceIn(minDepth, maxDepth),
                threshold = thresholdBase.coerceIn(0.15, 0.95),
            )
        }
        val noiseSpan = (adaptiveDiffMax - adaptiveDiffMin).coerceAtLeast(1.0)
        val noiseNorm = ((noiseQuantile - adaptiveDiffMin) / noiseSpan).coerceIn(0.0, 1.0)
        val motionNorm = (kotlin.math.hypot(motionDx, motionDy) / motionCompMaxPx.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        val depthOffset = when {
            noiseNorm >= 0.75 -> 2
            noiseNorm >= 0.45 -> 1
            noiseNorm <= 0.20 -> -1
            else -> 0
        }
        val depth = (baseDepth + depthOffset).coerceIn(minDepth, maxDepth)
        val threshold = (thresholdBase + noiseNorm * noiseBoost + motionNorm * motionBoost).coerceIn(0.15, 0.95)
        return StackAdaptiveResult(depth = depth, threshold = threshold)
    }

    fun evaluatePoisson(
        timestamps: List<Long>,
        poissonEnabled: Boolean,
        poissonMinEvents: Int,
        poissonCvMin: Double,
        poissonCvMax: Double,
        poissonConfidenceSampleTarget: Int,
        poissonConfidenceMinWeight: Double,
        deadTimeMs: Double = 0.0,
        startupGuardEnabled: Boolean,
        startupMinEvents: Int,
    ): PoissonDecision {
        if (!poissonEnabled) {
            return PoissonDecision(
                cv = 0.0,
                rawCv = 0.0,
                pass = true,
                confidence = 1.0,
                warmupReady = true,
                sampleCount = 0,
            )
        }
        val sampleCount = timestamps.size
        val warmupReady = if (startupGuardEnabled) sampleCount >= maxOf(startupMinEvents, 1) else true
        if (sampleCount < poissonMinEvents) {
            return PoissonDecision(
                cv = 0.0,
                rawCv = 0.0,
                pass = if (startupGuardEnabled) warmupReady else true,
                confidence = if (startupGuardEnabled && !warmupReady) 0.0 else 0.35,
                warmupReady = warmupReady,
                sampleCount = sampleCount,
            )
        }
        val intervals = (1 until sampleCount).map { (timestamps[it] - timestamps[it - 1]).toDouble() }
        if (intervals.isEmpty()) {
            return PoissonDecision(
                cv = 0.0,
                rawCv = 0.0,
                pass = if (startupGuardEnabled) warmupReady else true,
                confidence = if (startupGuardEnabled && !warmupReady) 0.0 else 0.35,
                warmupReady = warmupReady,
                sampleCount = sampleCount,
            )
        }
        val mean = intervals.average()
        val variance = intervals.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance)
        val cv = if (mean > 0.0) (std / mean) else 0.0
        val expectedCvFloor = 0.30
        val expectedCv = if (deadTimeMs > 0.0 && mean > 1e-6) {
            (1.0 - (deadTimeMs / mean)).coerceIn(expectedCvFloor, 1.0)
        } else {
            1.0
        }
        val correctedCv = (cv / expectedCv).coerceIn(0.0, 3.0)
        val pass = correctedCv in poissonCvMin..poissonCvMax
        val confidence = computePoissonConfidence(
            cv = correctedCv,
            cvMin = poissonCvMin,
            cvMax = poissonCvMax,
            sampleCount = sampleCount,
            sampleTarget = poissonConfidenceSampleTarget,
            minWeight = poissonConfidenceMinWeight,
        )
        return PoissonDecision(
            cv = correctedCv,
            rawCv = cv,
            pass = pass,
            confidence = confidence,
            warmupReady = warmupReady,
            sampleCount = sampleCount,
        )
    }

    fun computePoissonConfidence(
        cv: Double,
        cvMin: Double,
        cvMax: Double,
        sampleCount: Int = 10,
        sampleTarget: Int = 10,
        minWeight: Double = 0.35,
    ): Double {
        if (cv <= 0.0) return 0.0
        val left = (1.0 - cvMin).coerceAtLeast(0.15)
        val right = (cvMax - 1.0).coerceAtLeast(0.15)
        val tolerance = maxOf(left, right)
        val deviation = kotlin.math.abs(cv - 1.0)
        val base = (1.0 - deviation / tolerance).coerceIn(0.0, 1.0)
        val intervals = (sampleCount - 1).coerceAtLeast(0)
        val target = sampleTarget.coerceAtLeast(1)
        val weight = (intervals.toDouble() / target.toDouble()).coerceIn(minWeight.coerceIn(0.0, 1.0), 1.0)
        return (base * weight).coerceIn(0.0, 1.0)
    }

    fun computeTempSlopeBoost(
        slopeCPerSec: Double,
        thresholdCPerSec: Double,
        boostMax: Double,
        phaseGain: Double = 1.0,
    ): Double {
        val threshold = thresholdCPerSec.coerceAtLeast(1e-4)
        val normalized = (kotlin.math.abs(slopeCPerSec) / threshold).coerceIn(0.0, 1.0)
        return normalized * boostMax.coerceAtLeast(0.0) * phaseGain.coerceAtLeast(0.0)
    }
}
