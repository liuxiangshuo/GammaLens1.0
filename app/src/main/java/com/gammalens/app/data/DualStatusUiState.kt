package com.gammalens.app.data

/**
 * Dual-camera status for UI chips.
 */
data class DualStatusUiState(
    val dualActive: Boolean = false,
    val enablePairing: Boolean = false,
    val pairMade: Boolean? = null,
    val deltaNs: Long? = null,
    val suppressionActive: Boolean = false,
    val fallback: Boolean = false,
)
