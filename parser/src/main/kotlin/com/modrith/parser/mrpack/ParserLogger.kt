package com.modrith.parser.mrpack

import java.util.logging.Level
import java.util.logging.Logger

interface ParserLogger {
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

class JvmParserLogger(
    private val logger: Logger = Logger.getLogger("com.modrith.parser.mrpack"),
) : ParserLogger {
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
            "$key=${sanitize(value)}"
        }
        logger.log(
            level,
            buildString {
                append("$event $safeAttributes")
                cause?.let { append(" cause=${it::class.simpleName ?: "unknown"}") }
            },
        )
    }

    private fun sanitize(value: Any?): String {
        val text = value?.toString() ?: "null"
        val redacted = UrlPattern.replace(text, "[redacted-url]")
        return if (redacted.startsWith("/") || WindowsPathPattern.matches(redacted)) {
            "[redacted-path]"
        } else {
            redacted
        }
    }

    private companion object {
        val UrlPattern = Regex("(?:https?|file|content)://\\S+")
        val WindowsPathPattern = Regex("^[A-Za-z]:[\\\\/].*")
    }
}
