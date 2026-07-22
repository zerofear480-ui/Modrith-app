package com.modrith.orchestrator

import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageProvider
import com.modrith.launcher.LauncherInstance
import com.modrith.models.InstallPlan
import com.modrith.models.MrPackParseResult
import com.modrith.models.ResolvedInstallPlan
import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow

interface InstallManager {
    fun install(
        source: MrPackSource,
        launcherInstance: LauncherInstance,
        cacheDirectory: Path,
        downloadConfiguration: com.modrith.models.DownloadConfiguration =
            com.modrith.models.DownloadConfiguration(),
    ): InstallSession

    fun resume(sessionId: String): InstallSession
}

interface InstallSession {
    val id: String
    val state: StateFlow<InstallState>

    fun cancel()

    suspend fun await(): InstallResult
}

fun interface OrchestratorParser {
    suspend fun parse(
        archive: Path,
        cacheDirectory: Path,
        sessionId: String,
    ): MrPackParseResult
}

fun interface OrchestratorResolver {
    suspend fun resolve(plan: InstallPlan): ResolvedInstallPlan
}

data class LauncherTarget(
    val storage: StorageProvider,
    val root: StoragePath = StoragePath.ROOT,
)

sealed interface LauncherTargetResult {
    data class Success(
        val target: LauncherTarget,
    ) : LauncherTargetResult

    data class Failure(
        val error: InstallError,
    ) : LauncherTargetResult
}

fun interface LauncherTargetProvider {
    suspend fun targetFor(instance: LauncherInstance): LauncherTargetResult
}

data class ResolvedArchiveSource(
    val path: Path,
    val deleteAfterParsing: Boolean,
)

sealed interface ArchiveSourceResult {
    data class Success(
        val source: ResolvedArchiveSource,
    ) : ArchiveSourceResult

    data class Failure(
        val error: InstallError,
    ) : ArchiveSourceResult
}

fun interface MrPackSourceResolver {
    suspend fun resolve(
        source: MrPackSource,
        cacheDirectory: Path,
        sessionId: String,
    ): ArchiveSourceResult
}

interface OrchestratorCheckpointRepository {
    suspend fun get(sessionId: String): OrchestratorCheckpoint?

    suspend fun save(checkpoint: OrchestratorCheckpoint)
}
