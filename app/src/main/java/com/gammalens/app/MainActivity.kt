package com.gammalens.app

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import kotlin.math.sqrt
import android.content.res.Configuration
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import android.graphics.SurfaceTexture
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gammalens.app.camera.CameraPipeline
import com.gammalens.app.camera.DetectionConfig
import com.gammalens.app.camera.DetectionMode
import com.gammalens.app.camera.EventListener
import com.gammalens.app.camera.FramePacket
import com.gammalens.app.camera.FrameProcessor
import com.gammalens.app.camera.FrameStatsSnapshot
import com.gammalens.app.camera.FrameSynchronizer
import com.gammalens.app.camera.MeasurementReliability
import com.gammalens.app.camera.OpenCvBootstrap
import com.gammalens.app.camera.ProcessableItem
import com.gammalens.app.camera.WhiteDotTfliteClassifier
import com.gammalens.app.camera.releaseMatsSafely
import com.gammalens.app.data.StatsRepository
import com.gammalens.app.data.EventRepository
import com.gammalens.app.data.DebugLogRepository
import com.gammalens.app.data.HistogramRepository
import com.gammalens.app.data.DeviceProfile
import com.gammalens.app.data.DeviceCalibrationRepository
import com.gammalens.app.data.EventItem
import com.gammalens.app.data.RunSnapshotManager
import com.gammalens.app.config.ReleaseVersionConfig
import com.gammalens.app.ui.LiveFragment
import com.gammalens.app.ui.EventsFragment
import com.gammalens.app.ui.SpectrumFragment
import com.gammalens.app.ui.CalibrationFragment
import com.gammalens.app.ui.DebugFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 主活动：GammaLens Omni - 5 标签 UI，检测管线不变。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CAMERA_PERMISSION = 100
        private const val LOG_OPENCV_VERBOSE = false
        private const val PRIMARY_STREAM_ID = "cam0"
        private const val STATUS_INFO = "info"
        private const val STATUS_WARN = "warn"
        private const val STATUS_ERROR = "error"
    }

    val statsRepository = StatsRepository()
    val eventRepository = EventRepository()
    val debugLogRepository = DebugLogRepository()
    val histogramRepository = HistogramRepository(eventRepository)
    var deviceProfile = DeviceProfile(
        deviceModel = android.os.Build.MODEL ?: "",
        captureSize = "1280x720",
        fpsTarget = 10,
    )
    var runSnapshotManager: RunSnapshotManager? = null
    private lateinit var calibrationRepository: DeviceCalibrationRepository
    private lateinit var detectionConfig: DetectionConfig

    private var previewTextureView: TextureView? = null
    private var pipeline0: CameraPipeline? = null
    private var pipeline1: CameraPipeline? = null
    private var requestedStart = false
    private var isResumed = false
    private var isTextureReady = false
    private lateinit var frameProcessor: FrameProcessor
    private var deepModelClassifier: WhiteDotTfliteClassifier? = null
    private lateinit var sharedSync: FrameSynchronizer
    private lateinit var orientationEventListener: OrientationEventListener
    private var lastRotationInt = android.view.Surface.ROTATION_0
    private lateinit var frontIds: List<String>
    private var eventCount = 0
    private var lastEventTimeMs = 0L
    private var lastScoreEma = 0.0
    private var lastCooldownSeconds = 0
    private val eventTimesMs = ArrayDeque<Long>()
    private val loggedFirstFrameStreams = HashSet<String>()
    private var dispatchThread: HandlerThread? = null
    private var dispatchHandler: Handler? = null
    private var primaryOnlyLogged = false
    private val uiUpdateHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            pushStatsToRepository()
            uiUpdateHandler.postDelayed(this, 1000)
        }
    }
    private var lastFps = 0f
    private var lastDualActive = false
    private var lastFallback = false
    private var lastSuppressionActive = false
    private var lastProcessMsAvg = 0.0
    private var lastMog2NonZero = 0
    private var lastDiffNonZero = 0
    private var lastFusedNonZero = 0
    private var lastCandidateCount = 0
    private var lastSuppressedCount = 0
    private var lastDarkFieldReady = false
    private var lastMotionStable = true
    private var lastReliability = "POOR"
    private var reliabilityReliableSamples = 0
    private var reliabilityLimitedSamples = 0
    private var reliabilityPoorSamples = 0
    private var lastSignificanceZ = 0.0
    private var significanceMean30s = 0.0
    private var significanceMean60s = 0.0
    private var significanceCi30s = 0.0
    private var significanceCi60s = 0.0
    private var stabilityLevel = "UNSTABLE"
    private var lastClassifierProbability = 0.0
    private var lastDeepModelProbability = 0.0
    private var lastCusumScore = 0.0
    private var lastCusumPass = false
    private var lastDiffContribution = 0.0
    private var lastMog2Contribution = 0.0
    private var lastFusionDecisionPath = "and_fallback"
    private var lastSuppressionApplied = false
    private var lastSuppressionReason = "none"
    private var lastSustainedFrames = 0
    private var lastTrajectoryLength = 0.0
    private var lastPeakStability = 0.0
    private var lastTempCompensationTerm = 0.0
    private var lastDeviceTempC = 25.0
    private val significanceSamples = ArrayDeque<Pair<Long, Double>>()
    private var darkSessionSampleCount = 0
    private var darkSessionBaselineSum = 0.0
    private var darkSessionHotPixelSum = 0.0
    private var lastCalibrationDeltaOffset = 0.0
    private var lastCalibrationDeltaHotGain = 0.0
    private var lastCalibrationRollbackSuggested = false
    private var lastTemperatureBucket = "unknown"
    private var lastHotPixelMapSize = 0
    private var lastHotPixelMapHitCount = 0
    private var lastPulseWidthConsistency = 0.0
    private var lastShortWindowVarianceRatio = 1.0
    private var lastRoiEdgeSuppressedCount = 0
    private var lastStackedNonZeroCount = 0
    private var lastPoissonCv = 0.0
    private var lastPoissonPass = false
    private var lastPoissonConfidence = 0.0
    private var lastRiskScore = 0.0
    private var lastRiskTriggerState = false
    private var lastTempSlopeCPerSec = 0.0
    private var lastTempCompPhase = "steady"
    private var lastDeadTimeMsUsed = 0.0
    private var lastPulseAcceptedCount = 0
    private var lastPulseDroppedByDeadTime = 0
    private var lastStackDepthUsed = 0
    private var lastStackThresholdUsed = 0.0
    private var lastPoissonSampleCount = 0
    private var lastPoissonWarmupReady = false
    private var lastRoiAppliedStage = "post_analysis"
    private var lastFunnelCandidateCount = 0
    private var lastFunnelSafetyPassCount = 0
    private var lastFunnelRiskPassCount = 0
    private var lastFunnelConfirmedCount = 0
    private var lastFunnelSuppressedCount = 0
    private var systemStatusMessage = ""
    private var systemStatusLevel = STATUS_INFO
    private var lastStatusToastMs = 0L
    private var currentScenarioId = "dark_static"
    private var currentExperimentId = "prod"
    private var currentVariantId = "balanced"
    private var currentReleaseState = ReleaseVersionConfig.DEFAULT_RELEASE_STATE
    private var currentModelVersion = ReleaseVersionConfig.DEFAULT_MODEL_VERSION
    private var lastRollbackReason = "none"
    private var shadedPrecisionMode = true
    @Volatile private var currentDetectionMode: DetectionMode = DetectionMode.FUSION

    fun setPreviewTextureView(v: TextureView?) {
        if (previewTextureView == v) return
        if (previewTextureView != null) {
            stopCameraPreview()
            requestedStart = false
            isTextureReady = false
        }
        previewTextureView = v
        if (v != null) {
            v.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    Log.d(TAG, "TextureView surface available: ${width}x${height}")
                    Log.i("GL_SURF", "surface available: ${width}x${height}, hash=${surface.hashCode()}")
                    applyDisplayRotationToPipeline()
                    isTextureReady = true
                    tryStartPipeline()
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                    Log.i("GL_SURF", "surface destroyed, hash=${surface.hashCode()}")
                    stopCameraPreview()
                    requestedStart = false
                    isTextureReady = false
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            }
            if (v.isAvailable) {
                isTextureReady = true
                tryStartPipeline()
            }
        }
    }

    private fun getTextureView(): TextureView? = previewTextureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedSync = FrameSynchronizer(enablePairing = false).apply {
            setPairWindowNs(detectionConfig.dualPairWindowNs)
        }
        calibrationRepository = DeviceCalibrationRepository(this)
        val calibration = calibrationRepository.loadOrDefault()
        detectionConfig = DetectionConfig.defaultInstance()
        detectionConfig.calibrationKey = calibration.key
        detectionConfig.calibrationDarkFieldOffset = calibration.darkFieldOffset
        detectionConfig.calibrationHotPixelGain = calibration.hotPixelGain
        detectionConfig.calibrationTempCompensationCoeff = calibration.tempCompensationCoeff
        detectionConfig.calibrationVersion = calibration.version
        detectionConfig.releaseState = currentReleaseState
        detectionConfig.modelVersion = currentModelVersion

        // 初始化帧处理器（回调含 blob 信息供事件列表与导出）
        deepModelClassifier = if (detectionConfig.deepModelEnabled) {
            WhiteDotTfliteClassifier(
                context = this,
                inputSize = detectionConfig.deepModelInputSize,
            )
        } else {
            null
        }
        frameProcessor = FrameProcessor(
            onRadiationCandidate = this::onRadiationCandidateDetected,
            initialConfig = detectionConfig,
            deepModelInference = { gray -> deepModelClassifier?.infer(gray) },
        )
        frameProcessor.onStatsSnapshot = this::onFrameStatsSnapshot
        frameProcessor.onSuppressed = this::onSuppressedByDualFlash
        frameProcessor.onEvtLog = debugLogRepository::pushGlEvt
        sharedSync.onSyncLog = debugLogRepository::pushGlSync
        frameProcessor.setDetectionMode(currentDetectionMode)
        frameProcessor.setShadedPrecisionMode(shadedPrecisionMode)
        applyCurrentHotPixelMap()

        // 初始化方向监听器
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                // 禁用旋转更新，App锁定为竖屏
                return
            }
        }

        // 检查 OpenCV 初始化
        if (!OpenCvBootstrap.ensureInitialized()) {
            Log.e(TAG, "OpenCV initialization failed, camera will not start")
            setSystemStatus(getString(R.string.status_camera_init_failed), level = STATUS_ERROR, toast = true)
            Toast.makeText(this, "OpenCV 初始化失败", Toast.LENGTH_LONG).show()
            return
        }

        // OpenCV初始化成功提示
        if (LOG_OPENCV_VERBOSE) {
            // 保留详细的OpenCV信息输出（如果需要）
            Log.i("OpenCvBootstrap", "OpenCV init OK - verbose mode enabled")
        } else {
            Log.i("OpenCvBootstrap", "OpenCV init OK")
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LiveFragment())
            .commit()

        findViewById<BottomNavigationView>(R.id.bottom_nav)?.setOnItemSelectedListener { item ->
            val frag = when (item.itemId) {
                R.id.nav_live -> LiveFragment()
                R.id.nav_events -> EventsFragment()
                R.id.nav_spectrum -> SpectrumFragment()
                R.id.nav_calibration -> CalibrationFragment()
                R.id.nav_debug -> DebugFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag)
                .commit()
            true
        }

        Log.d(TAG, "Activity created")
    }

    private fun onRadiationCandidateDetected(
        streamId: String,
        scoreEma: Double,
        _tsNs: Long,
        cooldownSeconds: Int,
        blobCount: Int,
        maxArea: Double,
        nonZeroCount: Int,
    ) {
        runOnUiThread {
            eventCount++
            val now = SystemClock.elapsedRealtime()
            lastEventTimeMs = now
            lastScoreEma = scoreEma
            lastCooldownSeconds = cooldownSeconds
            eventTimesMs.addLast(now)
            val cutoffTime = now - 60_000
            while (eventTimesMs.isNotEmpty() && eventTimesMs.first < cutoffTime) {
                eventTimesMs.removeFirst()
            }
            val dualSnapshot = if (lastDualActive) "dual pairing=$lastSuppressionActive" else null
            val timestamp = System.currentTimeMillis()
            eventRepository.addEvent(
                timestampMs = timestamp,
                streamId = streamId,
                scoreEma = scoreEma,
                blobCount = blobCount,
                maxArea = maxArea.toFloat(),
                nonZeroCount = nonZeroCount,
                suppressed = false,
                suppressReason = null,
                dualSnapshot = dualSnapshot,
            )
            runSnapshotManager?.appendEvent(
                EventItem(0L, timestamp, streamId, scoreEma, blobCount, maxArea.toFloat(), nonZeroCount, false, null, dualSnapshot)
            )
            pushStatsToRepository()
        }
    }

    private fun onSuppressedByDualFlash(
        streamId: String,
        scoreEma: Double,
        blobCount: Int,
        maxArea: Double,
        nonZeroCount: Int,
    ) {
        runOnUiThread {
            val ts = System.currentTimeMillis()
            eventRepository.addEvent(
                timestampMs = ts,
                streamId = streamId,
                scoreEma = scoreEma,
                blobCount = blobCount,
                maxArea = maxArea.toFloat(),
                nonZeroCount = nonZeroCount,
                suppressed = true,
                suppressReason = "dual otherHadFlash",
                dualSnapshot = "dual",
            )
            runSnapshotManager?.appendEvent(
                EventItem(0L, ts, streamId, scoreEma, blobCount, maxArea.toFloat(), nonZeroCount, true, "dual otherHadFlash", "dual")
            )
            histogramRepository.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true

        frameProcessor.eventListener = object : EventListener {
            override fun onRadiationEvent(score: Int, blobCount: Int, maxArea: Float) {
                // 事件已在 onRadiationCandidate 中写入 eventRepository
            }
        }

        uiUpdateHandler.post(uiUpdateRunnable)
        setSystemStatus(getString(R.string.status_waiting_camera), level = STATUS_INFO)

        tryStartPipeline()
    }

    override fun onPause() {
        super.onPause()
        // 停止UI更新
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
        stopCameraPreview()
        requestedStart = false
        isResumed = false
    }

    /**
     * 权限请求结果回调
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permission granted")
                setSystemStatus(getString(R.string.status_waiting_camera), level = STATUS_INFO)
                tryStartPipeline()
            } else {
                Log.e(TAG, "Camera permission denied")
                setSystemStatus(getString(R.string.status_camera_permission_denied), level = STATUS_ERROR, toast = true)
                Toast.makeText(this, "需要相机权限才能运行应用", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 尝试启动相机管线
     * 只有在所有条件满足且从未启动过时才真正启动
     */
    private fun tryStartPipeline() {
        // 条件检查：必须 resumed + texture ready + 权限OK + 未启动过
        if (!isResumed || !isTextureReady) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            setSystemStatus(getString(R.string.status_permission_required), level = STATUS_ERROR)
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
            return
        }

        if (requestedStart) {
            return
        }

        val textureView = getTextureView()
        if (textureView == null) {
            return
        }
        setSystemStatus(getString(R.string.status_waiting_camera), level = STATUS_INFO)

        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

            startDispatchThread()

            val backPair = pickBackCameras(cameraManager)
            val isDual = backPair != null
            sharedSync = FrameSynchronizer(enablePairing = isDual).apply {
                setPairWindowNs(detectionConfig.dualPairWindowNs)
            }
            sharedSync.onSyncLog = { line -> debugLogRepository.pushGlSync(line) }
            sharedSync.reset()

            if (backPair != null) {
                // 双摄模式
                val (cam0, cam1) = backPair
                Log.i("GL_DUAL", "START cam0=$cam0 cam1=$cam1")

                // 检查是否是mixed facing（cam1是前置）
                val isMixedFacing = frontIds.contains(cam1)

                // 统一的frame packet处理回调（串行到 FrameDispatch 线程）
                val onFramePacketCallback: (FramePacket) -> Unit = { packet ->
                    dispatchFramePacket(packet) { streamId ->
                        if (streamId == "cam0") cam0 else cam1
                    }
                }

                // 创建双摄pipeline
                pipeline0 = CameraPipeline(
                    context = this,
                    cameraId = cam0,
                    streamId = "cam0",
                    textureView = textureView, // cam0使用屏幕预览
                    onFramePacket = onFramePacketCallback
                )

                pipeline1 = CameraPipeline(
                    context = this,
                    cameraId = cam1,
                    streamId = "cam1",
                    textureView = textureView, // 仍然传textureView，但不会使用
                    onFramePacket = onFramePacketCallback,
                    enablePreview = false // cam1仅采集不预览
                )

                Log.i("GL_DUAL", "PIPELINE_IDS cam0Id=$cam0 cam1Id=$cam1")
                applyDisplayRotationToPipeline()

                // 启动双摄（先 cam0 保证预览；延迟开 cam1 减轻 ERROR_CAMERA_IN_USE）
                Log.i("GL_DUAL", "START_CALL cam0=$cam0 cam1=$cam1")
                requestedStart = true
                pipeline0?.start()
                uiUpdateHandler.postDelayed({
                    pipeline1?.start()
                }, 400)
                Log.i("GL_DUAL", "START_CALLED")
                val mvpLine = "PASS mode=dual dualActive=true captureSize=1280x720 fpsTarget=10 pairing=true suppression=active"
                Log.i("MVP_STATUS", mvpLine)
                debugLogRepository.pushMvpStatus(mvpLine)
                setSystemStatus(getString(R.string.status_camera_dual_running), level = STATUS_INFO)
                lastDualActive = true
                lastFallback = false
                lastSuppressionActive = true
                deviceProfile = deviceProfile.copy(cameraId = "$cam0,$cam1")
                startRunSnapshot(currentModeLabel())

            } else {
                // 单摄退化模式 - 选择第一个可用的后置摄像头
                Log.i("GL_DUAL", "DUAL_NOT_SUPPORTED using_single_cam")
                val allIds = cameraManager.cameraIdList
                val backIds = allIds.filter { id ->
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
                val singleCamId = backIds.firstOrNull() ?: "0" // 默认fallback到0

                // 统一的frame packet处理回调（串行到 FrameDispatch 线程）
                val onFramePacketCallback: (FramePacket) -> Unit = { packet ->
                    dispatchFramePacket(packet) { _ -> singleCamId }
                }

                pipeline0 = CameraPipeline(
                    context = this,
                    cameraId = singleCamId,
                    streamId = "cam0",
                    textureView = textureView,
                    onFramePacket = onFramePacketCallback
                )
                applyDisplayRotationToPipeline()

                // 启动单摄
                requestedStart = true
                pipeline0?.start()
                val mvpLineSingle = "PASS mode=single dualActive=false captureSize=1280x720 fpsTarget=10 pairing=false suppression=n/a"
                Log.i("MVP_STATUS", mvpLineSingle)
                debugLogRepository.pushMvpStatus(mvpLineSingle)
                setSystemStatus(getString(R.string.status_camera_single_running), level = STATUS_WARN)
                lastDualActive = false
                lastFallback = false
                lastSuppressionActive = false
                deviceProfile = deviceProfile.copy(cameraId = singleCamId)
                startRunSnapshot(currentModeLabel())
            }

        } catch (e: Exception) {
            Log.e("GL_DUAL", "ERROR ${e.message}")
            setSystemStatus(getString(R.string.status_camera_dual_failed), level = STATUS_WARN, toast = true)
            // 退化为单摄重试
            sharedSync = FrameSynchronizer(enablePairing = false).apply {
                setPairWindowNs(detectionConfig.dualPairWindowNs)
            }
            sharedSync.reset()
            if (pipeline0 == null) {
                try {
                    val fallbackCameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                    // 选择第一个可用的后置摄像头
                    val allIds = fallbackCameraManager.cameraIdList
                    val backIds = allIds.filter { id ->
                        val characteristics = fallbackCameraManager.getCameraCharacteristics(id)
                        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    }
                    val fallbackCamId = backIds.firstOrNull() ?: "0" // 默认fallback到0

                    // 统一的frame packet处理回调（串行到 FrameDispatch 线程）
                    val onFramePacketCallback: (FramePacket) -> Unit = { packet ->
                        dispatchFramePacket(packet) { _ -> fallbackCamId }
                    }

                    pipeline0 = CameraPipeline(
                        context = this,
                        cameraId = fallbackCamId,
                        streamId = "cam0",
                        textureView = textureView,
                        onFramePacket = onFramePacketCallback
                    )
                    applyDisplayRotationToPipeline()
                    requestedStart = true
                    pipeline0?.start()
                    val mvpFallback = "PASS mode=single dualActive=false fallback=true captureSize=1280x720 fpsTarget=10"
                    Log.i("MVP_STATUS", mvpFallback)
                    debugLogRepository.pushMvpStatus(mvpFallback)
                    setSystemStatus(getString(R.string.status_camera_single_running), level = STATUS_WARN)
                    lastDualActive = false
                    lastFallback = true
                    lastSuppressionActive = false
                    deviceProfile = deviceProfile.copy(cameraId = fallbackCamId)
                    startRunSnapshot(currentModeLabel())
                } catch (fallbackException: Exception) {
                    Log.e("GL_DUAL", "Fallback camera setup also failed: ${fallbackException.message}")
                    val failLine = "FAIL reason=fallback_exception ${fallbackException.message}"
                    Log.e("MVP_STATUS", failLine)
                    debugLogRepository.pushMvpStatus(failLine)
                    setSystemStatus(getString(R.string.status_camera_init_failed), level = STATUS_ERROR, toast = true)
                }
            }
        }
    }

    private fun setSystemStatus(message: String, level: String = STATUS_INFO, toast: Boolean = false) {
        systemStatusMessage = message
        systemStatusLevel = level
        if (!toast || message.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusToastMs < 3_000L) return
        lastStatusToastMs = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 读取当前显示旋转角度
     */
    private fun readDisplayRotationInt(): Int {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        return display.rotation
    }

    /**
     * 应用显示旋转到CameraPipeline
     */
    private fun applyDisplayRotationToPipeline() {
        val rotationInt = readDisplayRotationInt()
        // 统一由 CameraPipeline 执行旋转计算；MainActivity 只同步当前 displayRotation。
        pipeline0?.setDisplayRotation(rotationInt)
        pipeline1?.setDisplayRotation(rotationInt)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // OrientationEventListener is the authoritative source, ignore configuration changes
    }

    /**
     * 选择可用的后置摄像头对
     */
    private fun pickBackCameras(cameraManager: CameraManager): Pair<String, String>? {
        try {
            // 枚举所有相机并打印详细信息
            val allIds = cameraManager.cameraIdList
            for (id in allIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: -1
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
                val scalerMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val hasScalerMap = scalerMap != null

                Log.i("GL_CAM", "cameraId=$id, LENS_FACING=$lensFacing, REQUEST_AVAILABLE_CAPABILITIES=${capabilities.joinToString(",")}, INFO_SUPPORTED_HARDWARE_LEVEL=$hardwareLevel, hasScalerMap=$hasScalerMap")
            }

            val backIds = allIds.filter { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            frontIds = allIds.filter { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }

            // 打印汇总信息
            val concurrentSetsCount = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                cameraManager.concurrentCameraIds.size
            } else {
                -1
            }

            val concurrentSetsStr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val sets = cameraManager.concurrentCameraIds.take(10).map { it.sorted().joinToString(",") }
                val result = sets.joinToString("; ")
                if (cameraManager.concurrentCameraIds.size > 10) "$result; ..." else result
            } else {
                "N/A"
            }

            Log.i("GL_DUAL", "ENUM allIds=[${allIds.joinToString(",")}], backIds=[${backIds.joinToString(",")}], frontIds=[${frontIds.joinToString(",")}], apiLevel=${android.os.Build.VERSION.SDK_INT}, hasConcurrentApi=${android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R}, concurrentSetsCount=$concurrentSetsCount, concurrentSets=[$concurrentSetsStr]")

            if (backIds.size < 2) {
                Log.i("GL_DUAL", "ONLY_ONE_BACK_CAMERA backIds=$backIds")
                return null
            }

            // 检查并发支持（API 30+）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val concurrentCombos = cameraManager.concurrentCameraIds
                for (combo in concurrentCombos) {
                    val matchingBackIds = backIds.filter { combo.contains(it) }
                    if (matchingBackIds.size >= 2) {
                        return Pair(matchingBackIds[0], matchingBackIds[1])
                    }
                }
            }

            // 如果没有并发支持的信息，尝试前两个
            Log.i("GL_DUAL", "CONCURRENT_UNSUPPORTED pair=[${backIds[0]},${backIds[1]}] backIds=$backIds")
            return null

        } catch (e: CameraAccessException) {
            Log.e("GL_DUAL", "ERROR ${e.message}")
            return null
        }
    }

    /**
     * 查找可并发打开的后置+前置摄像头组合（仅Debug模式使用）
     */
    private fun findMixedConcurrentPair(cameraManager: CameraManager, backId: String, frontIds: List<String>): Pair<String, String>? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return null
        }

        try {
            for (combo in cameraManager.concurrentCameraIds) {
                if (combo.contains(backId)) {
                    for (frontId in frontIds) {
                        if (combo.contains(frontId)) {
                            return Pair(backId, frontId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("GL_DUAL", "Error checking mixed concurrent pair: ${e.message}")
        }

        return null
    }

    private fun pushStatsToRepository() {
        lastDeviceTempC = readBatteryTempC() ?: lastDeviceTempC
        postFrameProcessorUpdate("updateDeviceTempC") {
            frameProcessor.updateDeviceTempC(lastDeviceTempC)
        }
        applyCurrentHotPixelMap()
        val currentTime = SystemClock.elapsedRealtime()
        val lastEventAgoSec = if (lastEventTimeMs > 0) {
            ((currentTime - lastEventTimeMs) / 1000).toInt()
        } else -1
        val cooldownLeftSec = if (lastEventTimeMs > 0 && lastCooldownSeconds > 0) {
            val elapsedSec = ((currentTime - lastEventTimeMs) / 1000).toInt()
            maxOf(0, lastCooldownSeconds - elapsedSec)
        } else -1
        val cutoffTime = currentTime - 60_000
        while (eventTimesMs.isNotEmpty() && eventTimesMs.first < cutoffTime) {
            eventTimesMs.removeFirst()
        }
        val rate60s = eventTimesMs.size
        val runtimeStatusMessage = if (
            lastProcessMsAvg > detectionConfig.perfWarnProcessMs &&
            systemStatusLevel != STATUS_ERROR
        ) {
            "处理耗时偏高(${String.format("%.1f", lastProcessMsAvg)}ms)，建议保持设备静止并关闭后台应用"
        } else {
            systemStatusMessage
        }
        val runtimeStatusLevel = if (
            lastProcessMsAvg > detectionConfig.perfWarnProcessMs &&
            systemStatusLevel != STATUS_ERROR
        ) {
            STATUS_WARN
        } else {
            systemStatusLevel
        }
        statsRepository.updateLiveStats(
            scoreEma = lastScoreEma,
            events = eventCount,
            rate60s = rate60s,
            lastAgoSec = lastEventAgoSec,
            cooldownLeftSec = cooldownLeftSec,
            fps = lastFps,
            processMsAvg = lastProcessMsAvg,
            detectionMode = currentDetectionMode.name,
            mog2NonZero = lastMog2NonZero,
            diffNonZero = lastDiffNonZero,
            fusedNonZero = lastFusedNonZero,
            darkFieldReady = lastDarkFieldReady,
            motionStable = lastMotionStable,
            reliability = lastReliability,
            significanceInstant = lastSignificanceZ,
            significanceMean30s = significanceMean30s,
            significanceMean60s = significanceMean60s,
            significanceCi30s = significanceCi30s,
            significanceCi60s = significanceCi60s,
            stabilityLevel = stabilityLevel,
            classifierProbability = lastClassifierProbability,
            cusumScore = lastCusumScore,
            cusumPass = lastCusumPass,
            diffContribution = lastDiffContribution,
            mog2Contribution = lastMog2Contribution,
            fusionDecisionPath = lastFusionDecisionPath,
            suppressionReason = lastSuppressionReason,
            hotPixelMapHitCount = lastHotPixelMapHitCount,
            hotPixelMapSize = lastHotPixelMapSize,
            pulseWidthConsistency = lastPulseWidthConsistency,
            shortWindowVarianceRatio = lastShortWindowVarianceRatio,
            roiEdgeSuppressedCount = lastRoiEdgeSuppressedCount,
            stackedNonZeroCount = lastStackedNonZeroCount,
            poissonCv = lastPoissonCv,
            poissonPass = lastPoissonPass,
            poissonConfidence = lastPoissonConfidence,
            riskScore = lastRiskScore,
            riskTriggerState = lastRiskTriggerState,
            tempSlopeCPerSec = lastTempSlopeCPerSec,
            tempCompPhase = lastTempCompPhase,
            deadTimeMsUsed = lastDeadTimeMsUsed,
            pulseAcceptedCount = lastPulseAcceptedCount,
            pulseDroppedByDeadTime = lastPulseDroppedByDeadTime,
            stackDepthUsed = lastStackDepthUsed,
            stackThresholdUsed = lastStackThresholdUsed,
            poissonSampleCount = lastPoissonSampleCount,
            poissonWarmupReady = lastPoissonWarmupReady,
            roiAppliedStage = lastRoiAppliedStage,
            systemStatusMessage = runtimeStatusMessage,
            systemStatusLevel = runtimeStatusLevel,
            captureSize = deviceProfile.captureSize,
            fpsTarget = deviceProfile.fpsTarget,
        )
        statsRepository.updateDualStatus(
            dualActive = lastDualActive,
            enablePairing = lastDualActive,
            pairMade = null,
            deltaNs = null,
            suppressionActive = lastSuppressionActive,
            fallback = lastFallback,
        )
    }

    private fun readBatteryTempC(): Double? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tempTenths = intent.getIntExtra("temperature", Int.MIN_VALUE)
        if (tempTenths == Int.MIN_VALUE) return null
        return tempTenths / 10.0
    }

    private fun applyCurrentHotPixelMap() {
        if (!detectionConfig.hotPixelMapEnabled) return
        val (bucket, points) = calibrationRepository.getHotPixelMapForTemperature(
            key = detectionConfig.calibrationKey,
            tempC = lastDeviceTempC,
        )
        lastTemperatureBucket = bucket
        lastHotPixelMapSize = points.size
        postFrameProcessorUpdate("updateHotPixelMap") {
            frameProcessor.updateHotPixelMap(points, bucket)
        }
    }

    private fun updateLongWindowStats(nowMs: Long, significanceZ: Double) {
        significanceSamples.addLast(nowMs to significanceZ)
        while (significanceSamples.isNotEmpty() && nowMs - significanceSamples.first.first > detectionConfig.longWindow60s * 1000L) {
            significanceSamples.removeFirst()
        }
        val samples30 = significanceSamples.filter { nowMs - it.first <= detectionConfig.longWindow30s * 1000L }.map { it.second }
        val samples60 = significanceSamples.map { it.second }
        val stat30 = computeWindowStat(samples30)
        val stat60 = computeWindowStat(samples60)
        significanceMean30s = stat30.first
        significanceCi30s = stat30.second
        significanceMean60s = stat60.first
        significanceCi60s = stat60.second
        stabilityLevel = when {
            stat60.second <= 0.35 && stat60.first >= 2.0 -> "HIGH"
            stat60.second <= 0.70 && stat60.first >= 1.3 -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun computeWindowStat(values: List<Double>): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance)
        val ci95 = 1.96 * std / sqrt(values.size.toDouble())
        return mean to ci95
    }

    private fun currentModeLabel(): String {
        return if (shadedPrecisionMode) "遮光测量(精测)" else "环境巡检(参考)"
    }

    private fun startRunSnapshot(modeLabel: String) {
        reliabilityReliableSamples = 0
        reliabilityLimitedSamples = 0
        reliabilityPoorSamples = 0
        significanceSamples.clear()
        darkSessionSampleCount = 0
        darkSessionBaselineSum = 0.0
        darkSessionHotPixelSum = 0.0
        lastCalibrationDeltaOffset = 0.0
        lastCalibrationDeltaHotGain = 0.0
        lastCalibrationRollbackSuggested = false
        lastTemperatureBucket = "unknown"
        lastHotPixelMapSize = 0
        lastHotPixelMapHitCount = 0
        lastCusumScore = 0.0
        lastCusumPass = false
        lastPulseWidthConsistency = 0.0
        lastShortWindowVarianceRatio = 1.0
        lastRoiEdgeSuppressedCount = 0
        lastStackedNonZeroCount = 0
        lastPoissonCv = 0.0
        lastPoissonPass = false
        lastPoissonConfidence = 0.0
        lastRiskScore = 0.0
        lastRiskTriggerState = false
        lastTempSlopeCPerSec = 0.0
        lastTempCompPhase = "steady"
        lastDeadTimeMsUsed = 0.0
        lastPulseAcceptedCount = 0
        lastPulseDroppedByDeadTime = 0
        lastStackDepthUsed = 0
        lastStackThresholdUsed = 0.0
        lastPoissonSampleCount = 0
        lastPoissonWarmupReady = false
        lastRoiAppliedStage = "post_analysis"
        lastFunnelCandidateCount = 0
        lastFunnelSafetyPassCount = 0
        lastFunnelRiskPassCount = 0
        lastFunnelConfirmedCount = 0
        lastFunnelSuppressedCount = 0
        systemStatusMessage = ""
        systemStatusLevel = STATUS_INFO
        runSnapshotManager = RunSnapshotManager(this)
        runSnapshotManager!!.startSession(
            deviceProfile = deviceProfile,
            modeLabel = modeLabel,
            dualActive = lastDualActive,
            detectionMode = currentDetectionMode.name,
            detectionConfig = detectionConfig,
            scenarioId = currentScenarioId,
            experimentId = currentExperimentId,
            variantId = currentVariantId,
            releaseState = currentReleaseState,
            modelVersion = currentModelVersion,
            exposureTimeNs = 100_000_000L,
            iso = null,
            aeLock = true,
            awbLock = true,
            afLock = true,
        )
    }

    private fun onFrameStatsSnapshot(s: FrameStatsSnapshot) {
        val nowMs = SystemClock.elapsedRealtime()
        lastFps = s.fps
        lastProcessMsAvg = s.processMsAvg
        lastMog2NonZero = s.mog2NonZeroCount
        lastDiffNonZero = s.diffNonZeroCount
        lastFusedNonZero = s.fusedNonZeroCount
        lastCandidateCount = s.candidateCount
        lastSuppressedCount = s.suppressedCount
        currentDetectionMode = s.detectionMode
        lastDarkFieldReady = s.darkFieldReady
        lastMotionStable = s.motionStable
        lastReliability = s.measurementReliability.name
        lastSignificanceZ = s.significanceZ
        lastClassifierProbability = s.classifierProbability
        lastDeepModelProbability = s.deepModelProbability
        lastCusumScore = s.cusumScore
        lastCusumPass = s.cusumPass
        lastDiffContribution = s.diffContribution
        lastMog2Contribution = s.mog2Contribution
        lastFusionDecisionPath = s.fusionDecisionPath
        lastSuppressionApplied = s.suppressionApplied
        lastSuppressionReason = s.suppressionReason
        lastSustainedFrames = s.sustainedFrames
        lastTrajectoryLength = s.trajectoryLength
        lastPeakStability = s.peakStability
        lastTempCompensationTerm = s.tempCompensationTerm
        lastHotPixelMapHitCount = s.hotPixelMapHitCount
        lastHotPixelMapSize = s.hotPixelMapSize
        lastPulseWidthConsistency = s.pulseWidthConsistency
        lastShortWindowVarianceRatio = s.shortWindowVarianceRatio
        lastRoiEdgeSuppressedCount = s.roiEdgeSuppressedCount
        lastStackedNonZeroCount = s.stackedNonZeroCount
        lastPoissonCv = s.poissonCv
        lastPoissonPass = s.poissonPass
        lastPoissonConfidence = s.poissonConfidence
        lastRiskScore = s.riskScore
        lastRiskTriggerState = s.riskTriggerState
        lastTempSlopeCPerSec = s.tempSlopeCPerSec
        lastTempCompPhase = s.tempCompPhase
        lastDeadTimeMsUsed = s.deadTimeMsUsed
        lastPulseAcceptedCount = s.pulseAcceptedCount
        lastPulseDroppedByDeadTime = s.pulseDroppedByDeadTime
        lastStackDepthUsed = s.stackDepthUsed
        lastStackThresholdUsed = s.stackThresholdUsed
        lastPoissonSampleCount = s.poissonSampleCount
        lastPoissonWarmupReady = s.poissonWarmupReady
        lastRoiAppliedStage = s.roiAppliedStage
        lastFunnelCandidateCount = s.funnelCandidateCount
        lastFunnelSafetyPassCount = s.funnelSafetyPassCount
        lastFunnelRiskPassCount = s.funnelRiskPassCount
        lastFunnelConfirmedCount = s.funnelConfirmedCount
        lastFunnelSuppressedCount = s.funnelSuppressedCount
        lastTemperatureBucket = s.hotPixelMapBucket
        updateLongWindowStats(nowMs, s.significanceZ)
        when (s.measurementReliability) {
            MeasurementReliability.RELIABLE -> reliabilityReliableSamples++
            MeasurementReliability.LIMITED -> reliabilityLimitedSamples++
            MeasurementReliability.POOR -> reliabilityPoorSamples++
        }
        if (s.darkFieldReady) {
            darkSessionSampleCount++
            darkSessionBaselineSum += s.baselineMean
            darkSessionHotPixelSum += s.hotPixelSuppressedCount.toDouble()
        }

        val perfLine = "mode=${s.detectionMode.name} reliability=${s.measurementReliability.name} dark=${s.darkFieldReady} stable=${s.motionStable} fps=${String.format("%.1f", s.fps)} procMs=${String.format("%.2f", s.processMsAvg)} nonZero(m/d/f)=${s.mog2NonZeroCount}/${s.diffNonZeroCount}/${s.fusedNonZeroCount} contrib(d/m)=${String.format("%.2f", s.diffContribution)}/${String.format("%.2f", s.mog2Contribution)} pulse=${String.format("%.2f", s.pulseDensity)} neigh=${String.format("%.2f", s.neighborhoodConsistency)} lite(pw/vr)=${String.format("%.2f", s.pulseWidthConsistency)}/${String.format("%.2f", s.shortWindowVarianceRatio)} cusum=${String.format("%.2f", s.cusumScore)}:${s.cusumPass} hpMap=${s.hotPixelMapSize}/${s.hotPixelMapHitCount}@${s.hotPixelMapBucket} roiSupp=${s.roiEdgeSuppressedCount}:${s.roiAppliedStage} stack=${s.stackedNonZeroCount}@d${s.stackDepthUsed}/t${String.format("%.2f", s.stackThresholdUsed)} poisson(raw/corr)=${String.format("%.2f", s.poissonRawCv)}/${String.format("%.2f", s.poissonCorrectedCv)}:${s.poissonPass}:${String.format("%.2f", s.poissonConfidence)}:${s.poissonSampleCount}/${if (s.poissonWarmupReady) "ready" else "warmup"} risk=${String.format("%.2f", s.riskScore)}:${s.riskTriggerState} deadTime=${String.format("%.0f", s.deadTimeMsUsed)}ms:${s.pulseAcceptedCount}/${s.pulseDroppedByDeadTime} deep=${String.format("%.2f", s.deepModelProbability)} path=${s.fusionDecisionPath} suppress=${s.suppressionApplied}:${s.suppressionReason} z=${String.format("%.2f", s.significanceZ)} z30=${String.format("%.2f", significanceMean30s)}±${String.format("%.2f", significanceCi30s)} z60=${String.format("%.2f", significanceMean60s)}±${String.format("%.2f", significanceCi60s)} stable=$stabilityLevel cls=${String.format("%.2f", s.classifierProbability)} baseline=${String.format("%.1f", s.baselineMean)}±${String.format("%.1f", s.baselineStd)} feat=${String.format("%.2f", s.eventFeatureScore)} peak=${String.format("%.1f", s.peakIntensity)} peakStable=${String.format("%.2f", s.peakStability)} traj=${String.format("%.1f", s.trajectoryLength)} sustained=${s.sustainedFrames} tempTerm=${String.format("%.2f", s.tempCompensationTerm)} tempSlope=${String.format("%.3f", s.tempSlopeCPerSec)} tempPhase=${s.tempCompPhase} contrast=${String.format("%.1f", s.localContrast)} thr=${String.format("%.1f", s.adaptiveDiffThreshold)} nq=${String.format("%.1f", s.noiseQuantile)} motion=(${String.format("%.1f", s.motionDx)},${String.format("%.1f", s.motionDy)}) warmup=${s.warmupActive} hotPx=${s.hotPixelSuppressedCount} track=${String.format("%.2f", s.trackStability)} confirm=${String.format("%.1f", s.confirmScore)} pairPenalty=${String.format("%.2f", s.pairPenalty)} q=${String.format("%.2f", s.qualityScore)} cand=${s.candidateCount} supp=${s.suppressedCount}"
        debugLogRepository.pushPerfStatus(perfLine)
        runSnapshotManager?.setRuntimeMetrics(
            detectionMode = s.detectionMode.name,
            fps = s.fps,
            processMsAvg = s.processMsAvg,
            candidateCount = s.candidateCount,
            suppressedCount = s.suppressedCount,
            mog2NonZero = s.mog2NonZeroCount,
            diffNonZero = s.diffNonZeroCount,
            fusedNonZero = s.fusedNonZeroCount,
            adaptiveDiffThreshold = s.adaptiveDiffThreshold,
            motionDx = s.motionDx,
            motionDy = s.motionDy,
            qualityScore = s.qualityScore,
            warmupActive = s.warmupActive,
            noiseQuantile = s.noiseQuantile,
            hotPixelSuppressedCount = s.hotPixelSuppressedCount,
            trackStability = s.trackStability,
            confirmScore = s.confirmScore,
            pairPenalty = s.pairPenalty,
            darkFieldReady = s.darkFieldReady,
            motionStable = s.motionStable,
            reliability = s.measurementReliability.name,
            reliabilityReliableSamples = reliabilityReliableSamples,
            reliabilityLimitedSamples = reliabilityLimitedSamples,
            reliabilityPoorSamples = reliabilityPoorSamples,
            significanceZ = s.significanceZ,
            baselineMean = s.baselineMean,
            baselineStd = s.baselineStd,
            peakIntensity = s.peakIntensity,
            localContrast = s.localContrast,
            eventFeatureScore = s.eventFeatureScore,
            significanceMean30s = significanceMean30s,
            significanceMean60s = significanceMean60s,
            significanceCi30s = significanceCi30s,
            significanceCi60s = significanceCi60s,
            stabilityLevel = stabilityLevel,
            classifierProbability = s.classifierProbability,
            sustainedFrames = s.sustainedFrames,
            trajectoryLength = s.trajectoryLength,
            peakStability = s.peakStability,
            tempCompensationTerm = s.tempCompensationTerm,
            temperatureC = lastDeviceTempC,
            calibrationVersion = detectionConfig.calibrationVersion,
            diffContribution = s.diffContribution,
            mog2Contribution = s.mog2Contribution,
            fusionDecisionPath = s.fusionDecisionPath,
            suppressionApplied = s.suppressionApplied,
            suppressionReason = s.suppressionReason,
            significanceMean60sLayered = s.significanceMean60s,
            significanceCi60sLayered = s.significanceCi60s,
            calibrationDeltaOffset = lastCalibrationDeltaOffset,
            calibrationDeltaHotGain = lastCalibrationDeltaHotGain,
            calibrationRollbackSuggested = lastCalibrationRollbackSuggested,
            temperatureBucket = lastTemperatureBucket,
            scenarioId = currentScenarioId,
            experimentId = currentExperimentId,
            variantId = currentVariantId,
            releaseState = currentReleaseState,
            modelVersion = currentModelVersion,
            rollbackReason = lastRollbackReason,
            pulseDensity = s.pulseDensity,
            neighborhoodConsistency = s.neighborhoodConsistency,
            deepModelProbability = s.deepModelProbability,
            cusumScore = s.cusumScore,
            cusumPass = s.cusumPass,
            hotPixelMapBucket = s.hotPixelMapBucket,
            hotPixelMapSize = s.hotPixelMapSize,
            hotPixelMapHitCount = s.hotPixelMapHitCount,
            pulseWidthConsistency = s.pulseWidthConsistency,
            shortWindowVarianceRatio = s.shortWindowVarianceRatio,
            roiEdgeSuppressedCount = s.roiEdgeSuppressedCount,
            stackedNonZeroCount = s.stackedNonZeroCount,
            poissonCv = s.poissonCv,
            poissonRawCv = s.poissonRawCv,
            poissonCorrectedCv = s.poissonCorrectedCv,
            poissonPass = s.poissonPass,
            poissonConfidence = s.poissonConfidence,
            riskScore = s.riskScore,
            riskTriggerState = s.riskTriggerState,
            tempSlopeCPerSec = s.tempSlopeCPerSec,
            tempCompPhase = s.tempCompPhase,
            deadTimeMsUsed = s.deadTimeMsUsed,
            pulseAcceptedCount = s.pulseAcceptedCount,
            pulseDroppedByDeadTime = s.pulseDroppedByDeadTime,
            stackDepthUsed = s.stackDepthUsed,
            stackThresholdUsed = s.stackThresholdUsed,
            poissonSampleCount = s.poissonSampleCount,
            poissonWarmupReady = s.poissonWarmupReady,
            roiAppliedStage = s.roiAppliedStage,
            funnelCandidateCount = s.funnelCandidateCount,
            funnelSafetyPassCount = s.funnelSafetyPassCount,
            funnelRiskPassCount = s.funnelRiskPassCount,
            funnelConfirmedCount = s.funnelConfirmedCount,
            funnelSuppressedCount = s.funnelSuppressedCount,
        )
        runSnapshotManager?.appendRuntimeTimeseries(
            timestampMs = System.currentTimeMillis(),
            riskScore = s.riskScore,
            riskTriggerState = s.riskTriggerState,
            poissonConfidence = s.poissonConfidence,
            poissonRawCv = s.poissonRawCv,
            poissonCorrectedCv = s.poissonCorrectedCv,
            poissonSampleCount = s.poissonSampleCount,
            deadTimeMsUsed = s.deadTimeMsUsed,
            tempCompPhase = s.tempCompPhase,
            warmupActive = s.warmupActive,
            temperatureBucket = s.hotPixelMapBucket,
            fps = s.fps,
            processMsAvg = s.processMsAvg,
            measurementReliability = s.measurementReliability.name,
        )
    }

    fun setDetectionMode(mode: DetectionMode) {
        currentDetectionMode = mode
        frameProcessor.setDetectionMode(mode)
        val line = "DETECTION_MODE=${mode.name}"
        Log.i("GL_MODE", line)
        debugLogRepository.pushMvpStatus(line)
    }

    fun getDetectionMode(): DetectionMode = currentDetectionMode

    fun setMeasurementAmbientMode(enabled: Boolean) {
        shadedPrecisionMode = !enabled
        currentScenarioId = if (enabled) "ambient_light" else "dark_static"
        currentVariantId = if (enabled) "ambient_ref" else "balanced"
        frameProcessor.setShadedPrecisionMode(shadedPrecisionMode)
        val line = "MEAS_MODE=${if (enabled) "AMBIENT" else "SHADED"} shadedPrecision=$shadedPrecisionMode"
        Log.i("GL_MODE", line)
        debugLogRepository.pushMvpStatus(line)
    }

    fun setReleaseState(state: String, modelVersion: String) {
        val normalized = when (state.lowercase()) {
            "baseline" -> "baseline"
            "candidate" -> "candidate"
            "promoted" -> "promoted"
            else -> "baseline"
        }
        currentReleaseState = normalized
        currentModelVersion = modelVersion.ifBlank { currentModelVersion }
        detectionConfig = detectionConfig.updated {
            it.releaseState = currentReleaseState
            it.modelVersion = currentModelVersion
        }
        frameProcessor.updateConfig(detectionConfig)
        val line = "RELEASE_STATE=$currentReleaseState model=$currentModelVersion"
        Log.i("GL_MODE", line)
        debugLogRepository.pushMvpStatus(line)
    }

    /**
     * 停止相机预览
     */
    private fun stopCameraPreview() {
        pipeline0?.stop()
        pipeline1?.stop()

        stopDispatchThread()
        applyOnlineCalibrationIfNeeded()
        runSnapshotManager?.endSession(debugLogRepository)

        // 清理资源
        sharedSync.reset()
        frameProcessor.close()

        // 重置日志状态
        loggedFirstFrameStreams.clear()
        primaryOnlyLogged = false

        // 重置引用
        pipeline0 = null
        pipeline1 = null
    }

    private fun applyOnlineCalibrationIfNeeded() {
        if (detectionConfig.hotPixelMapEnabled && darkSessionSampleCount >= detectionConfig.hotPixelMapMinFrames) {
            val learnedPoints = frameProcessor.snapshotLearnedHotPixels(detectionConfig.hotPixelMapMaxPoints)
            if (learnedPoints.isNotEmpty()) {
                calibrationRepository.mergeHotPixelMapFromSession(
                    key = detectionConfig.calibrationKey,
                    tempC = lastDeviceTempC,
                    points = learnedPoints,
                    minOccurrences = detectionConfig.hotPixelMapMinOccurrences,
                    maxPoints = detectionConfig.hotPixelMapMaxPoints,
                )
                applyCurrentHotPixelMap()
            }
        }
        if (darkSessionSampleCount < detectionConfig.onlineCalibrationMinSamples) return
        val meanBaseline = darkSessionBaselineSum / darkSessionSampleCount.toDouble()
        val meanHotPixel = darkSessionHotPixelSum / darkSessionSampleCount.toDouble()
        val update = calibrationRepository.smoothUpdateFromDarkSession(
            key = detectionConfig.calibrationKey,
            darkFieldMean = meanBaseline,
            hotPixelSuppressedCount = meanHotPixel.toInt(),
            tempC = lastDeviceTempC,
            alpha = detectionConfig.onlineCalibrationAlpha,
            stepMaxOffset = detectionConfig.calibrationStepMaxOffset,
            stepMaxHotGain = detectionConfig.calibrationStepMaxHotGain,
            medianWindow = detectionConfig.calibrationMedianWindow,
            freezeTempLow = detectionConfig.calibrationFreezeTempLow,
            freezeTempHigh = detectionConfig.calibrationFreezeTempHigh,
        )
        val updated = update.profile
        detectionConfig = detectionConfig.updated {
            it.calibrationDarkFieldOffset = updated.darkFieldOffset
            it.calibrationHotPixelGain = updated.hotPixelGain
            it.calibrationTempCompensationCoeff = updated.tempCompensationCoeff
            it.calibrationVersion = updated.version
        }
        frameProcessor.updateConfig(detectionConfig)
        lastCalibrationDeltaOffset = update.deltaOffset
        lastCalibrationDeltaHotGain = update.deltaHotPixelGain
        lastCalibrationRollbackSuggested = update.rollbackSuggested
        lastTemperatureBucket = update.temperatureBucket
        lastRollbackReason = if (update.rollbackSuggested) "calibration_step_guard" else "none"
        val logLine = "online_calibration version=${updated.version} offset=${"%.3f".format(updated.darkFieldOffset)} hotGain=${"%.3f".format(updated.hotPixelGain)} dOffset=${"%.3f".format(update.deltaOffset)} dHot=${"%.3f".format(update.deltaHotPixelGain)} rollback=${update.rollbackSuggested} tempBucket=${update.temperatureBucket} frozen=${update.frozenByTemperature}"
        Log.i("GL_CAL", logLine)
        debugLogRepository.pushMvpStatus(logLine)
        darkSessionSampleCount = 0
        darkSessionBaselineSum = 0.0
        darkSessionHotPixelSum = 0.0
    }

    private fun startDispatchThread() {
        val t = dispatchThread
        if (t != null && t.isAlive) return
        val thread = HandlerThread("FrameDispatch").apply { start() }
        dispatchThread = thread
        dispatchHandler = Handler(thread.looper)
        Log.i("GL_DISPATCH", "thread=${thread.name} started")
    }

    private fun postFrameProcessorUpdate(tag: String, action: () -> Unit) {
        val handler = dispatchHandler
        val thread = dispatchThread
        if (handler == null || thread == null || !thread.isAlive) {
            try {
                action()
            } catch (e: Exception) {
                Log.w(TAG, "FrameProcessor update($tag) failed in direct mode: ${e.message}")
            }
            return
        }
        val posted = handler.post {
            try {
                action()
            } catch (e: Exception) {
                Log.w(TAG, "FrameProcessor update($tag) failed in dispatch thread: ${e.message}")
            }
        }
        if (!posted) {
            Log.w(TAG, "FrameProcessor update($tag) rejected by dispatch queue, fallback direct")
            try {
                action()
            } catch (e: Exception) {
                Log.w(TAG, "FrameProcessor update($tag) fallback failed: ${e.message}")
            }
        }
    }

    private fun dispatchFramePacket(packet: FramePacket, cameraIdResolver: (String) -> String) {
        val handler = dispatchHandler
        if (handler == null) {
            Log.w("GL_DUAL", "Dispatch handler unavailable, dropping streamId=${packet.streamId} frameNo=${packet.frameNumber}")
            releaseFramePacketSafely(packet)
            return
        }
        val posted = handler.post {
            try {
                if (!loggedFirstFrameStreams.contains(packet.streamId)) {
                    loggedFirstFrameStreams.add(packet.streamId)
                    val cameraId = cameraIdResolver(packet.streamId)
                    Log.i("GL_DUAL", "IN_FIRST streamId=${packet.streamId} cameraId=$cameraId tsNs=${packet.timestampNs}")
                }
                val items = sharedSync.submit(packet)
                if (!primaryOnlyLogged) {
                    primaryOnlyLogged = true
                    Log.i("GL_DUAL", "PROCESS_PRIMARY_ONLY primary=$PRIMARY_STREAM_ID")
                }
                for (item in items) {
                    if (item.anchor.streamId == PRIMARY_STREAM_ID) {
                        consumeItemSafely(item)
                    } else {
                        item.releaseMatsSafely()
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Dispatch submit failed streamId=${packet.streamId} frameNo=${packet.frameNumber} tsNs=${packet.timestampNs}",
                    e
                )
                releaseFramePacketSafely(packet)
            }
        }
        if (!posted) {
            Log.w("GL_DUAL", "Dispatch queue rejected packet streamId=${packet.streamId} frameNo=${packet.frameNumber}")
            releaseFramePacketSafely(packet)
        }
    }

    private fun releaseFramePacketSafely(packet: FramePacket) {
        try {
            packet.grayMat.release()
        } catch (_: Exception) {
        }
    }

    private fun consumeItemSafely(item: ProcessableItem) {
        try {
            frameProcessor.consume(item)
        } catch (e: Exception) {
            val anchor = item.anchor
            Log.e(
                TAG,
                "Dispatch consume failed streamId=${anchor.streamId} frameNo=${anchor.frameNumber} tsNs=${anchor.timestampNs}",
                e
            )
        }
    }

    private fun stopDispatchThread() {
        val t = dispatchThread ?: return
        if (!t.isAlive) {
            dispatchThread = null
            dispatchHandler = null
            return
        }
        t.quitSafely()
        try {
            t.join(2_500L)
        } catch (e: InterruptedException) {
            Log.w("GL_DISPATCH", "join interrupted: ${e.message}")
            Thread.currentThread().interrupt()
        }
        dispatchThread = null
        dispatchHandler = null
        Log.i("GL_DISPATCH", "stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        deepModelClassifier?.close()
        deepModelClassifier = null
    }
}