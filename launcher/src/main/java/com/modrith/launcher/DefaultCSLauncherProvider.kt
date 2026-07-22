package com.modrith.launcher

import com.modrith.filesystem.StorageEntryType
import com.modrith.filesystem.StorageError
import com.modrith.filesystem.StorageErrorCode
import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageProvider
import com.modrith.filesystem.StorageResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class DefaultCSLauncherProvider(
    private val scanner: LauncherScanner = DefaultLauncherScanner(),
    private val validator: LauncherValidator = DefaultLauncherValidator(),
    private val logger: LauncherLogger = JvmLauncherLogger(),
) : LauncherProvider {
    override suspend fun inspect(
        storage: StorageProvider,
        root: StoragePath,
    ): LauncherResult {
        logger.info(
            "launcher.scan.started",
            mapOf("providerId" to storage.providerId, "root" to root.value),
        )
        return when (val scan = scanner.scan(storage, root)) {
            is LauncherScanResult.Failure -> {
                logger.warn(
                    "launcher.scan.failed",
                    mapOf("errors" to scan.errors.map(LauncherError::code)),
                )
                LauncherResult.Failure(scan.errors, scan.warnings)
            }
            is LauncherScanResult.Success -> validator.validate(scan.snapshot).also { result ->
                val info = (result as? LauncherResult.Success)?.info
                logger.info(
                    "launcher.scan.completed",
                    mapOf(
                        "compatible" to info?.capabilities?.compatible,
                        "profiles" to info?.instances?.size,
                        "versions" to info?.versions?.size,
                        "errors" to info?.errors?.size,
                        "warnings" to info?.warnings?.size,
                    ),
                )
            }
        }
    }
}

class DefaultLauncherScanner(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    },
    private val logger: LauncherLogger = JvmLauncherLogger(),
    private val maxProfilesBytes: Long = 4L * 1024 * 1024,
    private val maxVersionMetadataBytes: Long = 1024L * 1024,
) : LauncherScanner {
    override suspend fun scan(
        storage: StorageProvider,
        root: StoragePath,
    ): LauncherScanResult {
        val rootMetadata = storage.metadata(root)
        if (rootMetadata is StorageResult.Failure) {
            return LauncherScanResult.Failure(listOf(rootMetadata.error.toLauncherError(root)))
        }
        if ((rootMetadata as StorageResult.Success).value.type != StorageEntryType.DIRECTORY) {
            return LauncherScanResult.Failure(
                listOf(
                    LauncherError(
                        LauncherErrorCode.INVALID_ROOT,
                        "The selected launcher root is not a directory.",
                        root,
                        recoverable = true,
                    ),
                ),
            )
        }

        val warnings = mutableListOf<LauncherWarning>()
        val errors = mutableListOf<LauncherError>()
        val detected = mutableSetOf<LauncherDirectory>()
        for (directory in LauncherDirectory.entries) {
            currentCoroutineContext().ensureActive()
            val path = root.child(directory.relativePath)
            when (val metadata = storage.metadata(path)) {
                is StorageResult.Success -> {
                    if (metadata.value.type == StorageEntryType.DIRECTORY) {
                        detected += directory
                    } else {
                        errors += LauncherError(
                            LauncherErrorCode.UNSUPPORTED_STRUCTURE,
                            "Expected launcher directory is not a directory.",
                            path,
                            recoverable = true,
                        )
                    }
                }
                is StorageResult.Failure -> {
                    if (metadata.error.code == StorageErrorCode.NOT_FOUND) {
                        warnings += LauncherWarning(
                            LauncherWarningCode.MISSING_DIRECTORY,
                            "Launcher directory is missing.",
                            path,
                        )
                    } else {
                        errors += metadata.error.toLauncherError(path)
                    }
                }
            }
        }

        val profilesPath = root.child(PROFILES_FILE_NAME)
        val profilesResult = scanProfiles(storage, profilesPath)
        val profiles = when (profilesResult) {
            is ProfileScanResult.Success -> profilesResult.profiles
            is ProfileScanResult.Failure -> {
                errors += profilesResult.error
                emptyList()
            }
            is ProfileScanResult.Missing -> emptyList()
        }
        val versions = if (LauncherDirectory.VERSIONS in detected) {
            scanVersions(storage, root.child(LauncherDirectory.VERSIONS.relativePath), warnings, errors)
        } else {
            emptyList()
        }
        if (LauncherDirectory.VERSIONS in detected && versions.isEmpty()) {
            warnings += LauncherWarning(
                LauncherWarningCode.EMPTY_VERSIONS_DIRECTORY,
                "No installed launcher versions were detected.",
                root.child(LauncherDirectory.VERSIONS.relativePath),
            )
        }

        logger.debug(
            "launcher.structure.scanned",
            mapOf(
                "directories" to detected.map(LauncherDirectory::name),
                "profiles" to profiles.size,
                "versions" to versions.size,
            ),
        )
        return LauncherScanResult.Success(
            LauncherScanSnapshot(
                root = root,
                detectedDirectories = detected.toSet(),
                profilesFilePresent = profilesResult !is ProfileScanResult.Missing,
                profiles = profiles,
                versions = versions,
                warnings = warnings.toList(),
                errors = errors.toList(),
            ),
        )
    }

    private suspend fun scanProfiles(
        storage: StorageProvider,
        path: StoragePath,
    ): ProfileScanResult {
        val metadata = storage.metadata(path)
        if (metadata is StorageResult.Failure) {
            return if (metadata.error.code == StorageErrorCode.NOT_FOUND) {
                ProfileScanResult.Missing(
                    LauncherError(
                        LauncherErrorCode.MISSING_PROFILES_FILE,
                        "launcher_profiles.json is missing.",
                        path,
                        recoverable = true,
                    ),
                )
            } else {
                ProfileScanResult.Failure(metadata.error.toLauncherError(path))
            }
        }
        if ((metadata as StorageResult.Success).value.type != StorageEntryType.FILE) {
            return ProfileScanResult.Failure(
                LauncherError(
                    LauncherErrorCode.UNSUPPORTED_STRUCTURE,
                    "launcher_profiles.json is not a file.",
                    path,
                    recoverable = true,
                ),
            )
        }
        val text = when (val read = readText(storage, path, maxProfilesBytes)) {
            is TextReadResult.Success -> read.text
            is TextReadResult.Failure -> return ProfileScanResult.Failure(read.error)
        }
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: return invalidProfilesSchema(path, "Launcher profiles root must be an object.")
        } catch (error: IllegalArgumentException) {
            return ProfileScanResult.Failure(
                LauncherError(
                    LauncherErrorCode.INVALID_PROFILES_JSON,
                    "launcher_profiles.json is not valid JSON.",
                    path,
                    recoverable = true,
                ),
            )
        }
        val profiles = root["profiles"] as? JsonObject
            ?: return invalidProfilesSchema(path, "Launcher profiles must contain a profiles object.")
        return ProfileScanResult.Success(
            profiles.map { (profileId, element) ->
                parseProfile(profileId, element)
            },
        )
    }

    private fun parseProfile(
        profileId: String,
        element: JsonElement,
    ): LauncherProfileCandidate {
        val profile = element as? JsonObject
            ?: return LauncherProfileCandidate(
                profileId,
                null,
                null,
                null,
                null,
                corrupted = true,
            )
        val name = profile.string("name")
        val lastVersionId = profile.string("lastVersionId")
        val gameDirectory = profile.string("gameDir")
        val profileType = profile.string("type")
        val corrupted = profileId.isBlank() ||
            name.isNullOrBlank() ||
            lastVersionId.isNullOrBlank() ||
            profile.hasNonStringValue("name") ||
            profile.hasNonStringValue("lastVersionId") ||
            profile.hasNonStringValue("gameDir") ||
            profile.hasNonStringValue("type")
        return LauncherProfileCandidate(
            profileId = profileId,
            name = name,
            lastVersionId = lastVersionId,
            gameDirectory = gameDirectory,
            profileType = profileType,
            corrupted = corrupted,
        )
    }

    private suspend fun scanVersions(
        storage: StorageProvider,
        versionsRoot: StoragePath,
        warnings: MutableList<LauncherWarning>,
        errors: MutableList<LauncherError>,
    ): List<LauncherVersion> {
        val listed = storage.list(versionsRoot)
        if (listed is StorageResult.Failure) {
            errors += listed.error.toLauncherError(versionsRoot)
            return emptyList()
        }
        return (listed as StorageResult.Success).value
            .filter { it.type == StorageEntryType.DIRECTORY }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { entry ->
                currentCoroutineContext().ensureActive()
                scanVersion(storage, entry.path, entry.name, warnings)
            }
    }

    private suspend fun scanVersion(
        storage: StorageProvider,
        versionPath: StoragePath,
        directoryName: String,
        warnings: MutableList<LauncherWarning>,
    ): LauncherVersion {
        val metadataPath = versionPath.child("$directoryName.json")
        val metadata = when (val exists = storage.exists(metadataPath)) {
            is StorageResult.Success -> {
                if (exists.value) readVersionMetadata(storage, metadataPath, warnings) else null
            }
            is StorageResult.Failure -> {
                warnings += LauncherWarning(
                    LauncherWarningCode.CORRUPTED_VERSION_METADATA,
                    "Version metadata could not be inspected.",
                    metadataPath,
                )
                null
            }
        }
        val id = metadata?.id?.takeIf(String::isNotBlank) ?: directoryName
        val detectionText = buildString {
            append(id)
            append(' ')
            append(metadata?.inheritsFrom.orEmpty())
            append(' ')
            append(metadata?.libraries.orEmpty().joinToString(" "))
        }.lowercase(Locale.ROOT)
        val loader = when {
            "net.fabricmc:fabric-loader" in detectionText ||
                "fabric-loader" in detectionText ||
                id.lowercase(Locale.ROOT).startsWith("fabric-") -> LauncherLoader.FABRIC
            "net.neoforged" in detectionText ||
                "neoforge" in detectionText ||
                "neo-forge" in detectionText -> LauncherLoader.NEOFORGE
            "net.minecraftforge" in detectionText ||
                "-forge-" in detectionText ||
                id.lowercase(Locale.ROOT).startsWith("forge-") -> LauncherLoader.FORGE
            id.isNotBlank() -> LauncherLoader.VANILLA
            else -> LauncherLoader.UNKNOWN
        }
        val minecraftVersion = metadata?.inheritsFrom?.takeIf(String::isNotBlank)
            ?: inferMinecraftVersion(id, loader)
        val loaderVersion = inferLoaderVersion(metadata, id, loader)
        if (loader == LauncherLoader.UNKNOWN) {
            warnings += LauncherWarning(
                LauncherWarningCode.UNKNOWN_VERSION_TYPE,
                "Launcher version type could not be determined.",
                versionPath,
            )
        }
        return LauncherVersion(id, minecraftVersion, loader, loaderVersion, versionPath)
    }

    private suspend fun readVersionMetadata(
        storage: StorageProvider,
        path: StoragePath,
        warnings: MutableList<LauncherWarning>,
    ): VersionMetadata? {
        val text = when (val read = readText(storage, path, maxVersionMetadataBytes)) {
            is TextReadResult.Success -> read.text
            is TextReadResult.Failure -> {
                warnings += LauncherWarning(
                    LauncherWarningCode.CORRUPTED_VERSION_METADATA,
                    "Version metadata could not be read safely.",
                    path,
                )
                return null
            }
        }
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            VersionMetadata(
                id = root.string("id"),
                inheritsFrom = root.string("inheritsFrom"),
                libraries = (root["libraries"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("name") },
            )
        } catch (error: IllegalArgumentException) {
            warnings += LauncherWarning(
                LauncherWarningCode.CORRUPTED_VERSION_METADATA,
                "Version metadata is invalid JSON.",
                path,
            )
            null
        }
    }

    private suspend fun readText(
        storage: StorageProvider,
        path: StoragePath,
        maxBytes: Long,
    ): TextReadResult {
        val input = storage.openInput(path)
        if (input is StorageResult.Failure) {
            return TextReadResult.Failure(input.error.toLauncherError(path))
        }
        return try {
            val text = (input as StorageResult.Success<InputStream>).value.use {
                readBounded(it, maxBytes)
            }
            TextReadResult.Success(text)
        } catch (error: ReadLimitException) {
            TextReadResult.Failure(
                LauncherError(
                    if (path.name == PROFILES_FILE_NAME) {
                        LauncherErrorCode.PROFILES_TOO_LARGE
                    } else {
                        LauncherErrorCode.STORAGE_IO
                    },
                    "Launcher metadata exceeds the safe read limit.",
                    path,
                    recoverable = true,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            TextReadResult.Failure(
                LauncherError(
                    LauncherErrorCode.STORAGE_IO,
                    "Launcher metadata could not be read.",
                    path,
                    recoverable = true,
                ),
            )
        }
    }

    private suspend fun readBounded(input: InputStream, maxBytes: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw ReadLimitException()
            output.write(buffer, 0, read)
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun invalidProfilesSchema(
        path: StoragePath,
        message: String,
    ) = ProfileScanResult.Failure(
        LauncherError(
            LauncherErrorCode.INVALID_PROFILES_SCHEMA,
            message,
            path,
            recoverable = true,
        ),
    )

    private fun inferMinecraftVersion(
        id: String,
        loader: LauncherLoader,
    ): String? {
        val lower = id.lowercase(Locale.ROOT)
        return when (loader) {
            LauncherLoader.FABRIC -> FabricIdPattern.matchEntire(lower)?.groupValues?.get(2)
            LauncherLoader.FORGE -> ForgeIdPattern.matchEntire(lower)?.groupValues?.get(1)
            LauncherLoader.NEOFORGE -> NeoForgeIdPattern.matchEntire(lower)?.groupValues?.get(1)
            LauncherLoader.VANILLA -> id.takeIf(VersionPattern::matches)
            LauncherLoader.UNKNOWN -> null
        }
    }

    private fun inferLoaderVersion(
        metadata: VersionMetadata?,
        id: String,
        loader: LauncherLoader,
    ): String? {
        val coordinatePrefix = when (loader) {
            LauncherLoader.FABRIC -> "net.fabricmc:fabric-loader:"
            LauncherLoader.FORGE -> "net.minecraftforge:forge:"
            LauncherLoader.NEOFORGE -> "net.neoforged:neoforge:"
            else -> null
        }
        coordinatePrefix?.let { prefix ->
            metadata?.libraries
                ?.firstOrNull { it.lowercase(Locale.ROOT).startsWith(prefix) }
                ?.substringAfterLast(':')
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
        }
        val lower = id.lowercase(Locale.ROOT)
        return when (loader) {
            LauncherLoader.FABRIC ->
                FabricIdPattern.matchEntire(lower)?.groupValues?.get(1)
            LauncherLoader.FORGE ->
                ForgeIdPattern.matchEntire(lower)?.groupValues?.get(2)
            LauncherLoader.NEOFORGE ->
                NeoForgeIdPattern.matchEntire(lower)?.groupValues?.get(2)
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull.takeIf { primitive.isString }
    }

    private fun JsonObject.hasNonStringValue(key: String): Boolean =
        this[key]?.let { it !is JsonPrimitive || !it.isString } == true

    private fun StorageError.toLauncherError(pathOverride: StoragePath): LauncherError =
        LauncherError(
            code = when (code) {
                StorageErrorCode.READ_PERMISSION_DENIED,
                StorageErrorCode.PERSIST_PERMISSION_FAILED,
                -> LauncherErrorCode.READ_PERMISSION_DENIED
                StorageErrorCode.INVALID_PATH,
                StorageErrorCode.NOT_A_DIRECTORY,
                StorageErrorCode.NOT_A_FILE,
                -> LauncherErrorCode.UNSUPPORTED_STRUCTURE
                else -> LauncherErrorCode.STORAGE_IO
            },
            message = message,
            path = path ?: pathOverride,
            recoverable = recoverable,
        )

    private fun StoragePath.child(name: String): StoragePath =
        if (isRoot) StoragePath(name) else StoragePath("$value/$name")

    private sealed interface ProfileScanResult {
        data class Success(val profiles: List<LauncherProfileCandidate>) : ProfileScanResult
        data class Failure(val error: LauncherError) : ProfileScanResult
        data class Missing(val error: LauncherError) : ProfileScanResult
    }

    private sealed interface TextReadResult {
        data class Success(val text: String) : TextReadResult
        data class Failure(val error: LauncherError) : TextReadResult
    }

    private data class VersionMetadata(
        val id: String?,
        val inheritsFrom: String?,
        val libraries: List<String>,
    )

    private class ReadLimitException : IOException()

    private companion object {
        const val PROFILES_FILE_NAME = "launcher_profiles.json"
        val VersionPattern = Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+._][0-9A-Za-z]+)*$")
        val FabricIdPattern = Regex("^fabric-loader-([0-9a-z.+_-]+)-([0-9a-z.+_-]+)$")
        val ForgeIdPattern = Regex("^([0-9a-z.+_-]+)-forge-([0-9a-z.+_-]+)$")
        val NeoForgeIdPattern = Regex("^([0-9a-z.+_-]+)-(?:neo-?forge)-([0-9a-z.+_-]+)$")
    }
}

class DefaultLauncherValidator : LauncherValidator {
    override fun validate(snapshot: LauncherScanSnapshot): LauncherResult {
        val warnings = snapshot.warnings.toMutableList()
        val errors = snapshot.errors.toMutableList()

        if (!snapshot.profilesFilePresent) {
            errors += LauncherError(
                LauncherErrorCode.MISSING_PROFILES_FILE,
                "launcher_profiles.json is required for launcher compatibility.",
                snapshot.root.child("launcher_profiles.json"),
                recoverable = true,
            )
        }
        if (LauncherDirectory.VERSIONS !in snapshot.detectedDirectories) {
            errors += LauncherError(
                LauncherErrorCode.MISSING_VERSIONS_DIRECTORY,
                "The versions directory is required for launcher compatibility.",
                snapshot.root.child(LauncherDirectory.VERSIONS.relativePath),
                recoverable = true,
            )
        }

        val instances = mutableListOf<LauncherInstance>()
        val profileNames = mutableMapOf<String, String>()
        snapshot.profiles.forEach { profile ->
            if (profile.corrupted) {
                errors += LauncherError(
                    LauncherErrorCode.CORRUPTED_PROFILE,
                    "Launcher profile is missing required string metadata.",
                    snapshot.root.child("launcher_profiles.json"),
                    recoverable = true,
                )
                return@forEach
            }
            val name = requireNotNull(profile.name)
            val normalizedName = name.trim().lowercase(Locale.ROOT)
            val previous = profileNames.putIfAbsent(normalizedName, profile.profileId)
            if (previous != null) {
                errors += LauncherError(
                    LauncherErrorCode.DUPLICATE_PROFILE,
                    "Multiple launcher profiles use the same name.",
                    snapshot.root.child("launcher_profiles.json"),
                    recoverable = true,
                )
            }
            val versionId = requireNotNull(profile.lastVersionId)
            if (snapshot.versions.none { it.id == versionId }) {
                warnings += LauncherWarning(
                    LauncherWarningCode.PROFILE_VERSION_NOT_INSTALLED,
                    "Launcher profile references a version that was not detected.",
                    snapshot.root.child("launcher_profiles.json"),
                )
            }
            instances += LauncherInstance(
                profileId = profile.profileId,
                name = name,
                lastVersionId = versionId,
                gameDirectory = profile.gameDirectory,
                profileType = profile.profileType,
            )
        }

        val structureErrors = errors.any {
            it.code in setOf(
                LauncherErrorCode.INVALID_ROOT,
                LauncherErrorCode.READ_PERMISSION_DENIED,
                LauncherErrorCode.MISSING_PROFILES_FILE,
                LauncherErrorCode.MISSING_VERSIONS_DIRECTORY,
                LauncherErrorCode.UNSUPPORTED_STRUCTURE,
                LauncherErrorCode.INVALID_PROFILES_JSON,
                LauncherErrorCode.INVALID_PROFILES_SCHEMA,
                LauncherErrorCode.PROFILES_TOO_LARGE,
                LauncherErrorCode.STORAGE_IO,
            )
        }
        val capabilities = LauncherCapabilities(
            compatible = !structureErrors && errors.none {
                it.code == LauncherErrorCode.CORRUPTED_PROFILE ||
                    it.code == LauncherErrorCode.DUPLICATE_PROFILE
            },
            readOnly = true,
            canReadProfiles = snapshot.profilesFilePresent &&
                errors.none {
                    it.code == LauncherErrorCode.INVALID_PROFILES_JSON ||
                        it.code == LauncherErrorCode.INVALID_PROFILES_SCHEMA ||
                        it.code == LauncherErrorCode.PROFILES_TOO_LARGE
                },
            canScanVersions = LauncherDirectory.VERSIONS in snapshot.detectedDirectories,
            hasFabric = snapshot.versions.any { it.loader == LauncherLoader.FABRIC },
            hasForge = snapshot.versions.any { it.loader == LauncherLoader.FORGE },
            hasNeoForge = snapshot.versions.any { it.loader == LauncherLoader.NEOFORGE },
            canInstall = false,
            canCreateInstances = false,
            canEditProfiles = false,
        )
        return LauncherResult.Success(
            LauncherInfo(
                launcherId = "cs-launcher-v2",
                displayName = "CS Launcher V2",
                root = snapshot.root,
                instances = instances.toList(),
                versions = snapshot.versions.toList(),
                detectedDirectories = snapshot.detectedDirectories.toSet(),
                capabilities = capabilities,
                warnings = warnings.distinct().toList(),
                errors = errors.distinct().toList(),
            ),
        )
    }

    private fun StoragePath.child(name: String): StoragePath =
        if (isRoot) StoragePath(name) else StoragePath("$value/$name")
}
