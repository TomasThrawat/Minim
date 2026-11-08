package com.minim.music.data.ai

import android.content.Context

/** Gemini uses only the API key entered by the user on this device. */
object GeminiRoute {
    @Volatile var defaultKnownOff = true

    @Suppress("UNUSED_PARAMETER")
    fun hasBuiltIn(context: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun endpoint(context: Context, settings: GeminiSettings): GeminiEndpoint {
        val key = settings.apiKey()?.takeIf { it.isNotBlank() }
            ?: throw GeminiException(GeminiProblem.DefaultUnavailable)
        return GeminiEndpoint.direct(settings.model, key)
    }
}
