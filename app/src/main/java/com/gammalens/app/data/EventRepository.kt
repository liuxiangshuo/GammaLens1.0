package com.gammalens.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_EVENTS = 2000

/**
 * In-memory event store. Appends on trigger and on SUPPRESSED; capped to avoid OOM.
 */
class EventRepository {

    private val _events = MutableStateFlow<List<EventItem>>(emptyList())
    val events: StateFlow<List<EventItem>> = _events.asStateFlow()

    private var nextId = 0L
    private val list = mutableListOf<EventItem>()

    fun addEvent(
        timestampMs: Long,
        streamId: String,
        scoreEma: Double,
        blobCount: Int,
        maxArea: Float,
        nonZeroCount: Int,
        suppressed: Boolean,
        suppressReason: String? = null,
        dualSnapshot: String? = null,
    ) {
        val item = EventItem(
            id = nextId++,
            timestampMs = timestampMs,
            streamId = streamId,
            scoreEma = scoreEma,
            blobCount = blobCount,
            maxArea = maxArea,
            nonZeroCount = nonZeroCount,
            suppressed = suppressed,
            suppressReason = suppressReason,
            dualSnapshot = dualSnapshot,
        )
        synchronized(list) {
            list.add(0, item)
            while (list.size > MAX_EVENTS) list.removeAt(list.lastIndex)
            _events.value = list.toList()
        }
    }

    fun getEventsForExport(): List<EventItem> = synchronized(list) { list.toList() }
}
