package com.modrith.orchestrator

import java.util.concurrent.ConcurrentHashMap

class InMemoryOrchestratorCheckpointRepository : OrchestratorCheckpointRepository {
    private val checkpoints = ConcurrentHashMap<String, OrchestratorCheckpoint>()

    override suspend fun get(sessionId: String): OrchestratorCheckpoint? = checkpoints[sessionId]

    override suspend fun save(checkpoint: OrchestratorCheckpoint) {
        checkpoints[checkpoint.sessionId] = checkpoint
    }
}
