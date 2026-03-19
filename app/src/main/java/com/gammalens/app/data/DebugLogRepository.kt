package com.gammalens.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_MVP_STATUS = 5
private const val MAX_GL_SYNC = 30
private const val MAX_GL_EVT = 30
private const val MAX_PERF_STATUS = 30

/**
 * In-memory ring buffers for debug panels. Push from pipeline/logcat hooks or via setter.
 */
class DebugLogRepository {

    private val _mvpStatusLines = MutableStateFlow<List<String>>(emptyList())
    val mvpStatusLines: StateFlow<List<String>> = _mvpStatusLines.asStateFlow()

    private val _glSyncLines = MutableStateFlow<List<String>>(emptyList())
    val glSyncLines: StateFlow<List<String>> = _glSyncLines.asStateFlow()

    private val _glEvtLines = MutableStateFlow<List<String>>(emptyList())
    val glEvtLines: StateFlow<List<String>> = _glEvtLines.asStateFlow()

    private val _perfStatusLines = MutableStateFlow<List<String>>(emptyList())
    val perfStatusLines: StateFlow<List<String>> = _perfStatusLines.asStateFlow()

    private val mvpList = ArrayDeque<String>()
    private val syncList = ArrayDeque<String>()
    private val evtList = ArrayDeque<String>()
    private val perfList = ArrayDeque<String>()

    fun pushMvpStatus(line: String) {
        mvpList.addFirst(line)
        while (mvpList.size > MAX_MVP_STATUS) mvpList.removeLast()
        _mvpStatusLines.value = mvpList.toList()
    }

    fun pushGlSync(line: String) {
        syncList.addFirst(line)
        while (syncList.size > MAX_GL_SYNC) syncList.removeLast()
        _glSyncLines.value = syncList.toList()
    }

    fun pushGlEvt(line: String) {
        evtList.addFirst(line)
        while (evtList.size > MAX_GL_EVT) evtList.removeLast()
        _glEvtLines.value = evtList.toList()
    }

    fun pushPerfStatus(line: String) {
        perfList.addFirst(line)
        while (perfList.size > MAX_PERF_STATUS) perfList.removeLast()
        _perfStatusLines.value = perfList.toList()
    }

    fun getDebugSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("=== MVP_STATUS ===")
        mvpList.forEach { sb.appendLine(it) }
        sb.appendLine("=== GL_SYNC (recent) ===")
        syncList.take(10).forEach { sb.appendLine(it) }
        sb.appendLine("=== GL_EVT (recent) ===")
        evtList.take(10).forEach { sb.appendLine(it) }
        sb.appendLine("=== PERF (recent) ===")
        perfList.take(10).forEach { sb.appendLine(it) }
        return sb.toString()
    }

    /** Last N GL_SYNC lines for run debug.txt export. */
    fun getGlSyncLinesForExport(n: Int = 30): List<String> = syncList.take(n).toList()

    /** Last N GL_EVT lines for run debug.txt export. */
    fun getGlEvtLinesForExport(n: Int = 30): List<String> = evtList.take(n).toList()

    /** Last N PERF lines for run debug.txt export. */
    fun getPerfLinesForExport(n: Int = 30): List<String> = perfList.take(n).toList()
}
