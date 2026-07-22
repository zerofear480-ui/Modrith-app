package com.modrith.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MrPackManifest(
    val formatVersion: Int,
    val game: String,
    val versionId: String,
    val name: String,
    val summary: String? = null,
    val files: List<MrPackFile>,
    val dependencies: MrPackDependencies,
)

@Serializable
data class MrPackFile(
    val path: String,
    val hashes: MrPackHashes,
    val env: MrPackEnvironment? = null,
    val downloads: List<String>,
    val fileSize: Long? = null,
)

@Serializable
data class MrPackHashes(
    val sha1: String? = null,
    val sha512: String? = null,
)

@Serializable
data class MrPackEnvironment(
    val client: EnvironmentSupport = EnvironmentSupport.REQUIRED,
    val server: EnvironmentSupport = EnvironmentSupport.REQUIRED,
)

@Serializable
enum class EnvironmentSupport {
    @SerialName("required")
    REQUIRED,

    @SerialName("optional")
    OPTIONAL,

    @SerialName("unsupported")
    UNSUPPORTED,
}

@Serializable
data class MrPackDependencies(
    val minecraft: String? = null,
    @SerialName("fabric-loader")
    val fabricLoader: String? = null,
    @SerialName("forge")
    val forge: String? = null,
    @SerialName("quilt-loader")
    val quiltLoader: String? = null,
    @SerialName("neoforge")
    val neoforge: String? = null,
    val additional: Map<String, String> = emptyMap(),
)

enum class LoaderType {
    FABRIC,
    FORGE,
    QUILT,
    NEOFORGE,
}

data class DownloadEntry(
    val path: String,
    val urls: List<String>,
    val hashes: MrPackHashes,
    val sizeBytes: Long?,
    val clientRequirement: EnvironmentSupport,
)

data class OverrideFile(
    val archivePath: String,
    val relativePath: String,
    val sizeBytes: Long?,
)

data class OverrideFolder(
    val name: String,
    val extractedPath: String,
    val files: List<OverrideFile>,
)

enum class InstallWarningCode {
    CLIENT_UNSUPPORTED_FILE,
    OPTIONAL_CLIENT_FILE,
    UNKNOWN_DEPENDENCY,
    DOWNLOAD_SIZE_UNAVAILABLE,
}

data class InstallWarning(
    val code: InstallWarningCode,
    val message: String,
    val path: String? = null,
)

data class InstallPlan(
    val manifest: MrPackManifest,
    val minecraftVersion: String,
    val loaderType: LoaderType,
    val loaderVersion: String,
    val modCount: Int,
    val overrideFolders: List<OverrideFolder>,
    val downloadEntries: List<DownloadEntry>,
    val totalDownloadSize: Long?,
    val warnings: List<InstallWarning>,
    val cacheDirectory: String,
)

enum class MrPackParseErrorCode {
    ARCHIVE_NOT_FOUND,
    CORRUPTED_ZIP,
    MISSING_INDEX,
    DUPLICATE_INDEX,
    INVALID_JSON,
    INVALID_SCHEMA,
    UNSUPPORTED_FORMAT_VERSION,
    UNSUPPORTED_GAME,
    UNSUPPORTED_DEPENDENCIES,
    UNSAFE_ARCHIVE_ENTRY,
    ARCHIVE_LIMIT_EXCEEDED,
    CACHE_IO,
    INTERNAL,
}

data class MrPackParseError(
    val code: MrPackParseErrorCode,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

sealed interface MrPackParseResult {
    data class Success(
        val plan: InstallPlan,
    ) : MrPackParseResult

    data class Failure(
        val error: MrPackParseError,
    ) : MrPackParseResult
}
