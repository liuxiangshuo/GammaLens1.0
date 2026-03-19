package com.gammalens.app.config

/**
 * Single source of truth for runtime release/model tagging.
 */
object ReleaseVersionConfig {
    const val DEFAULT_RELEASE_STATE: String = "promoted"
    const val DEFAULT_MODEL_VERSION: String = "v8-r3-nomodel-prod"
}
