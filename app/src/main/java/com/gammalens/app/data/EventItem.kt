package com.gammalens.app.data

/**
 * Single event (trigger or suppressed) for the Events list and CSV export.
 */
data class EventItem(
    val id: Long,
    val timestampMs: Long,
    val streamId: String,
    val scoreEma: Double,
    val blobCount: Int,
    val maxArea: Float,
    val nonZeroCount: Int,
    val suppressed: Boolean,
    val suppressReason: String? = null,
    val dualSnapshot: String? = null,
)
