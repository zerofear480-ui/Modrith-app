package com.modrith.orchestrator

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DefaultMrPackSourceResolver(
    private val contentResolver: ContentResolver? = null,
) : MrPackSourceResolver {
    override suspend fun resolve(
        source: MrPackSource,
        cacheDirectory: Path,
        sessionId: String,
    ): ArchiveSourceResult = withContext(Dispatchers.IO) {
        when (source) {
            is MrPackSource.FileSource -> validateFile(source.file.toPath())
            is MrPackSource.UriSource -> resolveUri(source.uri, cacheDirectory, sessionId)
        }
    }

    private fun validateFile(path: Path): ArchiveSourceResult =
        if (Files.isRegularFile(path)) {
            ArchiveSourceResult.Success(
                ResolvedArchiveSource(path.toAbsolutePath().normalize(), deleteAfterParsing = false),
            )
        } else {
            failure("SOURCE_NOT_FOUND", "The selected MRPack file does not exist.")
        }

    private suspend fun resolveUri(
        rawUri: String,
        cacheDirectory: Path,
        sessionId: String,
    ): ArchiveSourceResult {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
            ?: return failure("INVALID_URI", "The selected MRPack URI is invalid.")
        if (uri.scheme.isNullOrBlank() || uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = runCatching {
                if (uri.scheme.isNullOrBlank()) Paths.get(rawUri) else Paths.get(uri.path.orEmpty())
            }.getOrNull() ?: return failure("INVALID_URI", "The selected file URI is invalid.")
            return validateFile(path)
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return failure("UNSUPPORTED_URI", "Only file and content MRPack URIs are supported.")
        }
        val resolver = contentResolver
            ?: return failure(
                "CONTENT_RESOLVER_UNAVAILABLE",
                "A ContentResolver is required for content MRPack URIs.",
            )

        val sourceDirectory = cacheDirectory.resolve(".orchestrator/sources")
        val finalPath = sourceDirectory.resolve("$sessionId.mrpack")
        val temporaryPath = sourceDirectory.resolve(".$sessionId.${UUID.randomUUID()}.tmp")
        return try {
            Files.createDirectories(sourceDirectory)
            val input = resolver.openInputStream(uri)
                ?: return failure("SOURCE_NOT_FOUND", "The selected MRPack URI cannot be opened.")
            input.buffered().use { sourceStream ->
                Files.newOutputStream(
                    temporaryPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = sourceStream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            try {
                Files.move(
                    temporaryPath,
                    finalPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
            }
            ArchiveSourceResult.Success(
                ResolvedArchiveSource(finalPath, deleteAfterParsing = true),
            )
        } catch (error: CancellationException) {
            Files.deleteIfExists(temporaryPath)
            throw error
        } catch (error: SecurityException) {
            Files.deleteIfExists(temporaryPath)
            failure("READ_PERMISSION_DENIED", "Read permission for the MRPack URI was denied.")
        } catch (error: IOException) {
            Files.deleteIfExists(temporaryPath)
            failure("SOURCE_IO", "The MRPack URI could not be copied into app cache.")
        }
    }

    private fun failure(
        code: String,
        message: String,
    ) = ArchiveSourceResult.Failure(
        InstallError(
            source = InstallErrorSource.SOURCE,
            code = code,
            message = message,
            recoverable = true,
        ),
    )
}
