package com.modrith.orchestrator

import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class InstallLogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}

enum class InstallLogSource {
    ORCHESTRATOR,
    PARSER,
    RESOLVER,
    DOWNLOADER,
    FILESYSTEM,
    INSTALLER,
    LAUNCHER,
}

data class InstallLogEntry(
    val timestampEpochMillis: Long,
    val sessionId: String,
    val source: InstallLogSource,
    val level: InstallLogLevel,
    val event: String,
    val attributes: Map<String, String>,
)

interface InstallLogger {
    fun log(
        sessionId: String,
        source: InstallLogSource,
        level: InstallLogLevel,
        event: String,
        attributes: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null,
    )

    fun entries(sessionId: String): StateFlow<List<InstallLogEntry>>
}

class JvmInstallLogger(
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = Logger.getLogger("com.modrith.orchestrator"),
) : InstallLogger {
    private val sessions = ConcurrentHashMap<String, MutableStateFlow<List<InstallLogEntry>>>()

    override fun log(
        sessionId: String,
        source: InstallLogSource,
        level: InstallLogLevel,
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) {
        val safeAttributes = attributes.mapValues { (_, value) -> redact(value) }.toMap()
        val entry = InstallLogEntry(
            timestampEpochMillis = clock(),
            sessionId = sessionId,
            source = source,
            level = level,
            event = event,
            attributes = safeAttributes,
        )
        sessions.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.update { it + entry }
        logger.log(
            level.toJavaLevel(),
            buildString {
                append("$source $event ")
                append(safeAttributes.entries.joinToString(prefix = "{", postfix = "}"))
                cause?.let { append(" cause=${it::class.simpleName ?: "unknown"}") }
            },
        )
    }

    override fun entries(sessionId: String): StateFlow<List<InstallLogEntry>> =
        sessions.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.asStateFlow()

    private fun redact(value: Any?): String {
        val text = UriPattern.replace(value?.toString() ?: "null", "[redacted]")
        return if (text.startsWith("/") || WindowsPathPattern.matches(text)) {
            "[redacted-path]"
        } else {
            text
        }
    }

    private fun InstallLogLevel.toJavaLevel(): Level = when (this) {
        InstallLogLevel.DEBUG -> Level.FINE
        InstallLogLevel.INFO -> Level.INFO
        InstallLogLevel.WARNING -> Level.WARNING
        InstallLogLevel.ERROR -> Level.SEVERE
    }

    private companion object {
        val UriPattern = Regex("(?:https?|content|file)://\\S+")
        val WindowsPathPattern = Regex("^[A-Za-z]:[\\\\/].*")
    }
}
