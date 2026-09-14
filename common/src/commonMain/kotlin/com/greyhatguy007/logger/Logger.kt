package com.greyhatguy007.logger

import co.touchlab.kermit.Logger as Kermit

object Logger {
    // Tags suppressed at all log levels. Add a tag here to silence its logs globally.
    private val mutedTags =
        setOf(
            "DiscordWebSocket",
        )

    private fun isMuted(tag: String): Boolean = tag in mutedTags

    fun d(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        Kermit.withTag(tag).d { message }
    }

    fun i(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        Kermit.withTag(tag).i { message }
    }

    fun w(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        Kermit.withTag(tag).w { message }
    }

    fun e(
        tag: String,
        message: String,
        e: Throwable? = null,
    ) {
        if (isMuted(tag)) return
        Kermit.withTag(tag).e(throwable = e) { message }
    }
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}
