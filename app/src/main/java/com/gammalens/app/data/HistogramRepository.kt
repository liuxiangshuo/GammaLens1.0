package com.gammalens.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val WINDOW_MS = 60_000
private val AREA_BUCKETS = listOf(0f to 50f, 50f to 100f, 100f to 200f, 200f to 350f, 350f to 500f)

/**
 * Builds area histogram from events in last 60s sliding window.
 */
class HistogramRepository(private val eventRepository: EventRepository) {

    private val _histogram = MutableStateFlow(HistogramUiState())
    val histogram: StateFlow<HistogramUiState> = _histogram.asStateFlow()

    fun refresh() {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        val eventsInWindow = eventRepository.getEventsForExport()
            .filter { it.timestampMs >= cutoff }
        val buckets = IntArray(AREA_BUCKETS.size)
        for (e in eventsInWindow) {
            val area = e.maxArea
            for (i in AREA_BUCKETS.indices) {
                val (lo, hi) = AREA_BUCKETS[i]
                if (area >= lo && area < hi) {
                    buckets[i]++
                    break
                }
            }
        }
        _histogram.value = HistogramUiState(
            areaBuckets = buckets.toList(),
            bucketLabels = AREA_BUCKETS.map { "${it.first.toInt()}-${it.second.toInt()}" },
            windowSec = WINDOW_MS / 1000,
            totalCount = eventsInWindow.size,
        )
    }
}
