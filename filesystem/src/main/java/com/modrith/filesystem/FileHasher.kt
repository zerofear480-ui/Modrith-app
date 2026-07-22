package com.modrith.filesystem

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class FileHasher(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    suspend fun hash(
        provider: StorageProvider,
        path: StoragePath,
        algorithms: Set<StorageHashAlgorithm>,
        progressListener: StorageProgressListener = StorageProgressListener.NONE,
    ): StorageResult<FileHashResult> = withContext(Dispatchers.IO) {
        if (algorithms.isEmpty()) {
            return@withContext failure(
                StorageErrorCode.UNSUPPORTED_OPERATION,
                "At least one hash algorithm is required.",
                path,
                recoverable = false,
            )
        }
        val inputResult = provider.openInput(path, bufferSize)
        if (inputResult is StorageResult.Failure) return@withContext inputResult
        val sourceInput = (inputResult as StorageResult.Success<java.io.InputStream>).value
        val digests = algorithms.associateWith { MessageDigest.getInstance(it.jcaName) }
        var bytes = 0L
        try {
            sourceInput.use { input ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digests.values.forEach { it.update(buffer, 0, read) }
                    bytes += read
                    progressListener.emit(
                        StorageProgress(
                            StorageOperation.HASH,
                            path,
                            0,
                            1,
                            bytes,
                            null,
                        ),
                    )
                }
            }
            StorageResult.Success(
                FileHashResult(
                    path = path,
                    sizeBytes = bytes,
                    hashes = digests.mapValues { (_, digest) -> digest.digest().toHex() },
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            ioFailure("File hashing failed.", path, error)
        }
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte)
}
