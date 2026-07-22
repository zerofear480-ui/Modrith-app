package com.modrith.orchestrator

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InstallCoordinator(
    private val pipeline: InstallPipeline,
    private val checkpoints: OrchestratorCheckpointRepository,
    private val logger: InstallLogger,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val activeSessions = ConcurrentHashMap<String, DefaultOrchestratorSession>()

    fun start(request: InstallRequest): InstallSession {
        val sessionId = UUID.randomUUID().toString()
        val checkpoint = OrchestratorCheckpoint(
            sessionId = sessionId,
            request = request,
            phase = CheckpointPhase.CREATED,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        return launch(sessionId, checkpoint)
    }

    fun resume(sessionId: String): InstallSession {
        activeSessions[sessionId]?.let { return it }
        val session = newSession(sessionId)
        activeSessions[sessionId] = session
        session.attach(
            scope.launch {
                val checkpoint = runCatching { checkpoints.get(sessionId) }
                    .getOrElse { error ->
                        finish(session, coordinatorFailure(sessionId, error, null))
                        return@launch
                    }
                if (checkpoint == null) {
                    val error = InstallError(
                        source = InstallErrorSource.ORCHESTRATOR,
                        code = "CHECKPOINT_NOT_FOUND",
                        message = "The requested installation checkpoint does not exist.",
                        recoverable = false,
                    )
                    finish(
                        session,
                        InstallResult.Failure(
                            sessionId,
                            error,
                            logger.entries(sessionId).value,
                            checkpoint = null,
                        ),
                    )
                    return@launch
                }
                runPipeline(session, checkpoint)
            },
        )
        return session
    }

    private fun launch(
        sessionId: String,
        checkpoint: OrchestratorCheckpoint,
    ): InstallSession {
        val session = newSession(sessionId)
        activeSessions[sessionId] = session
        session.attach(
            scope.launch {
                runCatching { checkpoints.save(checkpoint) }
                    .getOrElse { error ->
                        finish(session, coordinatorFailure(sessionId, error, checkpoint))
                        return@launch
                    }
                runPipeline(session, checkpoint)
            },
        )
        return session
    }

    private suspend fun runPipeline(
        session: DefaultOrchestratorSession,
        checkpoint: OrchestratorCheckpoint,
    ) {
        val observer = object : PipelineObserver {
            override val cancellationRequested: Boolean
                get() = session.cancellationRequested

            override fun registerCancellation(action: (() -> Unit)?) {
                session.registerCancellation(action)
            }

            override fun update(
                progress: InstallProgress,
                checkpoint: OrchestratorCheckpoint,
                error: InstallError?,
            ) {
                session.update(progress, checkpoint, error)
            }
        }
        val result = try {
            pipeline.execute(checkpoint, observer)
        } catch (_: CancellationException) {
            InstallResult.Cancelled(
                session.id,
                logger.entries(session.id).value,
                checkpoints.get(session.id),
            )
        } catch (error: Throwable) {
            val structured = InstallError(
                source = InstallErrorSource.ORCHESTRATOR,
                code = "COORDINATOR_FAILURE",
                message = "The install coordinator failed unexpectedly.",
                recoverable = false,
                details = mapOf("type" to (error::class.simpleName ?: "unknown")),
            )
            InstallResult.Failure(
                session.id,
                structured,
                logger.entries(session.id).value,
                checkpoints.get(session.id),
            )
        }
        finish(session, result)
    }

    private fun finish(
        session: DefaultOrchestratorSession,
        result: InstallResult,
    ) {
        session.finish(result)
        activeSessions.remove(session.id, session)
    }

    private fun coordinatorFailure(
        sessionId: String,
        cause: Throwable,
        checkpoint: OrchestratorCheckpoint?,
    ): InstallResult.Failure {
        val error = InstallError(
            source = InstallErrorSource.ORCHESTRATOR,
            code = "CHECKPOINT_IO",
            message = "The orchestrator checkpoint could not be read or saved.",
            recoverable = true,
            details = mapOf("type" to (cause::class.simpleName ?: "unknown")),
        )
        logger.log(
            sessionId,
            InstallLogSource.ORCHESTRATOR,
            InstallLogLevel.ERROR,
            "install.checkpoint_failed",
            mapOf("type" to cause::class.simpleName),
            cause,
        )
        return InstallResult.Failure(
            sessionId,
            error,
            logger.entries(sessionId).value,
            checkpoint,
        )
    }

    private fun newSession(sessionId: String): DefaultOrchestratorSession =
        DefaultOrchestratorSession(
            id = sessionId,
            initialState = InstallState(
                sessionId = sessionId,
                status = InstallStatus.CREATED,
                progress = initialProgress(),
                checkpoint = null,
                error = null,
                logs = logger.entries(sessionId).value,
            ),
            logger = logger,
        )
}

internal class DefaultOrchestratorSession(
    override val id: String,
    initialState: InstallState,
    private val logger: InstallLogger,
) : InstallSession {
    private val mutableState = MutableStateFlow(initialState)
    private val result = CompletableDeferred<InstallResult>()
    private val cancellation = AtomicBoolean(false)
    private val activeCancellation = AtomicReference<(() -> Unit)?>(null)
    private val job = AtomicReference<Job?>(null)

    override val state: StateFlow<InstallState> = mutableState.asStateFlow()

    val cancellationRequested: Boolean
        get() = cancellation.get()

    fun attach(attachedJob: Job) {
        job.set(attachedJob)
        if (cancellation.get() && activeCancellation.get() == null) {
            attachedJob.cancel(CancellationException("Orchestrator session cancelled."))
        }
    }

    fun registerCancellation(action: (() -> Unit)?) {
        activeCancellation.set(action)
        if (action != null && cancellation.get()) {
            action()
        }
    }

    fun update(
        progress: InstallProgress,
        checkpoint: OrchestratorCheckpoint,
        error: InstallError?,
    ) {
        mutableState.update { current ->
            current.copy(
                status = when {
                    error != null -> InstallStatus.FAILED
                    progress.phase == InstallPhase.COMPLETED -> InstallStatus.COMPLETED
                    else -> InstallStatus.RUNNING
                },
                progress = progress,
                checkpoint = checkpoint,
                error = error,
                logs = logger.entries(id).value,
            )
        }
    }

    override fun cancel() {
        if (!cancellation.compareAndSet(false, true)) return
        val active = activeCancellation.get()
        if (active != null) {
            active()
        } else {
            job.get()?.cancel(CancellationException("Orchestrator session cancelled."))
        }
    }

    override suspend fun await(): InstallResult = result.await()

    fun finish(finalResult: InstallResult) {
        val checkpoint = finalResult.checkpoint
        val finalProgress = when (finalResult) {
            is InstallResult.Success -> mutableState.value.progress.copy(
                phase = InstallPhase.COMPLETED,
                percentage = 100.0,
                estimatedRemainingMillis = 0,
                currentSpeedBytesPerSecond = 0,
                statusMessage = "Installation completed.",
            )
            is InstallResult.Failure -> mutableState.value.progress.copy(
                currentSpeedBytesPerSecond = 0,
                estimatedRemainingMillis = null,
                statusMessage = finalResult.error.message,
            )
            is InstallResult.Cancelled -> mutableState.value.progress.copy(
                currentSpeedBytesPerSecond = 0,
                estimatedRemainingMillis = null,
                statusMessage = "Installation cancelled.",
            )
        }
        mutableState.value = InstallState(
            sessionId = id,
            status = when (finalResult) {
                is InstallResult.Success -> InstallStatus.COMPLETED
                is InstallResult.Failure -> InstallStatus.FAILED
                is InstallResult.Cancelled -> InstallStatus.CANCELLED
            },
            progress = finalProgress,
            checkpoint = checkpoint,
            error = (finalResult as? InstallResult.Failure)?.error,
            logs = finalResult.logs,
        )
        result.complete(finalResult)
    }
}
