package com.modrith.downloader.logging

import java.util.logging.Level
import java.util.logging.Logger

interface DownloadLogger {
    fun debug(event: String, attributes: Map<String, Any?> = emptyMap())

    fun info(event: String, attributes: Map<String, Any?> = emptyMap())

    fun warn(
        event: String,
        attributes: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null,
    )

    fun error(
        event: String,
        attributes: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null,
    )
}

class JvmDownloadLogger(
    private val logger: Logger = Logger.getLogger("com.modrith.downloader"),
) : DownloadLogger {
    override fun debug(event: String, attributes: Map<String, Any?>) {
        log(Level.FINE, event, attributes)
    }

    override fun info(event: String, attributes: Map<String, Any?>) {
        log(Level.INFO, event, attributes)
    }

    override fun warn(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) {
        log(Level.WARNING, event, attributes, cause)
    }

    override fun error(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) {
        log(Level.SEVERE, event, attributes, cause)
    }

    private fun log(
        level: Level,
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable? = null,
    ) {
        val safeAttributes = attributes.entries.joinToString(
            prefix = "{",
            postfix = "}",
        ) { (key, value) ->
            "$key=${redact(value)}"
        }
        logger.log(
            level,
            buildString {
                append("$event $safeAttributes")
                cause?.let { append(" cause=${it::class.simpleName ?: "unknown"}") }
            },
        )
    }

    private fun redact(value: Any?): String {
        val text = value?.toString() ?: "null"
        return UrlPattern.replace(text, "[redacted-url]")
    }

    private companion object {
        val UrlPattern = Regex("(?:https?|file|content)://\\S+")
    }
}
