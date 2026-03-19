package com.gammalens.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import com.gammalens.app.camera.OpenCvBootstrap
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 手动控制结果：记录最终设置的参数和降级原因
 */
data class ManualControlResult(
    val aeMode: Int,
    val afMode: Int,
    val exposureNs: Long,
    val iso: Int,
    val downgradeReasons: List<String>
)

/**
 * 单路相机管线：Camera2 API 核心实现
 * 负责后置单摄预览 + ImageReader 帧捕获
 */
class CameraPipeline(
    private val context: Context,
    private val cameraId: String,
    private val streamId: String,
    private val textureView: TextureView,
    private val onFramePacket: (FramePacket) -> Unit,
    private val previewSurfaceOverride: Surface? = null,
    private val enablePreview: Boolean = true
) {

    companion object {
        private const val TAG = "CameraPipeline"
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
    }

    // 相机组件
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: android.view.Surface? = null
    private var previewSurfaceOwnedByPipeline: Boolean = false
    private var previewSize: Size? = null
    private var frameNumber: Long = 0
    private var displayRotation: Int = android.view.Surface.ROTATION_0
    private var currentDisplayRotation: Int = android.view.Surface.ROTATION_0
    private var rotationDegrees: Int = 0
    private var isMirrored: Boolean = false
    private var lastAppliedDisplayRotation: Int = android.view.Surface.ROTATION_0
    private var lastAppliedRotationDegrees: Int = 0
    private var sensorOrientation: Int? = null
    @Volatile private var currentRotationDegrees: Int = 0
    @Volatile private var currentIsMirrored: Boolean = false
    private var dbgPktSrcCount = 0
    private val frameSynchronizer = FrameSynchronizer()

    // 状态机和代际保护
    @Volatile private var running = false
    @Volatile private var closing = false
    @Volatile private var generation: Long = 0
    private val stateLock = Any()

    // 延迟重试机制
    @Volatile private var pendingStart = false
    @Volatile private var pendingStartGeneration: Long = 0

    // 尺寸日志控制
    private var sizeLogged = false

    // 首次帧日志控制
    private var firstFrameLogged = false

    // 线程和同步
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)
    @Volatile private var openLockHeld = false

    // 相机状态回调
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!running || closing) {
                Log.w(TAG, "Ignored stale onOpened callback (running=$running, closing=$closing)")
                camera.close()
                releaseOpenCloseLockIfHeld()
                return
            }

            Log.i("GL_PIPE", "$streamId OPENED cameraId=${camera.id}")
            releaseOpenCloseLockIfHeld()
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "Camera disconnected: ${camera.id}")
            Log.w("GL_PIPE", "$streamId DISCONNECTED cameraId=${camera.id}")
            releaseOpenCloseLockIfHeld()
            closeCamera()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: ${camera.id}, error: $error")
            Log.e("GL_PIPE", "$streamId ERROR cameraId=${camera.id} code=$error")
            releaseOpenCloseLockIfHeld()
            closeCamera()
        }
    }

    // ImageReader 帧可用监听器：YUV转灰度Mat并生成FramePacket
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener

        try {
            // 代际检查：忽略过期回调
            if (!running || closing) {
                Log.v(TAG, "Ignored stale onImageAvailable callback (running=$running, closing=$closing)")
                image.close()
                return@OnImageAvailableListener
            }

            // 确保OpenCV已初始化
            if (!OpenCvBootstrap.ensureInitialized()) {
                Log.e(TAG, "OpenCV not initialized, skipping frame")
                image.close()
                return@OnImageAvailableListener
            }
            // 获取Y平面（灰度数据）
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride

            val width = image.width
            val height = image.height

            // 创建OpenCV灰度Mat
            val grayMat = Mat(height, width, CvType.CV_8UC1)

            // 处理Y平面数据，考虑rowStride和pixelStride
            if (yPixelStride == 1 && yRowStride == width) {
                // 连续内存，直接拷贝
                val yBytes = ByteArray(yBuffer.remaining())
                yBuffer.get(yBytes)
                grayMat.put(0, 0, yBytes)
            } else {
                // 非连续内存，需要逐行处理
                val yBytes = ByteArray(width * height)
                val bufferLimit = yBuffer.limit()
                for (row in 0 until height) {
                    val rowStart = row * yRowStride
                    for (col in 0 until width) {
                        val pixelIndex = rowStart + col * yPixelStride
                        if (pixelIndex >= 0 && pixelIndex < bufferLimit) {
                            yBytes[row * width + col] = yBuffer.get(pixelIndex)
                        }
                    }
                }
                grayMat.put(0, 0, yBytes)
            }

            // 生成FramePacket并回调
            val framePacket = FramePacket(
                streamId = streamId,
                timestampNs = image.timestamp,
                width = width,
                height = height,
                grayMat = grayMat,
                frameNumber = frameNumber++,
                rotationDegrees = this.currentRotationDegrees,
                isMirrored = this.currentIsMirrored
            )

            // 首次帧日志（只打一次）
            if (!firstFrameLogged) {
                firstFrameLogged = true
                Log.i("GL_PIPE", "$streamId FIRST_FRAME cameraId=${cameraId} frameNumber=${framePacket.frameNumber} tsNs=${framePacket.timestampNs}")
            }

            // 回调FramePacket给上层处理
            onFramePacket(framePacket)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            // 必须释放Image
            image.close()
        }
    }

    /**
     * 设置显示旋转角度
     * @param rotation Surface.ROTATION_0/90/180/270
     */
    fun setDisplayRotation(displayRotation: Int) {
        // 如果还没拿到sensorOrientation，先缓存displayRotation，等待setupCamera完成后再计算
        val sensorOrientation = this.sensorOrientation ?: run {
            this.currentDisplayRotation = displayRotation
            if (lastAppliedDisplayRotation != displayRotation) {
                lastAppliedDisplayRotation = displayRotation
            }
            return
        }

        // 只在displayRotation变化时才更新
        if (displayRotation == lastAppliedDisplayRotation) return
        lastAppliedDisplayRotation = displayRotation
        this.currentDisplayRotation = displayRotation

        val displayRotationDegrees = when (displayRotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }

        val newRotationDegrees = (sensorOrientation - displayRotationDegrees + 360) % 360

        // 更新currentRotationDegrees（每次都更新）
        this.currentRotationDegrees = newRotationDegrees
        this.currentIsMirrored = false

        // 只在rotationDegrees变化时打印日志
        if (newRotationDegrees != lastAppliedRotationDegrees) {
            lastAppliedRotationDegrees = newRotationDegrees
            this.rotationDegrees = newRotationDegrees
            Log.i("GL_ROT_CHG", "cameraId=$cameraId, sensorOrientation=$sensorOrientation, displayRotation=$displayRotation, displayRotationDegrees=$displayRotationDegrees, rotationDegrees=$newRotationDegrees, isMirrored=false")
        }
    }

    /**
     * 启动相机管线
     */
    fun start() {
        Log.i("GL_PIPE", "$streamId START_REQ cameraId=${cameraId} enablePreview=$enablePreview")

        synchronized(stateLock) {
            if (running) {
                Log.d(TAG, "start() ignored: pipeline already running")
                return
            }
            if (closing) {
                // 延迟重试，不要直接忽略
                pendingStart = true
                scheduleStartRetry("closing")
                return
            }
            running = true
            closing = false
            generation++
            sizeLogged = false  // 重置尺寸日志标志
            Log.i("GL_PIPE", "START streamId=$streamId")
        }

        val localGen = generation

        try {
            startBackgroundThread()
            setupCamera()
            openCamera()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera pipeline", e)
            Log.e("GL_PIPE", "$streamId FAIL stage=open cameraId=${cameraId} err=${e.message}")
            stop()
        }
    }

    /**
     * 停止相机管线
     */
    fun stop() {
        synchronized(stateLock) {
            if (!running) {
                Log.d(TAG, "stop() ignored: pipeline not running")
                return
            }
            closing = true
            running = false
            generation++
        }

        closeCamera()
        stopBackgroundThread()
        frameSynchronizer.reset()

        // 重置门闩状态，允许重新启动
        closing = false

        Log.i("GL_PIPE", "STOP streamId=$streamId")

        // 确保所有Surface引用都被清理，避免gralloc错误
        // 注意：不要释放SurfaceTexture本身，让系统管理
    }

    /**
     * 设置相机参数
     */
    private fun setupCamera() {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val manager = cameraManager ?: throw IllegalStateException("cameraManager is null")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) {
                Log.w(TAG, "Configured cameraId=$cameraId is not back camera, facing=$facing")
            }

            // 只使用传入 cameraId 的特征，避免参数和实际打开设备不一致
            val chosenSize = chooseConservativeSize(characteristics)
            previewSize = chosenSize

            // 计算旋转角度并打印日志
            this.sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val sensorOrientation = this.sensorOrientation!!

            // 如果之前有缓存的displayRotation，现在重新计算并更新currentRotationDegrees
            if (lastAppliedDisplayRotation != android.view.Surface.ROTATION_0 || currentDisplayRotation != android.view.Surface.ROTATION_0) {
                val cachedDisplayRotation = if (lastAppliedDisplayRotation != android.view.Surface.ROTATION_0) lastAppliedDisplayRotation else currentDisplayRotation
                val displayRotationDegrees = when (cachedDisplayRotation) {
                    android.view.Surface.ROTATION_0 -> 0
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val initialRotationDegrees = (sensorOrientation - displayRotationDegrees + 360) % 360
                this.currentRotationDegrees = initialRotationDegrees
                this.currentIsMirrored = false
                Log.i("GL_ROT_CHG", "cameraId=$cameraId, sensorOrientation=$sensorOrientation, displayRotation=$cachedDisplayRotation, displayRotationDegrees=$displayRotationDegrees, rotationDegrees=$initialRotationDegrees, isMirrored=false")
            }
            val displayRotationDegrees = when (currentDisplayRotation) {
                android.view.Surface.ROTATION_0 -> 0
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            this.rotationDegrees = (sensorOrientation - displayRotationDegrees + 360) % 360
            this.isMirrored = false
            this.currentRotationDegrees = this.rotationDegrees
            this.currentIsMirrored = this.isMirrored
            this.lastAppliedRotationDegrees = this.rotationDegrees

            Log.i("GL_ROT", "cameraId=$cameraId, sensorOrientation=$sensorOrientation, displayRotation=$currentDisplayRotation, displayRotationDegrees=$displayRotationDegrees, rotationDegrees=${this.rotationDegrees}, isMirrored=${this.isMirrored}")

            imageReader = ImageReader.newInstance(
                chosenSize.width, chosenSize.height,
                ImageFormat.YUV_420_888, 2
            )
            imageReader?.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up cameraId=$cameraId", e)
            throw e
        }
    }

    /**
     * 打开相机
     */
    private fun openCamera() {
        var acquired = false
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted, skip openCamera")
                Log.e("GL_PIPE", "$streamId FAIL stage=open cameraId=$cameraId err=permission_denied")
                return
            }
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            acquired = true
            openLockHeld = true
            cameraManager?.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            Log.e("GL_PIPE", "$streamId FAIL stage=open cameraId=${cameraId} err=${e.message}")
            if (acquired) {
                releaseOpenCloseLockIfHeld()
            }
        }
    }

    /**
     * 创建相机预览会话
     */
    private fun createCameraPreviewSession() {
        try {
            // 代际检查
            if (!running || closing) {
                Log.w(TAG, "Ignored stale createCameraPreviewSession (running=$running, closing=$closing)")
                return
            }

            // 释放旧的预览Surface（仅释放由当前pipeline创建的对象）
            if (previewSurfaceOwnedByPipeline) {
                previewSurface?.release()
            }
            previewSurface = null
            previewSurfaceOwnedByPipeline = false

            // 空值保护
            val cameraDevice = cameraDevice ?: run {
                Log.e(TAG, "Camera device is null")
                return
            }

            val previewSize = previewSize ?: run {
                Log.e(TAG, "Preview size is null")
                return
            }

            val texture = textureView.surfaceTexture ?: run {
                Log.w(TAG, "Surface texture is null")
                return
            }

            val chosenSize = previewSize!!  // previewSize == chosenSize

            // 构建输出 surfaces 列表
            val surfaces = mutableListOf<Surface>()

            // ImageReader surface 必须一直存在
            val imageReaderSurface = imageReader?.surface ?: run {
                Log.e(TAG, "ImageReader surface is null")
                return
            }
            surfaces.add(imageReaderSurface)

            // 只有启用预览时才创建和添加preview surface
            if (enablePreview) {
                if (previewSurfaceOverride != null) {
                    // 使用外部提供的离屏Surface
                    Log.i("GL_SURF", "using offscreen surface for streamId=$streamId")
                    previewSurface = previewSurfaceOverride
                    previewSurfaceOwnedByPipeline = false
                } else {
                    // 使用TextureView的Surface
                    if (!textureView.isAvailable) {
                        Log.w(TAG, "TextureView is not available")
                        return
                    }

                    val texture = textureView.surfaceTexture ?: run {
                        Log.w(TAG, "Surface texture is null")
                        return
                    }

                    Log.i("GL_SURF", "creating session with SurfaceTexture hash=${texture.hashCode()}")
                    texture.setDefaultBufferSize(chosenSize.width, chosenSize.height)
                    previewSurface = Surface(texture)
                    previewSurfaceOwnedByPipeline = true
                }
                previewSurface?.let { surfaces.add(it) }
            }

            val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            surfaces.forEach { captureRequestBuilder.addTarget(it) }

            // 应用手动控制设置
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId)
            if (characteristics != null) {
                val controlResult = applyManualControls(captureRequestBuilder, characteristics)
                logManualControlResult(controlResult)
            }

            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running || closing) {
                            Log.w(TAG, "Ignored stale onConfigured callback (running=$running, closing=$closing)")
                            session.close()
                            return
                        }

                        Log.i("GL_PIPE", "$streamId SESSION_OK targets=${surfaces.size} hasPreview=${enablePreview && previewSurface != null}")
                        cameraCaptureSession = session
                        try {
                            session.setRepeatingRequest(
                                captureRequestBuilder!!.build(),
                                null, backgroundHandler
                            )
                            Log.i("GL_PIPE", "$streamId REPEAT_OK")
                            Log.d(TAG, "Camera preview session started")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting preview", e)
                            Log.e("GL_PIPE", "$streamId REPEAT_FAIL ex=${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (!running || closing) {
                            Log.w(TAG, "Ignored stale onConfigureFailed callback (running=$running, closing=$closing)")
                            return
                        }
                        Log.e(TAG, "Camera configuration failed")
                        Log.e("GL_PIPE", "$streamId SESSION_FAIL")
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera preview session", e)
            Log.e("GL_PIPE", "$streamId FAIL stage=session cameraId=${cameraId} err=${e.message}")
        }
    }

    /**
     * 关闭相机
     */
    private fun closeCamera() {
        var acquired = false
        try {
            acquired = cameraOpenCloseLock.tryAcquire(1200, TimeUnit.MILLISECONDS)
            if (!acquired) {
                Log.w("GL_CAM", "closeCamera lock timeout, fallback cleanup without lock streamId=$streamId")
            }

            // 安全停止重复请求 - 特别处理CAMERA_ERROR(3)
            cameraCaptureSession?.let { session ->
                try {
                    session.stopRepeating()
                    Log.d(TAG, "Stopped repeating requests")
                } catch (e: Exception) {
                    // 吃掉所有异常，包括CAMERA_ERROR，避免二次崩溃
                    Log.w("GL_CAM", "Failed to stop repeating: ${e.message}")
                }
                try {
                    session.close()
                } catch (e: Exception) {
                    Log.w("GL_CAM", "Failed to close session: ${e.message}")
                }
            }

            cameraCaptureSession = null

            try {
                cameraDevice?.close()
            } catch (e: Exception) {
                Log.w("GL_CAM", "Failed to close camera device: ${e.message}")
            }
            cameraDevice = null

            try {
                imageReader?.close()
            } catch (e: Exception) {
                Log.w("GL_CAM", "Failed to close image reader: ${e.message}")
            }
            imageReader = null

            // 释放预览Surface
            try {
                if (previewSurfaceOwnedByPipeline) {
                    previewSurface?.release()
                }
            } catch (e: Exception) {
                Log.w("GL_SURF", "Failed to release preview surface: ${e.message}")
            }
            previewSurface = null
            previewSurfaceOwnedByPipeline = false

            // 注意：不要释放SurfaceTexture本身（textureView.surfaceTexture），让系统管理
            Log.d(TAG, "Camera cleanup completed")

            // 检查是否有待处理的启动请求
            if (pendingStart) {
                pendingStart = false
                scheduleStartRetry("closeFinished")
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while closing camera", e)
            Thread.currentThread().interrupt()
        } finally {
            if (acquired) {
                cameraOpenCloseLock.release()
            }
        }
    }

    private fun releaseOpenCloseLockIfHeld() {
        if (openLockHeld) {
            openLockHeld = false
            cameraOpenCloseLock.release()
        }
    }

    /**
     * 启动后台线程
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground-$streamId").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    /**
     * 停止后台线程
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(1200L)
            if (backgroundThread?.isAlive == true) {
                Log.w(TAG, "Background thread join timeout, forcing quit")
                backgroundThread?.quit()
                backgroundThread?.join(500L)
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
            Thread.currentThread().interrupt()
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }

    /**
     * 选择共同支持的合理尺寸
     * 优先1280x720/720x1280，否则选择最优尺寸
     */
    private fun chooseConservativeSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(1280, 720)

        // 从StreamConfigurationMap拿到两类尺寸并求交集
        val stSizes: List<android.util.Size> =
            (map.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()).toList()
        val yuvSizes: List<android.util.Size> =
            (map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: emptyArray()).toList()
        val common: List<android.util.Size> = stSizes.filter { st: android.util.Size ->
            yuvSizes.any { y: android.util.Size -> y.width == st.width && y.height == st.height }
        }

        if (common.isEmpty()) {
            Log.w(TAG, "No common sizes found, using default 1280x720")
            return Size(1280, 720)
        }

        val screenAspect = maxOf(textureView.width, textureView.height).toFloat() /
                          minOf(textureView.width, textureView.height).toFloat()

        // 优先选择1280x720或720x1280
        val target1280x720 = Size(1280, 720)
        val target720x1280 = Size(720, 1280)

        val candidates = mutableListOf<Size>()
        if (common.contains(target1280x720)) candidates.add(target1280x720)
        if (common.contains(target720x1280)) candidates.add(target720x1280)

        val chosenSize = if (candidates.isNotEmpty()) {
            // 选aspectDiff更小的
            candidates.minByOrNull { size ->
                val sizeAspect = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height).toFloat()
                kotlin.math.abs(sizeAspect - screenAspect)
            } ?: candidates.first()
        } else {
            // 否则：在common里挑选"长边不超过1920"的尺寸
            val filtered = common.filter { maxOf(it.width, it.height) <= 1920 }
            val candidates2 = if (filtered.isNotEmpty()) filtered else common

            // 评分：先按aspectDiff升序，再按longDiff升序
            candidates2.minByOrNull { size ->
                val longSide = maxOf(size.width, size.height)
                val shortSide = minOf(size.width, size.height)
                val sizeAspect = longSide.toFloat() / shortSide.toFloat()

                val aspectDiff = kotlin.math.abs(sizeAspect - screenAspect)
                val longDiff = kotlin.math.abs(longSide - 1280)

                // 复合评分：aspect优先，其次是longSide接近1280
                aspectDiff * 1000 + longDiff
            } ?: candidates2.first()
        }

        return logChosenSize(chosenSize)
    }

    /**
     * 记录并返回选择的尺寸
     */
    private fun logChosenSize(size: Size): Size {
        if (!sizeLogged) {
            val tw = textureView.width
            val th = textureView.height
            val screenAspect = (kotlin.math.max(tw, th).toFloat() / kotlin.math.min(tw, th).toFloat())
            val chosenAspect = (kotlin.math.max(size.width, size.height).toFloat() / kotlin.math.min(size.width, size.height).toFloat())
            android.util.Log.i("GL_SIZE",
                "textureViewSize=${tw}x${th}, chosenSize=${size.width}x${size.height}, screenAspect=${"%.3f".format(screenAspect)}, chosenAspect=${"%.3f".format(chosenAspect)}, bufferSizeSet=${size.width}x${size.height}"
            )
            sizeLogged = true
        }

        return size
    }

    /**
     * 调度延迟重试启动
     */
    private fun scheduleStartRetry(reason: String) {
        val currentGen = generation
        val scheduledGen = ++pendingStartGeneration

        backgroundHandler?.postDelayed({
            // 检查代际是否仍然有效
            if (scheduledGen == pendingStartGeneration && !running && !closing) {
                Log.i("GL_START", "retry start now (reason: $reason)")
                start() // 递归调用，但这次应该成功
            }
        }, 200L) // 200ms 延迟

        Log.d("GL_START", "defer start due to $reason")
    }

    /**
     * 选择最优尺寸（保留原有方法以防需要）
     */
    private fun chooseOptimalSize(
        choices: Array<Size>,
        textureViewWidth: Int,
        textureViewHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Size {
        val bigEnough = mutableListOf<Size>()
        val notBigEnough = mutableListOf<Size>()

        for (option in choices) {
            if (option.width <= maxWidth && option.height <= maxHeight &&
                option.height == option.width * textureViewHeight / textureViewWidth) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        return when {
            bigEnough.isNotEmpty() -> bigEnough.minByOrNull { it.width * it.height } ?: choices[0]
            notBigEnough.isNotEmpty() -> notBigEnough.maxByOrNull { it.width * it.height } ?: choices[0]
            else -> choices[0]
        }
    }

    /**
     * 应用手动控制设置
     */
    private fun applyManualControls(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics
    ): ManualControlResult {
        val downgradeReasons = mutableListOf<String>()

        // 获取设备能力信息
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val hasManualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

        // 获取可用模式
        val aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()

        // 获取范围
        val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

        // 目标值
        val targetExposureNs: Long = 100_000_000L // 100ms
        val targetIso: Int = sensitivityRange?.upper ?: 1600 // 默认ISO

        // 应用ISO设置
        var finalIso = targetIso
        try {
            if (sensitivityRange == null) {
                downgradeReasons.add("SENSOR_INFO_SENSITIVITY_RANGE not available, using default ISO")
                finalIso = 1600
            } else {
                finalIso = sensitivityRange.upper
            }
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, finalIso)
        } catch (e: IllegalArgumentException) {
            downgradeReasons.add("Failed to set ISO: ${e.message}")
        }

        // 应用曝光时间设置
        var finalExposureNs = targetExposureNs
        try {
            if (exposureTimeRange == null) {
                downgradeReasons.add("SENSOR_INFO_EXPOSURE_TIME_RANGE not available, using default exposure")
                finalExposureNs = 33_333_333L // ~30fps
            } else {
                // clamp 到可用范围内
                finalExposureNs = targetExposureNs.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
                if (finalExposureNs != targetExposureNs) {
                    downgradeReasons.add("Exposure time clamped from ${targetExposureNs}ns to ${finalExposureNs}ns")
                }
            }
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, finalExposureNs)
        } catch (e: IllegalArgumentException) {
            downgradeReasons.add("Failed to set exposure time: ${e.message}")
        }

        // 应用AE模式设置
        var finalAeMode = CaptureRequest.CONTROL_AE_MODE_OFF
        try {
            if (aeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF) && hasManualSensor) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            } else {
                finalAeMode = CaptureRequest.CONTROL_AE_MODE_ON
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                downgradeReasons.add("AE_MODE_OFF not supported or MANUAL_SENSOR not available, using AE_MODE_ON")
            }
        } catch (e: IllegalArgumentException) {
            finalAeMode = CaptureRequest.CONTROL_AE_MODE_ON
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            downgradeReasons.add("Failed to set AE mode OFF: ${e.message}, using AE_MODE_ON")
        }

        // 应用AF模式设置
        var finalAfMode = CaptureRequest.CONTROL_AF_MODE_OFF
        try {
            if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            } else {
                // 降级到更常见的模式
                finalAfMode = if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                } else {
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, finalAfMode)
                downgradeReasons.add("AF_MODE_OFF not supported, using AF_MODE_${getAfModeName(finalAfMode)}")
            }
        } catch (e: IllegalArgumentException) {
            finalAfMode = CaptureRequest.CONTROL_AF_MODE_AUTO
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            downgradeReasons.add("Failed to set AF mode: ${e.message}, using AF_MODE_AUTO")
        }

        return ManualControlResult(
            aeMode = finalAeMode,
            afMode = finalAfMode,
            exposureNs = finalExposureNs,
            iso = finalIso,
            downgradeReasons = downgradeReasons
        )
    }

    /**
     * 记录手动控制结果和设备信息
     */
    private fun logManualControlResult(result: ManualControlResult) {
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!) ?: return

        // 设备基本信息
        Log.i(TAG, "=== Camera Manual Control Setup ===")
        Log.i(TAG, "Camera ID: $cameraId")

        // 设备能力
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        Log.i(TAG, "REQUEST_AVAILABLE_CAPABILITIES: ${capabilities.joinToString()}")
        Log.i(TAG, "MANUAL_SENSOR supported: ${capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)}")

        // 范围信息
        val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        Log.i(TAG, "SENSOR_INFO_SENSITIVITY_RANGE: $sensitivityRange")

        val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        Log.i(TAG, "SENSOR_INFO_EXPOSURE_TIME_RANGE: $exposureTimeRange")

        // 可用模式
        val aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        Log.i(TAG, "CONTROL_AE_AVAILABLE_MODES: ${aeModes.joinToString { getAeModeName(it) }}")

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        Log.i(TAG, "CONTROL_AF_AVAILABLE_MODES: ${afModes.joinToString { getAfModeName(it) }}")

        // 最终设置
        Log.i(TAG, "=== Final Settings ===")
        Log.i(TAG, "AE Mode: ${getAeModeName(result.aeMode)}")
        Log.i(TAG, "AF Mode: ${getAfModeName(result.afMode)}")
        Log.i(TAG, "Exposure Time: ${result.exposureNs}ns (${String.format("%.2f", result.exposureNs / 1_000_000.0)}ms)")
        Log.i(TAG, "ISO: ${result.iso}")

        // 降级原因
        if (result.downgradeReasons.isNotEmpty()) {
            Log.w(TAG, "=== Downgrade Reasons ===")
            result.downgradeReasons.forEach { reason ->
                Log.w(TAG, "- $reason")
            }
        } else {
            Log.i(TAG, "No downgrades needed - full manual control available")
        }

        Log.i(TAG, "=== Camera Setup Complete ===")
    }

    /**
     * 获取AE模式名称
     */
    private fun getAeModeName(mode: Int): String = when (mode) {
        CaptureRequest.CONTROL_AE_MODE_OFF -> "OFF"
        CaptureRequest.CONTROL_AE_MODE_ON -> "ON"
        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "ON_AUTO_FLASH_REDEYE"
        else -> "UNKNOWN($mode)"
    }

    /**
     * 获取AF模式名称
     */
    private fun getAfModeName(mode: Int): String = when (mode) {
        CaptureRequest.CONTROL_AF_MODE_OFF -> "OFF"
        CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO"
        CaptureRequest.CONTROL_AF_MODE_MACRO -> "MACRO"
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "UNKNOWN($mode)"
    }
}