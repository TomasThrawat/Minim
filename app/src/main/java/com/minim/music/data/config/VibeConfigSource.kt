package com.minim.music.data.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Local feature defaults; no remote configuration service is used. */
class VibeConfigSource {
    private val _config = MutableStateFlow(VibeConfig.DEFAULT)
    val config: StateFlow<VibeConfig> = _config

    @Suppress("UNUSED_PARAMETER")
    fun refresh(force: Boolean = false) = Unit
}
