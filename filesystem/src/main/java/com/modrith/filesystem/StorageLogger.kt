package com.modrith.filesystem

import java.util.logging.Level
import java.util.logging.Logger

interface StorageLogger {
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

class JvmStorageLogger(
    private val logger: Logger = Logger.getLogger("com.modrith.filesystem"),
) : StorageLogger {
    override fun debug(event: String, attributes: Map<String, Any?>) =
        log(Level.FINE, event, attributes)

    override fun info(event: String, attributes: Map<String, Any?>) =
        log(Level.INFO, event, attributes)

    override fun warn(event: String, attributes: Map<String, Any?>, cause: Throwable?) =
        log(Level.WARNING, event, attributes, cause)

    override fun error(event: String, attributes: Map<String, Any?>, cause: Throwable?) =
        log(Level.SEVERE, event, attributes, cause)

    private fun log(
        level: Level,
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable? = null,
    ) {
        val safeAttributes = attributes.entries.joinToString(prefix = "{", postfix = "}") {
            "${it.key}=${redact(it.value)}"
        }
        logger.log(
            level,
            buildString {
                append("$event $safeAttributes")
                cause?.let { append(" cause=${it::class.simpleName ?: "unknown"}") }
            },
        )
    }

    private fun redact(value: Any?): String =
        UriPattern.replace(value?.toString() ?: "null", "[redacted-uri]")

    private companion object {
        val UriPattern = Regex("(?:content|file)://\\S+")
    }
}
