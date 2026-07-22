package com.modrith.installer

import com.modrith.filesystem.StorageErrorCode
import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageProvider
import com.modrith.filesystem.StorageResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface InstallRepository {
    suspend fun get(transactionId: String): InstallRepositoryResult<InstallTransaction>

    suspend fun save(transaction: InstallTransaction): InstallRepositoryResult<Unit>

    suspend fun pending(): InstallRepositoryResult<List<InstallTransaction>>

    suspend fun delete(transactionId: String): InstallRepositoryResult<Unit>
}

sealed interface InstallRepositoryResult<out T> {
    data class Success<T>(
        val value: T,
    ) : InstallRepositoryResult<T>

    data class Failure(
        val failure: InstallFailure,
    ) : InstallRepositoryResult<Nothing>
}

class StorageInstallRepository(
    private val storage: StorageProvider,
    private val stateDirectory: StoragePath = StoragePath(".modrith-installer/transactions"),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : InstallRepository {
    override suspend fun get(
        transactionId: String,
    ): InstallRepositoryResult<InstallTransaction> {
        val path = transactionPath(transactionId)
        val input = storage.openInput(path)
        if (input is StorageResult.Failure) {
            return if (input.error.code == StorageErrorCode.NOT_FOUND) {
                InstallRepositoryResult.Failure(
                    InstallFailure(
                        InstallFailureCode.TRANSACTION_NOT_FOUND,
                        "Install transaction does not exist.",
                        recoverable = false,
                    ),
                )
            } else {
                input.error.toRepositoryFailure()
            }
        }
        return try {
            val text = (input as StorageResult.Success).value.bufferedReader().use { it.readText() }
            InstallRepositoryResult.Success(json.decodeFromString<InstallTransaction>(text))
        } catch (error: SerializationException) {
            InstallRepositoryResult.Failure(
                InstallFailure(
                    InstallFailureCode.TRANSACTION_CORRUPT,
                    "Install transaction state is invalid.",
                    recoverable = false,
                ),
            )
        }
    }

    override suspend fun save(
        transaction: InstallTransaction,
    ): InstallRepositoryResult<Unit> {
        val directory = storage.createDirectory(stateDirectory)
        if (directory is StorageResult.Failure) return directory.error.toRepositoryFailure()
        val result = storage.replaceFile(transactionPath(transaction.id), JSON_MIME_TYPE) { output ->
            output.write(json.encodeToString(transaction).toByteArray(Charsets.UTF_8))
        }
        return when (result) {
            is StorageResult.Success -> InstallRepositoryResult.Success(Unit)
            is StorageResult.Failure -> result.error.toRepositoryFailure()
        }
    }

    override suspend fun pending(): InstallRepositoryResult<List<InstallTransaction>> {
        val exists = storage.exists(stateDirectory)
        if (exists is StorageResult.Failure) return exists.error.toRepositoryFailure()
        if (!(exists as StorageResult.Success).value) {
            return InstallRepositoryResult.Success(emptyList())
        }
        val entries = storage.list(stateDirectory)
        if (entries is StorageResult.Failure) return entries.error.toRepositoryFailure()
        val transactions = mutableListOf<InstallTransaction>()
        for (entry in (entries as StorageResult.Success).value) {
            if (!entry.name.endsWith(JSON_EXTENSION)) continue
            val id = entry.name.removeSuffix(JSON_EXTENSION)
            when (val result = get(id)) {
                is InstallRepositoryResult.Success -> {
                    if (result.value.status == InstallTransactionStatus.ACTIVE) {
                        transactions += result.value
                    }
                }
                is InstallRepositoryResult.Failure -> return result
            }
        }
        return InstallRepositoryResult.Success(transactions.sortedBy { it.createdAtEpochMillis })
    }

    override suspend fun delete(transactionId: String): InstallRepositoryResult<Unit> {
        val path = transactionPath(transactionId)
        val exists = storage.exists(path)
        if (exists is StorageResult.Failure) return exists.error.toRepositoryFailure()
        if (!(exists as StorageResult.Success).value) {
            return InstallRepositoryResult.Success(Unit)
        }
        return when (val result = storage.delete(path)) {
            is StorageResult.Success -> InstallRepositoryResult.Success(Unit)
            is StorageResult.Failure -> result.error.toRepositoryFailure()
        }
    }

    private fun transactionPath(transactionId: String): StoragePath =
        StoragePath("${stateDirectory.value}/$transactionId$JSON_EXTENSION")

    private fun com.modrith.filesystem.StorageError.toRepositoryFailure() =
        InstallRepositoryResult.Failure(
            InstallFailure(
                code = when (code) {
                    StorageErrorCode.READ_PERMISSION_DENIED,
                    StorageErrorCode.WRITE_PERMISSION_DENIED,
                    StorageErrorCode.PERSIST_PERMISSION_FAILED,
                    -> InstallFailureCode.PERMISSION_DENIED
                    else -> InstallFailureCode.DESTINATION_IO
                },
                message = message,
                recoverable = recoverable,
                path = path?.value,
            ),
        )

    private companion object {
        const val JSON_EXTENSION = ".json"
        const val JSON_MIME_TYPE = "application/json"
    }
}
