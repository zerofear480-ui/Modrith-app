package com.modrith.resolver

import com.modrith.models.DownloadEntry
import com.modrith.models.EnvironmentSupport
import com.modrith.models.HashAlgorithm
import com.modrith.models.InstallPlan
import com.modrith.models.LoaderType
import com.modrith.models.MrPackDependencies
import com.modrith.models.ResolutionError
import com.modrith.models.ResolutionErrorCode
import com.modrith.models.ResolutionWarning
import com.modrith.models.ResolutionWarningCode
import com.modrith.models.ResolvedDownloadEntry
import com.modrith.models.ResolvedFileType
import com.modrith.models.ResolvedHash
import com.modrith.models.ResolvedInstallPlan
import com.modrith.models.ResolvedInstallStep
import com.modrith.models.ResolvedInstallStepType
import com.modrith.models.ResolvedLoader
import com.modrith.models.ResolvedOverrideFile
import java.net.URI
import java.util.Locale

class InstallPlanResolver(
    supportedLoaders: Set<LoaderType> = LoaderType.entries.toSet(),
) {
    private val supportedLoaders = supportedLoaders.toSet()

    fun resolve(plan: InstallPlan): ResolvedInstallPlan {
        val warnings = mutableListOf<ResolutionWarning>()
        val recoverableErrors = mutableListOf<ResolutionError>()
        val fatalErrors = mutableListOf<ResolutionError>()

        validateRequiredMetadata(plan, fatalErrors)
        val minecraftVersion = resolveMinecraftVersion(plan, fatalErrors)
        val loader = resolveLoader(plan, warnings, fatalErrors)
        plan.warnings.forEach { sourceWarning ->
            warnings += ResolutionWarning(
                code = ResolutionWarningCode.SOURCE_PLAN_WARNING,
                message = sourceWarning.message,
                path = sourceWarning.path,
            )
        }

        val resolvedDownloads = resolveDownloads(
            plan = plan,
            warnings = warnings,
            recoverableErrors = recoverableErrors,
            fatalErrors = fatalErrors,
        )
        val resolvedOverrides = resolveOverrides(plan, fatalErrors)
        detectDestinationConflicts(resolvedDownloads, resolvedOverrides, fatalErrors)
        val totalDownloadSize = calculateTotalSize(
            resolvedDownloads,
            recoverableErrors,
            fatalErrors,
        )
        val installOrder = resolveInstallOrder(resolvedDownloads, resolvedOverrides)

        return ResolvedInstallPlan(
            source = plan,
            minecraftVersion = minecraftVersion,
            loader = loader,
            downloadEntries = resolvedDownloads.toList(),
            overrideFiles = resolvedOverrides.toList(),
            installOrder = installOrder,
            totalDownloadSize = totalDownloadSize,
            warnings = warnings.distinct().toList(),
            recoverableErrors = recoverableErrors.distinct().toList(),
            fatalErrors = fatalErrors.distinct().toList(),
        )
    }

    private fun validateRequiredMetadata(
        plan: InstallPlan,
        fatalErrors: MutableList<ResolutionError>,
    ) {
        if (plan.manifest.name.isBlank()) {
            fatalErrors += fatal(
                ResolutionErrorCode.MISSING_PACK_NAME,
                "Pack name is required.",
            )
        }
        if (plan.manifest.versionId.isBlank()) {
            fatalErrors += fatal(
                ResolutionErrorCode.MISSING_VERSION_ID,
                "Pack versionId is required.",
            )
        }
        if (plan.manifest.game.isBlank()) {
            fatalErrors += fatal(
                ResolutionErrorCode.MISSING_GAME,
                "Pack game metadata is required.",
            )
        } else if (plan.manifest.game != MINECRAFT_GAME_ID) {
            fatalErrors += fatal(
                ResolutionErrorCode.INVALID_GAME,
                "Only Minecraft install plans are supported.",
            )
        }
        if (plan.cacheDirectory.isBlank()) {
            fatalErrors += fatal(
                ResolutionErrorCode.MISSING_CACHE_DIRECTORY,
                "Parser cache metadata is required.",
            )
        }
    }

    private fun resolveMinecraftVersion(
        plan: InstallPlan,
        fatalErrors: MutableList<ResolutionError>,
    ): String? {
        val planVersion = plan.minecraftVersion.trim()
        val manifestVersion = plan.manifest.dependencies.minecraft?.trim()
        if (planVersion.isBlank() && manifestVersion.isNullOrBlank()) {
            fatalErrors += fatal(
                ResolutionErrorCode.MISSING_MINECRAFT_VERSION,
                "Minecraft version is required.",
            )
            return null
        }

        val resolved = planVersion.ifBlank { manifestVersion.orEmpty() }
        if (!VersionPattern.matches(resolved)) {
            fatalErrors += fatal(
                ResolutionErrorCode.INVALID_MINECRAFT_VERSION,
                "Minecraft version contains unsupported characters.",
            )
        }
        if (
            planVersion.isNotBlank() &&
            !manifestVersion.isNullOrBlank() &&
            planVersion != manifestVersion
        ) {
            fatalErrors += fatal(
                ResolutionErrorCode.MINECRAFT_VERSION_MISMATCH,
                "InstallPlan and manifest Minecraft versions do not match.",
            )
        }
        return resolved.takeIf(String::isNotBlank)
    }

    private fun resolveLoader(
        plan: InstallPlan,
        warnings: MutableList<ResolutionWarning>,
        fatalErrors: MutableList<ResolutionError>,
    ): ResolvedLoader? {
        val declarations = plan.manifest.dependencies.loaderDeclarations()
        if (declarations.isEmpty()) {
            val unknownDependencies = plan.manifest.dependencies.additional.keys
                .filterNot { it.lowercase(Locale.ROOT) in LoaderAliases }
            if (unknownDependencies.isEmpty()) {
                fatalErrors += fatal(
                    ResolutionErrorCode.MISSING_LOADER,
                    "A supported loader dependency is required.",
                )
            } else {
                fatalErrors += fatal(
                    ResolutionErrorCode.UNSUPPORTED_LOADER,
                    "Unsupported loader dependency: ${unknownDependencies.sorted().joinToString()}.",
                )
            }
            return null
        }

        val grouped = declarations.groupBy(LoaderDeclaration::type)
        if (grouped.size > 1) {
            fatalErrors += fatal(
                ResolutionErrorCode.AMBIGUOUS_LOADER,
                "Multiple loader families are declared.",
            )
            return null
        }

        val type = grouped.keys.single()
        if (type !in supportedLoaders) {
            fatalErrors += fatal(
                ResolutionErrorCode.UNSUPPORTED_LOADER,
                "The declared loader is not supported.",
            )
            return null
        }
        val versions = declarations.map { it.version.trim() }.filter(String::isNotEmpty).distinct()
        if (versions.isEmpty()) {
            fatalErrors += fatal(
                ResolutionErrorCode.MISSING_LOADER_VERSION,
                "Loader version is required.",
            )
            return null
        }
        if (versions.size > 1) {
            fatalErrors += fatal(
                ResolutionErrorCode.AMBIGUOUS_LOADER,
                "Conflicting versions are declared for the same loader.",
            )
            return null
        }

        val version = versions.single()
        if (!VersionPattern.matches(version)) {
            fatalErrors += fatal(
                ResolutionErrorCode.INVALID_LOADER_VERSION,
                "Loader version contains unsupported characters.",
            )
        }
        if (plan.loaderType != type || plan.loaderVersion.trim() != version) {
            fatalErrors += fatal(
                ResolutionErrorCode.LOADER_VERSION_MISMATCH,
                "InstallPlan loader metadata does not match manifest dependencies.",
            )
        }

        val canonicalId = type.canonicalId()
        declarations
            .filter { it.identifier != canonicalId }
            .forEach { declaration ->
                warnings += ResolutionWarning(
                    code = ResolutionWarningCode.LOADER_IDENTIFIER_NORMALIZED,
                    message = "Loader identifier '${declaration.identifier}' normalized to '$canonicalId'.",
                )
            }
        return ResolvedLoader(type, canonicalId, version)
    }

    private fun resolveDownloads(
        plan: InstallPlan,
        warnings: MutableList<ResolutionWarning>,
        recoverableErrors: MutableList<ResolutionError>,
        fatalErrors: MutableList<ResolutionError>,
    ): List<ResolvedDownloadEntry> {
        val resolved = mutableListOf<ResolvedDownloadEntry>()
        val artifactDestinations = mutableMapOf<String, MutableList<String>>()

        plan.downloadEntries.forEach { entry ->
            if (entry.clientRequirement == EnvironmentSupport.UNSUPPORTED) {
                warnings += ResolutionWarning(
                    ResolutionWarningCode.UNSUPPORTED_CLIENT_FILE_EXCLUDED,
                    "A client-unsupported file was excluded.",
                    entry.path,
                )
                return@forEach
            }
            if (entry.clientRequirement == EnvironmentSupport.OPTIONAL) {
                warnings += ResolutionWarning(
                    ResolutionWarningCode.OPTIONAL_FILE,
                    "An optional client file remains optional in the resolved plan.",
                    entry.path,
                )
            }

            val destinationPath = normalizePath(entry.path)
            if (destinationPath == null) {
                fatalErrors += fatal(
                    ResolutionErrorCode.INVALID_DESTINATION_PATH,
                    "Download destination path is invalid.",
                    entry.path,
                )
                return@forEach
            }
            val fileType = resolveFileType(destinationPath)
            if (fileType == null) {
                fatalErrors += fatal(
                    ResolutionErrorCode.UNSUPPORTED_FILE_TYPE,
                    "Download destination has an unsupported file type.",
                    destinationPath,
                )
                return@forEach
            }
            val preferredHash = resolveHash(entry, fatalErrors) ?: return@forEach
            val validUrls = resolveUrls(entry, warnings, fatalErrors)
            if (validUrls.isEmpty()) return@forEach

            val sizeBytes = entry.sizeBytes
            if (sizeBytes != null && sizeBytes < 0) {
                fatalErrors += fatal(
                    ResolutionErrorCode.INVALID_FILE_SIZE,
                    "Download size must not be negative.",
                    destinationPath,
                )
            }

            val artifactKey = "${preferredHash.algorithm.name.lowercase()}:${preferredHash.value}"
            artifactDestinations.getOrPut(artifactKey, ::mutableListOf) += destinationPath
            resolved += ResolvedDownloadEntry(
                destinationPath = destinationPath,
                urls = validUrls,
                hashes = entry.hashes,
                preferredHash = preferredHash,
                sizeBytes = sizeBytes,
                required = entry.clientRequirement == EnvironmentSupport.REQUIRED,
                fileType = fileType,
                artifactKey = artifactKey,
            )
        }

        artifactDestinations
            .filterValues { it.size > 1 }
            .forEach { (_, destinations) ->
                warnings += ResolutionWarning(
                    code = ResolutionWarningCode.DUPLICATE_DOWNLOAD_ARTIFACT,
                    message = "The same artifact is referenced by multiple download entries.",
                    path = destinations.sorted().joinToString(),
                )
                recoverableErrors += recoverable(
                    ResolutionErrorCode.DUPLICATE_DOWNLOAD_ENTRY,
                    "Duplicate download entries can share one verified artifact.",
                    destinations.sorted().joinToString(),
                )
            }

        return resolved
    }

    private fun resolveHash(
        entry: DownloadEntry,
        fatalErrors: MutableList<ResolutionError>,
    ): ResolvedHash? {
        val sha512 = entry.hashes.sha512
        val sha1 = entry.hashes.sha1
        if (sha512 == null && sha1 == null) {
            fatalErrors += fatal(
                ResolutionErrorCode.MISSING_HASH,
                "At least one SHA-512 or SHA-1 hash is required.",
                entry.path,
            )
            return null
        }
        if (sha512 != null && !Sha512Pattern.matches(sha512)) {
            fatalErrors += fatal(
                ResolutionErrorCode.INVALID_HASH,
                "SHA-512 hash is invalid.",
                entry.path,
            )
            return null
        }
        if (sha1 != null && !Sha1Pattern.matches(sha1)) {
            fatalErrors += fatal(
                ResolutionErrorCode.INVALID_HASH,
                "SHA-1 hash is invalid.",
                entry.path,
            )
            return null
        }
        return if (sha512 != null) {
            ResolvedHash(HashAlgorithm.SHA512, sha512.lowercase(Locale.ROOT))
        } else {
            ResolvedHash(HashAlgorithm.SHA1, requireNotNull(sha1).lowercase(Locale.ROOT))
        }
    }

    private fun resolveUrls(
        entry: DownloadEntry,
        warnings: MutableList<ResolutionWarning>,
        fatalErrors: MutableList<ResolutionError>,
    ): List<String> {
        if (entry.urls.isEmpty()) {
            fatalErrors += fatal(
                ResolutionErrorCode.MISSING_DOWNLOAD_URL,
                "At least one download URL is required.",
                entry.path,
            )
            return emptyList()
        }

        val valid = entry.urls.filter(::isValidDownloadUrl).distinct()
        val invalidCount = entry.urls.size - valid.size
        if (invalidCount > 0 && valid.isNotEmpty()) {
            warnings += ResolutionWarning(
                ResolutionWarningCode.INVALID_MIRROR_IGNORED,
                "$invalidCount invalid download mirror(s) were ignored.",
                entry.path,
            )
        }
        if (valid.isEmpty()) {
            fatalErrors += fatal(
                ResolutionErrorCode.INVALID_DOWNLOAD_URL,
                "No valid HTTPS download URL remains.",
                entry.path,
            )
        }
        return valid
    }

    private fun resolveOverrides(
        plan: InstallPlan,
        fatalErrors: MutableList<ResolutionError>,
    ): List<ResolvedOverrideFile> {
        val resolved = mutableListOf<ResolvedOverrideFile>()
        val destinationSources = mutableMapOf<String, MutableList<String>>()

        plan.overrideFolders.forEach { folder ->
            folder.files.forEach { file ->
                val destinationPath = normalizePath(file.relativePath)
                if (destinationPath == null) {
                    fatalErrors += fatal(
                        ResolutionErrorCode.INVALID_DESTINATION_PATH,
                        "Override destination path is invalid.",
                        file.relativePath,
                    )
                    return@forEach
                }
                if (destinationPath.hasDangerousOverrideExtension()) {
                    fatalErrors += fatal(
                        ResolutionErrorCode.UNSUPPORTED_FILE_TYPE,
                        "Executable override file types are not supported.",
                        destinationPath,
                    )
                    return@forEach
                }
                val collisionKey = destinationPath.lowercase(Locale.ROOT)
                destinationSources.getOrPut(collisionKey, ::mutableListOf) += file.archivePath
                resolved += ResolvedOverrideFile(
                    sourceRoot = folder.name,
                    sourcePath = file.archivePath,
                    destinationPath = destinationPath,
                    sizeBytes = file.sizeBytes,
                )
            }
        }

        destinationSources
            .filterValues { it.size > 1 }
            .forEach { (destination, sources) ->
                fatalErrors += fatal(
                    ResolutionErrorCode.OVERRIDE_CONFLICT,
                    "Multiple override files target the same destination: ${sources.sorted().joinToString()}.",
                    destination,
                )
            }
        return resolved
    }

    private fun detectDestinationConflicts(
        downloads: List<ResolvedDownloadEntry>,
        overrides: List<ResolvedOverrideFile>,
        fatalErrors: MutableList<ResolutionError>,
    ) {
        downloads
            .groupBy { it.destinationPath.lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }
            .forEach { (path, _) ->
                fatalErrors += fatal(
                    ResolutionErrorCode.DUPLICATE_DESTINATION_PATH,
                    "Multiple downloads target the same destination.",
                    path,
                )
            }

        val downloadPaths = downloads
            .associateBy { it.destinationPath.lowercase(Locale.ROOT) }
        overrides.forEach { override ->
            val key = override.destinationPath.lowercase(Locale.ROOT)
            if (key in downloadPaths) {
                fatalErrors += fatal(
                    ResolutionErrorCode.DOWNLOAD_OVERRIDE_CONFLICT,
                    "A download and override target the same destination.",
                    override.destinationPath,
                )
            }
        }
    }

    private fun calculateTotalSize(
        downloads: List<ResolvedDownloadEntry>,
        recoverableErrors: MutableList<ResolutionError>,
        fatalErrors: MutableList<ResolutionError>,
    ): Long? {
        var total = 0L
        var totalAvailable = true
        downloads.groupBy(ResolvedDownloadEntry::artifactKey).forEach { (_, entries) ->
            entries.filter { it.sizeBytes == null }.forEach { entry ->
                recoverableErrors += recoverable(
                    ResolutionErrorCode.MISSING_FILE_SIZE,
                    "Download size is unavailable.",
                    entry.destinationPath,
                )
            }
            val declaredSizes = entries.mapNotNull(ResolvedDownloadEntry::sizeBytes)
                .filter { it >= 0 }
                .distinct()
            if (declaredSizes.size > 1) {
                fatalErrors += fatal(
                    ResolutionErrorCode.INVALID_FILE_SIZE,
                    "Duplicate artifact entries declare conflicting sizes.",
                    entries.map(ResolvedDownloadEntry::destinationPath).sorted().joinToString(),
                )
                totalAvailable = false
                return@forEach
            }
            val size = declaredSizes.singleOrNull()
            if (size == null) {
                totalAvailable = false
                return@forEach
            }
            if (total > Long.MAX_VALUE - size) {
                fatalErrors += fatal(
                    ResolutionErrorCode.DOWNLOAD_SIZE_OVERFLOW,
                    "Total download size overflowed.",
                )
                totalAvailable = false
                return@forEach
            }
            total += size
        }
        return total.takeIf { totalAvailable }
    }

    private fun resolveInstallOrder(
        downloads: List<ResolvedDownloadEntry>,
        overrides: List<ResolvedOverrideFile>,
    ): List<ResolvedInstallStep> {
        val orderedDownloads = downloads.sortedWith(
            compareBy<ResolvedDownloadEntry>(
                { it.fileType.installPriority },
                { it.destinationPath.lowercase(Locale.ROOT) },
            ),
        )
        val orderedOverrides = overrides.sortedWith(
            compareBy<ResolvedOverrideFile>(
                { it.sourceRoot.overridePriority },
                { it.destinationPath.lowercase(Locale.ROOT) },
            ),
        )
        return buildList {
            orderedDownloads.forEach { entry ->
                add(
                    ResolvedInstallStep(
                        order = size,
                        type = ResolvedInstallStepType.DOWNLOAD,
                        destinationPath = entry.destinationPath,
                        sourceReference = entry.artifactKey,
                    ),
                )
            }
            orderedOverrides.forEach { entry ->
                add(
                    ResolvedInstallStep(
                        order = size,
                        type = ResolvedInstallStepType.APPLY_OVERRIDE,
                        destinationPath = entry.destinationPath,
                        sourceReference = entry.sourcePath,
                    ),
                )
            }
        }
    }

    private fun normalizePath(path: String): String? {
        if (
            path.isBlank() ||
            path.startsWith('/') ||
            '\\' in path ||
            '\u0000' in path ||
            DrivePrefix.matches(path)
        ) {
            return null
        }
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }
        return segments.joinToString("/")
    }

    private fun resolveFileType(path: String): ResolvedFileType? {
        val lowerPath = path.lowercase(Locale.ROOT)
        return when {
            lowerPath.startsWith("mods/") && lowerPath.endsWith(".jar") ->
                ResolvedFileType.MOD
            lowerPath.startsWith("resourcepacks/") && lowerPath.endsWith(".zip") ->
                ResolvedFileType.RESOURCE_PACK
            lowerPath.startsWith("shaderpacks/") && lowerPath.endsWith(".zip") ->
                ResolvedFileType.SHADER_PACK
            "/datapacks/" in lowerPath && lowerPath.endsWith(".zip") ->
                ResolvedFileType.DATA_PACK
            lowerPath.endsWith(".jar") || lowerPath.endsWith(".zip") ->
                ResolvedFileType.OTHER_ARCHIVE
            else -> null
        }
    }

    private fun isValidDownloadUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    }

    private fun String.hasDangerousOverrideExtension(): Boolean {
        val extension = substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        return extension in DangerousOverrideExtensions
    }

    private fun fatal(
        code: ResolutionErrorCode,
        message: String,
        path: String? = null,
    ) = ResolutionError(code, message, path, recoverable = false)

    private fun recoverable(
        code: ResolutionErrorCode,
        message: String,
        path: String? = null,
    ) = ResolutionError(code, message, path, recoverable = true)

    private data class LoaderDeclaration(
        val type: LoaderType,
        val identifier: String,
        val version: String,
    )

    private companion object {
        const val MINECRAFT_GAME_ID = "minecraft"
        val VersionPattern = Regex("^[0-9A-Za-z][0-9A-Za-z._+\\-]*$")
        val Sha1Pattern = Regex("^[0-9a-fA-F]{40}$")
        val Sha512Pattern = Regex("^[0-9a-fA-F]{128}$")
        val DrivePrefix = Regex("^[A-Za-z]:.*")
        val LoaderAliases = mapOf(
            "fabric" to LoaderType.FABRIC,
            "fabricloader" to LoaderType.FABRIC,
            "fabric-loader" to LoaderType.FABRIC,
            "forge" to LoaderType.FORGE,
            "forge-loader" to LoaderType.FORGE,
            "neoforge" to LoaderType.NEOFORGE,
            "neo-forge" to LoaderType.NEOFORGE,
            "neo-forge-loader" to LoaderType.NEOFORGE,
            "quilt" to LoaderType.QUILT,
            "quilt-loader" to LoaderType.QUILT,
        )
        val DangerousOverrideExtensions = setOf(
            "apk",
            "bat",
            "cmd",
            "com",
            "dll",
            "dylib",
            "exe",
            "msi",
            "ps1",
            "sh",
            "so",
        )
    }

    private fun MrPackDependencies.loaderDeclarations(): List<LoaderDeclaration> = buildList {
        fabricLoader?.let { add(LoaderDeclaration(LoaderType.FABRIC, "fabric-loader", it)) }
        forge?.let { add(LoaderDeclaration(LoaderType.FORGE, "forge", it)) }
        neoforge?.let { add(LoaderDeclaration(LoaderType.NEOFORGE, "neoforge", it)) }
        quiltLoader?.let { add(LoaderDeclaration(LoaderType.QUILT, "quilt-loader", it)) }
        additional.forEach { (identifier, version) ->
            LoaderAliases[identifier.lowercase(Locale.ROOT)]?.let { type ->
                add(LoaderDeclaration(type, identifier, version))
            }
        }
    }

    private fun LoaderType.canonicalId(): String = when (this) {
        LoaderType.FABRIC -> "fabric-loader"
        LoaderType.FORGE -> "forge"
        LoaderType.NEOFORGE -> "neoforge"
        LoaderType.QUILT -> "quilt-loader"
    }

    private val ResolvedFileType.installPriority: Int
        get() = when (this) {
            ResolvedFileType.MOD -> 0
            ResolvedFileType.RESOURCE_PACK -> 1
            ResolvedFileType.SHADER_PACK -> 2
            ResolvedFileType.DATA_PACK -> 3
            ResolvedFileType.OTHER_ARCHIVE -> 4
        }

    private val String.overridePriority: Int
        get() = when (this) {
            "overrides" -> 0
            "client-overrides" -> 1
            else -> 2
        }
}
