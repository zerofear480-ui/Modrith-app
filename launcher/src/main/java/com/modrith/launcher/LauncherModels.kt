package com.modrith.launcher

import com.modrith.filesystem.StoragePath

data class LauncherInfo(
    val launcherId: String,
    val displayName: String,
    val root: StoragePath,
    val instances: List<LauncherInstance>,
    val versions: List<LauncherVersion>,
    val detectedDirectories: Set<LauncherDirectory>,
    val capabilities: LauncherCapabilities,
    val warnings: List<LauncherWarning>,
    val errors: List<LauncherError>,
)

data class LauncherInstance(
    val profileId: String,
    val name: String,
    val lastVersionId: String,
    val gameDirectory: String?,
    val profileType: String?,
)

data class LauncherVersion(
    val id: String,
    val minecraftVersion: String?,
    val loader: LauncherLoader,
    val loaderVersion: String?,
    val path: StoragePath,
)

data class LauncherCapabilities(
    val compatible: Boolean,
    val readOnly: Boolean,
    val canReadProfiles: Boolean,
    val canScanVersions: Boolean,
    val hasFabric: Boolean,
    val hasForge: Boolean,
    val hasNeoForge: Boolean,
    val canInstall: Boolean,
    val canCreateInstances: Boolean,
    val canEditProfiles: Boolean,
)

enum class LauncherLoader {
    VANILLA,
    FABRIC,
    FORGE,
    NEOFORGE,
    UNKNOWN,
}

enum class LauncherDirectory(
    val relativePath: String,
) {
    ASSETS("assets"),
    LIBRARIES("libraries"),
    MODS("mods"),
    CONFIG("config"),
    RESOURCE_PACKS("resourcepacks"),
    SHADER_PACKS("shaderpacks"),
    DATA_PACKS("datapacks"),
    SAVES("saves"),
    VERSIONS("versions"),
}

data class LauncherWarning(
    val code: LauncherWarningCode,
    val message: String,
    val path: StoragePath? = null,
)

enum class LauncherWarningCode {
    MISSING_DIRECTORY,
    DUPLICATE_PROFILE,
    EMPTY_VERSIONS_DIRECTORY,
    CORRUPTED_VERSION_METADATA,
    UNKNOWN_VERSION_TYPE,
    PROFILE_VERSION_NOT_INSTALLED,
}

data class LauncherError(
    val code: LauncherErrorCode,
    val message: String,
    val path: StoragePath? = null,
    val recoverable: Boolean,
)

enum class LauncherErrorCode {
    INVALID_ROOT,
    READ_PERMISSION_DENIED,
    MISSING_PROFILES_FILE,
    MISSING_ASSETS_DIRECTORY,
    MISSING_LIBRARIES_DIRECTORY,
    MISSING_VERSIONS_DIRECTORY,
    UNSUPPORTED_STRUCTURE,
    PROFILES_TOO_LARGE,
    INVALID_PROFILES_JSON,
    INVALID_PROFILES_SCHEMA,
    DUPLICATE_PROFILE,
    CORRUPTED_PROFILE,
    STORAGE_IO,
    INTERNAL,
}

sealed interface LauncherResult {
    data class Success(
        val info: LauncherInfo,
    ) : LauncherResult

    data class Failure(
        val errors: List<LauncherError>,
        val warnings: List<LauncherWarning> = emptyList(),
    ) : LauncherResult
}

data class LauncherScanSnapshot(
    val root: StoragePath,
    val detectedDirectories: Set<LauncherDirectory>,
    val profilesFilePresent: Boolean,
    val profiles: List<LauncherProfileCandidate>,
    val versions: List<LauncherVersion>,
    val warnings: List<LauncherWarning>,
    val errors: List<LauncherError>,
)

data class LauncherProfileCandidate(
    val profileId: String,
    val name: String?,
    val lastVersionId: String?,
    val gameDirectory: String?,
    val profileType: String?,
    val corrupted: Boolean,
)
