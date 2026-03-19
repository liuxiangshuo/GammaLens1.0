package com.gammalens.app.camera

import org.opencv.core.Mat

/**
 * 帧数据包：封装单帧图像信息
 * 用于在相机管线和处理链之间传递数据
 */
data class FramePacket(
    val streamId: String,           // 流标识（如 "main_camera"）
    val timestampNs: Long,          // 帧时间戳（纳秒）
    val width: Int,                 // 帧宽度
    val height: Int,                // 帧高度
    val grayMat: Mat,               // 灰度Mat（Step 2开始必须有）
    val frameNumber: Long,          // 帧序号
    val rotationDegrees: Int,       // 旋转角度（0/90/180/270）
    val isMirrored: Boolean         // 是否镜像（后摄为false）
)

/**
 * 可处理单元：单帧或配对帧（用于共模抑制）
 * 当 other != null 时，仅当 anchor 有 blob 且 other 无亮度突变时才触发事件。
 */
data class ProcessableItem(val anchor: FramePacket, val other: FramePacket? = null)