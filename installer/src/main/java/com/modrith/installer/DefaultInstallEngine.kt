package com.modrith.installer

import com.modrith.filesystem.StorageProvider
import com.modrith.models.DownloadSession
import com.modrith.models.ResolvedInstallPlan
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class DefaultInstallEngine(
    repository: InstallRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    diskSpaceChecker: InstallDiskSpaceChecker = InstallDiskSpaceChecker.UNKNOWN,
    logger: InstallLogger = JvmInstallLogger(),
    clock: InstallClock = SystemInstallClock,
    includeExceptionDetails: Boolean = false,
) : InstallEngine {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = InstallWorker(
        repository = repository,
        diskSpaceChecker = diskSpaceChecker,
        logger = logger,
        clock = clock,
        includeExceptionDetails = includeExceptionDetails,
    )

    override fun start(
        plan: ResolvedInstallPlan,
        downloadSession: DownloadSession,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ): InstallSession {
        val transactionId = UUID.randomUUID().toString()
        return session(transactionId) {
            worker.install(
                transactionId,
                plan,
                downloadSession,
                destination,
                progressListener,
            )
        }
    }

    override fun resume(
        transactionId: String,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ): InstallSession = session(transactionId) {
        worker.resume(transactionId, destination, progressListener)
    }

    private fun session(
        transactionId: String,
        block: suspend () -> InstallSessionResult,
    ): InstallSession {
        val cancelled = AtomicBoolean(false)
        val deferred = scope.async { block() }
        return DefaultInstallSession(transactionId, deferred, cancelled)
    }
}

private class DefaultInstallSession(
    override val id: String,
    private val deferred: Deferred<InstallSessionResult>,
    private val cancelled: AtomicBoolean,
) : InstallSession {
    override fun cancel() {
        cancelled.set(true)
        deferred.cancel(CancellationException("Install session cancelled."))
    }

    override suspend fun await(): InstallSessionResult = try {
        deferred.await()
    } catch (error: CancellationException) {
        if (!cancelled.get()) throw error
        InstallSessionResult(
            transactionId = id,
            status = InstallSessionStatus.CANCELLED,
            installedFiles = 0,
            installedBytes = 0,
            failure = InstallFailure(
                InstallFailureCode.CANCELLED,
                "Installation was cancelled and rollback was requested.",
                recoverable = true,
            ),
        )
    } catch (error: RuntimeException) {
        InstallSessionResult(
            transactionId = id,
            status = InstallSessionStatus.FAILED,
            installedFiles = 0,
            installedBytes = 0,
            failure = InstallFailure(
                InstallFailureCode.INTERNAL,
                "Install session failed unexpectedly.",
                recoverable = false,
            ),
        )
    }
}
