package com.modrith.installer

import com.modrith.filesystem.CacheProvider
import com.modrith.filesystem.CopyEngine
import com.modrith.filesystem.FileHasher
import com.modrith.filesystem.StorageError
import com.modrith.filesystem.StorageErrorCode
import com.modrith.filesystem.StorageHashAlgorithm
import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageProvider
import com.modrith.filesystem.StorageResult
import com.modrith.models.DownloadArtifactStatus
import com.modrith.models.DownloadSession
import com.modrith.models.DownloadSessionStatus
import com.modrith.models.ResolvedDownloadEntry
import com.modrith.models.ResolvedInstallPlan
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class InstallWorker(
    private val repository: InstallRepository,
    private val diskSpaceChecker: InstallDiskSpaceChecker = InstallDiskSpaceChecker.UNKNOWN,
    private val logger: InstallLogger = JvmInstallLogger(),
    private val clock: InstallClock = SystemInstallClock,
    private val fileHasher: FileHasher = FileHasher(),
    private val copyEngine: CopyEngine = CopyEngine(),
    private val includeExceptionDetails: Boolean = false,
) {
    suspend fun install(
        transactionId: String,
        plan: ResolvedInstallPlan,
        downloadSession: DownloadSession,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ): InstallSessionResult {
        logger.info(
            "install.step.before",
            stepAttributes(transactionId, "validate_plan"),
        )
        if (!plan.isReady) {
            return failed(
                transactionId,
                InstallFailure(
                    InstallFailureCode.PLAN_NOT_READY,
                    "ResolvedInstallPlan contains unresolved errors.",
                    recoverable = false,
                ),
            )
        }
        logger.info(
            "install.step.after",
            stepAttributes(transactionId, "validate_plan"),
        )
        emit(
            progressListener,
            transactionId,
            InstallProgressPhase.WAITING_FOR_DOWNLOADS,
            null,
            0,
            plan.downloadEntries.size + plan.overrideFiles.size,
            0,
            plan.totalDownloadSize,
        )
        logger.info(
            "install.step.before",
            stepAttributes(transactionId, "await_downloads"),
        )
        val downloadResult = downloadSession.await()
        logger.info(
            "install.step.after",
            stepAttributes(
                transactionId,
                "await_downloads",
                attributes = mapOf("status" to downloadResult.status),
            ),
        )
        if (downloadResult.status != DownloadSessionStatus.COMPLETED) {
            return failed(
                transactionId,
                InstallFailure(
                    InstallFailureCode.DOWNLOAD_FAILED,
                    downloadResult.failure?.message ?: "Downloads did not complete.",
                    downloadResult.failure?.recoverable ?: true,
                ),
            )
        }

        var transaction: InstallTransaction? = null
        var activeStep = "prepare_operations"
        var activePath: String? = null
        return try {
            logger.info(
                "install.step.before",
                stepAttributes(transactionId, activeStep),
            )
            val operations = prepareOperations(
                transactionId,
                plan,
                downloadResult.artifacts.associateBy { it.artifactKey },
                progressListener,
            )
            logger.info(
                "install.step.after",
                stepAttributes(
                    transactionId,
                    activeStep,
                    attributes = mapOf("operations" to operations.size),
                ),
            )
            val now = clock.currentTimeMillis()
            transaction = InstallTransaction(
                id = transactionId,
                destinationProviderId = destination.providerId,
                status = InstallTransactionStatus.ACTIVE,
                checkpoint = InstallCheckpoint(InstallCheckpointPhase.PREPARED, 0),
                operations = operations,
                log = listOf(logEntry("install.transaction_created")),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            activeStep = "save_transaction"
            logger.info(
                "install.step.before",
                stepAttributes(transactionId, activeStep),
            )
            transaction = save(requireNotNull(transaction))
            logger.info(
                "install.step.after",
                stepAttributes(transactionId, activeStep),
            )

            activeStep = "check_disk_space"
            val requiredBytes = requiredWorkingSpace(operations)
            logger.info(
                "install.step.before",
                stepAttributes(
                    transactionId,
                    activeStep,
                    attributes = mapOf("requiredBytes" to requiredBytes),
                ),
            )
            if (diskSpaceChecker.hasSufficientSpace(destination, requiredBytes) == false) {
                throw InstallFailureException(
                    InstallFailure(
                        InstallFailureCode.DISK_FULL,
                        "Insufficient destination space for staging and backups.",
                        recoverable = true,
                    ),
                )
            }
            logger.info(
                "install.step.after",
                stepAttributes(transactionId, activeStep),
            )
            activeStep = "execute_transaction"
            execute(requireNotNull(transaction), destination, progressListener)
        } catch (error: CancellationException) {
            transaction?.let { current ->
                withContext(NonCancellable) {
                    rollback(
                        latestTransaction(current),
                        destination,
                        cancelledFailure(),
                        progressListener,
                    )
                }
            }
            throw error
        } catch (error: InstallFailureException) {
            val current = transaction
            if (current == null) {
                failed(transactionId, error.failure)
            } else {
                rollbackAndFail(
                    latestTransaction(current),
                    destination,
                    error.failure,
                    progressListener,
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.error(
                "install.unexpected_failure",
                stepAttributes(transactionId, activeStep, activePath),
                error,
            )
            val failure = unexpectedFailure(
                error = error,
                fallbackMessage = "An unexpected installer failure occurred.",
                step = activeStep,
                path = activePath,
            )
            val current = transaction
            if (current == null) {
                failed(transactionId, failure)
            } else {
                rollbackAndFail(
                    latestTransaction(current),
                    destination,
                    failure,
                    progressListener,
                )
            }
        }
    }

    suspend fun resume(
        transactionId: String,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ): InstallSessionResult {
        val loaded = repository.get(transactionId)
        if (loaded is InstallRepositoryResult.Failure) {
            return failed(transactionId, loaded.failure)
        }
        val transaction = (loaded as InstallRepositoryResult.Success).value
        if (transaction.destinationProviderId != destination.providerId) {
            return failed(
                transactionId,
                InstallFailure(
                    InstallFailureCode.PROVIDER_MISMATCH,
                    "The selected destination does not match the interrupted transaction.",
                    recoverable = true,
                ),
            )
        }
        if (transaction.status == InstallTransactionStatus.COMPLETED) {
            return completed(transaction)
        }
        if (transaction.status != InstallTransactionStatus.ACTIVE) {
            return failed(
                transactionId,
                transaction.failure?.toFailure() ?: InstallFailure(
                    InstallFailureCode.TRANSACTION_CORRUPT,
                    "The transaction is not resumable.",
                    recoverable = false,
                ),
            )
        }

        return try {
            if (transaction.checkpoint.phase == InstallCheckpointPhase.ROLLING_BACK) {
                val failure = transaction.failure?.toFailure() ?: InstallFailure(
                    InstallFailureCode.INTERNAL,
                    "Interrupted rollback resumed.",
                    recoverable = true,
                )
                rollbackAndFail(transaction, destination, failure, progressListener)
            } else {
                execute(transaction, destination, progressListener)
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                rollback(
                    latestTransaction(transaction),
                    destination,
                    cancelledFailure(),
                    progressListener,
                )
            }
            throw error
        } catch (error: InstallFailureException) {
            rollbackAndFail(
                latestTransaction(transaction),
                destination,
                error.failure,
                progressListener,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.error(
                "install.resume_unexpected_failure",
                stepAttributes(transactionId, "resume_transaction"),
                error,
            )
            val failure = unexpectedFailure(
                error = error,
                fallbackMessage = "An unexpected installer failure occurred while resuming.",
                step = "resume_transaction",
                path = transaction.operations.getOrNull(transaction.checkpoint.operationIndex)
                    ?.destinationPath,
            )
            rollbackAndFail(
                latestTransaction(transaction),
                destination,
                failure,
                progressListener,
            )
        }
    }

    private suspend fun execute(
        initial: InstallTransaction,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ): InstallSessionResult {
        var transaction = initial
        logger.info(
            "install.step.before",
            stepAttributes(transaction.id, "execute_transaction"),
        )
        if (
            transaction.checkpoint.phase == InstallCheckpointPhase.PREPARED ||
            transaction.checkpoint.phase == InstallCheckpointPhase.STAGING
        ) {
            transaction = stage(transaction, destination, progressListener)
        }
        if (transaction.checkpoint.phase == InstallCheckpointPhase.COMMITTING) {
            transaction = commit(transaction, destination, progressListener)
        }

        transaction = save(
            transaction.copy(
                status = InstallTransactionStatus.COMPLETED,
                checkpoint = InstallCheckpoint(
                    InstallCheckpointPhase.COMPLETED,
                    transaction.operations.size,
                ),
                log = transaction.log + logEntry("install.completed"),
                updatedAtEpochMillis = clock.currentTimeMillis(),
            ),
        )
        cleanup(transaction, destination, progressListener)
        logger.info(
            "install.completed",
            mapOf("transactionId" to transaction.id, "files" to transaction.operations.size),
        )
        logger.info(
            "install.step.after",
            stepAttributes(transaction.id, "execute_transaction"),
        )
        return completed(transaction)
    }

    private suspend fun stage(
        initial: InstallTransaction,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ): InstallTransaction {
        var transaction = save(
            initial.copy(
                checkpoint = InstallCheckpoint(
                    InstallCheckpointPhase.STAGING,
                    initial.operations.indexOfFirst { it.state == InstallFileState.PENDING }
                        .coerceAtLeast(0),
                ),
                log = initial.log + logEntry("install.staging_started"),
                updatedAtEpochMillis = clock.currentTimeMillis(),
            ),
        )
        val totalBytes = transaction.operations.sumOf(InstallFileOperation::expectedSizeBytes)
        var processedBytes = transaction.operations
            .filter { it.state != InstallFileState.PENDING }
            .sumOf(InstallFileOperation::expectedSizeBytes)

        transaction.operations.indices.forEach { index ->
            currentCoroutineContext().ensureActive()
            val operation = transaction.operations[index]
            if (operation.state != InstallFileState.PENDING) return@forEach
            emit(
                progressListener,
                transaction.id,
                InstallProgressPhase.STAGING,
                operation.destinationPath,
                index,
                transaction.operations.size,
                processedBytes,
                totalBytes,
            )
            logger.info(
                "install.step.before",
                stepAttributes(
                    transaction.id,
                    "stage_copy",
                    operation.destinationPath,
                    mapOf("operationIndex" to index, "sourceType" to operation.sourceType),
                ),
            )
            try {
                copyAbsoluteSource(
                    operation.sourcePath,
                    destination,
                    StoragePath(operation.stagePath),
                )
            } catch (error: Throwable) {
                if (error is CancellationException || error is InstallFailureException) throw error
                throw InstallStepException("stage_copy", operation.destinationPath, error)
            }
            logger.info(
                "install.step.after",
                stepAttributes(
                    transaction.id,
                    "stage_copy",
                    operation.destinationPath,
                    mapOf("operationIndex" to index),
                ),
            )
            logger.info(
                "install.step.before",
                stepAttributes(
                    transaction.id,
                    "stage_verify",
                    operation.destinationPath,
                    mapOf("operationIndex" to index),
                ),
            )
            try {
                verifyProviderFile(
                    destination,
                    StoragePath(operation.stagePath),
                    operation.expectedSizeBytes,
                    operation.expectedSha1,
                    operation.expectedSha512,
                )
            } catch (error: Throwable) {
                if (error is CancellationException || error is InstallFailureException) throw error
                throw InstallStepException("stage_verify", operation.destinationPath, error)
            }
            logger.info(
                "install.step.after",
                stepAttributes(
                    transaction.id,
                    "stage_verify",
                    operation.destinationPath,
                    mapOf("operationIndex" to index),
                ),
            )
            processedBytes += operation.expectedSizeBytes
            transaction = save(
                transaction.updateOperation(
                    index,
                    operation.copy(state = InstallFileState.STAGED),
                    InstallCheckpoint(InstallCheckpointPhase.STAGING, index + 1),
                    "install.file_staged",
                ),
            )
        }
        return save(
            transaction.copy(
                checkpoint = InstallCheckpoint(InstallCheckpointPhase.COMMITTING, 0),
                log = transaction.log + logEntry("install.commit_started"),
                updatedAtEpochMillis = clock.currentTimeMillis(),
            ),
        )
    }

    private suspend fun commit(
        initial: InstallTransaction,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ): InstallTransaction {
        var transaction = initial
        val totalBytes = transaction.operations.sumOf(InstallFileOperation::expectedSizeBytes)
        var installedBytes = transaction.operations
            .filter { it.state == InstallFileState.INSTALLED }
            .sumOf(InstallFileOperation::expectedSizeBytes)

        transaction.operations.indices.forEach { index ->
            currentCoroutineContext().ensureActive()
            var operation = transaction.operations[index]
            if (operation.state == InstallFileState.INSTALLED) return@forEach
            if (operation.state == InstallFileState.PENDING) {
                throw InstallFailureException(
                    InstallFailure(
                        InstallFailureCode.TRANSACTION_CORRUPT,
                        "A file was not staged before commit.",
                        recoverable = false,
                        operation.destinationPath,
                    ),
                )
            }
            emit(
                progressListener,
                transaction.id,
                InstallProgressPhase.COMMITTING,
                operation.destinationPath,
                index,
                transaction.operations.size,
                installedBytes,
                totalBytes,
            )

            if (operation.state == InstallFileState.STAGED) {
                val destinationPath = StoragePath(operation.destinationPath)
                logger.info(
                    "install.step.before",
                    stepAttributes(
                        transaction.id,
                        "commit_inspect_destination",
                        operation.destinationPath,
                        mapOf("operationIndex" to index),
                    ),
                )
                val existed = try {
                    destination.exists(destinationPath).valueOrThrow()
                } catch (error: Throwable) {
                    if (error is CancellationException || error is InstallFailureException) {
                        throw error
                    }
                    throw InstallStepException(
                        "commit_inspect_destination",
                        operation.destinationPath,
                        error,
                    )
                }
                logger.info(
                    "install.step.after",
                    stepAttributes(
                        transaction.id,
                        "commit_inspect_destination",
                        operation.destinationPath,
                        mapOf("operationIndex" to index, "existed" to existed),
                    ),
                )
                var originalSha512: String? = null
                if (existed) {
                    logger.info(
                        "install.step.before",
                        stepAttributes(
                            transaction.id,
                            "commit_backup",
                            operation.destinationPath,
                            mapOf("operationIndex" to index),
                        ),
                    )
                    try {
                        val originalHash = hashProviderFile(
                            destination,
                            destinationPath,
                            setOf(StorageHashAlgorithm.SHA512),
                        )
                        originalSha512 =
                            originalHash.hashes.getValue(StorageHashAlgorithm.SHA512)
                        copyProviderFile(
                            destination,
                            destinationPath,
                            StoragePath(operation.backupPath),
                        )
                        verifyProviderFile(
                            destination,
                            StoragePath(operation.backupPath),
                            originalHash.sizeBytes,
                            expectedSha1 = null,
                            expectedSha512 = originalSha512,
                        )
                    } catch (error: Throwable) {
                        if (error is CancellationException || error is InstallFailureException) {
                            throw error
                        }
                        throw InstallStepException(
                            "commit_backup",
                            operation.destinationPath,
                            error,
                        )
                    }
                    logger.info(
                        "install.step.after",
                        stepAttributes(
                            transaction.id,
                            "commit_backup",
                            operation.destinationPath,
                            mapOf("operationIndex" to index),
                        ),
                    )
                }
                operation = operation.copy(
                    state = InstallFileState.BACKED_UP,
                    originalExisted = existed,
                    originalSha512 = originalSha512,
                )
                transaction = save(
                    transaction.updateOperation(
                        index,
                        operation,
                        InstallCheckpoint(InstallCheckpointPhase.COMMITTING, index),
                        if (existed) "install.file_backed_up" else "install.new_file_recorded",
                    ),
                )
            }

            if (operation.state == InstallFileState.BACKED_UP) {
                logger.info(
                    "install.step.before",
                    stepAttributes(
                        transaction.id,
                        "commit_copy",
                        operation.destinationPath,
                        mapOf("operationIndex" to index),
                    ),
                )
                try {
                    copyProviderFile(
                        destination,
                        StoragePath(operation.stagePath),
                        StoragePath(operation.destinationPath),
                    )
                    verifyProviderFile(
                        destination,
                        StoragePath(operation.destinationPath),
                        operation.expectedSizeBytes,
                        operation.expectedSha1,
                        operation.expectedSha512,
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException || error is InstallFailureException) {
                        throw error
                    }
                    throw InstallStepException("commit_copy", operation.destinationPath, error)
                }
                logger.info(
                    "install.step.after",
                    stepAttributes(
                        transaction.id,
                        "commit_copy",
                        operation.destinationPath,
                        mapOf("operationIndex" to index),
                    ),
                )
                installedBytes += operation.expectedSizeBytes
                operation = operation.copy(state = InstallFileState.INSTALLED)
                transaction = save(
                    transaction.updateOperation(
                        index,
                        operation,
                        InstallCheckpoint(InstallCheckpointPhase.COMMITTING, index + 1),
                        "install.file_committed",
                    ),
                )
            }
        }
        return transaction
    }

    private suspend fun rollbackAndFail(
        transaction: InstallTransaction,
        destination: StorageProvider,
        failure: InstallFailure,
        progressListener: InstallProgressListener,
    ): InstallSessionResult {
        val rollback = rollback(transaction, destination, failure, progressListener)
        return when (rollback) {
            is InstallRepositoryResult.Success -> failed(transaction.id, failure)
            is InstallRepositoryResult.Failure -> failed(transaction.id, rollback.failure)
        }
    }

    private suspend fun rollback(
        initial: InstallTransaction,
        destination: StorageProvider,
        failure: InstallFailure,
        progressListener: InstallProgressListener,
    ): InstallRepositoryResult<InstallTransaction> {
        var transaction = runCatching {
            save(
                initial.copy(
                    checkpoint = InstallCheckpoint(
                        InstallCheckpointPhase.ROLLING_BACK,
                        initial.operations.lastIndex,
                    ),
                    failure = failure.persisted(),
                    log = initial.log + logEntry(
                        "install.rollback_started",
                        detail = failure.code.name,
                    ),
                    updatedAtEpochMillis = clock.currentTimeMillis(),
                ),
            )
        }.getOrElse {
            return InstallRepositoryResult.Failure(rollbackFailure())
        }

        return try {
            transaction.operations.indices.reversed().forEach { index ->
                val operation = transaction.operations[index]
                if (
                    operation.state != InstallFileState.BACKED_UP &&
                    operation.state != InstallFileState.INSTALLED
                ) {
                    return@forEach
                }
                emit(
                    progressListener,
                    transaction.id,
                    InstallProgressPhase.ROLLING_BACK,
                    operation.destinationPath,
                    transaction.operations.lastIndex - index,
                    transaction.operations.size,
                    0,
                    null,
                )
                val destinationPath = StoragePath(operation.destinationPath)
                if (operation.originalExisted == true) {
                    copyProviderFile(
                        destination,
                        StoragePath(operation.backupPath),
                        destinationPath,
                    )
                    verifyProviderFile(
                        destination,
                        destinationPath,
                        expectedSize = null,
                        expectedSha1 = null,
                        expectedSha512 = requireNotNull(operation.originalSha512),
                    )
                } else {
                    deleteIfExists(destination, destinationPath)
                }
                transaction = save(
                    transaction.updateOperation(
                        index,
                        operation.copy(state = InstallFileState.RESTORED),
                        InstallCheckpoint(InstallCheckpointPhase.ROLLING_BACK, index - 1),
                        "install.file_restored",
                    ),
                )
            }
            cleanup(transaction, destination, progressListener)
            transaction = save(
                transaction.copy(
                    status = InstallTransactionStatus.ROLLED_BACK,
                    checkpoint = InstallCheckpoint(InstallCheckpointPhase.ROLLED_BACK, -1),
                    log = transaction.log + logEntry("install.rollback_completed"),
                    updatedAtEpochMillis = clock.currentTimeMillis(),
                ),
            )
            logger.info(
                "install.rollback_completed",
                mapOf("transactionId" to transaction.id),
            )
            InstallRepositoryResult.Success(transaction)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.error(
                "install.rollback_failed",
                stepAttributes(
                    transaction.id,
                    "rollback",
                    transaction.operations.getOrNull(transaction.checkpoint.operationIndex)
                        ?.destinationPath,
                ),
                error,
            )
            val rollbackFailure = rollbackFailure()
            runCatching {
                save(
                    transaction.copy(
                        status = InstallTransactionStatus.ROLLBACK_FAILED,
                        failure = rollbackFailure.persisted(),
                        log = transaction.log + logEntry("install.rollback_failed"),
                        updatedAtEpochMillis = clock.currentTimeMillis(),
                    ),
                )
            }
            InstallRepositoryResult.Failure(rollbackFailure)
        }
    }

    private suspend fun cleanup(
        transaction: InstallTransaction,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ) {
        emit(
            progressListener,
            transaction.id,
            InstallProgressPhase.CLEANING_UP,
            null,
            transaction.operations.size,
            transaction.operations.size,
            transaction.operations.sumOf(InstallFileOperation::expectedSizeBytes),
            transaction.operations.sumOf(InstallFileOperation::expectedSizeBytes),
        )
        val root = transactionRoot(transaction.id)
        when (val exists = destination.exists(root)) {
            is StorageResult.Success -> {
                if (exists.value) {
                    val deleted = destination.delete(root, recursive = true)
                    if (deleted is StorageResult.Failure) {
                        logger.warn(
                            "install.cleanup_failed",
                            mapOf("transactionId" to transaction.id, "code" to deleted.error.code),
                        )
                    }
                }
            }
            is StorageResult.Failure -> logger.warn(
                "install.cleanup_failed",
                mapOf("transactionId" to transaction.id, "code" to exists.error.code),
            )
        }
    }

    private suspend fun prepareOperations(
        transactionId: String,
        plan: ResolvedInstallPlan,
        downloads: Map<String, com.modrith.models.DownloadArtifactResult>,
        progressListener: InstallProgressListener,
    ): List<InstallFileOperation> {
        val operations = mutableListOf<InstallFileOperation>()
        val totalFiles = plan.downloadEntries.size + plan.overrideFiles.size
        var verifiedBytes = 0L

        plan.downloadEntries.forEach { entry ->
            currentCoroutineContext().ensureActive()
            val result = downloads[entry.artifactKey]
                ?: throw InstallFailureException(
                    InstallFailure(
                        InstallFailureCode.MISSING_DOWNLOAD,
                        "A required downloaded artifact is missing.",
                        recoverable = true,
                        entry.destinationPath,
                    ),
                )
            if (
                result.status != DownloadArtifactStatus.DOWNLOADED &&
                result.status != DownloadArtifactStatus.SKIPPED_VERIFIED
            ) {
                throw InstallFailureException(
                    InstallFailure(
                        InstallFailureCode.MISSING_DOWNLOAD,
                        "A downloaded artifact is not available for installation.",
                        recoverable = true,
                        entry.destinationPath,
                    ),
                )
            }
            val sourcePath = result.cachePath
                ?: throw InstallFailureException(
                    InstallFailure(
                        InstallFailureCode.MISSING_DOWNLOAD,
                        "A downloaded artifact has no cache path.",
                        recoverable = true,
                        entry.destinationPath,
                    ),
                )
            val hashes = verifyAbsoluteSource(sourcePath, entry)
            val size = hashes.sizeBytes
            operations += operation(
                transactionId = transactionId,
                sourceType = InstallSourceType.DOWNLOAD,
                sourcePath = sourcePath,
                destinationPath = entry.destinationPath,
                expectedSha1 = entry.hashes.sha1?.lowercase(Locale.ROOT),
                expectedSha512 = entry.hashes.sha512?.lowercase(Locale.ROOT)
                    ?: hashes.hashes.getValue(StorageHashAlgorithm.SHA512),
                expectedSize = size,
            )
            verifiedBytes += size
            emit(
                progressListener,
                transactionId,
                InstallProgressPhase.VERIFYING,
                entry.destinationPath,
                operations.size,
                totalFiles,
                verifiedBytes,
                null,
            )
        }

        val cacheRoot = Paths.get(plan.source.cacheDirectory).toAbsolutePath().normalize()
        plan.overrideFiles.forEach { override ->
            currentCoroutineContext().ensureActive()
            val source = cacheRoot.resolve(override.sourcePath).normalize()
            if (!source.startsWith(cacheRoot)) {
                throw InstallFailureException(
                    InstallFailure(
                        InstallFailureCode.SOURCE_NOT_FOUND,
                        "Override source escapes the parser cache.",
                        recoverable = false,
                        override.destinationPath,
                    ),
                )
            }
            val hash = hashAbsoluteFile(source, setOf(StorageHashAlgorithm.SHA512))
            operations += operation(
                transactionId = transactionId,
                sourceType = InstallSourceType.OVERRIDE,
                sourcePath = source.toString(),
                destinationPath = override.destinationPath,
                expectedSha1 = null,
                expectedSha512 = hash.hashes.getValue(StorageHashAlgorithm.SHA512),
                expectedSize = hash.sizeBytes,
            )
            verifiedBytes += hash.sizeBytes
            emit(
                progressListener,
                transactionId,
                InstallProgressPhase.VERIFYING,
                override.destinationPath,
                operations.size,
                totalFiles,
                verifiedBytes,
                null,
            )
        }
        return operations.toList()
    }

    private suspend fun verifyAbsoluteSource(
        path: String,
        entry: ResolvedDownloadEntry,
    ): com.modrith.filesystem.FileHashResult {
        val expectedSha1 = entry.hashes.sha1
        val expectedSha512 = entry.hashes.sha512
        val algorithms = buildSet {
            add(StorageHashAlgorithm.SHA512)
            if (expectedSha1 != null) add(StorageHashAlgorithm.SHA1)
        }
        val result = hashAbsoluteFile(Paths.get(path), algorithms)
        if (entry.sizeBytes != null && result.sizeBytes != entry.sizeBytes) {
            throw InstallFailureException(
                InstallFailure(
                    InstallFailureCode.SIZE_MISMATCH,
                    "Downloaded artifact size changed before installation.",
                    recoverable = true,
                    entry.destinationPath,
                ),
            )
        }
        val actualSha1 = result.hashes[StorageHashAlgorithm.SHA1]
        if (
            expectedSha1 != null &&
            actualSha1 != expectedSha1.lowercase(Locale.ROOT)
        ) {
            throw hashMismatch(entry.destinationPath)
        }
        val actualSha512 = result.hashes.getValue(StorageHashAlgorithm.SHA512)
        if (
            expectedSha512 != null &&
            actualSha512 != expectedSha512.lowercase(Locale.ROOT)
        ) {
            throw hashMismatch(entry.destinationPath)
        }
        return result
    }

    private suspend fun hashAbsoluteFile(
        path: Path,
        algorithms: Set<StorageHashAlgorithm>,
    ): com.modrith.filesystem.FileHashResult {
        if (!Files.isRegularFile(path)) {
            throw InstallFailureException(
                InstallFailure(
                    InstallFailureCode.SOURCE_NOT_FOUND,
                    "Installation source file does not exist.",
                    recoverable = true,
                    path.fileName?.toString(),
                ),
            )
        }
        val parent = path.toAbsolutePath().normalize().parent
            ?: throw InstallFailureException(
                InstallFailure(
                    InstallFailureCode.SOURCE_NOT_FOUND,
                    "Installation source path has no parent.",
                    recoverable = false,
                ),
            )
        val provider = CacheProvider(parent)
        val result = fileHasher.hash(provider, StoragePath(path.fileName.toString()), algorithms)
        return when (result) {
            is StorageResult.Success -> result.value
            is StorageResult.Failure -> throw InstallFailureException(
                result.error.toInstallFailure(source = true),
            )
        }
    }

    private suspend fun copyAbsoluteSource(
        sourcePath: String,
        destination: StorageProvider,
        destinationPath: StoragePath,
    ) {
        val source = Paths.get(sourcePath).toAbsolutePath().normalize()
        val parent = source.parent
            ?: throw InstallFailureException(
                InstallFailure(
                    InstallFailureCode.SOURCE_NOT_FOUND,
                    "Installation source path has no parent.",
                    recoverable = false,
                ),
            )
        val provider = CacheProvider(parent)
        val result = copyEngine.copy(
            provider,
            StoragePath(source.fileName.toString()),
            destination,
            destinationPath,
        )
        if (result is StorageResult.Failure) {
            throw InstallFailureException(result.error.toInstallFailure(source = false))
        }
    }

    private suspend fun copyProviderFile(
        provider: StorageProvider,
        source: StoragePath,
        destination: StoragePath,
    ) {
        val result = copyEngine.copy(provider, source, provider, destination)
        if (result is StorageResult.Failure) {
            throw InstallFailureException(result.error.toInstallFailure(source = false))
        }
    }

    private suspend fun verifyProviderFile(
        provider: StorageProvider,
        path: StoragePath,
        expectedSize: Long?,
        expectedSha1: String?,
        expectedSha512: String,
    ) {
        val algorithms = buildSet {
            add(StorageHashAlgorithm.SHA512)
            if (expectedSha1 != null) add(StorageHashAlgorithm.SHA1)
        }
        val result = hashProviderFile(provider, path, algorithms)
        if (expectedSize != null && result.sizeBytes != expectedSize) {
            throw InstallFailureException(
                InstallFailure(
                    InstallFailureCode.SIZE_MISMATCH,
                    "Installed file size does not match its verified source.",
                    recoverable = true,
                    path.value,
                ),
            )
        }
        if (
            expectedSha1 != null &&
            result.hashes[StorageHashAlgorithm.SHA1] != expectedSha1.lowercase(Locale.ROOT)
        ) {
            throw hashMismatch(path.value)
        }
        if (
            result.hashes[StorageHashAlgorithm.SHA512] !=
            expectedSha512.lowercase(Locale.ROOT)
        ) {
            throw hashMismatch(path.value)
        }
    }

    private suspend fun hashProviderFile(
        provider: StorageProvider,
        path: StoragePath,
        algorithms: Set<StorageHashAlgorithm>,
    ): com.modrith.filesystem.FileHashResult = when (
        val result = fileHasher.hash(provider, path, algorithms)
    ) {
        is StorageResult.Success -> result.value
        is StorageResult.Failure -> throw InstallFailureException(
            result.error.toInstallFailure(source = false),
        )
    }

    private suspend fun deleteIfExists(
        provider: StorageProvider,
        path: StoragePath,
    ) {
        val exists = provider.exists(path).valueOrThrow()
        if (!exists) return
        val result = provider.delete(path)
        if (result is StorageResult.Failure) {
            throw InstallFailureException(result.error.toInstallFailure(source = false))
        }
    }

    private suspend fun save(transaction: InstallTransaction): InstallTransaction {
        return when (val result = repository.save(transaction)) {
            is InstallRepositoryResult.Success -> transaction
            is InstallRepositoryResult.Failure -> throw InstallFailureException(result.failure)
        }
    }

    private suspend fun latestTransaction(fallback: InstallTransaction): InstallTransaction =
        when (val result = repository.get(fallback.id)) {
            is InstallRepositoryResult.Success -> result.value
            is InstallRepositoryResult.Failure -> fallback
        }

    private fun operation(
        transactionId: String,
        sourceType: InstallSourceType,
        sourcePath: String,
        destinationPath: String,
        expectedSha1: String?,
        expectedSha512: String,
        expectedSize: Long,
    ): InstallFileOperation {
        val root = transactionRoot(transactionId).value
        return InstallFileOperation(
            sourceType = sourceType,
            sourcePath = sourcePath,
            destinationPath = destinationPath,
            expectedSha1 = expectedSha1,
            expectedSha512 = expectedSha512,
            expectedSizeBytes = expectedSize,
            stagePath = "$root/stage/$destinationPath",
            backupPath = "$root/backup/$destinationPath",
        )
    }

    private fun InstallTransaction.updateOperation(
        index: Int,
        operation: InstallFileOperation,
        checkpoint: InstallCheckpoint,
        event: String,
    ): InstallTransaction = copy(
        operations = operations.toMutableList().also { it[index] = operation }.toList(),
        checkpoint = checkpoint,
        log = log + logEntry(event, operation.destinationPath),
        updatedAtEpochMillis = clock.currentTimeMillis(),
    )

    private fun requiredWorkingSpace(operations: List<InstallFileOperation>): Long {
        var total = 0L
        operations.forEach { operation ->
            repeat(WORKING_SPACE_MULTIPLIER) {
                if (total > Long.MAX_VALUE - operation.expectedSizeBytes) return Long.MAX_VALUE
                total += operation.expectedSizeBytes
            }
        }
        return total
    }

    private fun completed(transaction: InstallTransaction): InstallSessionResult =
        InstallSessionResult(
            transactionId = transaction.id,
            status = InstallSessionStatus.COMPLETED,
            installedFiles = transaction.operations.size,
            installedBytes = transaction.operations.sumOf(InstallFileOperation::expectedSizeBytes),
        )

    private fun failed(
        transactionId: String,
        failure: InstallFailure,
    ): InstallSessionResult = InstallSessionResult(
        transactionId = transactionId,
        status = InstallSessionStatus.FAILED,
        installedFiles = 0,
        installedBytes = 0,
        failure = failure,
    )

    private fun unexpectedFailure(
        error: Throwable,
        fallbackMessage: String,
        step: String,
        path: String?,
    ): InstallFailure {
        val stepError = error as? InstallStepException
        val cause = stepError?.cause ?: error
        val failingStep = stepError?.step ?: step
        val failingPath = stepError?.path ?: path
        val frame = cause.stackTrace.firstOrNull {
            it.className.startsWith("com.modrith.")
        } ?: cause.stackTrace.firstOrNull()
        val details = if (includeExceptionDetails) {
            buildMap {
                put(
                    "exceptionClass",
                    cause::class.qualifiedName ?: cause::class.simpleName.orEmpty(),
                )
                put("exceptionMessage", cause.message.orEmpty())
                put("failingMethod", frame?.methodName.orEmpty())
                put("failingFile", frame?.fileName.orEmpty())
                put("failingLine", frame?.lineNumber?.toString().orEmpty())
                put("failingStep", failingStep)
                failingPath?.let { put("failingPath", it) }
                put("stackTrace", cause.stackTraceToString())
            }
        } else {
            emptyMap()
        }
        val message = if (includeExceptionDetails) {
            buildString {
                append(cause::class.simpleName ?: "Throwable")
                cause.message?.takeIf(String::isNotBlank)?.let {
                    append(": ")
                    append(it)
                }
                append(" in ")
                append(frame?.methodName ?: failingStep)
                frame?.fileName?.let {
                    append(" (")
                    append(it)
                    if (frame.lineNumber > 0) {
                        append(':')
                        append(frame.lineNumber)
                    }
                    append(')')
                }
                failingPath?.let {
                    append(" path=")
                    append(it)
                }
            }
        } else {
            fallbackMessage
        }
        return InstallFailure(
            InstallFailureCode.INTERNAL,
            message,
            recoverable = false,
            path = failingPath,
            details = details,
        )
    }

    private fun stepAttributes(
        transactionId: String,
        step: String,
        path: String? = null,
        attributes: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = buildMap {
        put("transactionId", transactionId)
        put("step", step)
        path?.let { put("path", it) }
        putAll(attributes)
    }

    private fun logEntry(
        event: String,
        path: String? = null,
        detail: String? = null,
    ) = InstallTransactionLogEntry(clock.currentTimeMillis(), event, path, detail)

    private fun emit(
        listener: InstallProgressListener,
        transactionId: String,
        phase: InstallProgressPhase,
        path: String?,
        completedFiles: Int,
        totalFiles: Int,
        processedBytes: Long,
        totalBytes: Long?,
    ) {
        runCatching {
            listener.onProgress(
                InstallProgress(
                    transactionId,
                    phase,
                    path,
                    completedFiles,
                    totalFiles,
                    processedBytes,
                    totalBytes,
                ),
            )
        }
    }

    private fun StorageError.toInstallFailure(source: Boolean): InstallFailure =
        InstallFailure(
            code = when (code) {
                StorageErrorCode.READ_PERMISSION_DENIED,
                StorageErrorCode.WRITE_PERMISSION_DENIED,
                StorageErrorCode.PERSIST_PERMISSION_FAILED,
                -> InstallFailureCode.PERMISSION_DENIED
                StorageErrorCode.NOT_FOUND -> if (source) {
                    InstallFailureCode.SOURCE_NOT_FOUND
                } else {
                    InstallFailureCode.DESTINATION_IO
                }
                else -> InstallFailureCode.DESTINATION_IO
            },
            message = message,
            recoverable = recoverable,
            path = path?.value,
        )

    private fun <T> StorageResult<T>.valueOrThrow(): T = when (this) {
        is StorageResult.Success -> value
        is StorageResult.Failure -> throw InstallFailureException(
            error.toInstallFailure(source = false),
        )
    }

    private fun InstallFailure.persisted() = PersistedInstallFailure(
        code = code.name,
        message = message,
        recoverable = recoverable,
        path = path,
    )

    private fun PersistedInstallFailure.toFailure() = InstallFailure(
        code = runCatching { InstallFailureCode.valueOf(code) }
            .getOrDefault(InstallFailureCode.INTERNAL),
        message = message,
        recoverable = recoverable,
        path = path,
    )

    private fun hashMismatch(path: String) = InstallFailureException(
        InstallFailure(
            InstallFailureCode.HASH_MISMATCH,
            "File hash verification failed before installation.",
            recoverable = true,
            path,
        ),
    )

    private fun rollbackFailure() = InstallFailure(
        InstallFailureCode.ROLLBACK_FAILED,
        "The previous destination state could not be fully restored.",
        recoverable = false,
    )

    private fun cancelledFailure() = InstallFailure(
        InstallFailureCode.CANCELLED,
        "Installation was cancelled.",
        recoverable = true,
    )

    private fun transactionRoot(transactionId: String) =
        StoragePath(".modrith-installer/work/$transactionId")

    private class InstallFailureException(
        val failure: InstallFailure,
    ) : RuntimeException(failure.message)

    private class InstallStepException(
        val step: String,
        val path: String?,
        cause: Throwable,
    ) : RuntimeException(cause.message, cause)

    private companion object {
        const val WORKING_SPACE_MULTIPLIER = 2
    }
}

fun interface InstallClock {
    fun currentTimeMillis(): Long
}

object SystemInstallClock : InstallClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
