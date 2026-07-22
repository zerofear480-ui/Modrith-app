package com.modrith.parser.mrpack

import com.modrith.models.DownloadEntry
import com.modrith.models.EnvironmentSupport
import com.modrith.models.InstallPlan
import com.modrith.models.InstallWarning
import com.modrith.models.InstallWarningCode
import com.modrith.models.LoaderType
import com.modrith.models.MrPackDependencies
import com.modrith.models.MrPackEnvironment
import com.modrith.models.MrPackFile
import com.modrith.models.MrPackManifest
import com.modrith.models.MrPackParseError
import com.modrith.models.MrPackParseErrorCode
import com.modrith.models.MrPackParseResult
import com.modrith.models.OverrideFile
import com.modrith.models.OverrideFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MrPackParser(
    private val logger: ParserLogger = JvmParserLogger(),
    private val limits: MrPackParserLimits = MrPackParserLimits(),
) {
    fun parse(
        archive: Path,
        cacheRoot: Path,
    ): MrPackParseResult {
        val startedAt = System.nanoTime()
        logger.info(
            event = "mrpack.parse.started",
            attributes = mapOf("archiveName" to archive.fileName, "cacheRoot" to cacheRoot),
        )

        if (!Files.isRegularFile(archive)) {
            return failure(
                code = MrPackParseErrorCode.ARCHIVE_NOT_FOUND,
                message = "The MRPack archive does not exist or is not a regular file.",
                details = mapOf("archiveName" to archive.fileName.toString()),
            )
        }

        var extractionDirectory: Path? = null
        return try {
            ZipFile(archive.toFile()).use { zip ->
                val entries = inspectArchive(zip)
                val indexEntries = entries.filter { it.name == INDEX_FILE_NAME }
                if (indexEntries.isEmpty()) {
                    return failure(
                        MrPackParseErrorCode.MISSING_INDEX,
                        "The archive does not contain a root-level modrinth.index.json.",
                    )
                }
                if (indexEntries.size > 1) {
                    return failure(
                        MrPackParseErrorCode.DUPLICATE_INDEX,
                        "The archive contains more than one modrinth.index.json.",
                    )
                }

                val jsonText = zip.getInputStream(indexEntries.single()).use {
                    readBoundedText(it, limits.maxIndexBytes)
                }
                val manifest = parseManifest(jsonText)
                val planData = validateManifest(manifest, jsonText)

                extractionDirectory = createExtractionDirectory(cacheRoot)
                extractArchive(zip, entries, extractionDirectory)
                val plan = createInstallPlan(
                    manifest = planData.manifest,
                    entries = entries,
                    extractionDirectory = extractionDirectory,
                    warnings = planData.warnings,
                    loaderType = planData.loaderType,
                    loaderVersion = planData.loaderVersion,
                )
                logger.info(
                    event = "mrpack.parse.completed",
                    attributes = mapOf(
                        "name" to manifest.name,
                        "versionId" to manifest.versionId,
                        "files" to manifest.files.size,
                        "mods" to plan.modCount,
                        "overrideFolders" to plan.overrideFolders.map { it.name },
                        "elapsedMs" to elapsedMillis(startedAt),
                    ),
                )
                MrPackParseResult.Success(plan)
            }
        } catch (error: ParserFailureException) {
            extractionDirectory?.deleteRecursively()
            failure(error.code, error.message, error.details, error)
        } catch (error: ZipException) {
            extractionDirectory?.deleteRecursively()
            failure(
                MrPackParseErrorCode.CORRUPTED_ZIP,
                "The MRPack is not a readable ZIP archive.",
                cause = error,
            )
        } catch (error: SerializationException) {
            extractionDirectory?.deleteRecursively()
            failure(
                MrPackParseErrorCode.INVALID_JSON,
                "modrinth.index.json is not valid for the MRPack schema.",
                details = mapOf("cause" to error::class.simpleName.orEmpty()),
                cause = error,
            )
        } catch (error: IOException) {
            extractionDirectory?.deleteRecursively()
            failure(
                MrPackParseErrorCode.CACHE_IO,
                "The MRPack could not be read or extracted into cache.",
                details = mapOf("cause" to error::class.simpleName.orEmpty()),
                cause = error,
            )
        } catch (error: RuntimeException) {
            extractionDirectory?.deleteRecursively()
            failure(
                MrPackParseErrorCode.INTERNAL,
                "An unexpected parser failure occurred.",
                details = mapOf("cause" to error::class.simpleName.orEmpty()),
                cause = error,
            )
        }
    }

    private fun inspectArchive(zip: ZipFile): List<ZipEntry> {
        val entries = mutableListOf<ZipEntry>()
        val normalizedNames = mutableSetOf<String>()
        var totalDeclaredSize = 0L
        val enumeration = zip.entries()

        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entries.size >= limits.maxEntries) {
                throw ParserFailureException(
                    MrPackParseErrorCode.ARCHIVE_LIMIT_EXCEEDED,
                    "The archive contains too many entries.",
                    mapOf("limit" to limits.maxEntries.toString()),
                )
            }
            val normalizedName = validateEntryName(entry.name, entry.isDirectory)
            if (!normalizedNames.add(normalizedName)) {
                throw ParserFailureException(
                    MrPackParseErrorCode.UNSAFE_ARCHIVE_ENTRY,
                    "The archive contains duplicate entry paths.",
                    mapOf("entry" to normalizedName),
                )
            }
            if (entry.size > limits.maxEntryBytes) {
                throw ParserFailureException(
                    MrPackParseErrorCode.ARCHIVE_LIMIT_EXCEEDED,
                    "An archive entry exceeds the allowed size.",
                    mapOf("entry" to normalizedName, "limit" to limits.maxEntryBytes.toString()),
                )
            }
            if (entry.size >= 0) {
                totalDeclaredSize = addWithoutOverflow(totalDeclaredSize, entry.size)
                if (totalDeclaredSize > limits.maxTotalUncompressedBytes) {
                    throw ParserFailureException(
                        MrPackParseErrorCode.ARCHIVE_LIMIT_EXCEEDED,
                        "The archive exceeds the total extraction size limit.",
                        mapOf("limit" to limits.maxTotalUncompressedBytes.toString()),
                    )
                }
            }
            if (
                entry.size > 0 &&
                entry.compressedSize > 0 &&
                entry.size / entry.compressedSize.toDouble() > limits.maxCompressionRatio
            ) {
                throw ParserFailureException(
                    MrPackParseErrorCode.ARCHIVE_LIMIT_EXCEEDED,
                    "An archive entry exceeds the compression ratio limit.",
                    mapOf("entry" to normalizedName),
                )
            }
            entries += entry
        }

        logger.debug(
            event = "mrpack.archive.inspected",
            attributes = mapOf("entryCount" to entries.size, "declaredBytes" to totalDeclaredSize),
        )
        return entries
    }

    private fun validateEntryName(
        rawName: String,
        directory: Boolean,
    ): String {
        if (rawName.isBlank() || rawName.length > limits.maxEntryNameLength) {
            unsafeEntry(rawName, "blank or overlong entry name")
        }
        if ('\u0000' in rawName || '\\' in rawName || rawName.startsWith('/')) {
            unsafeEntry(rawName, "absolute, NUL, or backslash path")
        }
        if (DrivePrefix.matches(rawName)) {
            unsafeEntry(rawName, "drive-prefixed path")
        }

        val normalizedInput = if (directory) rawName.trimEnd('/') else rawName
        val segments = normalizedInput.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            unsafeEntry(rawName, "empty or traversal path segment")
        }
        return normalizedInput
    }

    private fun unsafeEntry(rawName: String, reason: String): Nothing {
        throw ParserFailureException(
            MrPackParseErrorCode.UNSAFE_ARCHIVE_ENTRY,
            "The archive contains an unsafe entry path.",
            mapOf("entry" to rawName.take(160), "reason" to reason),
        )
    }

    private fun parseManifest(jsonText: String): MrPackManifest {
        val root = try {
            JsonParser.parseToJsonElement(jsonText).jsonObject
        } catch (error: IllegalArgumentException) {
            throw ParserFailureException(
                MrPackParseErrorCode.INVALID_JSON,
                "modrinth.index.json is not valid JSON.",
                cause = error,
            )
        }
        val decoded = try {
            JsonParser.decodeFromJsonElement<MrPackManifest>(root)
        } catch (error: SerializationException) {
            throw ParserFailureException(
                MrPackParseErrorCode.INVALID_SCHEMA,
                "modrinth.index.json does not match the required MRPack schema.",
                cause = error,
            )
        }
        val dependenciesObject = root["dependencies"] as? JsonObject
            ?: throw ParserFailureException(
                MrPackParseErrorCode.INVALID_SCHEMA,
                "dependencies must be a JSON object.",
            )
        val knownDependencyKeys = setOf(
            "minecraft",
            "fabric-loader",
            "forge",
            "quilt-loader",
            "neoforge",
        )
        val additional = dependenciesObject
            .filterKeys { it !in knownDependencyKeys }
            .mapValues { (key, value) ->
                runCatching { value.jsonPrimitive.content }.getOrElse {
                    throw ParserFailureException(
                        MrPackParseErrorCode.INVALID_SCHEMA,
                        "Dependency versions must be strings.",
                        mapOf("dependency" to key),
                    )
                }
            }
        return decoded.copy(
            dependencies = decoded.dependencies.copy(additional = additional),
        )
    }

    private fun validateManifest(
        manifest: MrPackManifest,
        jsonText: String,
    ): ValidatedPlanData {
        if (manifest.formatVersion != SUPPORTED_FORMAT_VERSION) {
            throw ParserFailureException(
                MrPackParseErrorCode.UNSUPPORTED_FORMAT_VERSION,
                "Unsupported MRPack formatVersion.",
                mapOf(
                    "actual" to manifest.formatVersion.toString(),
                    "supported" to SUPPORTED_FORMAT_VERSION.toString(),
                ),
            )
        }
        if (manifest.game != SUPPORTED_GAME) {
            throw ParserFailureException(
                MrPackParseErrorCode.UNSUPPORTED_GAME,
                "Only Minecraft MRPack archives are supported.",
                mapOf("game" to manifest.game),
            )
        }
        requireSchema(manifest.name.isNotBlank(), "name must not be blank")
        requireSchema(manifest.versionId.isNotBlank(), "versionId must not be blank")
        requireSchema(manifest.files.isNotEmpty(), "files must not be empty")
        requireSchema(jsonText.toByteArray(Charsets.UTF_8).size <= limits.maxIndexBytes, "index is too large")

        val minecraftVersion = manifest.dependencies.minecraft
        requireSchema(!minecraftVersion.isNullOrBlank(), "dependencies.minecraft is required")
        val loaders = manifest.dependencies.loaderValues()
        if (loaders.size != 1) {
            throw ParserFailureException(
                MrPackParseErrorCode.UNSUPPORTED_DEPENDENCIES,
                "Exactly one supported mod loader dependency is required.",
                mapOf("loaderCount" to loaders.size.toString()),
            )
        }

        val warnings = mutableListOf<InstallWarning>()
        manifest.dependencies.additional.keys.sorted().forEach { key ->
            warnings += InstallWarning(
                code = InstallWarningCode.UNKNOWN_DEPENDENCY,
                message = "Unknown dependency '$key' was preserved but is not used by the parser.",
            )
        }

        val paths = mutableSetOf<String>()
        manifest.files.forEach { file ->
            validatePackFile(file)
            if (!paths.add(file.path)) {
                throw ParserFailureException(
                    MrPackParseErrorCode.INVALID_SCHEMA,
                    "Manifest file paths must be unique.",
                    mapOf("path" to file.path),
                )
            }
            when (file.env?.client ?: EnvironmentSupport.REQUIRED) {
                EnvironmentSupport.OPTIONAL -> warnings += InstallWarning(
                    InstallWarningCode.OPTIONAL_CLIENT_FILE,
                    "This client file is optional.",
                    file.path,
                )
                EnvironmentSupport.UNSUPPORTED -> warnings += InstallWarning(
                    InstallWarningCode.CLIENT_UNSUPPORTED_FILE,
                    "This file is not supported in the client environment.",
                    file.path,
                )
                EnvironmentSupport.REQUIRED -> Unit
            }
        }

        val (loaderType, loaderVersion) = loaders.single()
        logger.debug(
            event = "mrpack.manifest.validated",
            attributes = mapOf(
                "formatVersion" to manifest.formatVersion,
                "game" to manifest.game,
                "versionId" to manifest.versionId,
                "loader" to loaderType,
                "files" to manifest.files.size,
                "warnings" to warnings.size,
            ),
        )
        return ValidatedPlanData(manifest, loaderType, loaderVersion, warnings)
    }

    private fun validatePackFile(file: MrPackFile) {
        validateEntryName(file.path, directory = false)
        file.fileSize?.let { size ->
            requireSchema(size >= 0, "fileSize must be non-negative", file.path)
            requireSchema(size <= limits.maxDownloadBytes, "fileSize exceeds the parser limit", file.path)
        }
        requireSchema(file.downloads.isNotEmpty(), "downloads must not be empty", file.path)
        file.downloads.forEach { url ->
            val uri = runCatching { URI(url) }.getOrNull()
            requireSchema(
                uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank(),
                "download URLs must use HTTPS and include a host",
                file.path,
            )
        }
        val sha1Valid = file.hashes.sha1?.matches(Sha1Pattern) == true
        val sha512Valid = file.hashes.sha512?.matches(Sha512Pattern) == true
        requireSchema(
            sha1Valid || sha512Valid,
            "at least one valid SHA-1 or SHA-512 hash is required",
            file.path,
        )
        if (file.hashes.sha1 != null) {
            requireSchema(sha1Valid, "sha1 must contain 40 hexadecimal characters", file.path)
        }
        if (file.hashes.sha512 != null) {
            requireSchema(sha512Valid, "sha512 must contain 128 hexadecimal characters", file.path)
        }
    }

    private fun createExtractionDirectory(cacheRoot: Path): Path {
        Files.createDirectories(cacheRoot)
        val realCacheRoot = cacheRoot.toRealPath()
        val extractionDirectory = realCacheRoot.resolve("mrpack-${UUID.randomUUID()}")
        Files.createDirectory(extractionDirectory)
        logger.debug(
            event = "mrpack.cache.created",
            attributes = mapOf("directoryName" to extractionDirectory.fileName),
        )
        return extractionDirectory
    }

    private fun extractArchive(
        zip: ZipFile,
        entries: List<ZipEntry>,
        extractionDirectory: Path,
    ) {
        val root = extractionDirectory.toRealPath()
        var totalWritten = 0L
        entries.forEach { entry ->
            val normalizedName = validateEntryName(entry.name, entry.isDirectory)
            val target = root.resolve(normalizedName).normalize()
            if (!target.startsWith(root)) {
                unsafeEntry(entry.name, "resolved outside cache root")
            }
            if (entry.isDirectory) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                val temporary = target.resolveSibling("${target.fileName}.part")
                val bytesWritten = zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(temporary).use { output ->
                        copyBounded(input, output, limits.maxEntryBytes)
                    }
                }
                totalWritten = addWithoutOverflow(totalWritten, bytesWritten)
                if (totalWritten > limits.maxTotalUncompressedBytes) {
                    throw ParserFailureException(
                        MrPackParseErrorCode.ARCHIVE_LIMIT_EXCEEDED,
                        "The extracted archive exceeds the total size limit.",
                    )
                }
                runCatching {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        logger.info(
            event = "mrpack.archive.extracted",
            attributes = mapOf("entries" to entries.size, "bytes" to totalWritten),
        )
    }

    private fun createInstallPlan(
        manifest: MrPackManifest,
        entries: List<ZipEntry>,
        extractionDirectory: Path,
        warnings: List<InstallWarning>,
        loaderType: LoaderType,
        loaderVersion: String,
    ): InstallPlan {
        val downloadEntries = manifest.files.map { file ->
            DownloadEntry(
                path = file.path,
                urls = file.downloads,
                hashes = file.hashes,
                sizeBytes = file.fileSize,
                clientRequirement = file.env?.client ?: EnvironmentSupport.REQUIRED,
            )
        }
        val overrideFolders = OverrideRoots.mapNotNull { rootName ->
            val rootExists = entries.any {
                it.name == rootName || it.name == "$rootName/" || it.name.startsWith("$rootName/")
            }
            val files = entries
                .asSequence()
                .filterNot(ZipEntry::isDirectory)
                .filter { it.name.startsWith("$rootName/") }
                .map { entry ->
                    OverrideFile(
                        archivePath = entry.name,
                        relativePath = entry.name.removePrefix("$rootName/"),
                        sizeBytes = entry.size.takeIf { it >= 0 },
                    )
                }
                .sortedBy(OverrideFile::relativePath)
                .toList()
            if (!rootExists) {
                null
            } else {
                OverrideFolder(
                    name = rootName,
                    extractedPath = extractionDirectory.resolve(rootName).toString(),
                    files = files,
                )
            }
        }
        val totalSize = if (downloadEntries.all { it.sizeBytes != null }) {
            downloadEntries.fold(0L) { total, entry ->
                addWithoutOverflow(total, requireNotNull(entry.sizeBytes))
            }
        } else {
            null
        }
        val finalWarnings = warnings.toMutableList()
        if (totalSize == null) {
            finalWarnings += InstallWarning(
                code = InstallWarningCode.DOWNLOAD_SIZE_UNAVAILABLE,
                message = "The total download size is unavailable because one or more files omit fileSize.",
            )
        }

        return InstallPlan(
            manifest = manifest,
            minecraftVersion = requireNotNull(manifest.dependencies.minecraft),
            loaderType = loaderType,
            loaderVersion = loaderVersion,
            modCount = downloadEntries.count { it.path.startsWith("mods/") },
            overrideFolders = overrideFolders,
            downloadEntries = downloadEntries,
            totalDownloadSize = totalSize,
            warnings = finalWarnings,
            cacheDirectory = extractionDirectory.toString(),
        )
    }

    private fun readBoundedText(input: InputStream, maxBytes: Long): String {
        val output = ByteArrayOutputStream()
        copyBounded(input, output, maxBytes)
        return output.toString(Charsets.UTF_8)
    }

    private fun copyBounded(
        input: InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total = addWithoutOverflow(total, read.toLong())
            if (total > maxBytes) {
                throw ParserFailureException(
                    MrPackParseErrorCode.ARCHIVE_LIMIT_EXCEEDED,
                    "An archive entry exceeds the extraction size limit.",
                    mapOf("limit" to maxBytes.toString()),
                )
            }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun requireSchema(
        condition: Boolean,
        message: String,
        path: String? = null,
    ) {
        if (!condition) {
            throw ParserFailureException(
                MrPackParseErrorCode.INVALID_SCHEMA,
                message,
                path?.let { mapOf("path" to it) }.orEmpty(),
            )
        }
    }

    private fun addWithoutOverflow(left: Long, right: Long): Long {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            throw ParserFailureException(
                MrPackParseErrorCode.ARCHIVE_LIMIT_EXCEEDED,
                "A declared byte total overflowed.",
            )
        }
        return left + right
    }

    private fun failure(
        code: MrPackParseErrorCode,
        message: String,
        details: Map<String, String> = emptyMap(),
        cause: Throwable? = null,
    ): MrPackParseResult.Failure {
        logger.error(
            event = "mrpack.parse.failed",
            attributes = mapOf("code" to code, "details" to details),
            cause = cause,
        )
        return MrPackParseResult.Failure(MrPackParseError(code, message, details))
    }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

    private data class ValidatedPlanData(
        val manifest: MrPackManifest,
        val loaderType: LoaderType,
        val loaderVersion: String,
        val warnings: List<InstallWarning>,
    )

    private class ParserFailureException(
        val code: MrPackParseErrorCode,
        override val message: String,
        val details: Map<String, String> = emptyMap(),
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)

    private companion object {
        const val INDEX_FILE_NAME = "modrinth.index.json"
        const val SUPPORTED_FORMAT_VERSION = 1
        const val SUPPORTED_GAME = "minecraft"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val OverrideRoots = listOf("overrides", "client-overrides")
        val DrivePrefix = Regex("^[A-Za-z]:.*")
        val Sha1Pattern = Regex("^[0-9a-fA-F]{40}$")
        val Sha512Pattern = Regex("^[0-9a-fA-F]{128}$")
        val JsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = false
            coerceInputValues = false
            explicitNulls = false
        }
    }
}

data class MrPackParserLimits(
    val maxEntries: Int = 100_000,
    val maxEntryNameLength: Int = 1_024,
    val maxIndexBytes: Long = 4L * 1024 * 1024,
    val maxEntryBytes: Long = 512L * 1024 * 1024,
    val maxTotalUncompressedBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxDownloadBytes: Long = 8L * 1024 * 1024 * 1024,
    val maxCompressionRatio: Double = 1_000.0,
)

private fun MrPackDependencies.loaderValues(): List<Pair<LoaderType, String>> = buildList {
    fabricLoader?.takeIf(String::isNotBlank)?.let { add(LoaderType.FABRIC to it) }
    forge?.takeIf(String::isNotBlank)?.let { add(LoaderType.FORGE to it) }
    quiltLoader?.takeIf(String::isNotBlank)?.let { add(LoaderType.QUILT to it) }
    neoforge?.takeIf(String::isNotBlank)?.let { add(LoaderType.NEOFORGE to it) }
}

private fun Path.deleteRecursively() {
    if (!Files.exists(this)) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
