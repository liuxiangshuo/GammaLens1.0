package com.gammalens.app.data

/**
 * MVP histogram: area distribution in sliding window (e.g. last 60s).
 * bins: bucket index -> count. Area buckets e.g. 0-50, 50-100, ..., 450-500.
 */
data class HistogramUiState(
    val areaBuckets: List<Int> = emptyList(),
    val bucketLabels: List<String> = emptyList(),
    val windowSec: Int = 60,
    val totalCount: Int = 0,
)
