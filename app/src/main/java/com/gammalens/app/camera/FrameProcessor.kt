package com.gammalens.app.camera

import android.os.SystemClock
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video
import java.util.ArrayDeque

/**
 * 事件监听器接口
 */
interface EventListener {
    fun onRadiationEvent(score: Int, blobCount: Int, maxArea: Float)
}

enum class DetectionMode {
    MOG2_ONLY,
    DIFF_ONLY,
    FUSION
}

enum class MeasurementReliability {
    RELIABLE,
    LIMITED,
    POOR
}

data class FrameStatsSnapshot(
    val streamId: String,
    val fps: Float,
    val blobCount: Int,
    val maxArea: Double,
    val effectiveNonZeroCount: Int,
    val mog2NonZeroCount: Int,
    val diffNonZeroCount: Int,
    val fusedNonZeroCount: Int,
    val processMsAvg: Double,
    val detectionMode: DetectionMode,
    val candidateCount: Int,
    val suppressedCount: Int,
    val adaptiveDiffThreshold: Double,
    val motionDx: Double,
    val motionDy: Double,
    val qualityScore: Double,
    val warmupActive: Boolean,
    val noiseQuantile: Double,
    val hotPixelSuppressedCount: Int,
    val trackStability: Double,
    val confirmScore: Double,
    val pairPenalty: Double,
    val darkFieldReady: Boolean,
    val motionStable: Boolean,
    val measurementReliability: MeasurementReliability,
    val significanceZ: Double,
    val baselineMean: Double,
    val baselineStd: Double,
    val peakIntensity: Double,
    val localContrast: Double,
    val eventFeatureScore: Double,
    val classifierProbability: Double,
    val sustainedFrames: Int,
    val trajectoryLength: Double,
    val peakStability: Double,
    val tempCompensationTerm: Double,
    val diffContribution: Double,
    val mog2Contribution: Double,
    val fusionDecisionPath: String,
    val suppressionApplied: Boolean,
    val suppressionReason: String,
    val significanceMean60s: Double,
    val significanceCi60s: Double,
    val pulseDensity: Double,
    val neighborhoodConsistency: Double,
    val deepModelProbability: Double,
    val cusumScore: Double,
    val cusumPass: Boolean,
    val hotPixelMapBucket: String,
    val hotPixelMapSize: Int,
    val hotPixelMapHitCount: Int,
    val pulseWidthConsistency: Double,
    val shortWindowVarianceRatio: Double,
    val roiEdgeSuppressedCount: Int,
    val stackedNonZeroCount: Int,
    val poissonCv: Double,
    val poissonRawCv: Double,
    val poissonCorrectedCv: Double,
    val poissonPass: Boolean,
    val poissonConfidence: Double,
    val riskScore: Double,
    val riskTriggerState: Boolean,
    val tempSlopeCPerSec: Double,
    val tempCompPhase: String,
    val deadTimeMsUsed: Double,
    val pulseAcceptedCount: Int,
    val pulseDroppedByDeadTime: Int,
    val stackDepthUsed: Int,
    val stackThresholdUsed: Double,
    val poissonSampleCount: Int,
    val poissonWarmupReady: Boolean,
    val roiAppliedStage: String,
    val funnelCandidateCount: Int,
    val funnelSafetyPassCount: Int,
    val funnelRiskPassCount: Int,
    val funnelConfirmedCount: Int,
    val funnelSuppressedCount: Int,
)

/**
 * 帧处理器：图像处理链
 * Step 2：处理灰度Mat帧，每秒打印FPS统计
 */
class FrameProcessor(
    private val onRadiationCandidate: ((String, Double, Long, Int, Int, Double, Int) -> Unit)? = null,
    initialConfig: DetectionConfig = DetectionConfig.defaultInstance(),
    private val deepModelInference: ((Mat) -> Double?)? = null,
) {
    @Volatile private var config: DetectionConfig = initialConfig.deepCopy()
    @Volatile private var frameConfigSnapshot: DetectionConfig? = null
    @Volatile private var roiWeightsDirty: Boolean = true
    @Volatile private var closeInProgress: Boolean = false

    /** Called every ~1s with FPS/耗时/检测统计。 */
    var onStatsSnapshot: ((FrameStatsSnapshot) -> Unit)? = null

    /** Called when event is suppressed (dual-cam other had flash). Set from outside. */
    var onSuppressed: ((String, Double, Int, Double, Int) -> Unit)? = null

    /** Optional: forward GL_EVT log line for debug UI. */
    var onEvtLog: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "FrameProcessor"
        private const val MAX_STREAK_CAP = 8
    }

            // FPS统计
    private var frameCount = 0
    private var lastLogTimeMs = SystemClock.elapsedRealtime()
    private var lastPacketRef: FramePacket? = null
    private var rotationApplied = false

    // 背景建模器（持久成员，避免每帧重建）
    private var backgroundSubtractor: BackgroundSubtractorMOG2? = null

    // 复用的Mat缓冲区（性能优化）
    private var cachedW = 0
    private var cachedH = 0
    private lateinit var fgMask: Mat
    private lateinit var diffMask: Mat
    private lateinit var fusedMask: Mat
    private lateinit var analysisMask: Mat
    private lateinit var hotPixelHeatMap: Mat
    private lateinit var hotPixelMask: Mat
    private lateinit var hotPixelInvMask: Mat
    private lateinit var staticHotPixelMask: Mat
    private lateinit var hotPixelHitMask: Mat
    private lateinit var morphKernel: Mat
    private var previousAlignedFrame: Mat? = null
    private val diffNoiseWindow = ArrayDeque<Double>()
    private val motionWindow = ArrayDeque<Double>()
    private val confirmWindow = ArrayDeque<Pair<Long, Double>>()
    private val baselineWindow = ArrayDeque<Double>()

    // 粒子检测统计
    private var lastNonZeroCount = 0
    private var lastMog2NonZeroCount = 0
    private var lastDiffNonZeroCount = 0
    private var lastFusedNonZeroCount = 0
    private var lastBlobCount = 0
    private var lastMaxArea = 0.0
    private var lastEffectiveNonZeroCount = 0
    private var maskGuardLogged = false
    private var lastAdaptiveDiffThreshold = config.diffThresholdBase
    private var lastNoiseQuantile = config.diffThresholdBase
    private var lastMotionDx = 0.0
    private var lastMotionDy = 0.0
    private var mog2StrongStreak = 0
    private var diffStrongStreak = 0
    private var qualityScore = 0.0
    private var warmupStartMs = -1L
    private var warmupActive = true
    private var hotPixelSuppressedCount = 0
    private var lastTrackCenterX = Double.NaN
    private var lastTrackCenterY = Double.NaN
    private var lastTrackArea = 0.0
    private var trackStability = 0.0
    private var confirmScore = 0.0
    private var lastPairPenalty = 0.0
    private var darkFieldReady = false
    private var motionStable = true
    private var measurementReliability = MeasurementReliability.POOR
    private var lastSaturationRatio = 0.0
    private var significanceZ = 0.0
    private var baselineMean = 0.0
    private var baselineStd = 1.0
    private var peakIntensity = 0.0
    private var localContrast = 0.0
    private var eventFeatureScore = 0.0
    private var classifierProbability = 0.0
    private var sustainedFrames = 0
    private var trajectoryLength = 0.0
    private val peakWindow = ArrayDeque<Double>()
    private var peakStability = 0.0
    private var latestDeviceTempC = 25.0
    private var tempCompensationTerm = 0.0
    private var diffContribution = 0.0
    private var mog2Contribution = 0.0
    private var fusionDecisionPath = "and"
    private var suppressionApplied = false
    private var suppressionReason = "none"
    private var suppressionDelayedUntilMs = 0L
    private var shadedPrecisionMode = true
    private val significanceTimedWindow = ArrayDeque<Pair<Long, Double>>()
    private var significanceMean60s = 0.0
    private var significanceCi60s = 0.0
    private val pulseWindow = ArrayDeque<Int>()
    private var pulseDensity = 0.0
    private var neighborhoodConsistency = 0.0
    private var deepModelProbability = 0.0
    private var cusumPositive = 0.0
    private var cusumNegative = 0.0
    private var cusumScore = 0.0
    private var cusumPass = false
    private var hotPixelMapBucket = "unknown"
    private var hotPixelMapSize = 0
    private var hotPixelMapHitCount = 0
    private var pulseWidthConsistency = 0.0
    private var shortWindowVarianceRatio = 1.0
    private val pulseWidthRuns = ArrayDeque<Int>()
    private var currentPulseRun = 0
    private val nonZeroTimedWindow = ArrayDeque<Int>()
    private lateinit var roiWeightMat: Mat
    private var roiEdgeSuppressedCount = 0
    private lateinit var stackAccumulator: Mat
    private val stackQueue: ArrayDeque<Mat> = ArrayDeque()
    private var stackedNonZeroCount = 0
    private val eventTimestamps: ArrayDeque<Long> = ArrayDeque()
    private var poissonCv = 0.0
    private var poissonRawCv = 0.0
    private var poissonCorrectedCv = 0.0
    private var poissonPass = false
    private var poissonConfidence = 0.0
    private var riskScore = 0.0
    private var riskTriggerState = false
    private var lastPoissonPulseActive = false
    private var lastAcceptedPulseMs = 0L
    private var deadTimeMsUsed = 0.0
    private var pulseAcceptedCount = 0
    private var pulseDroppedByDeadTime = 0
    private var tempSlopeCPerSec = 0.0
    private var tempCompPhase = "steady"
    private var lastTempUpdateMs = 0L
    private var lastTempForSlope = 25.0
    private var stackDepthUsed = 0
    private var stackThresholdUsed = 0.0
    private var poissonSampleCount = 0
    private var poissonWarmupReady = false
    private var roiAppliedStage = "post_analysis"
    private var adaptedRiskTriggerHigh = config.riskScoreTriggerHigh
    private var adaptedRiskReleaseLow = config.riskScoreReleaseLow
    private var adaptedPoissonConfidenceMin = config.poissonConfidenceMin
    private var adaptedDeadTimeScale = 1.0
    private var tempAdaptiveFactor = 0.0
    private var dropRatioEma = 0.0
    private val deadTimeDropWindow: ArrayDeque<Boolean> = ArrayDeque()
    private var deadTimeDropWindowDropped = 0
    private var funnelCandidateCount = 0
    private var funnelSafetyPassCount = 0
    private var funnelRiskPassCount = 0
    private var funnelConfirmedCount = 0
    private var funnelSuppressedCount = 0

    // 事件触发器
    private var streakSeconds = 0
    private var cooldownRemainingSeconds = 0
    private var scoreEma = 0.0

    // 事件监听器和统计
    var eventListener: EventListener? = null
    private var eventCount = 0
    private var lastEventTimeMs = 0L
    private var candidateCount = 0
    private var suppressedCount = 0
    private var processMsWindowSum = 0.0
    private var processMsWindowFrames = 0
    @Volatile private var detectionMode: DetectionMode = DetectionMode.FUSION

    fun setDetectionMode(mode: DetectionMode) {
        detectionMode = mode
    }

    fun getDetectionMode(): DetectionMode = detectionMode

    @Synchronized
    fun updateConfig(newConfig: DetectionConfig) {
        if (closeInProgress) return
        val prev = config
        val next = newConfig.deepCopy()
        val roiChanged = prev.roiWeightEnabled != next.roiWeightEnabled ||
            prev.roiCenterWeight != next.roiCenterWeight ||
            prev.roiEdgeWeight != next.roiEdgeWeight ||
            prev.roiTransitionRatio != next.roiTransitionRatio
        config = next
        if (roiChanged) {
            roiWeightsDirty = true
        }
    }

    fun updateDeviceTempC(tempC: Double?) {
        if (closeInProgress) return
        if (tempC == null || tempC.isNaN()) return
        val clamped = tempC.coerceIn(-10.0, 80.0)
        val now = SystemClock.elapsedRealtime()
        if (lastTempUpdateMs > 0L) {
            val dtSec = ((now - lastTempUpdateMs).toDouble() / 1000.0).coerceAtLeast(1e-3)
            tempSlopeCPerSec = ((clamped - lastTempForSlope) / dtSec).coerceIn(-2.0, 2.0)
        } else {
            tempSlopeCPerSec = 0.0
        }
        lastTempForSlope = clamped
        lastTempUpdateMs = now
        latestDeviceTempC = clamped
    }

    fun setShadedPrecisionMode(enabled: Boolean) {
        if (closeInProgress) return
        shadedPrecisionMode = enabled
    }

    fun updateHotPixelMap(points: List<Pair<Int, Int>>, bucket: String) {
        if (closeInProgress) return
        hotPixelMapBucket = bucket
        hotPixelMapSize = points.size
        if (!::staticHotPixelMask.isInitialized) return
        staticHotPixelMask.setTo(Scalar(0.0))
        if (points.isEmpty()) return
        val maxX = staticHotPixelMask.cols() - 1
        val maxY = staticHotPixelMask.rows() - 1
        for ((xRaw, yRaw) in points) {
            if (xRaw < 0 || yRaw < 0) continue
            val x = xRaw.coerceAtMost(maxX)
            val y = yRaw.coerceAtMost(maxY)
            staticHotPixelMask.put(y, x, 255.0)
        }
    }

    fun snapshotLearnedHotPixels(maxPoints: Int): List<Pair<Int, Int>> {
        if (closeInProgress) return emptyList()
        if (!::hotPixelMask.isInitialized || maxPoints <= 0) return emptyList()
        val points = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until hotPixelMask.rows()) {
            for (x in 0 until hotPixelMask.cols()) {
                val value = hotPixelMask.get(y, x)?.firstOrNull() ?: 0.0
                if (value >= 200.0) {
                    points.add(x to y)
                    if (points.size >= maxPoints) return points
                }
            }
        }
        return points
    }

    /**
     * 确保缓冲区大小正确
     */
    private fun ensureBuffers(w: Int, h: Int) {
        if (cachedW != w || cachedH != h) {
            // 释放旧的缓冲区（但保留kernel，它是固定的）
            if (::fgMask.isInitialized) fgMask.release()
            if (::diffMask.isInitialized) diffMask.release()
            if (::fusedMask.isInitialized) fusedMask.release()
            if (::analysisMask.isInitialized) analysisMask.release()
            if (::hotPixelHeatMap.isInitialized) hotPixelHeatMap.release()
            if (::hotPixelMask.isInitialized) hotPixelMask.release()
            if (::hotPixelInvMask.isInitialized) hotPixelInvMask.release()
            if (::staticHotPixelMask.isInitialized) staticHotPixelMask.release()
            if (::hotPixelHitMask.isInitialized) hotPixelHitMask.release()
            if (::roiWeightMat.isInitialized) roiWeightMat.release()
            if (::stackAccumulator.isInitialized) stackAccumulator.release()
            for (m in stackQueue) m.release()
            stackQueue.clear()

            // 创建新的缓冲区
            fgMask = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
            diffMask = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
            fusedMask = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
            analysisMask = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
            hotPixelHeatMap = Mat(h, w, CvType.CV_32F, Scalar(0.0))
            hotPixelMask = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
            hotPixelInvMask = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
            staticHotPixelMask = Mat(h, w, org.opencv.core.CvType.CV_8UC1, Scalar(0.0))
            hotPixelHitMask = Mat(h, w, org.opencv.core.CvType.CV_8UC1, Scalar(0.0))
            roiWeightMat = Mat(h, w, CvType.CV_32F)
            stackAccumulator = Mat(h, w, CvType.CV_32F, Scalar(0.0))
            generateRoiWeightMap(w, h)
            roiWeightsDirty = false

            cachedW = w
            cachedH = h
        }
        if (::roiWeightMat.isInitialized && roiWeightsDirty) {
            generateRoiWeightMap(w, h)
            roiWeightsDirty = false
        }

        // 延迟初始化kernel（只创建一次）
        if (!::morphKernel.isInitialized) {
            morphKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        }
    }

    private fun generateRoiWeightMap(w: Int, h: Int) {
        val config = activeConfig()
        val cx = w / 2.0
        val cy = h / 2.0
        val hw = w / 2.0
        val hh = h / 2.0
        val maxR = 1.414
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = (x - cx) / hw
                val ny = (y - cy) / hh
                val r = kotlin.math.sqrt(nx * nx + ny * ny).coerceAtMost(maxR)
                val weight = if (r <= config.roiTransitionRatio) {
                    config.roiCenterWeight
                } else {
                    val t = ((r - config.roiTransitionRatio) / (maxR - config.roiTransitionRatio)).coerceIn(0.0, 1.0)
                    config.roiCenterWeight + t * (config.roiEdgeWeight - config.roiCenterWeight)
                }
                roiWeightMat.put(y, x, weight)
            }
        }
    }

    private data class DualSuppressionSignal(
        val globalFlash: Boolean,
        val localConsistent: Boolean,
        val shouldSuppress: Boolean,
        val pairPenalty: Double,
    )

    private data class MotionCompResult(
        val compensatedCurrent: Mat,
        val dx: Double,
        val dy: Double,
    )

    private data class TrackFeature(
        val stability: Double,
        val centerX: Double,
        val centerY: Double,
        val area: Double,
    )

    private data class Quad(
        val first: Int,
        val second: Double,
        val third: Double,
        val fourth: Double,
    )

    private data class StackAdaptiveParams(
        val depth: Int,
        val threshold: Double,
    )

    /**
     * 消费帧数据（单帧或配对）
     * 双摄时：仅当 anchor 有 blob 且 other 无亮度突变时才触发事件（共模抑制）。
     */
    fun consume(item: ProcessableItem) {
        if (closeInProgress) {
            item.releaseMatsSafely()
            return
        }
        val startNs = System.nanoTime()
        val frame = item.anchor
        val nowMs = SystemClock.elapsedRealtime()
        val cfg = config
        val config = cfg
        frameConfigSnapshot = cfg
        var phase = "init"
        if (warmupStartMs < 0L) warmupStartMs = nowMs
        warmupActive = nowMs - warmupStartMs < cfg.warmupMs
        updateBucketAdaptiveThresholds()
        val startupWindowMs = cfg.startupWindowSec.coerceAtLeast(0) * 1000L
        tempCompPhase = if (cfg.startupTempCompEnabled && nowMs - warmupStartMs < startupWindowMs) "startup" else "steady"
        val suppressionSignal = item.other?.let { other ->
            try {
                buildDualSuppressionSignal(frame.grayMat, other.grayMat, frame.timestampNs, other.timestampNs)
            } finally {
                other.grayMat.release()
            }
        } ?: DualSuppressionSignal(false, false, false, 0.0)
        lastPairPenalty = suppressionSignal.pairPenalty

        try {
            phase = "preprocess"
            // 延迟初始化背景建模器
            if (backgroundSubtractor == null) {
                backgroundSubtractor = Video.createBackgroundSubtractorMOG2(cfg.mog2History, cfg.mog2VarThreshold, false)
            }

            // 缓存最后一个FramePacket用于日志
            lastPacketRef = frame

            // 更新FPS统计
            frameCount++

            val currentTimeMs = SystemClock.elapsedRealtime()
            if (currentTimeMs - lastLogTimeMs >= 1000) {
                val fps = frameCount.toFloat() / ((currentTimeMs - lastLogTimeMs) / 1000f)
                val processMsAvg = if (processMsWindowFrames > 0) {
                    processMsWindowSum / processMsWindowFrames.toDouble()
                } else {
                    0.0
                }
                Log.d(TAG, "FPS=${String.format("%.1f", fps)} (${frame.streamId})")
                Log.d("GL_DET", "fps=${String.format("%.1f", fps)}, nonZeroCount=$lastNonZeroCount")
                Log.d("GL_BLOB", "fps=${String.format("%.1f", fps)}, blobCount=$lastBlobCount, maxArea=${String.format("%.1f", lastMaxArea)}, nonZeroCount=$lastEffectiveNonZeroCount")
                onStatsSnapshot?.invoke(
                    FrameStatsSnapshot(
                        streamId = frame.streamId,
                        fps = fps,
                        blobCount = lastBlobCount,
                        maxArea = lastMaxArea,
                        effectiveNonZeroCount = lastEffectiveNonZeroCount,
                        mog2NonZeroCount = lastMog2NonZeroCount,
                        diffNonZeroCount = lastDiffNonZeroCount,
                        fusedNonZeroCount = lastFusedNonZeroCount,
                        processMsAvg = processMsAvg,
                        detectionMode = detectionMode,
                        candidateCount = candidateCount,
                        suppressedCount = suppressedCount,
                        adaptiveDiffThreshold = lastAdaptiveDiffThreshold,
                        motionDx = lastMotionDx,
                        motionDy = lastMotionDy,
                        qualityScore = qualityScore,
                        warmupActive = warmupActive,
                        noiseQuantile = lastNoiseQuantile,
                        hotPixelSuppressedCount = hotPixelSuppressedCount,
                        trackStability = trackStability,
                        confirmScore = confirmScore,
                        pairPenalty = lastPairPenalty,
                        darkFieldReady = darkFieldReady,
                        motionStable = motionStable,
                        measurementReliability = measurementReliability,
                        significanceZ = significanceZ,
                        baselineMean = baselineMean,
                        baselineStd = baselineStd,
                        peakIntensity = peakIntensity,
                        localContrast = localContrast,
                        eventFeatureScore = eventFeatureScore,
                        classifierProbability = classifierProbability,
                        sustainedFrames = sustainedFrames,
                        trajectoryLength = trajectoryLength,
                        peakStability = peakStability,
                        tempCompensationTerm = tempCompensationTerm,
                        diffContribution = diffContribution,
                        mog2Contribution = mog2Contribution,
                        fusionDecisionPath = fusionDecisionPath,
                        suppressionApplied = suppressionApplied,
                        suppressionReason = suppressionReason,
                        significanceMean60s = significanceMean60s,
                        significanceCi60s = significanceCi60s,
                        pulseDensity = pulseDensity,
                        neighborhoodConsistency = neighborhoodConsistency,
                        deepModelProbability = deepModelProbability,
                        cusumScore = cusumScore,
                        cusumPass = cusumPass,
                        hotPixelMapBucket = hotPixelMapBucket,
                        hotPixelMapSize = hotPixelMapSize,
                        hotPixelMapHitCount = hotPixelMapHitCount,
                        pulseWidthConsistency = pulseWidthConsistency,
                        shortWindowVarianceRatio = shortWindowVarianceRatio,
                        roiEdgeSuppressedCount = roiEdgeSuppressedCount,
                        stackedNonZeroCount = stackedNonZeroCount,
                        poissonCv = poissonCv,
                        poissonRawCv = poissonRawCv,
                        poissonCorrectedCv = poissonCorrectedCv,
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
                        funnelCandidateCount = funnelCandidateCount,
                        funnelSafetyPassCount = funnelSafetyPassCount,
                        funnelRiskPassCount = funnelRiskPassCount,
                        funnelConfirmedCount = funnelConfirmedCount,
                        funnelSuppressedCount = funnelSuppressedCount,
                    )
                )

                // 事件触发器逻辑
                suppressionApplied = false
                suppressionReason = "none"
                if (cooldownRemainingSeconds > 0) {
                    cooldownRemainingSeconds--
                } else {
                    phase = "gate"
                    // 跳过无效帧的评分和触发逻辑
                    val isValidFrame = lastEffectiveNonZeroCount > 0

                    if (isValidFrame) {
                        // 计算融合分数（MOG2 + DIFF + 形态）
                        val sMog2 = (lastMog2NonZeroCount.toDouble() / 80_000.0).coerceIn(0.0, 1.0)
                        val sDiff = (lastDiffNonZeroCount.toDouble() / 80_000.0).coerceIn(0.0, 1.0)
                        val sBlob = (lastBlobCount.toDouble() / 200.0).coerceIn(0.0, 1.0)
                        val sArea = (lastMaxArea / 300.0).coerceIn(0.0, 1.0)
                        val sShape = 0.6 * sBlob + 0.4 * sArea
                        qualityScore = 0.45 * sShape + 0.35 * sDiff + 0.20 * sMog2
                        val mog2Scale = if (shadedPrecisionMode) {
                            if (config.shadedDisableMog2Score) 0.0 else config.shadedModeMog2Scale
                        } else {
                            1.0
                        }
                        val diffScale = if (shadedPrecisionMode) config.shadedModeDiffScale else 1.0
                        val mog2Weighted = (sMog2 * mog2Scale).coerceIn(0.0, 1.2)
                        val diffWeighted = (sDiff * diffScale).coerceIn(0.0, 1.2)
                        val raw = when (detectionMode) {
                            DetectionMode.MOG2_ONLY -> 0.65 * mog2Weighted + 0.35 * sShape
                            DetectionMode.DIFF_ONLY -> 0.65 * diffWeighted + 0.35 * sShape
                            DetectionMode.FUSION -> {
                                config.fusionWeightMog2 * mog2Weighted +
                                    config.fusionWeightDiff * diffWeighted +
                                    config.fusionWeightShape * sShape
                            }
                        }
                        val sumContribution = (mog2Weighted + diffWeighted).coerceAtLeast(1e-6)
                        mog2Contribution = (mog2Weighted / sumContribution).coerceIn(0.0, 1.0)
                        diffContribution = (diffWeighted / sumContribution).coerceIn(0.0, 1.0)
                        val (candidateThreshold, confirmThreshold, reliabilityWeight, pairPenaltyScale) = when (measurementReliability) {
                            MeasurementReliability.RELIABLE -> Quad(config.candidateScore, config.confirmScore.toDouble(), 1.0, 1.0)
                            MeasurementReliability.LIMITED -> Quad(config.candidateScore + 4, config.confirmScore + 5.0, 0.88, 1.25)
                            MeasurementReliability.POOR -> Quad(config.candidateScore + 10, config.confirmScore + 12.0, 0.74, 1.6)
                        }
                        val pairWeight = (1.0 - suppressionSignal.pairPenalty * config.suppressionSoftPenaltyScale * pairPenaltyScale).coerceIn(0.45, 1.0)
                        val score = (raw * pairWeight * 100).toInt().coerceIn(0, 100)
                        val linear = -2.2 +
                            1.6 * eventFeatureScore +
                            0.9 * trackStability +
                            0.7 * peakStability +
                            0.4 * (trajectoryLength / 320.0).coerceIn(0.0, 1.0) +
                            0.6 * (significanceZ / 4.0).coerceIn(0.0, 1.0) -
                            0.7 * suppressionSignal.pairPenalty +
                            0.3 * (sustainedFrames.toDouble() / 8.0).coerceIn(0.0, 1.0) -
                            0.25 * kotlin.math.abs(tempCompensationTerm) +
                            config.deepModelWeight * deepModelProbability
                        classifierProbability = 1.0 / (1.0 + kotlin.math.exp(-linear))
                        val zNorm = (significanceZ / 4.0).coerceIn(0.0, 1.0)
                        val evidence = (
                            0.45 * zNorm +
                                0.25 * pulseDensity +
                                0.20 * neighborhoodConsistency +
                                0.10 * trackStability
                            ).coerceIn(0.0, 1.2)
                        updateCusum(
                            evidence = evidence,
                            reset = warmupActive,
                            unstable = !motionStable || measurementReliability == MeasurementReliability.POOR,
                        )

                        // 更新EMA
                        scoreEma = cfg.emaAlpha * score + (1.0 - cfg.emaAlpha) * scoreEma

                        if (warmupActive) {
                            confirmWindow.clear()
                            streakSeconds = 0
                            riskScore = 0.0
                            riskTriggerState = false
                        } else if (scoreEma >= candidateThreshold && qualityScore >= config.qualityScoreMin) {
                            candidateCount++
                            funnelCandidateCount++
                            streakSeconds++
                            confirmWindow.addLast(currentTimeMs to scoreEma)
                            while (confirmWindow.isNotEmpty() && currentTimeMs - confirmWindow.first.first > cfg.confirmWindowSeconds * 1000L) {
                                confirmWindow.removeFirst()
                            }
                            val hits = confirmWindow.size
                            confirmScore = if (hits > 0) {
                                confirmWindow.map { it.second }.average() * (0.65 + 0.35 * trackStability) * reliabilityWeight
                            } else {
                                0.0
                            }

                            val suppressionHard = item.other != null &&
                                suppressionSignal.shouldSuppress &&
                                !(shadedPrecisionMode && config.shadedModeSuppressionSoftOnly) &&
                                suppressionSignal.pairPenalty >= config.suppressionHardPenaltyThreshold
                            val suppressionDelayActive = item.other != null &&
                                suppressionSignal.shouldSuppress &&
                                !suppressionHard
                            if (suppressionDelayActive) {
                                suppressionDelayedUntilMs = maxOf(suppressionDelayedUntilMs, currentTimeMs + config.layeredConfirmMinDelayMs)
                            }
                            val suppressionDelayPassed = currentTimeMs >= suppressionDelayedUntilMs
                            val zThreshold = when (measurementReliability) {
                                MeasurementReliability.RELIABLE -> 2.0
                                MeasurementReliability.LIMITED -> 2.8
                                MeasurementReliability.POOR -> 3.6
                            }
                            val startupPenaltyFactor = if (config.poissonStartupGuardEnabled && !poissonWarmupReady) {
                                (1.0 - config.poissonStartupPenalty).coerceIn(0.5, 1.0)
                            } else {
                                1.0
                            }
                            val guardedClassifierProbability = (classifierProbability * startupPenaltyFactor).coerceIn(0.0, 1.0)
                            val safetyGate = hits >= config.confirmMinHits &&
                                confirmScore >= confirmThreshold &&
                                scoreEma >= config.triggerScore &&
                                significanceZ >= zThreshold &&
                                significanceMean60s >= config.layeredConfirmZ60Min &&
                                significanceCi60s <= config.layeredConfirmCi60Max &&
                                guardedClassifierProbability >= config.classifierProbThreshold &&
                                (!config.deepModelEnabled || deepModelProbability >= config.deepModelThreshold) &&
                                (!config.featureLiteEnabled || pulseWidthConsistency >= config.pulseWidthConsistencyMin) &&
                                (!config.featureLiteEnabled || shortWindowVarianceRatio <= config.varianceRatioMax) &&
                                (!suppressionDelayActive || suppressionDelayPassed) &&
                                streakSeconds >= 2
                            val riskScoreComputed = FrameProcessorAlgorithms.computeRiskScore(
                                poissonConfidence = if (config.poissonConfidenceEnabled) poissonConfidence else 1.0,
                                cusumScore = cusumScore,
                                cusumEnabled = config.cusumEnabled,
                                cusumPass = cusumPass,
                                pulseDensity = pulseDensity,
                                neighborhoodConsistency = neighborhoodConsistency,
                                trackStability = trackStability,
                                qualityScore = qualityScore,
                                classifierProbability = guardedClassifierProbability,
                                warmupReady = poissonWarmupReady,
                                warmupPenalty = config.riskScoreWarmupPenalty,
                                weightPoisson = config.riskWeightPoisson,
                                weightCusum = config.riskWeightCusum,
                                weightStability = config.riskWeightStability,
                                weightQuality = config.riskWeightQuality,
                            )
                            val riskAlpha = cfg.riskScoreEmaAlpha.coerceIn(0.05, 1.0)
                            riskScore = if (warmupActive || riskScore <= 0.0) {
                                riskScoreComputed
                            } else {
                                (riskAlpha * riskScoreComputed + (1.0 - riskAlpha) * riskScore).coerceIn(0.0, 1.0)
                            }
                            riskScore = FrameProcessorAlgorithms.applyRiskCalibration(
                                score = riskScore,
                                enabled = cfg.riskCalibrationEnabled,
                                points = cfg.riskCalibrationPoints,
                            )
                            val nextRiskTriggerState = if (config.riskScoreEnabled) {
                                FrameProcessorAlgorithms.applyHysteresis(
                                    lastState = riskTriggerState,
                                    score = riskScore,
                                    triggerHigh = adaptedRiskTriggerHigh,
                                    releaseLow = adaptedRiskReleaseLow,
                                )
                            } else {
                                true
                            }
                            val hardFeatureGate =
                                pulseDensity >= config.pulseDensityMin &&
                                    neighborhoodConsistency >= config.minNeighborhoodConsistency &&
                                    trackStability >= config.minTrackStabilityConfirm
                            val noiseSpan = (config.adaptiveDiffMax - config.adaptiveDiffMin).coerceAtLeast(1.0)
                            val noiseNorm = ((lastNoiseQuantile - config.adaptiveDiffMin) / noiseSpan).coerceIn(0.0, 1.0)
                            val scenarioPenaltyScale = (
                                if (warmupActive) 0.70 else 1.0
                                ) * (0.90 + 0.25 * tempAdaptiveFactor + 0.20 * noiseNorm)
                            val softPenalty = ((
                                if (config.cusumEnabled && !cusumPass) config.riskPenaltyCusumFail.coerceAtLeast(0.0) else 0.0
                                ) + (
                                if (config.poissonCheckEnabled && !poissonPass) config.riskPenaltyPoissonFail.coerceAtLeast(0.0) else 0.0
                                ) + (
                                if (config.poissonConfidenceEnabled && poissonConfidence < adaptedPoissonConfidenceMin) {
                                    (
                                        (adaptedPoissonConfidenceMin - poissonConfidence).coerceIn(0.0, 0.25) *
                                            config.riskPenaltyPoissonConfidenceScale.coerceAtLeast(0.0)
                                        ).coerceIn(0.0, 0.35)
                                } else {
                                    0.0
                                }
                                )) * scenarioPenaltyScale.coerceIn(0.5, 1.4)
                            val adjustedRiskScore = (riskScore - softPenalty).coerceIn(0.0, 1.0)
                            val riskGate = nextRiskTriggerState && hardFeatureGate && adjustedRiskScore >= adaptedRiskReleaseLow
                            riskTriggerState = nextRiskTriggerState
                            if (safetyGate) funnelSafetyPassCount++
                            if (riskGate) funnelRiskPassCount++
                            val canConfirm = safetyGate && riskGate

                            if (canConfirm && !suppressionHard) {
                                funnelConfirmedCount++
                                // 二级确认后触发事件
                                eventCount++
                                lastEventTimeMs = currentTimeMs
                                eventListener?.onRadiationEvent(score, lastBlobCount, lastMaxArea.toFloat())
                                onRadiationCandidate?.invoke(frame.streamId, scoreEma, frame.timestampNs, cfg.cooldownSeconds, lastBlobCount, lastMaxArea, lastEffectiveNonZeroCount)
                                val evtLine = "streamId=${frame.streamId}, type=RADIATION_CANDIDATE, blobCount=$lastBlobCount, maxArea=${String.format("%.1f", lastMaxArea)}, nonZeroCount=$lastEffectiveNonZeroCount, streakSeconds=$streakSeconds, score=$score, scoreEma=${String.format("%.1f", scoreEma)}, confirmScore=${String.format("%.1f", confirmScore)}, clsProb=${String.format("%.2f", classifierProbability)}, cooldownSeconds=${config.cooldownSeconds}"
                                Log.i("GL_EVT", evtLine)
                                onEvtLog?.invoke(evtLine)
                                cooldownRemainingSeconds = cfg.cooldownSeconds
                                streakSeconds = 0
                                confirmWindow.clear()
                                if (config.cusumResetOnSuppression) {
                                    cusumPositive = 0.0
                                    cusumNegative = 0.0
                                    cusumScore = 0.0
                                    cusumPass = false
                                }
                                suppressionApplied = suppressionDelayActive
                                suppressionReason = if (suppressionDelayActive) "soft_delay" else "none"
                            } else if (canConfirm && suppressionHard) {
                                funnelSuppressedCount++
                                suppressedCount++
                                suppressionApplied = true
                                suppressionReason = "hard_global_flash"
                                val suppLine = "SUPPRESSED dual globalFlash=${suppressionSignal.globalFlash} localConsistent=${suppressionSignal.localConsistent} pairPenalty=${"%.2f".format(suppressionSignal.pairPenalty)} reason=$suppressionReason streamId=${frame.streamId}"
                                Log.d("GL_EVT", suppLine)
                                onEvtLog?.invoke(suppLine)
                                onSuppressed?.invoke(frame.streamId, scoreEma, lastBlobCount, lastMaxArea, lastEffectiveNonZeroCount)
                                if (config.cusumResetOnSuppression) {
                                    cusumPositive = 0.0
                                    cusumNegative = 0.0
                                    cusumScore = 0.0
                                    cusumPass = false
                                }
                            }
                        } else {
                            streakSeconds = 0
                            confirmScore = 0.0
                            riskTriggerState = false
                            suppressionApplied = false
                            suppressionReason = if (item.other != null && suppressionSignal.shouldSuppress) "soft_pending" else "none"
                            if (confirmWindow.isNotEmpty()) confirmWindow.removeFirst()
                        }
                    } else {
                        // 无效帧：重置streak，不更新EMA
                        streakSeconds = 0
                        confirmScore = 0.0
                        riskTriggerState = false
                        suppressionApplied = false
                        suppressionReason = "none"
                    }
                }

                frameCount = 0
                lastLogTimeMs = currentTimeMs
                processMsWindowSum = 0.0
                processMsWindowFrames = 0
            }

            phase = "vision"
            // 应用旋转以获得正确方向的图像
            val alignedMat = applyRotationIfNeeded(frame)

            // 确保缓冲区大小正确
            ensureBuffers(alignedMat.cols(), alignedMat.rows())

            // MOG2 分支
            // MOG2背景建模
            backgroundSubtractor!!.apply(alignedMat, fgMask, -1.0)
            Imgproc.threshold(fgMask, fgMask, 200.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_OPEN, morphKernel)
            Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_DILATE, morphKernel)
            lastMog2NonZeroCount = Core.countNonZero(fgMask)

            // 帧间差分分支（先做轻量平移补偿）
            val prev = previousAlignedFrame
            if (prev != null && prev.rows() == alignedMat.rows() && prev.cols() == alignedMat.cols()) {
                val motionComp = applyMotionCompensation(alignedMat, prev)
                lastMotionDx = motionComp.dx
                lastMotionDy = motionComp.dy
                Core.absdiff(motionComp.compensatedCurrent, prev, diffMask)
                val diffNoise = Core.mean(diffMask).`val`[0]
                lastAdaptiveDiffThreshold = computeAdaptiveDiffThreshold(diffNoise, alignedMat, suppressionSignal.pairPenalty)
                Imgproc.threshold(diffMask, diffMask, lastAdaptiveDiffThreshold, 255.0, Imgproc.THRESH_BINARY)
                Imgproc.morphologyEx(diffMask, diffMask, Imgproc.MORPH_OPEN, morphKernel)
                Imgproc.morphologyEx(diffMask, diffMask, Imgproc.MORPH_DILATE, morphKernel)
                if (motionComp.compensatedCurrent !== alignedMat) {
                    motionComp.compensatedCurrent.release()
                }
            } else {
                diffMask.setTo(Scalar(0.0))
                lastMotionDx = 0.0
                lastMotionDy = 0.0
                lastAdaptiveDiffThreshold = config.diffThresholdBase
            }
            roiEdgeSuppressedCount = 0
            roiAppliedStage = if (config.roiStackOrder.equals("pre_stack", ignoreCase = true)) "pre_stack" else "post_analysis"
            if (roiAppliedStage == "pre_stack") {
                roiEdgeSuppressedCount += applyRoiWeightToBinaryMask(diffMask)
            }

            // 多帧堆叠降噪：对 diffMask 做滑动窗口时域累积平均
            if (config.frameStackEnabled && shadedPrecisionMode && ::stackAccumulator.isInitialized) {
                val adaptive = computeStackAdaptiveParams()
                val currentFloat = Mat()
                diffMask.convertTo(currentFloat, CvType.CV_32F, 1.0 / 255.0)
                Core.add(stackAccumulator, currentFloat, stackAccumulator)
                stackQueue.addLast(currentFloat)
                if (stackQueue.size > adaptive.depth) {
                    val oldest = stackQueue.removeFirst()
                    Core.subtract(stackAccumulator, oldest, stackAccumulator)
                    oldest.release()
                }
                val n = stackQueue.size.toDouble().coerceAtLeast(1.0)
                val averaged = Mat()
                Core.divide(stackAccumulator, Scalar(n), averaged)
                Imgproc.threshold(averaged, averaged, adaptive.threshold, 1.0, Imgproc.THRESH_BINARY)
                averaged.convertTo(diffMask, CvType.CV_8U, 255.0)
                averaged.release()
                stackedNonZeroCount = Core.countNonZero(diffMask)
                stackDepthUsed = adaptive.depth
                stackThresholdUsed = adaptive.threshold
            } else {
                stackedNonZeroCount = 0
                stackDepthUsed = config.frameStackDepth
                stackThresholdUsed = config.frameStackThreshold
            }
            lastDiffNonZeroCount = Core.countNonZero(diffMask)
            updateMeasurementCondition(alignedMat, lastMotionDx, lastMotionDy)

            // 缓存当前帧作为下一帧差分基准
            previousAlignedFrame?.release()
            previousAlignedFrame = alignedMat.clone()

            // 融合掩码（软融合：稳定单分支兜底，双强时 OR，其他退化到 AND）
            updateBranchStability(lastMog2NonZeroCount, lastDiffNonZeroCount)
            val useMog2Stable = mog2StrongStreak >= config.branchStableStreak
            val useDiffStable = diffStrongStreak >= config.branchStableStreak
            when {
                useMog2Stable && useDiffStable -> {
                    Core.bitwise_or(fgMask, diffMask, fusedMask)
                    fusionDecisionPath = "or_stable_both"
                }
                useMog2Stable -> {
                    fgMask.copyTo(fusedMask)
                    fusionDecisionPath = "mog2_stable_only"
                }
                useDiffStable -> {
                    diffMask.copyTo(fusedMask)
                    fusionDecisionPath = "diff_stable_only"
                }
                else -> {
                    Core.bitwise_and(fgMask, diffMask, fusedMask)
                    fusionDecisionPath = "and_fallback"
                }
            }
            lastFusedNonZeroCount = Core.countNonZero(fusedMask)
            val rawAnalysisMask = when (detectionMode) {
                DetectionMode.MOG2_ONLY -> fgMask
                DetectionMode.DIFF_ONLY -> diffMask
                DetectionMode.FUSION -> if (shadedPrecisionMode && config.shadedDisableMog2Score) diffMask else fusedMask
            }
            updateHotPixelSuppression(rawAnalysisMask)
            if (config.hotPixelMapEnabled && ::staticHotPixelMask.isInitialized) {
                Core.bitwise_and(rawAnalysisMask, staticHotPixelMask, hotPixelHitMask)
                hotPixelMapHitCount = Core.countNonZero(hotPixelHitMask)
                Core.bitwise_or(hotPixelMask, staticHotPixelMask, hotPixelMask)
            } else {
                hotPixelMapHitCount = 0
            }
            Core.bitwise_not(hotPixelMask, hotPixelInvMask)
            Core.bitwise_and(rawAnalysisMask, hotPixelInvMask, analysisMask)

            // 空间 ROI 分权：支持 pre_stack / post_analysis 两种应用阶段
            if (roiAppliedStage == "post_analysis") {
                roiEdgeSuppressedCount += applyRoiWeightToBinaryMask(analysisMask)
            }

            // 统计候选像素数量
            lastNonZeroCount = Core.countNonZero(analysisMask)
            updateSignificance(lastNonZeroCount.toDouble(), darkFieldReady)
            updateSignificanceWindow(SystemClock.elapsedRealtime(), significanceZ)
            updatePulseFeatures(lastNonZeroCount, lastMog2NonZeroCount, lastDiffNonZeroCount)
            updateFeatureLite(lastNonZeroCount)
            updatePoissonConsistency(lastNonZeroCount, nowMs)
            updateDeepModelProbability(alignedMat)
            // 计算填充率并进行异常检测
            val totalPixels = analysisMask.rows() * analysisMask.cols()
            val fillRatio = if (totalPixels > 0) lastNonZeroCount.toDouble() / totalPixels else 0.0
            val isValidFrame = fillRatio in config.fillRatioMin..config.fillRatioMax

            // 设置有效统计值
            lastEffectiveNonZeroCount = if (isValidFrame) lastNonZeroCount else 0

            // 轮廓检测和blob统计（仅在有效帧时进行）
            if (isValidFrame) {
                // 空帧短路：如果前景像素太少，跳过轮廓检测以节省CPU
                if (lastNonZeroCount < config.nonZeroMinForContours) {
                    // 几乎没有前景，直接设置为0
                    lastBlobCount = 0
                    lastMaxArea = 0.0
                    sustainedFrames = 0
                    trackStability = (trackStability * 0.92).coerceIn(0.0, 1.0)
                } else {
                    val tmpMask = analysisMask.clone() // 拷贝避免findContours修改复用缓冲区
                    val contours = mutableListOf<MatOfPoint>()
                    val hierarchy = Mat()

                    Imgproc.findContours(tmpMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                    // 筛选候选blob
                    var blobCount = 0
                    var maxArea = 0.0
                    for (contour in contours) {
                        val area = Imgproc.contourArea(contour)
                        if (area in config.areaMin..config.areaMax) {
                            blobCount++
                            if (area > maxArea) {
                                maxArea = area
                            }
                        }
                    }

                    lastBlobCount = blobCount
                    lastMaxArea = maxArea
                    val mm = Core.minMaxLoc(alignedMat)
                    peakIntensity = mm.maxVal
                    localContrast = (mm.maxVal - mm.minVal).coerceAtLeast(0.0)
                    val track = computeTrackFeature(contours, maxArea)
                    trackStability = track.stability
                    val drift = if (!lastTrackCenterX.isNaN() && !lastTrackCenterY.isNaN()) {
                        kotlin.math.hypot(track.centerX - lastTrackCenterX, track.centerY - lastTrackCenterY)
                    } else {
                        0.0
                    }
                    trajectoryLength = (trajectoryLength + drift).coerceAtMost(50_000.0)
                    lastTrackCenterX = track.centerX
                    lastTrackCenterY = track.centerY
                    lastTrackArea = track.area
                    sustainedFrames = if (blobCount > 0) (sustainedFrames + 1).coerceAtMost(120) else 0
                    val shapeScore = (0.55 * (blobCount.toDouble() / 120.0).coerceIn(0.0, 1.0) +
                        0.45 * (maxArea / 280.0).coerceIn(0.0, 1.0))
                    val peakScore = (peakIntensity / 255.0).coerceIn(0.0, 1.0)
                    val contrastScore = (localContrast / 120.0).coerceIn(0.0, 1.0)
                    eventFeatureScore = (0.45 * shapeScore + 0.30 * peakScore + 0.25 * contrastScore).coerceIn(0.0, 1.0)
                    updatePeakStability(peakIntensity)
                    tempCompensationTerm = ((latestDeviceTempC - 25.0) * config.calibrationTempCompensationCoeff).coerceIn(-2.0, 2.0)

                    // 释放临时资源
                    tmpMask.release()
                    hierarchy.release()
                    contours.forEach { it.release() }
                }
            } else {
                // 无效帧：重置统计
                lastBlobCount = 0
                lastMaxArea = 0.0
                sustainedFrames = 0
                trackStability = (trackStability * 0.90).coerceIn(0.0, 1.0)
                peakIntensity = 0.0
                localContrast = 0.0
                eventFeatureScore = (eventFeatureScore * 0.88).coerceIn(0.0, 1.0)
                trajectoryLength = (trajectoryLength * 0.96).coerceAtLeast(0.0)
                updatePeakStability(0.0)
                tempCompensationTerm = ((latestDeviceTempC - 25.0) * config.calibrationTempCompensationCoeff).coerceIn(-2.0, 2.0)
            }

            // 一次性日志
            if (!maskGuardLogged) {
                Log.i("GL_MASK_GUARD", "thr=200, hiFill=0.40, loFill=1e-6")
                maskGuardLogged = true
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            processMsWindowSum += elapsedMs
            processMsWindowFrames++

            // 释放临时Mat（复用缓冲区不释放，只释放临时拷贝）
            if (alignedMat !== frame.grayMat) {
                alignedMat.release()
            }

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Error processing frame streamId=${frame.streamId} frameNo=${frame.frameNumber} tsNs=${frame.timestampNs} phase=$phase mode=${detectionMode.name}",
                e
            )
        } finally {
            frameConfigSnapshot = null
            // 释放Mat避免native内存泄漏
            frame.grayMat.release()
        }
    }

    private fun activeConfig(): DetectionConfig = frameConfigSnapshot ?: config

    /**
     * 根据rotationDegrees应用旋转，返回正确方向的Mat
     * @param frame 帧数据包
     * @return 旋转后的Mat（如果需要旋转则创建新Mat，否则返回原Mat）
     */
    private fun applyRotationIfNeeded(frame: FramePacket): Mat {
        val rotationDegrees = frame.rotationDegrees

        // 对于0度，直接返回原Mat
        if (rotationDegrees == 0) {
            return frame.grayMat
        }

        // 创建新的Mat进行旋转
        val alignedMat = Mat()

        when (rotationDegrees) {
            90 -> Core.rotate(frame.grayMat, alignedMat, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(frame.grayMat, alignedMat, Core.ROTATE_180)
            270 -> Core.rotate(frame.grayMat, alignedMat, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> {
                // 其他角度当作0度处理
                return frame.grayMat
            }
        }

        // 打印一次性日志
        if (!rotationApplied) {
            val srcSize = "${frame.grayMat.cols()}x${frame.grayMat.rows()}"
            val alignedSize = "${alignedMat.cols()}x${alignedMat.rows()}"
            Log.i("GL_ROT_APPLY", "streamId=${frame.streamId}, rotationDegrees=$rotationDegrees, srcSize=$srcSize, alignedSize=$alignedSize")
            rotationApplied = true
        }

        return alignedMat
    }

    private fun computeAdaptiveDiffThreshold(diffNoiseMean: Double, alignedMat: Mat, pairPenalty: Double): Double {
        val config = activeConfig()
        diffNoiseWindow.addLast(diffNoiseMean)
        while (diffNoiseWindow.size > config.adaptiveWindowSize) {
            diffNoiseWindow.removeFirst()
        }
        if (diffNoiseWindow.isEmpty()) return config.diffThresholdBase
        val sorted = diffNoiseWindow.toList().sorted()
        val qIndex = ((sorted.lastIndex.toDouble() * config.adaptiveQuantile).toInt()).coerceIn(0, sorted.lastIndex)
        val quantile = sorted[qIndex]
        val median = sorted[sorted.size / 2]
        lastNoiseQuantile = quantile
        val luminance = Core.mean(alignedMat).`val`[0]
        val luminanceBoost = if (luminance > 180.0) 3.0 else if (luminance > 140.0) 1.5 else 0.0
        val motionBoost = (kotlin.math.hypot(lastMotionDx, lastMotionDy) / 24.0).coerceIn(0.0, 2.0)
        val pairBoost = (pairPenalty * 2.0).coerceIn(0.0, 1.5)
        val tempPhaseGain = if (config.startupTempCompEnabled && tempCompPhase == "startup") {
            config.startupTempSlopeGain
        } else {
            config.steadyTempSlopeGain
        }
        val tempSlopeBoost = FrameProcessorAlgorithms.computeTempSlopeBoost(
            slopeCPerSec = tempSlopeCPerSec,
            thresholdCPerSec = config.tempSlopeThresholdCPerSec,
            boostMax = config.tempSlopeBoostMax,
            phaseGain = tempPhaseGain,
        )
        val base = maxOf(median, quantile) * config.adaptiveNoiseScale
        val adaptive = (base + luminanceBoost + motionBoost + pairBoost + tempSlopeBoost)
            .coerceIn(config.adaptiveDiffMin, config.adaptiveDiffMax)
        return adaptive
    }

    private fun updateBranchStability(mog2NonZero: Int, diffNonZero: Int) {
        val config = activeConfig()
        mog2StrongStreak = if (mog2NonZero >= config.branchStrongNonZero) {
            (mog2StrongStreak + 1).coerceAtMost(MAX_STREAK_CAP)
        } else {
            0
        }
        diffStrongStreak = if (diffNonZero >= config.branchStrongNonZero) {
            (diffStrongStreak + 1).coerceAtMost(MAX_STREAK_CAP)
        } else {
            0
        }
    }

    private fun updateHotPixelSuppression(mask: Mat) {
        val config = activeConfig()
        val mask32 = Mat()
        try {
            mask.convertTo(mask32, CvType.CV_32F, 1.0 / 255.0)
            val hotPixelAlpha = (config.hotPixelAlpha * config.calibrationHotPixelGain).coerceIn(0.005, 0.2)
            val hotPixelThreshold = (config.hotPixelThreshold / config.calibrationHotPixelGain).coerceIn(0.8, 4.0)
            Imgproc.accumulateWeighted(mask32, hotPixelHeatMap, hotPixelAlpha)
            Imgproc.threshold(hotPixelHeatMap, hotPixelMask, hotPixelThreshold, 255.0, Imgproc.THRESH_BINARY)
            hotPixelMask.convertTo(hotPixelMask, CvType.CV_8U)
            hotPixelSuppressedCount = Core.countNonZero(hotPixelMask)
        } finally {
            mask32.release()
        }
    }

    private fun updateSignificance(signalValue: Double, darkReady: Boolean) {
        if (darkReady || baselineWindow.isEmpty()) {
            baselineWindow.addLast(signalValue)
            while (baselineWindow.size > 180) baselineWindow.removeFirst()
        }
        if (baselineWindow.isEmpty()) {
            baselineMean = signalValue
            baselineStd = 1.0
            significanceZ = 0.0
            return
        }
        baselineMean = baselineWindow.average()
        val variance = baselineWindow.map { v -> (v - baselineMean) * (v - baselineMean) }.average()
        baselineStd = kotlin.math.sqrt(variance).coerceAtLeast(1.0)
        significanceZ = ((signalValue - baselineMean) / baselineStd).coerceIn(-8.0, 12.0)
    }

    private fun updatePeakStability(peak: Double) {
        peakWindow.addLast(peak)
        while (peakWindow.size > 30) peakWindow.removeFirst()
        if (peakWindow.size < 2) {
            peakStability = 0.0
            return
        }
        val mean = peakWindow.average()
        val varPeak = peakWindow.map { (it - mean) * (it - mean) }.average()
        val stdPeak = kotlin.math.sqrt(varPeak)
        peakStability = (1.0 - (stdPeak / 42.0)).coerceIn(0.0, 1.0)
    }

    private fun updateSignificanceWindow(nowMs: Long, z: Double) {
        significanceTimedWindow.addLast(nowMs to z)
        while (significanceTimedWindow.isNotEmpty() && nowMs - significanceTimedWindow.first.first > 60_000L) {
            significanceTimedWindow.removeFirst()
        }
        if (significanceTimedWindow.isEmpty()) {
            significanceMean60s = 0.0
            significanceCi60s = 0.0
            return
        }
        val values = significanceTimedWindow.map { it.second }
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val std = kotlin.math.sqrt(variance)
        significanceMean60s = mean
        significanceCi60s = 1.96 * std / kotlin.math.sqrt(values.size.toDouble())
    }

    private fun updatePulseFeatures(nonZero: Int, mog2NonZero: Int, diffNonZero: Int) {
        val pulseActive = if (nonZero >= config.pulseActiveNonZeroThreshold) 1 else 0
        pulseWindow.addLast(pulseActive)
        while (pulseWindow.size > config.pulseWindowSize) pulseWindow.removeFirst()
        val activeCount = pulseWindow.sum()
        pulseDensity = if (pulseWindow.isNotEmpty()) activeCount.toDouble() / pulseWindow.size.toDouble() else 0.0
        val denom = maxOf(1.0, maxOf(mog2NonZero, diffNonZero).toDouble())
        neighborhoodConsistency = (1.0 - kotlin.math.abs(mog2NonZero - diffNonZero).toDouble() / denom).coerceIn(0.0, 1.0)
    }

    private fun updateCusum(evidence: Double, reset: Boolean, unstable: Boolean) {
        val config = activeConfig()
        if (!config.cusumEnabled) {
            cusumPositive = 0.0
            cusumNegative = 0.0
            cusumScore = 0.0
            cusumPass = true
            return
        }
        if (reset) {
            cusumPositive = 0.0
            cusumNegative = 0.0
            cusumScore = 0.0
            cusumPass = false
            return
        }
        val decay = if (unstable) (config.cusumDecay * 0.92).coerceIn(0.65, 0.99) else config.cusumDecay.coerceIn(0.65, 0.99)
        val centered = evidence - config.cusumDriftK
        cusumPositive = kotlin.math.max(0.0, cusumPositive * decay + centered)
        cusumNegative = kotlin.math.max(0.0, cusumNegative * decay - centered)
        cusumScore = (cusumPositive - cusumNegative).coerceIn(-8.0, 12.0)
        cusumPass = cusumPositive >= config.cusumThresholdH
    }

    private fun updateFeatureLite(nonZero: Int) {
        val config = activeConfig()
        val pulseActive = nonZero >= config.pulseActiveNonZeroThreshold
        if (pulseActive) {
            currentPulseRun = (currentPulseRun + 1).coerceAtMost(300)
        } else if (currentPulseRun > 0) {
            pulseWidthRuns.addLast(currentPulseRun)
            currentPulseRun = 0
            while (pulseWidthRuns.size > config.pulseWidthWindowSize) {
                pulseWidthRuns.removeFirst()
            }
        }
        if (pulseWidthRuns.isEmpty()) {
            pulseWidthConsistency = 0.0
        } else {
            val mean = pulseWidthRuns.average()
            val variance = pulseWidthRuns.map { (it - mean) * (it - mean) }.average()
            val std = kotlin.math.sqrt(variance)
            pulseWidthConsistency = (1.0 - std / (mean + 1.0)).coerceIn(0.0, 1.0)
        }

        nonZeroTimedWindow.addLast(nonZero)
        while (nonZeroTimedWindow.size > config.varianceWindowSize) nonZeroTimedWindow.removeFirst()
        if (nonZeroTimedWindow.size < 6) {
            shortWindowVarianceRatio = 1.0
            return
        }
        val half = nonZeroTimedWindow.size / 2
        val first = nonZeroTimedWindow.take(half)
        val second = nonZeroTimedWindow.drop(half)
        val v1 = varianceOf(first).coerceAtLeast(1.0)
        val v2 = varianceOf(second).coerceAtLeast(1.0)
        shortWindowVarianceRatio = (v2 / v1).coerceIn(0.1, 20.0)
    }

    private fun computeStackAdaptiveParams(): StackAdaptiveParams {
        val config = activeConfig()
        val result = FrameProcessorAlgorithms.computeStackAdaptive(
            depthBase = config.frameStackDepth,
            thresholdBase = config.frameStackThreshold,
            adaptiveEnabled = config.frameStackAdaptiveEnabled,
            depthMin = config.frameStackDepthMin,
            depthMax = config.frameStackDepthMax,
            noiseQuantile = lastNoiseQuantile,
            adaptiveDiffMin = config.adaptiveDiffMin,
            adaptiveDiffMax = config.adaptiveDiffMax,
            motionDx = lastMotionDx,
            motionDy = lastMotionDy,
            motionCompMaxPx = config.motionCompMaxPx,
            noiseBoost = config.frameStackNoiseBoost,
            motionBoost = config.frameStackMotionBoost,
        )
        return StackAdaptiveParams(depth = result.depth, threshold = result.threshold)
    }

    private fun applyRoiWeightToBinaryMask(mask: Mat): Int {
        val config = activeConfig()
        if (!config.roiWeightEnabled || !::roiWeightMat.isInitialized) return 0
        val beforeCount = Core.countNonZero(mask)
        val maskFloat = Mat()
        return try {
            mask.convertTo(maskFloat, CvType.CV_32F, 1.0 / 255.0)
            Core.multiply(maskFloat, roiWeightMat, maskFloat)
            Imgproc.threshold(maskFloat, maskFloat, 0.5, 1.0, Imgproc.THRESH_BINARY)
            maskFloat.convertTo(mask, CvType.CV_8U, 255.0)
            val afterCount = Core.countNonZero(mask)
            (beforeCount - afterCount).coerceAtLeast(0)
        } finally {
            maskFloat.release()
        }
    }

    private fun varianceOf(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }

    private fun updateBucketAdaptiveThresholds() {
        val config = activeConfig()
        if (!config.bucketAdaptationEnabled) {
            adaptedRiskTriggerHigh = config.riskScoreTriggerHigh
            adaptedRiskReleaseLow = config.riskScoreReleaseLow
            adaptedPoissonConfidenceMin = config.poissonConfidenceMin
            adaptedDeadTimeScale = 1.0
            tempAdaptiveFactor = 0.0
            return
        }
        val tempC = latestDeviceTempC
        val coolMax = config.tempBucketCoolMaxC
        val warmMin = maxOf(coolMax + config.tempAdaptiveHysteresisC, config.tempBucketWarmMinC)
        val rawFactor = when {
            tempC <= coolMax -> 0.0
            tempC >= warmMin -> 1.0
            else -> ((tempC - coolMax) / (warmMin - coolMax)).coerceIn(0.0, 1.0)
        }
        val alpha = config.tempAdaptiveSmoothAlpha.coerceIn(0.05, 1.0)
        tempAdaptiveFactor = if (tempAdaptiveFactor <= 0.0) rawFactor else {
            (alpha * rawFactor + (1.0 - alpha) * tempAdaptiveFactor).coerceIn(0.0, 1.0)
        }
        val riskDelta = config.bucketRiskTriggerDeltaCool +
            tempAdaptiveFactor * (config.bucketRiskTriggerDeltaWarm - config.bucketRiskTriggerDeltaCool)
        val poissonDelta = config.bucketPoissonConfidenceDeltaCool +
            tempAdaptiveFactor * (config.bucketPoissonConfidenceDeltaWarm - config.bucketPoissonConfidenceDeltaCool)
        val deadScale = config.bucketDeadTimeScaleCool +
            tempAdaptiveFactor * (config.bucketDeadTimeScaleWarm - config.bucketDeadTimeScaleCool)
        adaptedRiskTriggerHigh = (config.riskScoreTriggerHigh + riskDelta).coerceIn(0.3, 0.95)
        adaptedRiskReleaseLow = (config.riskScoreReleaseLow + riskDelta * 0.7).coerceIn(0.2, adaptedRiskTriggerHigh)
        adaptedPoissonConfidenceMin = (config.poissonConfidenceMin + poissonDelta).coerceIn(0.2, 0.9)
        adaptedDeadTimeScale = deadScale.coerceIn(0.8, 1.5)
    }

    private fun updatePoissonConsistency(nonZero: Int, nowMs: Long) {
        val config = activeConfig()
        if (!config.poissonCheckEnabled) {
            poissonCv = 0.0
            poissonRawCv = 0.0
            poissonCorrectedCv = 0.0
            poissonPass = true
            poissonConfidence = 1.0
            deadTimeMsUsed = 0.0
            pulseAcceptedCount = 0
            pulseDroppedByDeadTime = 0
            poissonSampleCount = 0
            poissonWarmupReady = true
            return
        }
        val active = nonZero >= config.pulseActiveNonZeroThreshold
        val windowTotal = deadTimeDropWindow.size.coerceAtLeast(1)
        val dropRatioNow = deadTimeDropWindowDropped.toDouble() / windowTotal.toDouble()
        val emaAlpha = config.deadTimeDropEmaAlpha.coerceIn(0.05, 1.0)
        dropRatioEma = if (dropRatioEma <= 0.0) {
            dropRatioNow
        } else {
            (emaAlpha * dropRatioNow + (1.0 - emaAlpha) * dropRatioEma).coerceIn(0.0, 1.0)
        }
        if (!active && !lastPoissonPulseActive) {
            // No pulse transition in this frame: slowly relax toward target to improve recovery speed after scene shifts.
            val idleAlpha = (emaAlpha * 0.15).coerceIn(0.01, 0.2)
            dropRatioEma = ((1.0 - idleAlpha) * dropRatioEma + idleAlpha * config.deadTimeDropTarget).coerceIn(0.0, 1.0)
        }
        deadTimeMsUsed = if (config.deadTimeEnabled) {
            FrameProcessorAlgorithms.computeDeadTimeMs(
                noiseQuantile = lastNoiseQuantile,
                tempSlopeCPerSec = tempSlopeCPerSec,
                adaptiveDiffMin = config.adaptiveDiffMin,
                adaptiveDiffMax = config.adaptiveDiffMax,
                deadTimeMsMin = config.deadTimeMsMin,
                deadTimeMsMax = config.deadTimeMsMax,
                deadTimeNoiseScale = config.deadTimeNoiseScale,
                deadTimeTempScale = config.deadTimeTempScale,
                tempSlopeThresholdCPerSec = config.tempSlopeThresholdCPerSec,
                dropRatio = dropRatioEma,
                dropTarget = config.deadTimeDropTarget,
                dropFeedbackGain = config.deadTimeDropFeedbackGain,
            )
        } else {
            0.0
        }
        deadTimeMsUsed = (deadTimeMsUsed * adaptedDeadTimeScale).coerceIn(config.deadTimeMsMin, config.deadTimeMsMax * 1.5)
        if (active && !lastPoissonPulseActive) {
            val accepted = if (config.deadTimeEnabled) {
                FrameProcessorAlgorithms.acceptPulseWithDeadTime(
                    lastAcceptedTimestampMs = lastAcceptedPulseMs,
                    nowMs = nowMs,
                    deadTimeMs = deadTimeMsUsed,
                )
            } else {
                true
            }
            if (accepted) {
                eventTimestamps.addLast(nowMs)
                lastAcceptedPulseMs = nowMs
                pulseAcceptedCount++
                deadTimeDropWindow.addLast(false)
                while (eventTimestamps.size > config.poissonMinEvents * 4) {
                    eventTimestamps.removeFirst()
                }
            } else {
                pulseDroppedByDeadTime++
                deadTimeDropWindow.addLast(true)
                deadTimeDropWindowDropped++
            }
            val dropWindowLimit = config.deadTimeDropWindowSize.coerceAtLeast(8)
            while (deadTimeDropWindow.size > dropWindowLimit) {
                val oldestDropped = deadTimeDropWindow.removeFirst()
                if (oldestDropped) {
                    deadTimeDropWindowDropped = (deadTimeDropWindowDropped - 1).coerceAtLeast(0)
                }
            }
        }
        lastPoissonPulseActive = active
        val decision = FrameProcessorAlgorithms.evaluatePoisson(
            timestamps = eventTimestamps.toList(),
            poissonEnabled = config.poissonCheckEnabled,
            poissonMinEvents = config.poissonMinEvents,
            poissonCvMin = config.poissonCvMin,
            poissonCvMax = config.poissonCvMax,
            poissonConfidenceSampleTarget = config.poissonConfidenceSampleTarget,
            poissonConfidenceMinWeight = config.poissonConfidenceMinWeight,
            deadTimeMs = if (config.deadTimeEnabled) deadTimeMsUsed else 0.0,
            startupGuardEnabled = config.poissonStartupGuardEnabled,
            startupMinEvents = config.poissonStartupMinEvents,
        )
        poissonCv = decision.cv
        poissonRawCv = decision.rawCv
        poissonCorrectedCv = decision.cv
        poissonPass = decision.pass
        poissonConfidence = decision.confidence
        poissonSampleCount = decision.sampleCount
        poissonWarmupReady = decision.warmupReady
    }

    private fun updateDeepModelProbability(frame: Mat) {
        val config = activeConfig()
        if (!config.deepModelEnabled) {
            deepModelProbability = 0.0
            return
        }
        val p = deepModelInference?.invoke(frame)
        deepModelProbability = p?.coerceIn(0.0, 1.0) ?: deepModelProbability * 0.92
    }

    private fun updateMeasurementCondition(frame: Mat, dx: Double, dy: Double) {
        val config = activeConfig()
        val mean = MatOfDouble()
        val std = MatOfDouble()
        val satMask = Mat()
        try {
            Core.meanStdDev(frame, mean, std)
            val meanLuma = mean.get(0, 0)?.firstOrNull() ?: 255.0
            val stdLuma = std.get(0, 0)?.firstOrNull() ?: 255.0
            Imgproc.threshold(frame, satMask, 245.0, 255.0, Imgproc.THRESH_BINARY)
            val satCount = Core.countNonZero(satMask)
            val total = maxOf(1, frame.rows() * frame.cols())
            lastSaturationRatio = satCount.toDouble() / total.toDouble()

            val darkFieldMeanThreshold = config.darkFieldMaxMean + config.calibrationDarkFieldOffset
            darkFieldReady =
                meanLuma <= darkFieldMeanThreshold &&
                stdLuma <= config.darkFieldMaxStd &&
                lastSaturationRatio <= config.darkFieldMaxSaturationRatio

            val motionMag = kotlin.math.hypot(dx, dy)
            motionWindow.addLast(motionMag)
            while (motionWindow.size > 30) motionWindow.removeFirst()
            val motionAvg = if (motionWindow.isNotEmpty()) motionWindow.average() else 0.0
            motionStable = motionMag <= config.motionStableMaxPx && motionAvg <= config.motionStableAvgMaxPx

            measurementReliability = when {
                darkFieldReady && motionStable -> MeasurementReliability.RELIABLE
                darkFieldReady || motionStable -> MeasurementReliability.LIMITED
                else -> MeasurementReliability.POOR
            }
        } finally {
            mean.release()
            std.release()
            satMask.release()
        }
    }

    private fun applyMotionCompensation(current: Mat, previous: Mat): MotionCompResult {
        val config = activeConfig()
        val prevSmall = Mat()
        val currSmall = Mat()
        val prev32 = Mat()
        val curr32 = Mat()
        try {
            Imgproc.resize(previous, prevSmall, Size(config.motionEstimateWidth.toDouble(), config.motionEstimateHeight.toDouble()))
            Imgproc.resize(current, currSmall, Size(config.motionEstimateWidth.toDouble(), config.motionEstimateHeight.toDouble()))
            prevSmall.convertTo(prev32, CvType.CV_32F)
            currSmall.convertTo(curr32, CvType.CV_32F)
            val shift = Imgproc.phaseCorrelate(prev32, curr32)
            val scaleX = current.cols().toDouble() / config.motionEstimateWidth
            val scaleY = current.rows().toDouble() / config.motionEstimateHeight
            val dx = shift.x * scaleX
            val dy = shift.y * scaleY

            if (kotlin.math.abs(dx) > config.motionCompMaxPx || kotlin.math.abs(dy) > config.motionCompMaxPx) {
                return MotionCompResult(current, 0.0, 0.0)
            }

            val transform = Mat(2, 3, CvType.CV_64F)
            transform.put(0, 0, 1.0)
            transform.put(0, 1, 0.0)
            transform.put(0, 2, -dx)
            transform.put(1, 0, 0.0)
            transform.put(1, 1, 1.0)
            transform.put(1, 2, -dy)

            val compensated = Mat()
            Imgproc.warpAffine(
                current,
                compensated,
                transform,
                current.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE,
                Scalar(0.0)
            )
            transform.release()
            return MotionCompResult(compensated, dx, dy)
        } catch (_: Exception) {
            return MotionCompResult(current, 0.0, 0.0)
        } finally {
            prevSmall.release()
            currSmall.release()
            prev32.release()
            curr32.release()
        }
    }

    private fun computeTrackFeature(contours: List<MatOfPoint>, maxArea: Double): TrackFeature {
        if (contours.isEmpty() || maxArea <= 0.0) {
            return TrackFeature(
                stability = (trackStability * 0.9).coerceIn(0.0, 1.0),
                centerX = lastTrackCenterX,
                centerY = lastTrackCenterY,
                area = 0.0,
            )
        }
        var best: MatOfPoint? = null
        var bestArea = 0.0
        for (c in contours) {
            val a = Imgproc.contourArea(c)
            if (a > bestArea) {
                bestArea = a
                best = c
            }
        }
        val target = best ?: return TrackFeature(trackStability, lastTrackCenterX, lastTrackCenterY, 0.0)
        val m = Imgproc.moments(target, true)
        if (m.m00 <= 1.0) return TrackFeature(trackStability, lastTrackCenterX, lastTrackCenterY, bestArea)
        val cx = m.m10 / m.m00
        val cy = m.m01 / m.m00

        if (lastTrackCenterX.isNaN() || lastTrackCenterY.isNaN() || lastTrackArea <= 0.0) {
            return TrackFeature(0.5, cx, cy, bestArea)
        }
        val drift = kotlin.math.hypot(cx - lastTrackCenterX, cy - lastTrackCenterY)
        val driftNorm = (drift / 80.0).coerceIn(0.0, 1.0)
        val areaChange = kotlin.math.abs(bestArea - lastTrackArea) / maxOf(lastTrackArea, 1.0)
        val areaNorm = areaChange.coerceIn(0.0, 1.0)
        val stability = (1.0 - (0.65 * driftNorm + 0.35 * areaNorm)).coerceIn(0.0, 1.0)
        return TrackFeature(stability, cx, cy, bestArea)
    }

    private fun buildDualSuppressionSignal(anchor: Mat, other: Mat, anchorTsNs: Long, otherTsNs: Long): DualSuppressionSignal {
        val config = activeConfig()
        val globalFlash = Core.mean(other).`val`[0] > config.luminanceFlashThreshold
        val deltaNs = kotlin.math.abs(anchorTsNs - otherTsNs)
        val pairPenalty = (deltaNs.toDouble() / config.dualPairWindowNs.toDouble()).coerceIn(0.0, 1.0)
        if (!globalFlash) return DualSuppressionSignal(false, false, false, pairPenalty * 0.2)

        val anchorMask = Mat()
        val otherMask = Mat()
        try {
            Imgproc.threshold(anchor, anchorMask, config.dualHotspotThreshold, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(other, otherMask, config.dualHotspotThreshold, 255.0, Imgproc.THRESH_BINARY)
            val anchorMom = Imgproc.moments(anchorMask, true)
            val otherMom = Imgproc.moments(otherMask, true)
            if (anchorMom.m00 <= 1.0 || otherMom.m00 <= 1.0) {
                return DualSuppressionSignal(globalFlash = true, localConsistent = false, shouldSuppress = false, pairPenalty = pairPenalty * 0.5)
            }
            val anchorCenter = Point(anchorMom.m10 / anchorMom.m00, anchorMom.m01 / anchorMom.m00)
            val otherCenter = Point(otherMom.m10 / otherMom.m00, otherMom.m01 / otherMom.m00)
            val distance = kotlin.math.hypot(anchorCenter.x - otherCenter.x, anchorCenter.y - otherCenter.y)
            val localConsistent = distance <= config.dualHotspotMaxDistance
            val fullPenalty = (pairPenalty + if (localConsistent) 0.4 else 0.1).coerceIn(0.0, 1.0)
            return DualSuppressionSignal(
                globalFlash = true,
                localConsistent = localConsistent,
                shouldSuppress = localConsistent,
                pairPenalty = fullPenalty,
            )
        } finally {
            anchorMask.release()
            otherMask.release()
        }
    }

    /**
     * 清理资源（在FrameProcessor生命周期结束时调用）
     * 幂等：可以重复调用
     */
    @Synchronized
    fun close() {
        if (closeInProgress) return
        closeInProgress = true
        try {
        backgroundSubtractor = null
        previousAlignedFrame?.release()
        previousAlignedFrame = null
        diffNoiseWindow.clear()
        motionWindow.clear()
        confirmWindow.clear()
        baselineWindow.clear()
        peakWindow.clear()
        significanceTimedWindow.clear()
        pulseWindow.clear()
        if (::fgMask.isInitialized) {
            fgMask.release()
        }
        if (::diffMask.isInitialized) {
            diffMask.release()
        }
        if (::fusedMask.isInitialized) {
            fusedMask.release()
        }
        if (::analysisMask.isInitialized) {
            analysisMask.release()
        }
        if (::hotPixelHeatMap.isInitialized) {
            hotPixelHeatMap.release()
        }
        if (::hotPixelMask.isInitialized) {
            hotPixelMask.release()
        }
        if (::hotPixelInvMask.isInitialized) {
            hotPixelInvMask.release()
        }
        if (::staticHotPixelMask.isInitialized) {
            staticHotPixelMask.release()
        }
        if (::hotPixelHitMask.isInitialized) {
            hotPixelHitMask.release()
        }
        if (::roiWeightMat.isInitialized) {
            roiWeightMat.release()
        }
        if (::stackAccumulator.isInitialized) {
            stackAccumulator.release()
        }
        for (m in stackQueue) m.release()
        stackQueue.clear()
        if (::morphKernel.isInitialized) {
            morphKernel.release()
        }
        warmupStartMs = -1L
        warmupActive = true
        hotPixelSuppressedCount = 0
        lastTrackCenterX = Double.NaN
        lastTrackCenterY = Double.NaN
        lastTrackArea = 0.0
        trackStability = 0.0
        confirmScore = 0.0
        lastPairPenalty = 0.0
        darkFieldReady = false
        motionStable = true
        measurementReliability = MeasurementReliability.POOR
        lastSaturationRatio = 0.0
        significanceZ = 0.0
        baselineMean = 0.0
        baselineStd = 1.0
        peakIntensity = 0.0
        localContrast = 0.0
        eventFeatureScore = 0.0
        classifierProbability = 0.0
        sustainedFrames = 0
        trajectoryLength = 0.0
        peakStability = 0.0
        latestDeviceTempC = 25.0
        tempCompensationTerm = 0.0
        diffContribution = 0.0
        mog2Contribution = 0.0
        fusionDecisionPath = "and"
        suppressionApplied = false
        suppressionReason = "none"
        suppressionDelayedUntilMs = 0L
        significanceMean60s = 0.0
        significanceCi60s = 0.0
        pulseDensity = 0.0
        neighborhoodConsistency = 0.0
        deepModelProbability = 0.0
        cusumPositive = 0.0
        cusumNegative = 0.0
        cusumScore = 0.0
        cusumPass = false
        hotPixelMapBucket = "unknown"
        hotPixelMapSize = 0
        hotPixelMapHitCount = 0
        pulseWidthConsistency = 0.0
        shortWindowVarianceRatio = 1.0
        pulseWidthRuns.clear()
        currentPulseRun = 0
        nonZeroTimedWindow.clear()
        roiEdgeSuppressedCount = 0
        stackedNonZeroCount = 0
        eventTimestamps.clear()
        poissonCv = 0.0
        poissonRawCv = 0.0
        poissonCorrectedCv = 0.0
        poissonPass = false
        poissonConfidence = 0.0
        riskScore = 0.0
        riskTriggerState = false
        lastPoissonPulseActive = false
        lastAcceptedPulseMs = 0L
        deadTimeMsUsed = 0.0
        pulseAcceptedCount = 0
        pulseDroppedByDeadTime = 0
        tempSlopeCPerSec = 0.0
        tempCompPhase = "steady"
        lastTempUpdateMs = 0L
        lastTempForSlope = 25.0
        stackDepthUsed = 0
        stackThresholdUsed = 0.0
        poissonSampleCount = 0
        poissonWarmupReady = false
        roiAppliedStage = "post_analysis"
        adaptedRiskTriggerHigh = config.riskScoreTriggerHigh
        adaptedRiskReleaseLow = config.riskScoreReleaseLow
        adaptedPoissonConfidenceMin = config.poissonConfidenceMin
        adaptedDeadTimeScale = 1.0
        tempAdaptiveFactor = 0.0
        dropRatioEma = 0.0
        deadTimeDropWindow.clear()
        deadTimeDropWindowDropped = 0
        funnelCandidateCount = 0
        funnelSafetyPassCount = 0
        funnelRiskPassCount = 0
        funnelConfirmedCount = 0
        funnelSuppressedCount = 0
        // 重置缓存状态
        cachedW = 0
        cachedH = 0
        } finally {
            closeInProgress = false
        }
    }
}