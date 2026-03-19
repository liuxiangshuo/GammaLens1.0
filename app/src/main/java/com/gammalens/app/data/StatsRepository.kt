package com.gammalens.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for live stats and dual status.
 * Updated at 1Hz from the pipeline; UI subscribes via StateFlow.
 */
class StatsRepository {

    private val _liveStats = MutableStateFlow(LiveStatsUiState())
    val liveStats: StateFlow<LiveStatsUiState> = _liveStats.asStateFlow()

    private val _dualStatus = MutableStateFlow(DualStatusUiState())
    val dualStatus: StateFlow<DualStatusUiState> = _dualStatus.asStateFlow()

    fun updateLiveStats(
        scoreEma: Double,
        events: Int,
        rate60s: Int,
        lastAgoSec: Int,
        cooldownLeftSec: Int,
        fps: Float,
        processMsAvg: Double,
        detectionMode: String,
        mog2NonZero: Int,
        diffNonZero: Int,
        fusedNonZero: Int,
        darkFieldReady: Boolean,
        motionStable: Boolean,
        reliability: String,
        significanceInstant: Double,
        significanceMean30s: Double,
        significanceMean60s: Double,
        significanceCi30s: Double,
        significanceCi60s: Double,
        stabilityLevel: String,
        classifierProbability: Double,
        cusumScore: Double,
        cusumPass: Boolean,
        diffContribution: Double,
        mog2Contribution: Double,
        fusionDecisionPath: String,
        suppressionReason: String,
        hotPixelMapHitCount: Int,
        hotPixelMapSize: Int,
        pulseWidthConsistency: Double,
        shortWindowVarianceRatio: Double,
        roiEdgeSuppressedCount: Int,
        stackedNonZeroCount: Int,
        poissonCv: Double,
        poissonPass: Boolean,
        poissonConfidence: Double,
        riskScore: Double,
        riskTriggerState: Boolean,
        tempSlopeCPerSec: Double,
        tempCompPhase: String,
        deadTimeMsUsed: Double,
        pulseAcceptedCount: Int,
        pulseDroppedByDeadTime: Int,
        stackDepthUsed: Int,
        stackThresholdUsed: Double,
        poissonSampleCount: Int,
        poissonWarmupReady: Boolean,
        roiAppliedStage: String,
        systemStatusMessage: String,
        systemStatusLevel: String,
        captureSize: String,
        fpsTarget: Int,
    ) {
        val riskLevel = when {
            scoreEma >= 70 -> 2
            scoreEma >= 40 -> 1
            else -> 0
        }
        _liveStats.value = LiveStatsUiState(
            scoreEma = scoreEma,
            riskLevel = riskLevel,
            events = events,
            rate60s = rate60s,
            lastAgoSec = lastAgoSec,
            cooldownLeftSec = cooldownLeftSec,
            fps = fps,
            processMsAvg = processMsAvg,
            detectionMode = detectionMode,
            mog2NonZero = mog2NonZero,
            diffNonZero = diffNonZero,
            fusedNonZero = fusedNonZero,
            darkFieldReady = darkFieldReady,
            motionStable = motionStable,
            reliability = reliability,
            significanceInstant = significanceInstant,
            significanceMean30s = significanceMean30s,
            significanceMean60s = significanceMean60s,
            significanceCi30s = significanceCi30s,
            significanceCi60s = significanceCi60s,
            stabilityLevel = stabilityLevel,
            classifierProbability = classifierProbability,
            cusumScore = cusumScore,
            cusumPass = cusumPass,
            diffContribution = diffContribution,
            mog2Contribution = mog2Contribution,
            fusionDecisionPath = fusionDecisionPath,
            suppressionReason = suppressionReason,
            hotPixelMapHitCount = hotPixelMapHitCount,
            hotPixelMapSize = hotPixelMapSize,
            pulseWidthConsistency = pulseWidthConsistency,
            shortWindowVarianceRatio = shortWindowVarianceRatio,
            roiEdgeSuppressedCount = roiEdgeSuppressedCount,
            stackedNonZeroCount = stackedNonZeroCount,
            poissonCv = poissonCv,
            poissonPass = poissonPass,
            poissonConfidence = poissonConfidence,
            riskScore = riskScore,
            riskTriggerState = riskTriggerState,
            tempSlopeCPerSec = tempSlopeCPerSec,
            tempCompPhase = tempCompPhase,
            deadTimeMsUsed = deadTimeMsUsed,
            pulseAcceptedCount = pulseAcceptedCount,
            pulseDroppedByDeadTime = pulseDroppedByDeadTime,
            stackDepthUsed = stackDepthUsed,
            stackThresholdUsed = stackThresholdUsed,
            poissonSampleCount = poissonSampleCount,
            poissonWarmupReady = poissonWarmupReady,
            roiAppliedStage = roiAppliedStage,
            systemStatusMessage = systemStatusMessage,
            systemStatusLevel = systemStatusLevel,
            captureSize = captureSize,
            fpsTarget = fpsTarget,
        )
    }

    fun updateDualStatus(
        dualActive: Boolean,
        enablePairing: Boolean,
        pairMade: Boolean?,
        deltaNs: Long?,
        suppressionActive: Boolean,
        fallback: Boolean,
    ) {
        _dualStatus.value = DualStatusUiState(
            dualActive = dualActive,
            enablePairing = enablePairing,
            pairMade = pairMade,
            deltaNs = deltaNs,
            suppressionActive = suppressionActive,
            fallback = fallback,
        )
    }
}
