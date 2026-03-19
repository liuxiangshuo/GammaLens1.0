package com.gammalens.app.camera

/**
 * Centralized detection parameters for easier tuning and exports.
 */
class DetectionConfig {
    var warmupMs: Long = 4_000L
    var cooldownSeconds: Int = 5
    var triggerScore: Int = 70
    var candidateScore: Int = 62
    var confirmScore: Int = 68
    var confirmWindowSeconds: Int = 3
    var confirmMinHits: Int = 2
    var emaAlpha: Double = 0.3
    var fillRatioMin: Double = 1e-6
    var fillRatioMax: Double = 0.40
    var nonZeroMinForContours: Int = 16
    var areaMin: Double = 2.0
    var areaMax: Double = 500.0
    var mog2History: Int = 120
    var mog2VarThreshold: Double = 16.0
    var diffThresholdBase: Double = 20.0
    var adaptiveWindowSize: Int = 60
    var adaptiveDiffMin: Double = 8.0
    var adaptiveDiffMax: Double = 52.0
    var adaptiveQuantile: Double = 0.80
    var adaptiveNoiseScale: Double = 2.4
    var motionEstimateWidth: Int = 160
    var motionEstimateHeight: Int = 90
    var motionCompMaxPx: Double = 36.0
    var branchStrongNonZero: Int = 96
    var branchStableStreak: Int = 2
    var qualityScoreMin: Double = 0.28
    var luminanceFlashThreshold: Double = 180.0
    var dualPairWindowNs: Long = 50_000_000L
    var dualHotspotThreshold: Double = 34.0
    var dualHotspotMaxDistance: Double = 60.0
    var hotPixelAlpha: Double = 0.03
    var hotPixelThreshold: Double = 2.2
    var darkFieldMaxMean: Double = 32.0
    var darkFieldMaxStd: Double = 18.0
    var darkFieldMaxSaturationRatio: Double = 0.0005
    var motionStableMaxPx: Double = 2.4
    var motionStableAvgMaxPx: Double = 1.8
    var calibrationKey: String = "default"
    var calibrationDarkFieldOffset: Double = 0.0
    var calibrationHotPixelGain: Double = 1.0
    var calibrationTempCompensationCoeff: Double = 0.02
    var fusionWeightMog2: Double = 0.40
    var fusionWeightDiff: Double = 0.35
    var fusionWeightShape: Double = 0.25
    var shadedModeMog2Scale: Double = 0.70
    var shadedModeDiffScale: Double = 1.20
    var shadedDisableMog2Score: Boolean = true
    var shadedModeSuppressionSoftOnly: Boolean = true
    var suppressionHardPenaltyThreshold: Double = 0.82
    var suppressionSoftPenaltyScale: Double = 0.35
    var layeredConfirmZ60Min: Double = 1.8
    var layeredConfirmCi60Max: Double = 0.85
    var layeredConfirmMinDelayMs: Long = 1200L
    var classifierProbThreshold: Double = 0.55
    var longWindow30s: Int = 30
    var longWindow60s: Int = 60
    var onlineCalibrationAlpha: Double = 0.12
    var onlineCalibrationMinSamples: Int = 45
    var calibrationStepMaxOffset: Double = 0.6
    var calibrationStepMaxHotGain: Double = 0.10
    var calibrationMedianWindow: Int = 5
    var calibrationFreezeTempLow: Double = 2.0
    var calibrationFreezeTempHigh: Double = 48.0
    var pulseWindowSize: Int = 60
    var pulseActiveNonZeroThreshold: Int = 24
    var pulseDensityMin: Double = 0.08
    var minNeighborhoodConsistency: Double = 0.28
    var minTrackStabilityConfirm: Double = 0.16
    var cusumEnabled: Boolean = true
    var cusumDriftK: Double = 0.18
    var cusumThresholdH: Double = 2.8
    var cusumDecay: Double = 0.94
    var cusumResetOnSuppression: Boolean = true
    var hotPixelMapEnabled: Boolean = true
    var hotPixelMapMinOccurrences: Int = 3
    var hotPixelMapMinFrames: Int = 45
    var hotPixelMapMaxPoints: Int = 256
    var featureLiteEnabled: Boolean = true
    var pulseWidthWindowSize: Int = 40
    var pulseWidthConsistencyMin: Double = 0.45
    var varianceWindowSize: Int = 30
    var varianceRatioMax: Double = 2.8
    var roiWeightEnabled: Boolean = true
    var roiCenterWeight: Double = 1.0
    var roiEdgeWeight: Double = 0.3
    var roiTransitionRatio: Double = 0.7
    var roiStackOrder: String = "post_analysis"
    var frameStackEnabled: Boolean = true
    var frameStackDepth: Int = 5
    var frameStackThreshold: Double = 0.4
    var frameStackAdaptiveEnabled: Boolean = true
    var frameStackDepthMin: Int = 3
    var frameStackDepthMax: Int = 8
    var frameStackNoiseBoost: Double = 0.18
    var frameStackMotionBoost: Double = 0.08
    var poissonCheckEnabled: Boolean = true
    var poissonMinEvents: Int = 4
    var poissonCvMin: Double = 0.5
    var poissonCvMax: Double = 1.8
    var poissonConfidenceEnabled: Boolean = true
    var poissonConfidenceMin: Double = 0.45
    var poissonConfidenceSampleTarget: Int = 10
    var poissonConfidenceMinWeight: Double = 0.35
    var poissonStartupGuardEnabled: Boolean = true
    var poissonStartupMinEvents: Int = 6
    var poissonStartupPenalty: Double = 0.20
    var riskScoreEnabled: Boolean = true
    var riskScoreTriggerHigh: Double = 0.68
    var riskScoreReleaseLow: Double = 0.52
    var riskWeightPoisson: Double = 0.35
    var riskWeightCusum: Double = 0.25
    var riskWeightStability: Double = 0.20
    var riskWeightQuality: Double = 0.20
    var riskPenaltyCusumFail: Double = 0.12
    var riskPenaltyPoissonFail: Double = 0.10
    var riskPenaltyPoissonConfidenceScale: Double = 1.0
    var riskScoreWarmupPenalty: Double = 0.20
    var riskScoreEmaAlpha: Double = 0.35
    var riskCalibrationEnabled: Boolean = true
    var riskCalibrationPoints: List<Pair<Double, Double>> = listOf(
        0.0 to 0.0,
        0.5 to 0.42,
        0.75 to 0.68,
        1.0 to 1.0,
    )
    var deadTimeEnabled: Boolean = true
    var deadTimeMsMin: Double = 70.0
    var deadTimeMsMax: Double = 220.0
    var deadTimeNoiseScale: Double = 0.55
    var deadTimeTempScale: Double = 0.45
    var deadTimeDropTarget: Double = 0.16
    var deadTimeDropFeedbackGain: Double = 0.35
    var deadTimeDropWindowSize: Int = 80
    var deadTimeDropEmaAlpha: Double = 0.2
    var bucketAdaptationEnabled: Boolean = true
    var tempBucketCoolMaxC: Double = 18.0
    var tempBucketWarmMinC: Double = 36.0
    var bucketRiskTriggerDeltaCool: Double = 0.02
    var bucketRiskTriggerDeltaWarm: Double = 0.04
    var bucketPoissonConfidenceDeltaCool: Double = 0.00
    var bucketPoissonConfidenceDeltaWarm: Double = 0.05
    var bucketDeadTimeScaleCool: Double = 1.05
    var bucketDeadTimeScaleWarm: Double = 1.15
    var tempAdaptiveSmoothAlpha: Double = 0.25
    var tempAdaptiveHysteresisC: Double = 1.0
    var startupTempCompEnabled: Boolean = true
    var startupWindowSec: Int = 120
    var startupTempSlopeGain: Double = 1.6
    var steadyTempSlopeGain: Double = 1.0
    var tempSlopeThresholdCPerSec: Double = 0.03
    var tempSlopeBoostMax: Double = 2.0
    var perfWarnProcessMs: Double = 25.0
    var deepModelEnabled: Boolean = false
    var deepModelInputSize: Int = 64
    var deepModelWeight: Double = 0.30
    var deepModelThreshold: Double = 0.52
    var releaseState: String = "baseline"
    var modelVersion: String = "v8-r5-nomodel-prod"
    var calibrationVersion: Int = 1

    fun deepCopy(): DetectionConfig {
        val out = DetectionConfig()
        copyFieldsTo(out)
        out.normalizeInPlace()
        return out
    }

    fun updated(block: (DetectionConfig) -> Unit): DetectionConfig {
        val out = deepCopy()
        block(out)
        out.normalizeInPlace()
        return out
    }

    fun normalizeInPlace() {
        warmupMs = warmupMs.coerceAtLeast(0L)
        cooldownSeconds = cooldownSeconds.coerceAtLeast(0)
        confirmWindowSeconds = confirmWindowSeconds.coerceAtLeast(1)
        confirmMinHits = confirmMinHits.coerceAtLeast(1)
        adaptiveDiffMin = adaptiveDiffMin.coerceAtLeast(1.0)
        adaptiveDiffMax = maxOf(adaptiveDiffMin, adaptiveDiffMax)
        riskScoreTriggerHigh = riskScoreTriggerHigh.coerceIn(0.0, 1.0)
        riskScoreReleaseLow = riskScoreReleaseLow.coerceIn(0.0, riskScoreTriggerHigh)
        poissonConfidenceMin = poissonConfidenceMin.coerceIn(0.0, 1.0)
        poissonCvMin = poissonCvMin.coerceAtLeast(0.0)
        poissonCvMax = maxOf(poissonCvMin, poissonCvMax)
        deadTimeMsMin = deadTimeMsMin.coerceAtLeast(1.0)
        deadTimeMsMax = maxOf(deadTimeMsMin, deadTimeMsMax)
        deadTimeDropWindowSize = deadTimeDropWindowSize.coerceAtLeast(8)
        deadTimeDropEmaAlpha = deadTimeDropEmaAlpha.coerceIn(0.01, 1.0)
        tempAdaptiveSmoothAlpha = tempAdaptiveSmoothAlpha.coerceIn(0.05, 1.0)
        tempAdaptiveHysteresisC = tempAdaptiveHysteresisC.coerceAtLeast(0.0)
        calibrationVersion = calibrationVersion.coerceAtLeast(1)
        if (riskCalibrationPoints.size < 2) {
            riskCalibrationPoints = listOf(0.0 to 0.0, 1.0 to 1.0)
        }
    }

    private fun copyFieldsTo(target: DetectionConfig) {
        target.warmupMs = warmupMs
        target.cooldownSeconds = cooldownSeconds
        target.triggerScore = triggerScore
        target.candidateScore = candidateScore
        target.confirmScore = confirmScore
        target.confirmWindowSeconds = confirmWindowSeconds
        target.confirmMinHits = confirmMinHits
        target.emaAlpha = emaAlpha
        target.fillRatioMin = fillRatioMin
        target.fillRatioMax = fillRatioMax
        target.nonZeroMinForContours = nonZeroMinForContours
        target.areaMin = areaMin
        target.areaMax = areaMax
        target.mog2History = mog2History
        target.mog2VarThreshold = mog2VarThreshold
        target.diffThresholdBase = diffThresholdBase
        target.adaptiveWindowSize = adaptiveWindowSize
        target.adaptiveDiffMin = adaptiveDiffMin
        target.adaptiveDiffMax = adaptiveDiffMax
        target.adaptiveQuantile = adaptiveQuantile
        target.adaptiveNoiseScale = adaptiveNoiseScale
        target.motionEstimateWidth = motionEstimateWidth
        target.motionEstimateHeight = motionEstimateHeight
        target.motionCompMaxPx = motionCompMaxPx
        target.branchStrongNonZero = branchStrongNonZero
        target.branchStableStreak = branchStableStreak
        target.qualityScoreMin = qualityScoreMin
        target.luminanceFlashThreshold = luminanceFlashThreshold
        target.dualPairWindowNs = dualPairWindowNs
        target.dualHotspotThreshold = dualHotspotThreshold
        target.dualHotspotMaxDistance = dualHotspotMaxDistance
        target.hotPixelAlpha = hotPixelAlpha
        target.hotPixelThreshold = hotPixelThreshold
        target.darkFieldMaxMean = darkFieldMaxMean
        target.darkFieldMaxStd = darkFieldMaxStd
        target.darkFieldMaxSaturationRatio = darkFieldMaxSaturationRatio
        target.motionStableMaxPx = motionStableMaxPx
        target.motionStableAvgMaxPx = motionStableAvgMaxPx
        target.calibrationKey = calibrationKey
        target.calibrationDarkFieldOffset = calibrationDarkFieldOffset
        target.calibrationHotPixelGain = calibrationHotPixelGain
        target.calibrationTempCompensationCoeff = calibrationTempCompensationCoeff
        target.fusionWeightMog2 = fusionWeightMog2
        target.fusionWeightDiff = fusionWeightDiff
        target.fusionWeightShape = fusionWeightShape
        target.shadedModeMog2Scale = shadedModeMog2Scale
        target.shadedModeDiffScale = shadedModeDiffScale
        target.shadedDisableMog2Score = shadedDisableMog2Score
        target.shadedModeSuppressionSoftOnly = shadedModeSuppressionSoftOnly
        target.suppressionHardPenaltyThreshold = suppressionHardPenaltyThreshold
        target.suppressionSoftPenaltyScale = suppressionSoftPenaltyScale
        target.layeredConfirmZ60Min = layeredConfirmZ60Min
        target.layeredConfirmCi60Max = layeredConfirmCi60Max
        target.layeredConfirmMinDelayMs = layeredConfirmMinDelayMs
        target.classifierProbThreshold = classifierProbThreshold
        target.longWindow30s = longWindow30s
        target.longWindow60s = longWindow60s
        target.onlineCalibrationAlpha = onlineCalibrationAlpha
        target.onlineCalibrationMinSamples = onlineCalibrationMinSamples
        target.calibrationStepMaxOffset = calibrationStepMaxOffset
        target.calibrationStepMaxHotGain = calibrationStepMaxHotGain
        target.calibrationMedianWindow = calibrationMedianWindow
        target.calibrationFreezeTempLow = calibrationFreezeTempLow
        target.calibrationFreezeTempHigh = calibrationFreezeTempHigh
        target.pulseWindowSize = pulseWindowSize
        target.pulseActiveNonZeroThreshold = pulseActiveNonZeroThreshold
        target.pulseDensityMin = pulseDensityMin
        target.minNeighborhoodConsistency = minNeighborhoodConsistency
        target.minTrackStabilityConfirm = minTrackStabilityConfirm
        target.cusumEnabled = cusumEnabled
        target.cusumDriftK = cusumDriftK
        target.cusumThresholdH = cusumThresholdH
        target.cusumDecay = cusumDecay
        target.cusumResetOnSuppression = cusumResetOnSuppression
        target.hotPixelMapEnabled = hotPixelMapEnabled
        target.hotPixelMapMinOccurrences = hotPixelMapMinOccurrences
        target.hotPixelMapMinFrames = hotPixelMapMinFrames
        target.hotPixelMapMaxPoints = hotPixelMapMaxPoints
        target.featureLiteEnabled = featureLiteEnabled
        target.pulseWidthWindowSize = pulseWidthWindowSize
        target.pulseWidthConsistencyMin = pulseWidthConsistencyMin
        target.varianceWindowSize = varianceWindowSize
        target.varianceRatioMax = varianceRatioMax
        target.roiWeightEnabled = roiWeightEnabled
        target.roiCenterWeight = roiCenterWeight
        target.roiEdgeWeight = roiEdgeWeight
        target.roiTransitionRatio = roiTransitionRatio
        target.roiStackOrder = roiStackOrder
        target.frameStackEnabled = frameStackEnabled
        target.frameStackDepth = frameStackDepth
        target.frameStackThreshold = frameStackThreshold
        target.frameStackAdaptiveEnabled = frameStackAdaptiveEnabled
        target.frameStackDepthMin = frameStackDepthMin
        target.frameStackDepthMax = frameStackDepthMax
        target.frameStackNoiseBoost = frameStackNoiseBoost
        target.frameStackMotionBoost = frameStackMotionBoost
        target.poissonCheckEnabled = poissonCheckEnabled
        target.poissonMinEvents = poissonMinEvents
        target.poissonCvMin = poissonCvMin
        target.poissonCvMax = poissonCvMax
        target.poissonConfidenceEnabled = poissonConfidenceEnabled
        target.poissonConfidenceMin = poissonConfidenceMin
        target.poissonConfidenceSampleTarget = poissonConfidenceSampleTarget
        target.poissonConfidenceMinWeight = poissonConfidenceMinWeight
        target.poissonStartupGuardEnabled = poissonStartupGuardEnabled
        target.poissonStartupMinEvents = poissonStartupMinEvents
        target.poissonStartupPenalty = poissonStartupPenalty
        target.riskScoreEnabled = riskScoreEnabled
        target.riskScoreTriggerHigh = riskScoreTriggerHigh
        target.riskScoreReleaseLow = riskScoreReleaseLow
        target.riskWeightPoisson = riskWeightPoisson
        target.riskWeightCusum = riskWeightCusum
        target.riskWeightStability = riskWeightStability
        target.riskWeightQuality = riskWeightQuality
        target.riskPenaltyCusumFail = riskPenaltyCusumFail
        target.riskPenaltyPoissonFail = riskPenaltyPoissonFail
        target.riskPenaltyPoissonConfidenceScale = riskPenaltyPoissonConfidenceScale
        target.riskScoreWarmupPenalty = riskScoreWarmupPenalty
        target.riskScoreEmaAlpha = riskScoreEmaAlpha
        target.riskCalibrationEnabled = riskCalibrationEnabled
        target.riskCalibrationPoints = riskCalibrationPoints.toList()
        target.deadTimeEnabled = deadTimeEnabled
        target.deadTimeMsMin = deadTimeMsMin
        target.deadTimeMsMax = deadTimeMsMax
        target.deadTimeNoiseScale = deadTimeNoiseScale
        target.deadTimeTempScale = deadTimeTempScale
        target.deadTimeDropTarget = deadTimeDropTarget
        target.deadTimeDropFeedbackGain = deadTimeDropFeedbackGain
        target.deadTimeDropWindowSize = deadTimeDropWindowSize
        target.deadTimeDropEmaAlpha = deadTimeDropEmaAlpha
        target.bucketAdaptationEnabled = bucketAdaptationEnabled
        target.tempBucketCoolMaxC = tempBucketCoolMaxC
        target.tempBucketWarmMinC = tempBucketWarmMinC
        target.bucketRiskTriggerDeltaCool = bucketRiskTriggerDeltaCool
        target.bucketRiskTriggerDeltaWarm = bucketRiskTriggerDeltaWarm
        target.bucketPoissonConfidenceDeltaCool = bucketPoissonConfidenceDeltaCool
        target.bucketPoissonConfidenceDeltaWarm = bucketPoissonConfidenceDeltaWarm
        target.bucketDeadTimeScaleCool = bucketDeadTimeScaleCool
        target.bucketDeadTimeScaleWarm = bucketDeadTimeScaleWarm
        target.tempAdaptiveSmoothAlpha = tempAdaptiveSmoothAlpha
        target.tempAdaptiveHysteresisC = tempAdaptiveHysteresisC
        target.startupTempCompEnabled = startupTempCompEnabled
        target.startupWindowSec = startupWindowSec
        target.startupTempSlopeGain = startupTempSlopeGain
        target.steadyTempSlopeGain = steadyTempSlopeGain
        target.tempSlopeThresholdCPerSec = tempSlopeThresholdCPerSec
        target.tempSlopeBoostMax = tempSlopeBoostMax
        target.perfWarnProcessMs = perfWarnProcessMs
        target.deepModelEnabled = deepModelEnabled
        target.deepModelInputSize = deepModelInputSize
        target.deepModelWeight = deepModelWeight
        target.deepModelThreshold = deepModelThreshold
        target.releaseState = releaseState
        target.modelVersion = modelVersion
        target.calibrationVersion = calibrationVersion
    }

    companion object {
        @JvmStatic
        fun defaultInstance(): DetectionConfig = DetectionConfig().apply { normalizeInPlace() }
    }
}

