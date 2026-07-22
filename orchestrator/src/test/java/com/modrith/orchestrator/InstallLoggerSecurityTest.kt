package com.modrith.orchestrator

import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallLoggerSecurityTest {
    @Test
    fun loggerRedactsUrisAndAbsolutePathsWithoutPersistingThrowables() {
        val records = mutableListOf<LogRecord>()
        val javaLogger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(
                object : Handler() {
                    override fun publish(record: LogRecord) {
                        records += record
                    }

                    override fun flush() = Unit

                    override fun close() = Unit
                },
            )
        }
        val logger = JvmInstallLogger(clock = { 1L }, logger = javaLogger)

        logger.log(
            sessionId = "session",
            source = InstallLogSource.PARSER,
            level = InstallLogLevel.ERROR,
            event = "parse.failed",
            attributes = mapOf(
                "uri" to "content://provider/private/document",
                "path" to "/data/user/0/com.modrith.app/cache/archive.mrpack",
            ),
            cause = IllegalStateException("sensitive failure detail"),
        )

        val entry = logger.entries("session").value.single()
        assertTrue(entry.attributes.values.all { "private" !in it })
        assertTrue(entry.attributes.values.all { "/data/user" !in it })

        val record = records.single()
        assertFalse(record.message.contains("sensitive failure detail"))
        assertTrue(record.message.contains("cause=IllegalStateException"))
        assertNull(record.thrown)
    }
}
