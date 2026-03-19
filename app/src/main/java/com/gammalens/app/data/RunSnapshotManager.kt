package com.gammalens.app.data

import android.content.Context
import android.util.Log
import com.gammalens.app.BuildConfig
import com.gammalens.app.camera.DetectionConfig
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Manages per-session run snapshot: params.json, events.csv, summary.json, debug.txt.
 * Create on pipeline start; append events during run; call endSession on pipeline stop.
 */
class RunSnapshotManager(private val context: Context) {

    private var runDir: File? = null
    private var sessionStartMs: Long = 0L
    private var eventsCsvHeaderWritten = false
    private var runtimeTimeseriesHeaderWritten = false
    private val runtimeTimeseriesBuffer = ArrayDeque<String>()
    private var runtimeTimeseriesLastFlushMs = 0L
    private var eventCountThisSession = 0
    private var runtimeMetrics = JSONObject()

    companion object {
        private const val TAG = "RunSnapshot"
        private const val RUNS_DIR = "runs"
        private const val DEBUG_LINES_N = 30
        private const val RUNTIME_TS_FLUSH_LINES = 20
        private const val RUNTIME_TS_FLUSH_INTERVAL_MS = 2_000L

        // Detection constants (mirror FrameProcessor / FrameSynchronizer for snapshot only)
        private const val GRAYSCALE_THRESHOLD = 200.0
        private const val AREA_MIN = 2.0
        private const val AREA_MAX = 500.0
        private const val COOLDOWN_MS = 5000
        private const val FILL_RATIO_LO = 1e-6
        private const val FILL_RATIO_HI = 0.40
        private const val NONZERO_MIN_FOR_CONTOURS = 16
        private const val TRIGGER_BLOB_COUNT = 30
        private const val TRIGGER_CONSEC_SECONDS = 2
        private const val TRIGGER_SCORE = 70
        private const val EMA_ALPHA = 0.3
        private const val MOG2_HISTORY = 120
        private const val MOG2_VAR_THRESHOLD = 16.0
        private const val MOG2_LEARNING_RATE = -1.0
        private const val PAIR_WINDOW_NS = 50_000_000L
        private const val LUMINANCE_FLASH_THRESHOLD = 180.0
    }

    /**
     * Create run folder and write params.json. Call when measurement session starts.
     */
    fun startSession(
        deviceProfile: DeviceProfile,
        modeLabel: String,
        dualActive: Boolean,
        detectionMode: String,
        detectionConfig: DetectionConfig,
        scenarioId: String = "unknown",
        experimentId: String = "default",
        variantId: String = "default",
        releaseState: String = "baseline",
        modelVersion: String = "v8-r3-nomodel-prod",
        exposureTimeNs: Long = 100_000_000L,
        iso: Int? = null,
        aeLock: Boolean = true,
        awbLock: Boolean = true,
        afLock: Boolean = true,
    ): File {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val runName = "run_${dateFormat.format(Date())}"
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(File(baseDir, RUNS_DIR), runName)
        if (!dir.exists()) dir.mkdirs()
        runDir = dir
        sessionStartMs = System.currentTimeMillis()

        val params = buildParamsJson(
            deviceProfile = deviceProfile,
            modeLabel = modeLabel,
            dualActive = dualActive,
            detectionMode = detectionMode,
            detectionConfig = detectionConfig,
            scenarioId = scenarioId,
            experimentId = experimentId,
            variantId = variantId,
            releaseState = releaseState,
            modelVersion = modelVersion,
            exposureTimeNs = exposureTimeNs,
            iso = iso,
            aeLock = aeLock,
            awbLock = awbLock,
            afLock = afLock,
        )
        val paramsFile = File(dir, "params.json")
        paramsFile.writeText(params.toString(2))
        Log.i(TAG, "Run started: ${dir.absolutePath} params.json written")

        eventsCsvHeaderWritten = false
        runtimeTimeseriesHeaderWritten = false
        runtimeTimeseriesBuffer.clear()
        runtimeTimeseriesLastFlushMs = System.currentTimeMillis()
        eventCountThisSession = 0
        runtimeMetrics = JSONObject()
        return dir
    }

    private fun buildParamsJson(
        deviceProfile: DeviceProfile,
        modeLabel: String,
        dualActive: Boolean,
        detectionMode: String,
        detectionConfig: DetectionConfig,
        scenarioId: String,
        experimentId: String,
        variantId: String,
        releaseState: String,
        modelVersion: String,
        exposureTimeNs: Long,
        iso: Int?,
        aeLock: Boolean,
        awbLock: Boolean,
        afLock: Boolean,
    ): JSONObject {
        val app = JSONObject().apply {
            put("versionName", BuildConfig.VERSION_NAME ?: "?")
            put("versionCode", BuildConfig.VERSION_CODE)
            put("gitCommit", BuildConfig.GIT_COMMIT_HASH?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        }
        val device = JSONObject().apply {
            put("model", android.os.Build.MODEL ?: "")
            put("androidVersion", android.os.Build.VERSION.RELEASE ?: "")
        }
        val camera = JSONObject().apply {
            put("activeCameraIds", deviceProfile.cameraId)
            put("resolution", deviceProfile.captureSize)
            put("fpsTarget", deviceProfile.fpsTarget)
            put("exposureTimeNs", exposureTimeNs)
            put("iso", iso ?: JSONObject.NULL)
            put("aeLock", aeLock)
            put("awbLock", awbLock)
            put("afLock", afLock)
        }
        val detection = JSONObject().apply {
            put("mode", detectionMode)
            put("warmupMs", detectionConfig.warmupMs)
            put("grayscaleThreshold", GRAYSCALE_THRESHOLD)
            put("areaMin", AREA_MIN)
            put("areaMax", AREA_MAX)
            put("cooldownMs", COOLDOWN_MS)
            put("fillRatioGuardLo", FILL_RATIO_LO)
            put("fillRatioGuardHi", FILL_RATIO_HI)
            put("nonZeroMinForContours", NONZERO_MIN_FOR_CONTOURS)
            put("triggerBlobCount", TRIGGER_BLOB_COUNT)
            put("triggerConsecSeconds", TRIGGER_CONSEC_SECONDS)
            put("triggerScore", TRIGGER_SCORE)
            put("emaAlpha", EMA_ALPHA)
            put("candidateScore", detectionConfig.candidateScore)
            put("confirmScore", detectionConfig.confirmScore)
            put("confirmWindowSeconds", detectionConfig.confirmWindowSeconds)
            put("confirmMinHits", detectionConfig.confirmMinHits)
            put("classifierProbThreshold", detectionConfig.classifierProbThreshold)
            put("longWindow30s", detectionConfig.longWindow30s)
            put("longWindow60s", detectionConfig.longWindow60s)
            put("onlineCalibrationAlpha", detectionConfig.onlineCalibrationAlpha)
            put("onlineCalibrationMinSamples", detectionConfig.onlineCalibrationMinSamples)
            put("shadedModeMog2Scale", detectionConfig.shadedModeMog2Scale)
            put("shadedModeDiffScale", detectionConfig.shadedModeDiffScale)
            put("shadedDisableMog2Score", detectionConfig.shadedDisableMog2Score)
            put("shadedModeSuppressionSoftOnly", detectionConfig.shadedModeSuppressionSoftOnly)
            put("suppressionHardPenaltyThreshold", detectionConfig.suppressionHardPenaltyThreshold)
            put("suppressionSoftPenaltyScale", detectionConfig.suppressionSoftPenaltyScale)
            put("layeredConfirmZ60Min", detectionConfig.layeredConfirmZ60Min)
            put("layeredConfirmCi60Max", detectionConfig.layeredConfirmCi60Max)
            put("layeredConfirmMinDelayMs", detectionConfig.layeredConfirmMinDelayMs)
            put("calibrationStepMaxOffset", detectionConfig.calibrationStepMaxOffset)
            put("calibrationStepMaxHotGain", detectionConfig.calibrationStepMaxHotGain)
            put("calibrationMedianWindow", detectionConfig.calibrationMedianWindow)
            put("calibrationFreezeTempLow", detectionConfig.calibrationFreezeTempLow)
            put("calibrationFreezeTempHigh", detectionConfig.calibrationFreezeTempHigh)
            put("pulseWindowSize", detectionConfig.pulseWindowSize)
            put("pulseActiveNonZeroThreshold", detectionConfig.pulseActiveNonZeroThreshold)
            put("pulseDensityMin", detectionConfig.pulseDensityMin)
            put("minNeighborhoodConsistency", detectionConfig.minNeighborhoodConsistency)
            put("minTrackStabilityConfirm", detectionConfig.minTrackStabilityConfirm)
            put("cusumEnabled", detectionConfig.cusumEnabled)
            put("cusumDriftK", detectionConfig.cusumDriftK)
            put("cusumThresholdH", detectionConfig.cusumThresholdH)
            put("cusumDecay", detectionConfig.cusumDecay)
            put("cusumResetOnSuppression", detectionConfig.cusumResetOnSuppression)
            put("hotPixelMapEnabled", detectionConfig.hotPixelMapEnabled)
            put("hotPixelMapMinOccurrences", detectionConfig.hotPixelMapMinOccurrences)
            put("hotPixelMapMinFrames", detectionConfig.hotPixelMapMinFrames)
            put("hotPixelMapMaxPoints", detectionConfig.hotPixelMapMaxPoints)
            put("featureLiteEnabled", detectionConfig.featureLiteEnabled)
            put("pulseWidthWindowSize", detectionConfig.pulseWidthWindowSize)
            put("pulseWidthConsistencyMin", detectionConfig.pulseWidthConsistencyMin)
            put("varianceWindowSize", detectionConfig.varianceWindowSize)
            put("varianceRatioMax", detectionConfig.varianceRatioMax)
            put("roiWeightEnabled", detectionConfig.roiWeightEnabled)
            put("roiCenterWeight", detectionConfig.roiCenterWeight)
            put("roiEdgeWeight", detectionConfig.roiEdgeWeight)
            put("roiTransitionRatio", detectionConfig.roiTransitionRatio)
            put("roiStackOrder", detectionConfig.roiStackOrder)
            put("frameStackEnabled", detectionConfig.frameStackEnabled)
            put("frameStackDepth", detectionConfig.frameStackDepth)
            put("frameStackThreshold", detectionConfig.frameStackThreshold)
            put("frameStackAdaptiveEnabled", detectionConfig.frameStackAdaptiveEnabled)
            put("frameStackDepthMin", detectionConfig.frameStackDepthMin)
            put("frameStackDepthMax", detectionConfig.frameStackDepthMax)
            put("frameStackNoiseBoost", detectionConfig.frameStackNoiseBoost)
            put("frameStackMotionBoost", detectionConfig.frameStackMotionBoost)
            put("poissonCheckEnabled", detectionConfig.poissonCheckEnabled)
            put("poissonMinEvents", detectionConfig.poissonMinEvents)
            put("poissonCvMin", detectionConfig.poissonCvMin)
            put("poissonCvMax", detectionConfig.poissonCvMax)
            put("poissonConfidenceEnabled", detectionConfig.poissonConfidenceEnabled)
            put("poissonConfidenceMin", detectionConfig.poissonConfidenceMin)
            put("poissonConfidenceSampleTarget", detectionConfig.poissonConfidenceSampleTarget)
            put("poissonConfidenceMinWeight", detectionConfig.poissonConfidenceMinWeight)
            put("poissonStartupGuardEnabled", detectionConfig.poissonStartupGuardEnabled)
            put("poissonStartupMinEvents", detectionConfig.poissonStartupMinEvents)
            put("poissonStartupPenalty", detectionConfig.poissonStartupPenalty)
            put("riskScoreEnabled", detectionConfig.riskScoreEnabled)
            put("riskScoreTriggerHigh", detectionConfig.riskScoreTriggerHigh)
            put("riskScoreReleaseLow", detectionConfig.riskScoreReleaseLow)
            put("riskWeightPoisson", detectionConfig.riskWeightPoisson)
            put("riskWeightCusum", detectionConfig.riskWeightCusum)
            put("riskWeightStability", detectionConfig.riskWeightStability)
            put("riskWeightQuality", detectionConfig.riskWeightQuality)
            put("riskPenaltyCusumFail", detectionConfig.riskPenaltyCusumFail)
            put("riskPenaltyPoissonFail", detectionConfig.riskPenaltyPoissonFail)
            put("riskPenaltyPoissonConfidenceScale", detectionConfig.riskPenaltyPoissonConfidenceScale)
            put("riskScoreWarmupPenalty", detectionConfig.riskScoreWarmupPenalty)
            put("riskScoreEmaAlpha", detectionConfig.riskScoreEmaAlpha)
            put("riskCalibrationEnabled", detectionConfig.riskCalibrationEnabled)
            put(
                "riskCalibrationPoints",
                detectionConfig.riskCalibrationPoints.map { JSONObject().apply { put("x", it.first); put("y", it.second) } }
            )
            put("deadTimeEnabled", detectionConfig.deadTimeEnabled)
            put("deadTimeMsMin", detectionConfig.deadTimeMsMin)
            put("deadTimeMsMax", detectionConfig.deadTimeMsMax)
            put("deadTimeNoiseScale", detectionConfig.deadTimeNoiseScale)
            put("deadTimeTempScale", detectionConfig.deadTimeTempScale)
            put("deadTimeDropTarget", detectionConfig.deadTimeDropTarget)
            put("deadTimeDropFeedbackGain", detectionConfig.deadTimeDropFeedbackGain)
            put("deadTimeDropWindowSize", detectionConfig.deadTimeDropWindowSize)
            put("deadTimeDropEmaAlpha", detectionConfig.deadTimeDropEmaAlpha)
            put("bucketAdaptationEnabled", detectionConfig.bucketAdaptationEnabled)
            put("tempBucketCoolMaxC", detectionConfig.tempBucketCoolMaxC)
            put("tempBucketWarmMinC", detectionConfig.tempBucketWarmMinC)
            put("bucketRiskTriggerDeltaCool", detectionConfig.bucketRiskTriggerDeltaCool)
            put("bucketRiskTriggerDeltaWarm", detectionConfig.bucketRiskTriggerDeltaWarm)
            put("bucketPoissonConfidenceDeltaCool", detectionConfig.bucketPoissonConfidenceDeltaCool)
            put("bucketPoissonConfidenceDeltaWarm", detectionConfig.bucketPoissonConfidenceDeltaWarm)
            put("bucketDeadTimeScaleCool", detectionConfig.bucketDeadTimeScaleCool)
            put("bucketDeadTimeScaleWarm", detectionConfig.bucketDeadTimeScaleWarm)
            put("tempAdaptiveSmoothAlpha", detectionConfig.tempAdaptiveSmoothAlpha)
            put("tempAdaptiveHysteresisC", detectionConfig.tempAdaptiveHysteresisC)
            put("startupTempCompEnabled", detectionConfig.startupTempCompEnabled)
            put("startupWindowSec", detectionConfig.startupWindowSec)
            put("startupTempSlopeGain", detectionConfig.startupTempSlopeGain)
            put("steadyTempSlopeGain", detectionConfig.steadyTempSlopeGain)
            put("tempSlopeThresholdCPerSec", detectionConfig.tempSlopeThresholdCPerSec)
            put("tempSlopeBoostMax", detectionConfig.tempSlopeBoostMax)
            put("perfWarnProcessMs", detectionConfig.perfWarnProcessMs)
            put("deepModelEnabled", detectionConfig.deepModelEnabled)
            put("deepModelInputSize", detectionConfig.deepModelInputSize)
            put("deepModelWeight", detectionConfig.deepModelWeight)
            put("deepModelThreshold", detectionConfig.deepModelThreshold)
            put("adaptive", JSONObject().apply {
                put("windowSize", detectionConfig.adaptiveWindowSize)
                put("quantile", detectionConfig.adaptiveQuantile)
                put("noiseScale", detectionConfig.adaptiveNoiseScale)
                put("min", detectionConfig.adaptiveDiffMin)
                put("max", detectionConfig.adaptiveDiffMax)
            })
            put("hotPixelSuppression", JSONObject().apply {
                put("alpha", detectionConfig.hotPixelAlpha)
                put("threshold", detectionConfig.hotPixelThreshold)
            })
            put("deviceCalibration", JSONObject().apply {
                put("key", detectionConfig.calibrationKey)
                put("darkFieldOffset", detectionConfig.calibrationDarkFieldOffset)
                put("hotPixelGain", detectionConfig.calibrationHotPixelGain)
                put("tempCompensationCoeff", detectionConfig.calibrationTempCompensationCoeff)
                put("version", detectionConfig.calibrationVersion)
            })
            put("mog2", JSONObject().apply {
                put("history", MOG2_HISTORY)
                put("varThreshold", MOG2_VAR_THRESHOLD)
                put("learningRatePolicy", MOG2_LEARNING_RATE)
            })
            put("dualPairing", JSONObject().apply {
                put("pairWindowNs", PAIR_WINDOW_NS)
                put("enablePairing", dualActive)
            })
            put("suppression", JSONObject().apply {
                put("flashThreshold", LUMINANCE_FLASH_THRESHOLD)
                put("otherHadFlashSuppresses", true)
            })
        }
        return JSONObject().apply {
            put("app", app)
            put("device", device)
            put("camera", camera)
            put("modeLabel", modeLabel)
            put("scenarioId", scenarioId)
            put("experimentId", experimentId)
            put("variantId", variantId)
            put("releaseState", releaseState)
            put("modelVersion", modelVersion)
            put("detection", detection)
        }
    }

    fun setRuntimeMetrics(
        detectionMode: String,
        fps: Float,
        processMsAvg: Double,
        candidateCount: Int,
        suppressedCount: Int,
        mog2NonZero: Int,
        diffNonZero: Int,
        fusedNonZero: Int,
        adaptiveDiffThreshold: Double,
        motionDx: Double,
        motionDy: Double,
        qualityScore: Double,
        warmupActive: Boolean,
        noiseQuantile: Double,
        hotPixelSuppressedCount: Int,
        trackStability: Double,
        confirmScore: Double,
        pairPenalty: Double,
        darkFieldReady: Boolean,
        motionStable: Boolean,
        reliability: String,
        reliabilityReliableSamples: Int,
        reliabilityLimitedSamples: Int,
        reliabilityPoorSamples: Int,
        significanceZ: Double,
        baselineMean: Double,
        baselineStd: Double,
        peakIntensity: Double,
        localContrast: Double,
        eventFeatureScore: Double,
        significanceMean30s: Double,
        significanceMean60s: Double,
        significanceCi30s: Double,
        significanceCi60s: Double,
        stabilityLevel: String,
        classifierProbability: Double,
        sustainedFrames: Int,
        trajectoryLength: Double,
        peakStability: Double,
        tempCompensationTerm: Double,
        temperatureC: Double,
        calibrationVersion: Int,
        diffContribution: Double,
        mog2Contribution: Double,
        fusionDecisionPath: String,
        suppressionApplied: Boolean,
        suppressionReason: String,
        significanceMean60sLayered: Double,
        significanceCi60sLayered: Double,
        calibrationDeltaOffset: Double,
        calibrationDeltaHotGain: Double,
        calibrationRollbackSuggested: Boolean,
        temperatureBucket: String,
        scenarioId: String,
        experimentId: String,
        variantId: String,
        releaseState: String,
        modelVersion: String,
        rollbackReason: String,
        pulseDensity: Double,
        neighborhoodConsistency: Double,
        deepModelProbability: Double,
        cusumScore: Double,
        cusumPass: Boolean,
        hotPixelMapBucket: String,
        hotPixelMapSize: Int,
        hotPixelMapHitCount: Int,
        pulseWidthConsistency: Double,
        shortWindowVarianceRatio: Double,
        roiEdgeSuppressedCount: Int,
        stackedNonZeroCount: Int,
        poissonCv: Double,
        poissonRawCv: Double,
        poissonCorrectedCv: Double,
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
        funnelCandidateCount: Int,
        funnelSafetyPassCount: Int,
        funnelRiskPassCount: Int,
        funnelConfirmedCount: Int,
        funnelSuppressedCount: Int,
    ) {
        val totalSamples = (reliabilityReliableSamples + reliabilityLimitedSamples + reliabilityPoorSamples).coerceAtLeast(1)
        val unreliableRatio = reliabilityPoorSamples.toDouble() / totalSamples.toDouble()
        runtimeMetrics = JSONObject().apply {
            put("detectionMode", detectionMode)
            put("fps", fps.toDouble())
            put("processMsAvg", processMsAvg)
            put("candidateCount", candidateCount)
            put("suppressedCount", suppressedCount)
            put("mog2NonZero", mog2NonZero)
            put("diffNonZero", diffNonZero)
            put("fusedNonZero", fusedNonZero)
            put("adaptiveDiffThreshold", adaptiveDiffThreshold)
            put("motionDx", motionDx)
            put("motionDy", motionDy)
            put("qualityScore", qualityScore)
            put("warmupActive", warmupActive)
            put("noiseQuantile", noiseQuantile)
            put("hotPixelSuppressedCount", hotPixelSuppressedCount)
            put("trackStability", trackStability)
            put("confirmScore", confirmScore)
            put("pairPenalty", pairPenalty)
            put("darkFieldReady", darkFieldReady)
            put("motionStable", motionStable)
            put("measurementReliability", reliability)
            put("significanceZ", significanceZ)
            put("baselineMean", baselineMean)
            put("baselineStd", baselineStd)
            put("peakIntensity", peakIntensity)
            put("localContrast", localContrast)
            put("eventFeatureScore", eventFeatureScore)
            put("significanceMean30s", significanceMean30s)
            put("significanceMean60s", significanceMean60s)
            put("significanceCi30s", significanceCi30s)
            put("significanceCi60s", significanceCi60s)
            put("stabilityLevel", stabilityLevel)
            put("classifierProbability", classifierProbability)
            put("sustainedFrames", sustainedFrames)
            put("trajectoryLength", trajectoryLength)
            put("peakStability", peakStability)
            put("tempCompensationTerm", tempCompensationTerm)
            put("temperatureC", temperatureC)
            put("calibrationVersion", calibrationVersion)
            put("diffContribution", diffContribution)
            put("mog2Contribution", mog2Contribution)
            put("fusionDecisionPath", fusionDecisionPath)
            put("suppressionApplied", suppressionApplied)
            put("suppressionReason", suppressionReason)
            put("layeredConfirm", JSONObject().apply {
                put("significanceMean60s", significanceMean60sLayered)
                put("significanceCi60s", significanceCi60sLayered)
            })
            put("calibrationDelta", JSONObject().apply {
                put("offset", calibrationDeltaOffset)
                put("hotPixelGain", calibrationDeltaHotGain)
            })
            put("rollbackSuggested", calibrationRollbackSuggested)
            put("temperatureBucket", temperatureBucket)
            put("scenarioId", scenarioId)
            put("experimentId", experimentId)
            put("variantId", variantId)
            put("releaseState", releaseState)
            put("modelVersion", modelVersion)
            put("rollbackReason", rollbackReason)
            put("pulseDensity", pulseDensity)
            put("neighborhoodConsistency", neighborhoodConsistency)
            put("deepModelProbability", deepModelProbability)
            put("cusumScore", cusumScore)
            put("cusumPass", cusumPass)
            put("hotPixelMapBucket", hotPixelMapBucket)
            put("hotPixelMapSize", hotPixelMapSize)
            put("hotPixelMapHitCount", hotPixelMapHitCount)
            put("pulseWidthConsistency", pulseWidthConsistency)
            put("shortWindowVarianceRatio", shortWindowVarianceRatio)
            put("roiEdgeSuppressedCount", roiEdgeSuppressedCount)
            put("stackedNonZeroCount", stackedNonZeroCount)
            put("poissonCv", poissonCv)
            put("poissonRawCv", poissonRawCv)
            put("poissonCorrectedCv", poissonCorrectedCv)
            put("poissonPass", poissonPass)
            put("poissonConfidence", poissonConfidence)
            put("riskScore", riskScore)
            put("riskTriggerState", riskTriggerState)
            put("tempSlopeCPerSec", tempSlopeCPerSec)
            put("tempCompPhase", tempCompPhase)
            put("deadTimeMsUsed", deadTimeMsUsed)
            put("pulseAcceptedCount", pulseAcceptedCount)
            put("pulseDroppedByDeadTime", pulseDroppedByDeadTime)
            put("stackDepthUsed", stackDepthUsed)
            put("stackThresholdUsed", stackThresholdUsed)
            put("poissonSampleCount", poissonSampleCount)
            put("poissonWarmupReady", poissonWarmupReady)
            put("roiAppliedStage", roiAppliedStage)
            put("funnelCandidateCount", funnelCandidateCount)
            put("funnelSafetyPassCount", funnelSafetyPassCount)
            put("funnelRiskPassCount", funnelRiskPassCount)
            put("funnelConfirmedCount", funnelConfirmedCount)
            put("funnelSuppressedCount", funnelSuppressedCount)
            put("reliabilitySamples", JSONObject().apply {
                put("reliable", reliabilityReliableSamples)
                put("limited", reliabilityLimitedSamples)
                put("poor", reliabilityPoorSamples)
                put("poorRatio", unreliableRatio)
            })
            put("fusionWeights", JSONObject().apply {
                put("mog2", 0.40)
                put("diff", 0.35)
                put("shape", 0.25)
            })
            put("verificationScenes", listOf("static_weak_signal", "hand_shake_no_target", "strong_reflection"))
            put("acceptanceChecklist", JSONObject().apply {
                put("scene_dark_static", "required")
                put("scene_dark_light_motion", "required")
                put("scene_ambient_light", "required")
                put("target_false_positive_drop", ">=12%")
                put("target_recall_improvement", ">=2%")
                put("target_perf_budget", "processMsAvg<=+8%")
            })
        }
    }

    /**
     * Append one event to events.csv. Call from MainActivity when event is added to EventRepository.
     */
    fun appendEvent(item: EventItem) {
        val dir = runDir ?: return
        val file = File(dir, "events.csv")
        if (!eventsCsvHeaderWritten) {
            file.appendText("timestampMs,streamId,scoreEma,blobCount,maxArea,nonZeroCount,suppressed,suppressReason,dualSnapshot\n")
            eventsCsvHeaderWritten = true
        }
        val line = listOf(
            item.timestampMs,
            escapeCsv(item.streamId),
            item.scoreEma,
            item.blobCount,
            item.maxArea,
            item.nonZeroCount,
            item.suppressed,
            escapeCsv(item.suppressReason ?: ""),
            escapeCsv(item.dualSnapshot ?: ""),
        ).joinToString(",") + "\n"
        file.appendText(line)
        eventCountThisSession++
    }

    fun appendRuntimeTimeseries(
        timestampMs: Long,
        riskScore: Double,
        riskTriggerState: Boolean,
        poissonConfidence: Double,
        poissonRawCv: Double,
        poissonCorrectedCv: Double,
        poissonSampleCount: Int,
        deadTimeMsUsed: Double,
        tempCompPhase: String,
        warmupActive: Boolean,
        temperatureBucket: String,
        fps: Float,
        processMsAvg: Double,
        measurementReliability: String,
    ) {
        val dir = runDir ?: return
        val file = File(dir, "runtime_timeseries.csv")
        if (!runtimeTimeseriesHeaderWritten) {
            file.appendText("timestampMs,riskScore,riskTriggerState,poissonConfidence,poissonRawCv,poissonCorrectedCv,poissonSampleCount,deadTimeMsUsed,tempCompPhase,warmupActive,temperatureBucket,fps,processMsAvg,measurementReliability\n")
            runtimeTimeseriesHeaderWritten = true
        }
        val line = listOf(
            timestampMs,
            String.format(Locale.US, "%.4f", riskScore),
            riskTriggerState,
            String.format(Locale.US, "%.4f", poissonConfidence),
            String.format(Locale.US, "%.4f", poissonRawCv),
            String.format(Locale.US, "%.4f", poissonCorrectedCv),
            poissonSampleCount,
            String.format(Locale.US, "%.2f", deadTimeMsUsed),
            escapeCsv(tempCompPhase),
            warmupActive,
            escapeCsv(temperatureBucket),
            String.format(Locale.US, "%.2f", fps.toDouble()),
            String.format(Locale.US, "%.2f", processMsAvg),
            escapeCsv(measurementReliability),
        ).joinToString(",") + "\n"
        runtimeTimeseriesBuffer.addLast(line)
        val now = System.currentTimeMillis()
        val shouldFlushBySize = runtimeTimeseriesBuffer.size >= RUNTIME_TS_FLUSH_LINES
        val shouldFlushByTime = now - runtimeTimeseriesLastFlushMs >= RUNTIME_TS_FLUSH_INTERVAL_MS
        if (shouldFlushBySize || shouldFlushByTime) {
            flushRuntimeTimeseriesBuffer(file)
            runtimeTimeseriesLastFlushMs = now
        }
    }

    private fun flushRuntimeTimeseriesBuffer(file: File) {
        if (runtimeTimeseriesBuffer.isEmpty()) return
        val sb = StringBuilder()
        while (runtimeTimeseriesBuffer.isNotEmpty()) {
            sb.append(runtimeTimeseriesBuffer.removeFirst())
        }
        file.appendText(sb.toString())
    }

    private fun escapeCsv(s: String): String {
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return s
    }

    /**
     * Write summary.json and optional debug.txt. Call when measurement session stops.
     */
    fun endSession(debugLogRepository: DebugLogRepository?) {
        val dir = runDir ?: return
        val timeseriesFile = File(dir, "runtime_timeseries.csv")
        flushRuntimeTimeseriesBuffer(timeseriesFile)
        val endMs = System.currentTimeMillis()
        val durationMs = (endMs - sessionStartMs).coerceAtLeast(0)
        val durationMin = durationMs / 60_000f
        val meanRate60s = if (durationMin > 0) eventCountThisSession / durationMin else 0f

        val summary = JSONObject().apply {
            put("sessionStartMs", sessionStartMs)
            put("sessionEndMs", endMs)
            put("durationMs", durationMs)
            put("totalEvents", eventCountThisSession)
            put("meanRate60s", meanRate60s.toDouble())
            put("runtimeMetrics", runtimeMetrics)
            put("tempRange", JSONObject.NULL)
        }
        File(dir, "summary.json").writeText(summary.toString(2))
        Log.i(TAG, "Run ended: summary.json written")

        if (debugLogRepository != null) {
            val sb = StringBuilder()
            sb.appendLine("=== GL_SYNC (last $DEBUG_LINES_N) ===")
            debugLogRepository.getGlSyncLinesForExport(DEBUG_LINES_N).forEach { sb.appendLine(it) }
            sb.appendLine("=== GL_EVT (last $DEBUG_LINES_N) ===")
            debugLogRepository.getGlEvtLinesForExport(DEBUG_LINES_N).forEach { sb.appendLine(it) }
            sb.appendLine("=== PERF (last $DEBUG_LINES_N) ===")
            debugLogRepository.getPerfLinesForExport(DEBUG_LINES_N).forEach { sb.appendLine(it) }
            File(dir, "debug.txt").writeText(sb.toString())
        }
    }

    /** Current run directory (for export). Remains set after endSession so user can export. */
    fun getCurrentRunDir(): File? = runDir

    /** List of run directories (runs/run_*) sorted by name descending, for "latest" export. */
    fun getLatestRunDir(): File? {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val runsDir = File(baseDir, RUNS_DIR)
        if (!runsDir.isDirectory) return null
        return runsDir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
    }

    /**
     * Zip the given run directory and return the zip file (for sharing).
     * Caller must use FileProvider to get URI for the zip.
     */
    fun zipRunFolder(runDir: File): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val outZip = File(baseDir, "runs/${runDir.name}.zip")
        outZip.parentFile?.mkdirs()
        ZipOutputStream(outZip.outputStream()).use { zos ->
            runDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return outZip
    }
}
