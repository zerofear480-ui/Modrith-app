package com.modrith.launcher

import com.modrith.filesystem.CacheProvider
import com.modrith.filesystem.DeleteSummary
import com.modrith.filesystem.FileReplaceResult
import com.modrith.filesystem.StorageEntry
import com.modrith.filesystem.StorageError
import com.modrith.filesystem.StorageErrorCode
import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageProgressListener
import com.modrith.filesystem.StorageProvider
import com.modrith.filesystem.StorageResult
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCSLauncherProviderTest {
    @Test
    fun validLauncherDetectsProfilesVersionsLoadersAndUsesReadOnlyOperations() =
        withLauncherRoot { root, provider ->
            createStandardDirectories(provider)
            writeProfiles(
                provider,
                """
                {
                  "profiles": {
                    "vanilla": {
                      "name": "Vanilla",
                      "lastVersionId": "1.21.1",
                      "type": "custom"
                    },
                    "fabric": {
                      "name": "Fabric",
                      "lastVersionId": "fabric-loader-0.16.10-1.21.1"
                    },
                    "forge": {
                      "name": "Forge",
                      "lastVersionId": "1.20.1-forge-47.3.0"
                    },
                    "neoforge": {
                      "name": "NeoForge",
                      "lastVersionId": "1.21.1-neoforge-21.1.100"
                    }
                  }
                }
                """,
            )
            createVersion(provider, "1.21.1", versionJson("1.21.1"))
            createVersion(
                provider,
                "fabric-loader-0.16.10-1.21.1",
                versionJson(
                    "fabric-loader-0.16.10-1.21.1",
                    inheritsFrom = "1.21.1",
                    library = "net.fabricmc:fabric-loader:0.16.10",
                ),
            )
            createVersion(
                provider,
                "1.20.1-forge-47.3.0",
                versionJson(
                    "1.20.1-forge-47.3.0",
                    inheritsFrom = "1.20.1",
                    library = "net.minecraftforge:forge:1.20.1-47.3.0",
                ),
            )
            createVersion(
                provider,
                "1.21.1-neoforge-21.1.100",
                versionJson(
                    "1.21.1-neoforge-21.1.100",
                    inheritsFrom = "1.21.1",
                    library = "net.neoforged:neoforge:21.1.100",
                ),
            )
            val readOnly = MutationDetectingProvider(provider)

            val info = DefaultCSLauncherProvider()
                .inspect(readOnly)
                .success()

            assertTrue(info.capabilities.compatible)
            assertTrue(info.capabilities.readOnly)
            assertFalse(info.capabilities.canInstall)
            assertFalse(info.capabilities.canCreateInstances)
            assertFalse(info.capabilities.canEditProfiles)
            assertTrue(info.capabilities.hasFabric)
            assertTrue(info.capabilities.hasForge)
            assertTrue(info.capabilities.hasNeoForge)
            assertEquals(4, info.instances.size)
            assertEquals(4, info.versions.size)
            assertTrue(info.errors.isEmpty())
            assertEquals(0, readOnly.mutationCalls.get())
            assertTrue(Files.isDirectory(root.resolve("versions")))
        }

    @Test
    fun missingFoldersProduceWarningsWithoutInventingThem() =
        withLauncherRoot { _, provider ->
            provider.createDirectory(StoragePath("versions")).success()
            writeProfiles(provider, """{"profiles":{}}""")
            val readOnly = MutationDetectingProvider(provider)

            val info = DefaultCSLauncherProvider().inspect(readOnly).success()

            val missing = info.warnings.filter {
                it.code == LauncherWarningCode.MISSING_DIRECTORY
            }
            assertEquals(8, missing.size)
            assertTrue(info.capabilities.compatible)
            assertEquals(setOf(LauncherDirectory.VERSIONS), info.detectedDirectories)
            assertEquals(0, readOnly.mutationCalls.get())
        }

    @Test
    fun currentCSLauncherRootDerivesInstancesWhenLegacyProfilesAreMissing() =
        withLauncherRoot { _, provider ->
            provider.createDirectory(StoragePath("assets")).success()
            provider.createDirectory(StoragePath("libraries")).success()
            provider.createDirectory(StoragePath("versions")).success()
            writeProfiles(provider, """{"profiles":{}}""")
            createVersion(
                provider,
                "fabric-loader-0.16.10-1.21.1",
                versionJson(
                    "fabric-loader-0.16.10-1.21.1",
                    inheritsFrom = "1.21.1",
                    library = "net.fabricmc:fabric-loader:0.16.10",
                ),
            )

            val launcherProvider = DefaultCSLauncherProvider()
            val readOnly = MutationDetectingProvider(provider)
            val info = launcherProvider.inspect(readOnly).success()
            val rescanned = launcherProvider.inspect(readOnly).success()

            assertTrue(info.capabilities.compatible)
            assertEquals(
                setOf(
                    LauncherDirectory.ASSETS,
                    LauncherDirectory.LIBRARIES,
                    LauncherDirectory.VERSIONS,
                ),
                info.detectedDirectories,
            )
            assertEquals(1, info.instances.size)
            assertEquals(
                "cs-launcher-v2-version:fabric-loader-0.16.10-1.21.1",
                info.instances.single().profileId,
            )
            assertEquals(
                "fabric-loader-0.16.10-1.21.1",
                info.instances.single().lastVersionId,
            )
            assertEquals(null, info.instances.single().gameDirectory)
            assertEquals(info.instances, rescanned.instances)
            assertEquals(0, readOnly.mutationCalls.get())
        }

    @Test
    fun discoversMinecraftDirectoryBelowSelectedCSLauncherTree() =
        withLauncherRoot { _, provider ->
            val minecraftRoot = StoragePath(".minecraft")
            provider.createDirectory(minecraftRoot).success()
            createCurrentRootWithoutProfiles(provider, minecraftRoot)
            val logger = RecordingLauncherLogger()

            val info = DefaultCSLauncherProvider(logger = logger)
                .inspect(MutationDetectingProvider(provider))
                .success()

            assertEquals(minecraftRoot, info.root)
            assertTrue(info.capabilities.compatible)
            assertEquals(1, info.instances.size)
            assertTrue(
                logger.events.any {
                    it.event == "launcher.discovery.root_candidate" &&
                        it.attributes["path"] == "<selected-tree>" &&
                        it.attributes["hasDotMinecraft"] == true
                },
            )
            assertTrue(
                logger.events.any {
                    it.event == "launcher.discovery.candidate_accepted" &&
                        it.attributes["path"] == ".minecraft"
                },
            )
        }

    @Test
    fun acceptsSelectedMinecraftDirectoryWithoutLauncherProfiles() =
        withLauncherRoot { _, provider ->
            val minecraftRoot = StoragePath(".minecraft")
            provider.createDirectory(minecraftRoot).success()
            createCurrentRootWithoutProfiles(provider, minecraftRoot)

            val info = DefaultCSLauncherProvider()
                .inspect(MutationDetectingProvider(provider), minecraftRoot)
                .success()

            assertEquals(minecraftRoot, info.root)
            assertTrue(info.capabilities.compatible)
            assertFalse(info.capabilities.canReadProfiles)
            assertEquals(
                "cs-launcher-v2-version:fabric-loader-0.16.10-1.21.1",
                info.instances.single().profileId,
            )
            assertFalse(info.hasError(LauncherErrorCode.MISSING_PROFILES_FILE))
        }

    @Test
    fun legacyProfilesRemainCompatibleWithoutCurrentSharedDirectories() =
        withLauncherRoot { _, provider ->
            provider.createDirectory(StoragePath("versions")).success()
            writeProfiles(
                provider,
                """
                {
                  "profiles": {
                    "fabric": {
                      "name": "Fabric",
                      "lastVersionId": "fabric-loader-0.16.10-1.21.1"
                    }
                  }
                }
                """,
            )
            createVersion(
                provider,
                "fabric-loader-0.16.10-1.21.1",
                versionJson(
                    "fabric-loader-0.16.10-1.21.1",
                    inheritsFrom = "1.21.1",
                    library = "net.fabricmc:fabric-loader:0.16.10",
                ),
            )

            val info = DefaultCSLauncherProvider()
                .inspect(MutationDetectingProvider(provider))
                .success()

            assertTrue(info.capabilities.compatible)
            assertEquals(listOf("fabric"), info.instances.map(LauncherInstance::profileId))
        }

    @Test
    fun invalidLauncherProfilesJsonReturnsStructuredError() =
        withLauncherRoot { _, provider ->
            provider.createDirectory(StoragePath("versions")).success()
            provider.replaceFile(StoragePath("launcher_profiles.json"), "application/json") {
                it.write("{invalid-json".toByteArray())
            }.success()

            val info = DefaultCSLauncherProvider()
                .inspect(MutationDetectingProvider(provider))
                .success()

            assertFalse(info.capabilities.compatible)
            assertTrue(info.hasError(LauncherErrorCode.INVALID_PROFILES_JSON))
        }

    @Test
    fun duplicateProfilesAreDetectedByNormalizedProfileName() =
        withLauncherRoot { _, provider ->
            provider.createDirectory(StoragePath("versions")).success()
            createVersion(provider, "1.21.1", versionJson("1.21.1"))
            writeProfiles(
                provider,
                """
                {
                  "profiles": {
                    "first": {"name":"Shared","lastVersionId":"1.21.1"},
                    "second": {"name":" shared ","lastVersionId":"1.21.1"}
                  }
                }
                """,
            )

            val info = DefaultCSLauncherProvider()
                .inspect(MutationDetectingProvider(provider))
                .success()

            assertFalse(info.capabilities.compatible)
            assertTrue(info.hasError(LauncherErrorCode.DUPLICATE_PROFILE))
            assertEquals(2, info.instances.size)
        }

    @Test
    fun unsupportedStructureIsDetectedWhenKnownDirectoryIsAFile() =
        withLauncherRoot { _, provider ->
            provider.replaceFile(StoragePath("versions")) {
                it.write("not-a-directory".toByteArray())
            }.success()
            writeProfiles(provider, """{"profiles":{}}""")

            val info = DefaultCSLauncherProvider()
                .inspect(MutationDetectingProvider(provider))
                .success()

            assertFalse(info.capabilities.compatible)
            assertTrue(info.hasError(LauncherErrorCode.UNSUPPORTED_STRUCTURE))
            assertTrue(info.hasError(LauncherErrorCode.MISSING_VERSIONS_DIRECTORY))
        }

    @Test
    fun corruptedProfileIsExcludedAndReported() =
        withLauncherRoot { _, provider ->
            provider.createDirectory(StoragePath("versions")).success()
            createVersion(provider, "1.21.1", versionJson("1.21.1"))
            writeProfiles(
                provider,
                """
                {
                  "profiles": {
                    "valid": {"name":"Valid","lastVersionId":"1.21.1"},
                    "broken": {"name":42,"lastVersionId":false}
                  }
                }
                """,
            )

            val info = DefaultCSLauncherProvider()
                .inspect(MutationDetectingProvider(provider))
                .success()

            assertFalse(info.capabilities.compatible)
            assertTrue(info.hasError(LauncherErrorCode.CORRUPTED_PROFILE))
            assertEquals(listOf("valid"), info.instances.map { it.profileId })
        }

    @Test
    fun multipleInstalledVersionsAreDetectedAndClassified() =
        withLauncherRoot { _, provider ->
            provider.createDirectory(StoragePath("versions")).success()
            writeProfiles(provider, """{"profiles":{}}""")
            createVersion(provider, "1.20.4", versionJson("1.20.4"))
            createVersion(
                provider,
                "fabric-loader-0.15.11-1.20.4",
                versionJson(
                    "fabric-loader-0.15.11-1.20.4",
                    inheritsFrom = "1.20.4",
                    library = "net.fabricmc:fabric-loader:0.15.11",
                ),
            )
            createVersion(
                provider,
                "1.20.1-forge-47.2.0",
                versionJson(
                    "1.20.1-forge-47.2.0",
                    inheritsFrom = "1.20.1",
                    library = "net.minecraftforge:forge:1.20.1-47.2.0",
                ),
            )

            val info = DefaultCSLauncherProvider()
                .inspect(MutationDetectingProvider(provider))
                .success()

            assertEquals(3, info.versions.size)
            assertEquals(
                setOf(LauncherLoader.VANILLA, LauncherLoader.FABRIC, LauncherLoader.FORGE),
                info.versions.map(LauncherVersion::loader).toSet(),
            )
        }

    private suspend fun createStandardDirectories(provider: StorageProvider) {
        LauncherDirectory.entries.forEach { directory ->
            provider.createDirectory(StoragePath(directory.relativePath)).success()
        }
    }

    private suspend fun writeProfiles(
        provider: StorageProvider,
        content: String,
    ) {
        provider.replaceFile(StoragePath("launcher_profiles.json"), "application/json") {
            it.write(content.trimIndent().toByteArray())
        }.success()
    }

    private suspend fun createVersion(
        provider: StorageProvider,
        id: String,
        metadata: String,
        root: StoragePath = StoragePath.ROOT,
    ) {
        val versions = root.child("versions")
        provider.createDirectory(versions.child(id)).success()
        provider.replaceFile(versions.child(id).child("$id.json"), "application/json") {
            it.write(metadata.toByteArray())
        }.success()
    }

    private suspend fun createCurrentRootWithoutProfiles(
        provider: StorageProvider,
        root: StoragePath,
    ) {
        provider.createDirectory(root.child("assets")).success()
        provider.createDirectory(root.child("libraries")).success()
        provider.createDirectory(root.child("versions")).success()
        createVersion(
            provider = provider,
            id = "fabric-loader-0.16.10-1.21.1",
            metadata = versionJson(
                "fabric-loader-0.16.10-1.21.1",
                inheritsFrom = "1.21.1",
                library = "net.fabricmc:fabric-loader:0.16.10",
            ),
            root = root,
        )
    }

    private fun StoragePath.child(name: String): StoragePath =
        if (isRoot) StoragePath(name) else StoragePath("$value/$name")

    private fun versionJson(
        id: String,
        inheritsFrom: String? = null,
        library: String? = null,
    ): String = buildString {
        append("""{"id":"$id"""")
        if (inheritsFrom != null) append(""","inheritsFrom":"$inheritsFrom"""")
        if (library != null) append(""","libraries":[{"name":"$library"}]""")
        append('}')
    }

    private fun LauncherInfo.hasError(code: LauncherErrorCode): Boolean =
        errors.any { it.code == code }

    private fun LauncherResult.success(): LauncherInfo =
        (this as? LauncherResult.Success)?.info ?: error("Expected success, got $this")

    private fun <T> StorageResult<T>.success(): T =
        (this as? StorageResult.Success<T>)?.value ?: error("Expected success, got $this")

    private fun <T> withLauncherRoot(
        block: suspend (Path, CacheProvider) -> T,
    ): T = runBlocking {
        val root = Files.createTempDirectory("launcher-test")
        try {
            block(root, CacheProvider(root))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

private class RecordingLauncherLogger : LauncherLogger {
    val events = mutableListOf<Event>()

    override fun debug(event: String, attributes: Map<String, Any?>) {
        events += Event(event, attributes)
    }

    override fun info(event: String, attributes: Map<String, Any?>) {
        events += Event(event, attributes)
    }

    override fun warn(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) {
        events += Event(event, attributes)
    }

    override fun error(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) {
        events += Event(event, attributes)
    }

    data class Event(
        val event: String,
        val attributes: Map<String, Any?>,
    )
}

private class MutationDetectingProvider(
    private val delegate: StorageProvider,
) : StorageProvider by delegate {
    val mutationCalls = AtomicInteger()

    override suspend fun createDirectory(path: StoragePath): StorageResult<StorageEntry> =
        mutationFailure(path)

    override suspend fun openOutput(
        path: StoragePath,
        mimeType: String,
        append: Boolean,
        bufferSize: Int,
    ): StorageResult<OutputStream> = mutationFailure(path)

    override suspend fun replaceFile(
        path: StoragePath,
        mimeType: String,
        writer: suspend (OutputStream) -> Unit,
    ): StorageResult<FileReplaceResult> = mutationFailure(path)

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
        progressListener: StorageProgressListener,
    ): StorageResult<DeleteSummary> = mutationFailure(path)

    private fun <T> mutationFailure(path: StoragePath): StorageResult<T> {
        mutationCalls.incrementAndGet()
        return StorageResult.Failure(
            StorageError(
                StorageErrorCode.WRITE_PERMISSION_DENIED,
                "Mutation attempted during read-only launcher scan.",
                path,
                recoverable = false,
            ),
        )
    }
}
