package com.gammalens.app.camera

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * OpenCV 引导器：确保 OpenCV 库正确初始化
 * 线程安全，支持单次初始化
 */
object OpenCvBootstrap {

    private const val TAG = "OpenCvBootstrap"
    private const val OPENCV_LIBRARY_NAME = "opencv_java4"

    @Volatile
    private var initialized: Boolean = false

    /**
     * 确保 OpenCV 已初始化
     * 线程安全，只会执行一次初始化
     * @return 初始化是否成功
     */
    fun ensureInitialized(): Boolean {
        // 双重检查锁定模式
        if (initialized) {
            return true
        }

        return synchronized(this) {
            if (initialized) {
                true
            } else {
                val success = initializeOpenCV()
                initialized = success
                success
            }
        }
    }

    /**
     * 执行 OpenCV 初始化
     * 优先使用 OpenCVLoader.initDebug()，失败时尝试直接加载库
     */
    private fun initializeOpenCV(): Boolean {
        try {
            // 首先尝试使用 OpenCVLoader.initDebug()
            if (OpenCVLoader.initDebug()) {
                Log.i(TAG, "OpenCV initialized successfully using OpenCVLoader.initDebug()")
                return true
            } else {
                Log.w(TAG, "OpenCVLoader.initDebug() failed, trying direct library load")
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenCVLoader.initDebug() threw exception: ${e.message}")
        }

        try {
            // 备用方案：直接加载 OpenCV 库
            System.loadLibrary(OPENCV_LIBRARY_NAME)
            Log.i(TAG, "OpenCV initialized successfully using System.loadLibrary($OPENCV_LIBRARY_NAME)")
            return true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load OpenCV library '$OPENCV_LIBRARY_NAME': ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading OpenCV library: ${e.message}")
        }

        Log.e(TAG, "All OpenCV initialization methods failed")
        return false
    }
}