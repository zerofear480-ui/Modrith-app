package com.modrith.parser.mrpack

import com.modrith.models.LoaderType
import com.modrith.models.MrPackParseErrorCode
import com.modrith.models.MrPackParseResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MrPackParserTest {
    private val parser = MrPackParser(logger = RecordingParserLogger())

    @Test
    fun validMrPackProducesCompleteInstallPlanAndExtractsToCache() {
        withTempDirectory { root ->
            val archive = root.resolve("valid.mrpack")
            createArchive(
                archive,
                mapOf(
                    "modrinth.index.json" to validIndex(
                        extraRootField = """,
                          "futureRootField": {"accepted": true}
                        """.trimIndent(),
                    ),
                    "overrides/config/example.json" to """{"enabled":true}""",
                    "client-overrides/options.txt" to "fov:90",
                ),
            )

            val result = parser.parse(archive, root.resolve("cache")).success()

            assertEquals("1.21.1", result.plan.minecraftVersion)
            assertEquals(LoaderType.FABRIC, result.plan.loaderType)
            assertEquals("0.16.10", result.plan.loaderVersion)
            assertEquals(1, result.plan.modCount)
            assertEquals(2, result.plan.overrideFolders.size)
            assertEquals(1, result.plan.downloadEntries.size)
            assertEquals(1234L, result.plan.totalDownloadSize)
            assertTrue(
                Files.isRegularFile(
                    File(result.plan.cacheDirectory).toPath()
                        .resolve("overrides/config/example.json"),
                ),
            )
            assertTrue(
                Files.isRegularFile(
                    File(result.plan.cacheDirectory).toPath()
                        .resolve("client-overrides/options.txt"),
                ),
            )
        }
    }

    @Test
    fun corruptedZipReturnsStructuredError() {
        withTempDirectory { root ->
            val archive = root.resolve("corrupted.mrpack")
            Files.writeString(archive, "this is not a zip archive")

            val error = parser.parse(archive, root.resolve("cache")).failure()

            assertEquals(MrPackParseErrorCode.CORRUPTED_ZIP, error.error.code)
            assertFalse(error.error.message.isBlank())
        }
    }

    @Test
    fun missingModrinthIndexReturnsStructuredError() {
        withTempDirectory { root ->
            val archive = root.resolve("missing-index.mrpack")
            createArchive(archive, mapOf("overrides/options.txt" to "guiScale:2"))

            val error = parser.parse(archive, root.resolve("cache")).failure()

            assertEquals(MrPackParseErrorCode.MISSING_INDEX, error.error.code)
        }
    }

    @Test
    fun invalidJsonReturnsStructuredError() {
        withTempDirectory { root ->
            val archive = root.resolve("invalid-json.mrpack")
            createArchive(archive, mapOf("modrinth.index.json" to "{not-json"))

            val error = parser.parse(archive, root.resolve("cache")).failure()

            assertEquals(MrPackParseErrorCode.INVALID_JSON, error.error.code)
        }
    }

    @Test
    fun unsupportedFormatVersionReturnsStructuredError() {
        withTempDirectory { root ->
            val archive = root.resolve("unsupported-version.mrpack")
            createArchive(
                archive,
                mapOf("modrinth.index.json" to validIndex(formatVersion = 2)),
            )

            val error = parser.parse(archive, root.resolve("cache")).failure()

            assertEquals(MrPackParseErrorCode.UNSUPPORTED_FORMAT_VERSION, error.error.code)
            assertEquals("2", error.error.details["actual"])
            assertEquals("1", error.error.details["supported"])
        }
    }

    @Test
    fun parsesProvidedSpunkyOptimizedPack() {
        withTempDirectory { root ->
            val resource = checkNotNull(
                javaClass.classLoader.getResource("fixtures/Spunky Optimized 1.21.11.1.mrpack"),
            )
            val archive = Paths.get(resource.toURI())

            val result = parser.parse(archive, root.resolve("cache")).success()

            assertEquals("Spunky Optimized", result.plan.manifest.name)
            assertEquals("1.21.11.1", result.plan.manifest.versionId)
            assertEquals("1.21.11", result.plan.minecraftVersion)
            assertEquals(LoaderType.FABRIC, result.plan.loaderType)
            assertEquals(28, result.plan.modCount)
            assertEquals(30, result.plan.downloadEntries.size)
            assertTrue(result.plan.totalDownloadSize != null)
            assertEquals(listOf("overrides"), result.plan.overrideFolders.map { it.name })
            assertTrue(result.plan.overrideFolders.single().files.isNotEmpty())
        }
    }

    private fun validIndex(
        formatVersion: Int = 1,
        extraRootField: String = "",
    ): String = """
        {
          "formatVersion": $formatVersion,
          "game": "minecraft",
          "versionId": "test-version",
          "name": "Test Pack",
          "summary": "Parser fixture",
          "files": [
            {
              "path": "mods/example.jar",
              "hashes": {
                "sha1": "0123456789abcdef0123456789abcdef01234567",
                "futureHash": "ignored"
              },
              "env": {
                "client": "required",
                "server": "unsupported",
                "futureSide": "ignored"
              },
              "downloads": ["https://cdn.example.test/example.jar"],
              "fileSize": 1234,
              "futureFileField": true
            }
          ],
          "dependencies": {
            "minecraft": "1.21.1",
            "fabric-loader": "0.16.10"
          }
          $extraRootField
        }
    """.trimIndent()

    private fun createArchive(
        archive: Path,
        entries: Map<String, String>,
    ) {
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("mrpack-parser-test")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun MrPackParseResult.success(): MrPackParseResult.Success =
        this as? MrPackParseResult.Success ?: fail("Expected success, got $this").let {
            error("unreachable")
        }

    private fun MrPackParseResult.failure(): MrPackParseResult.Failure =
        this as? MrPackParseResult.Failure ?: fail("Expected failure, got $this").let {
            error("unreachable")
        }
}

private class RecordingParserLogger : ParserLogger {
    val events = mutableListOf<String>()

    override fun debug(event: String, attributes: Map<String, Any?>) {
        events += event
    }

    override fun info(event: String, attributes: Map<String, Any?>) {
        events += event
    }

    override fun warn(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) {
        events += event
    }

    override fun error(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) {
        events += event
    }
}
