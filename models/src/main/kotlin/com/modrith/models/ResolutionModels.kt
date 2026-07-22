package com.modrith.models

data class ResolvedLoader(
    val type: LoaderType,
    val canonicalId: String,
    val version: String,
)

data class ResolvedDownloadEntry(
    val destinationPath: String,
    val urls: List<String>,
    val hashes: MrPackHashes,
    val preferredHash: ResolvedHash,
    val sizeBytes: Long?,
    val required: Boolean,
    val fileType: ResolvedFileType,
    val artifactKey: String,
)

data class ResolvedHash(
    val algorithm: HashAlgorithm,
    val value: String,
)

enum class HashAlgorithm {
    SHA512,
    SHA1,
}

enum class ResolvedFileType {
    MOD,
    RESOURCE_PACK,
    SHADER_PACK,
    DATA_PACK,
    OTHER_ARCHIVE,
}

data class ResolvedOverrideFile(
    val sourceRoot: String,
    val sourcePath: String,
    val destinationPath: String,
    val sizeBytes: Long?,
)

data class ResolvedInstallStep(
    val order: Int,
    val type: ResolvedInstallStepType,
    val destinationPath: String,
    val sourceReference: String,
)

enum class ResolvedInstallStepType {
    DOWNLOAD,
    APPLY_OVERRIDE,
}

data class ResolvedInstallPlan(
    val source: InstallPlan,
    val minecraftVersion: String?,
    val loader: ResolvedLoader?,
    val downloadEntries: List<ResolvedDownloadEntry>,
    val overrideFiles: List<ResolvedOverrideFile>,
    val installOrder: List<ResolvedInstallStep>,
    val totalDownloadSize: Long?,
    val warnings: List<ResolutionWarning>,
    val recoverableErrors: List<ResolutionError>,
    val fatalErrors: List<ResolutionError>,
) {
    val isReady: Boolean
        get() = fatalErrors.isEmpty() && recoverableErrors.isEmpty()
}

data class ResolutionWarning(
    val code: ResolutionWarningCode,
    val message: String,
    val path: String? = null,
)

enum class ResolutionWarningCode {
    LOADER_IDENTIFIER_NORMALIZED,
    DUPLICATE_DOWNLOAD_ARTIFACT,
    INVALID_MIRROR_IGNORED,
    OPTIONAL_FILE,
    UNSUPPORTED_CLIENT_FILE_EXCLUDED,
    SOURCE_PLAN_WARNING,
}

data class ResolutionError(
    val code: ResolutionErrorCode,
    val message: String,
    val path: String? = null,
    val recoverable: Boolean,
)

enum class ResolutionErrorCode {
    MISSING_PACK_NAME,
    MISSING_VERSION_ID,
    MISSING_GAME,
    INVALID_GAME,
    MISSING_MINECRAFT_VERSION,
    INVALID_MINECRAFT_VERSION,
    MINECRAFT_VERSION_MISMATCH,
    MISSING_LOADER,
    UNSUPPORTED_LOADER,
    AMBIGUOUS_LOADER,
    LOADER_VERSION_MISMATCH,
    MISSING_LOADER_VERSION,
    INVALID_LOADER_VERSION,
    DUPLICATE_DOWNLOAD_ENTRY,
    DUPLICATE_DESTINATION_PATH,
    INVALID_DESTINATION_PATH,
    OVERRIDE_CONFLICT,
    DOWNLOAD_OVERRIDE_CONFLICT,
    MISSING_HASH,
    INVALID_HASH,
    MISSING_DOWNLOAD_URL,
    INVALID_DOWNLOAD_URL,
    UNSUPPORTED_FILE_TYPE,
    MISSING_FILE_SIZE,
    INVALID_FILE_SIZE,
    DOWNLOAD_SIZE_OVERFLOW,
    MISSING_CACHE_DIRECTORY,
}
