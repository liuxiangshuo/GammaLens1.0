package com.gammalens.app.camera

import android.util.Log
import kotlin.math.abs

/**
 * 帧对：双摄配对结果
 */
data class FramePair(val a: FramePacket, val b: FramePacket, val deltaNs: Long)

/**
 * 帧同步器
 * - enablePairing=false：直通，返回 [ProcessableItem(packet, null)]
 * - enablePairing=true：若 within window 则输出 ProcessableItem(anchor, other)，否则 ProcessableItem(anchor, null)；outCount=1
 */
class FrameSynchronizer(private val enablePairing: Boolean = false) {

    /** Optional: forward GL_SYNC log line for debug UI. */
    var onSyncLog: ((String) -> Unit)? = null

    private var logCount = 0
    private val lastByStreamId = mutableMapOf<String, FramePacket>()
    private val pairWindowNs = 50_000_000L  // 50ms
    private var latestPair: FramePair? = null
    private var latestPairTsNs: Long = -1L
    private var latestPairCapturedAtNs: Long = -1L
    private var latestDeltaNs: Long = -1L
    private var latestPairQuality: Double = 0.0

    /**
     * 提交一帧；enablePairing 时若 within window 则更新 latestPair，始终只返回一个 ProcessableItem，outCount=1。
     */
    fun submit(packet: FramePacket): List<ProcessableItem> {
        lastByStreamId[packet.streamId] = packet

        val pair = tryMakePair()
        val pairMade = pair != null
        val deltaNsLog = pair?.deltaNs ?: if (lastByStreamId.size >= 2) {
            val ts = lastByStreamId.values.map { it.timestampNs }
            abs((ts.maxOrNull() ?: 0L) - (ts.minOrNull() ?: 0L))
        } else {
            -1L
        }
        val isWithinWindow = deltaNsLog >= 0 && deltaNsLog <= pairWindowNs
        val pairQuality = if (isWithinWindow) {
            1.0 - (deltaNsLog.toDouble() / pairWindowNs.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        if (enablePairing && pair != null) {
            latestPair = pair
            latestPairTsNs = maxOf(pair.a.timestampNs, pair.b.timestampNs)
            latestPairCapturedAtNs = System.nanoTime()
            latestDeltaNs = pair.deltaNs
            latestPairQuality = pairQuality
        }

        logCount++
        if (logCount % 60 == 0) {
            val seenStreams = lastByStreamId.keys.sorted().joinToString(",")
            val pairCaptured = latestPair != null
            val latestPairAgeMs = if (latestPair != null && latestPairCapturedAtNs >= 0) {
                (System.nanoTime() - latestPairCapturedAtNs) / 1_000_000
            } else {
                -1L
            }
            val line = "seenStreams=[$seenStreams], enablePairing=$enablePairing, pairMade=$pairMade, deltaNs=$deltaNsLog, pairQuality=${"%.2f".format(pairQuality)}, isWithinWindow=$isWithinWindow, windowNs=$pairWindowNs, pairCaptured=$pairCaptured, latestPairAgeMs=$latestPairAgeMs, outCount=1"
            Log.d("GL_SYNC", line)
            onSyncLog?.invoke(line)
        }

        val anchor = lastByStreamId.values.maxByOrNull { it.timestampNs }!!
        val item = if (!enablePairing) {
            ProcessableItem(packet, null)
        } else {
            val other = if (pair != null && pair.a.streamId != pair.b.streamId) {
                if (pair.a.timestampNs >= pair.b.timestampNs) pair.b else pair.a
            } else null
            ProcessableItem(anchor, other)
        }
        return listOf(item)
    }

    /**
     * 配对：newest 与来自另一 stream 且时间最近的帧；deltaNs<=pairWindowNs 时返回配对。
     */
    private fun tryMakePair(): FramePair? {
        if (lastByStreamId.size < 2) return null
        val packets = lastByStreamId.values.toList()
        val newest = packets.maxByOrNull { it.timestampNs } ?: return null
        val other = packets.filter { it.streamId != newest.streamId }
            .minByOrNull { abs(it.timestampNs - newest.timestampNs) } ?: return null
        val deltaNs = abs(newest.timestampNs - other.timestampNs)
        return if (deltaNs <= pairWindowNs) FramePair(newest, other, deltaNs) else null
    }

    /**
     * 重置同步器状态
     */
    fun reset() {
        logCount = 0
        lastByStreamId.clear()
        latestPair = null
        latestPairTsNs = -1L
        latestPairCapturedAtNs = -1L
        latestDeltaNs = -1L
        latestPairQuality = 0.0
    }

    fun getLatestPairDeltaNs(): Long = latestDeltaNs

    fun getLatestPairQuality(): Double = latestPairQuality
}