package com.modrith.resolver

import com.modrith.models.DownloadEntry
import com.modrith.models.EnvironmentSupport
import com.modrith.models.InstallPlan
import com.modrith.models.InstallWarning
import com.modrith.models.InstallWarningCode
import com.modrith.models.LoaderType
import com.modrith.models.MrPackDependencies
import com.modrith.models.MrPackFile
import com.modrith.models.MrPackHashes
import com.modrith.models.MrPackManifest
import com.modrith.models.OverrideFile
import com.modrith.models.OverrideFolder
import com.modrith.models.ResolutionErrorCode
import com.modrith.models.ResolutionWarningCode
import com.modrith.models.ResolvedInstallStepType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPlanResolverTest {
    private val resolver = InstallPlanResolver()

    @Test
    fun validPlanProducesNormalizedResolvedPlanAndInstallOrder() {
        val plan = validPlan(
            downloads = listOf(
                download("shaderpacks/z.zip", size = 30),
                download("resourcepacks/a.zip", size = 20),
                download("mods/b.jar", size = 10),
            ),
            overrides = listOf(
                overrideFolder(
                    "client-overrides",
                    OverrideFile(
                        archivePath = "client-overrides/options.txt",
                        relativePath = "options.txt",
                        sizeBytes = 4,
                    ),
                ),
                overrideFolder(
                    "overrides",
                    OverrideFile(
                        archivePath = "overrides/config/example.json",
                        relativePath = "config/example.json",
                        sizeBytes = 5,
                    ),
                ),
            ),
        )

        val result = resolver.resolve(plan)

        assertTrue(result.isReady)
        assertEquals("1.21.1", result.minecraftVersion)
        assertEquals("fabric-loader", result.loader?.canonicalId)
        assertEquals(60L, result.totalDownloadSize)
        assertEquals(
            listOf(
                "mods/b.jar",
                "resourcepacks/a.zip",
                "shaderpacks/z.zip",
                "config/example.json",
                "options.txt",
            ),
            result.installOrder.map { it.destinationPath },
        )
        assertEquals(
            listOf(
                ResolvedInstallStepType.DOWNLOAD,
                ResolvedInstallStepType.DOWNLOAD,
                ResolvedInstallStepType.DOWNLOAD,
                ResolvedInstallStepType.APPLY_OVERRIDE,
                ResolvedInstallStepType.APPLY_OVERRIDE,
            ),
            result.installOrder.map { it.type },
        )
        assertEquals(result.installOrder.indices.toList(), result.installOrder.map { it.order })
    }

    @Test
    fun normalizesFabricForgeAndNeoForgeAliases() {
        val cases = listOf(
            Triple(LoaderType.FABRIC, "fabric", "fabric-loader"),
            Triple(LoaderType.FORGE, "forge-loader", "forge"),
            Triple(LoaderType.NEOFORGE, "neo-forge", "neoforge"),
        )

        cases.forEach { (type, alias, canonical) ->
            val plan = validPlan(
                loaderType = type,
                loaderVersion = "1.0.0",
                dependencies = MrPackDependencies(
                    minecraft = "1.21.1",
                    additional = mapOf(alias to "1.0.0"),
                ),
            )

            val result = resolver.resolve(plan)

            assertEquals(type, result.loader?.type)
            assertEquals(canonical, result.loader?.canonicalId)
            assertTrue(
                result.warnings.any {
                    it.code == ResolutionWarningCode.LOADER_IDENTIFIER_NORMALIZED
                },
            )
            assertFalse(result.fatalErrors.any { it.code == ResolutionErrorCode.MISSING_LOADER })
        }
    }

    @Test
    fun loaderOutsideConfiguredSupportSetIsFatal() {
        val resolver = InstallPlanResolver(supportedLoaders = setOf(LoaderType.FABRIC))
        val result = resolver.resolve(
            validPlan(
                loaderType = LoaderType.FORGE,
                loaderVersion = "52.0.1",
                dependencies = MrPackDependencies(
                    minecraft = "1.21.1",
                    forge = "52.0.1",
                ),
            ),
        )

        assertTrue(result.hasFatal(ResolutionErrorCode.UNSUPPORTED_LOADER))
    }

    @Test
    fun duplicateDownloadsAndDestinationPathsAreDetected() {
        val duplicate = download("mods/duplicate.jar", size = 10)
        val result = resolver.resolve(
            validPlan(downloads = listOf(duplicate, duplicate.copy())),
        )

        assertTrue(
            result.recoverableErrors.any {
                it.code == ResolutionErrorCode.DUPLICATE_DOWNLOAD_ENTRY && it.recoverable
            },
        )
        assertTrue(
            result.fatalErrors.any {
                it.code == ResolutionErrorCode.DUPLICATE_DESTINATION_PATH && !it.recoverable
            },
        )
        assertEquals(10L, result.totalDownloadSize)
        assertFalse(result.isReady)
    }

    @Test
    fun conflictingOverridesAndDownloadOverrideCollisionsAreFatal() {
        val result = resolver.resolve(
            validPlan(
                downloads = listOf(download("mods/a.jar")),
                overrides = listOf(
                    overrideFolder(
                        "overrides",
                        OverrideFile("overrides/mods/a.jar", "mods/a.jar", 1),
                        OverrideFile("overrides/config/shared.json", "config/shared.json", 1),
                    ),
                    overrideFolder(
                        "client-overrides",
                        OverrideFile(
                            "client-overrides/config/shared.json",
                            "config/shared.json",
                            1,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.hasFatal(ResolutionErrorCode.OVERRIDE_CONFLICT))
        assertTrue(result.hasFatal(ResolutionErrorCode.DOWNLOAD_OVERRIDE_CONFLICT))
    }

    @Test
    fun missingHashesInvalidUrlsAndUnsupportedTypesAreFatal() {
        val result = resolver.resolve(
            validPlan(
                downloads = listOf(
                    download(
                        path = "mods/no-hash.jar",
                        hashes = MrPackHashes(),
                    ),
                    download(
                        path = "mods/no-url.jar",
                        urls = listOf("http://example.test/no-url.jar"),
                    ),
                    download(path = "mods/native.exe"),
                ),
                overrides = listOf(
                    overrideFolder(
                        "overrides",
                        OverrideFile("overrides/tools/setup.exe", "tools/setup.exe", 10),
                    ),
                ),
            ),
        )

        assertTrue(result.hasFatal(ResolutionErrorCode.MISSING_HASH))
        assertTrue(result.hasFatal(ResolutionErrorCode.INVALID_DOWNLOAD_URL))
        assertTrue(result.hasFatal(ResolutionErrorCode.UNSUPPORTED_FILE_TYPE))
    }

    @Test
    fun unknownLoaderIdentifierIsFatal() {
        val result = resolver.resolve(
            validPlan(
                dependencies = MrPackDependencies(
                    minecraft = "1.21.1",
                    additional = mapOf("future-loader" to "1.0.0"),
                ),
            ),
        )

        assertTrue(result.hasFatal(ResolutionErrorCode.UNSUPPORTED_LOADER))
    }

    @Test
    fun invalidMirrorIsDroppedWhenAnotherMirrorIsValid() {
        val result = resolver.resolve(
            validPlan(
                downloads = listOf(
                    download(
                        path = "mods/a.jar",
                        urls = listOf(
                            "http://example.test/a.jar",
                            "https://cdn.example.test/a.jar",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("https://cdn.example.test/a.jar"), result.downloadEntries.single().urls)
        assertTrue(
            result.warnings.any {
                it.code == ResolutionWarningCode.INVALID_MIRROR_IGNORED
            },
        )
        assertFalse(result.hasFatal(ResolutionErrorCode.INVALID_DOWNLOAD_URL))
    }

    @Test
    fun missingSizeIsRecoverableAndMakesTotalUnavailable() {
        val result = resolver.resolve(
            validPlan(downloads = listOf(download("mods/a.jar", size = null))),
        )

        assertNull(result.totalDownloadSize)
        assertTrue(
            result.recoverableErrors.any {
                it.code == ResolutionErrorCode.MISSING_FILE_SIZE && it.recoverable
            },
        )
        assertFalse(result.isReady)
    }

    @Test
    fun missingMetadataVersionMismatchAndAmbiguousLoadersAreFatal() {
        val plan = validPlan(
            name = "",
            versionId = "",
            minecraftVersion = "1.21.2",
            loaderType = LoaderType.FORGE,
            loaderVersion = "",
            cacheDirectory = "",
            dependencies = MrPackDependencies(
                minecraft = "1.21.1",
                fabricLoader = "0.16.10",
                forge = "52.0.1",
            ),
        )

        val result = resolver.resolve(plan)

        assertTrue(result.hasFatal(ResolutionErrorCode.MISSING_PACK_NAME))
        assertTrue(result.hasFatal(ResolutionErrorCode.MISSING_VERSION_ID))
        assertTrue(result.hasFatal(ResolutionErrorCode.MINECRAFT_VERSION_MISMATCH))
        assertTrue(result.hasFatal(ResolutionErrorCode.AMBIGUOUS_LOADER))
        assertTrue(result.hasFatal(ResolutionErrorCode.MISSING_CACHE_DIRECTORY))
    }

    @Test
    fun sourceWarningsAndUnsupportedClientFilesBecomeResolverWarnings() {
        val unsupported = download("mods/server-only.jar").copy(
            clientRequirement = EnvironmentSupport.UNSUPPORTED,
        )
        val plan = validPlan(downloads = listOf(unsupported)).copy(
            warnings = listOf(
                InstallWarning(
                    InstallWarningCode.UNKNOWN_DEPENDENCY,
                    "Unknown dependency retained.",
                ),
            ),
        )

        val result = resolver.resolve(plan)

        assertTrue(
            result.warnings.any {
                it.code == ResolutionWarningCode.UNSUPPORTED_CLIENT_FILE_EXCLUDED
            },
        )
        assertTrue(
            result.warnings.any {
                it.code == ResolutionWarningCode.SOURCE_PLAN_WARNING
            },
        )
        assertTrue(result.downloadEntries.isEmpty())
    }

    private fun validPlan(
        name: String = "Test Pack",
        versionId: String = "1.0.0",
        minecraftVersion: String = "1.21.1",
        loaderType: LoaderType = LoaderType.FABRIC,
        loaderVersion: String = "0.16.10",
        dependencies: MrPackDependencies = MrPackDependencies(
            minecraft = minecraftVersion,
            fabricLoader = loaderVersion,
        ),
        downloads: List<DownloadEntry> = listOf(download("mods/example.jar")),
        overrides: List<OverrideFolder> = emptyList(),
        cacheDirectory: String = "/cache/mrpack-test",
    ): InstallPlan {
        val manifestFiles = downloads.map { entry ->
            MrPackFile(
                path = entry.path,
                hashes = entry.hashes,
                downloads = entry.urls,
                fileSize = entry.sizeBytes,
            )
        }
        return InstallPlan(
            manifest = MrPackManifest(
                formatVersion = 1,
                game = "minecraft",
                versionId = versionId,
                name = name,
                summary = null,
                files = manifestFiles,
                dependencies = dependencies,
            ),
            minecraftVersion = minecraftVersion,
            loaderType = loaderType,
            loaderVersion = loaderVersion,
            modCount = downloads.count { it.path.lowercase(Locale.ROOT).startsWith("mods/") },
            overrideFolders = overrides,
            downloadEntries = downloads,
            totalDownloadSize = downloads.mapNotNull { it.sizeBytes }.sum(),
            warnings = emptyList(),
            cacheDirectory = cacheDirectory,
        )
    }

    private fun download(
        path: String,
        size: Long? = 10,
        urls: List<String> = listOf("https://cdn.example.test/file"),
        hashes: MrPackHashes = hashesFor(path),
    ): DownloadEntry = DownloadEntry(
        path = path,
        urls = urls,
        hashes = hashes,
        sizeBytes = size,
        clientRequirement = EnvironmentSupport.REQUIRED,
    )

    private fun overrideFolder(
        name: String,
        vararg files: OverrideFile,
    ): OverrideFolder = OverrideFolder(
        name = name,
        extractedPath = "/cache/$name",
        files = files.toList(),
    )

    private fun com.modrith.models.ResolvedInstallPlan.hasFatal(
        code: ResolutionErrorCode,
    ): Boolean = fatalErrors.any { it.code == code && !it.recoverable }

    private companion object {
        fun hashesFor(path: String): MrPackHashes {
            val value = path.hashCode().toUInt().toString(16).padStart(8, '0')
            return MrPackHashes(
                sha512 = value.repeat(16),
                sha1 = value.repeat(5),
            )
        }
    }
}
