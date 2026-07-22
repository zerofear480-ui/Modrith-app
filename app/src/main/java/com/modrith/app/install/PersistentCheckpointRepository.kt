package com.modrith.app.install

import android.content.Context
import com.modrith.launcher.LauncherInstance
import com.modrith.models.DownloadConfiguration
import com.modrith.orchestrator.CheckpointPhase
import com.modrith.orchestrator.InstallRequest
import com.modrith.orchestrator.MrPackSource
import com.modrith.orchestrator.OrchestratorCheckpoint
import com.modrith.orchestrator.OrchestratorCheckpointRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class PersistentCheckpointRepository @Inject constructor(
    @ApplicationContext context: Context,
) : OrchestratorCheckpointRepository {
    private val preferences = context.getSharedPreferences(
        "orchestrator_checkpoints",
        Context.MODE_PRIVATE,
    )
    private val inMemory = ConcurrentHashMap<String, OrchestratorCheckpoint>()

    override suspend fun get(sessionId: String): OrchestratorCheckpoint? {
        inMemory[sessionId]?.let { return it }
        val encoded = preferences.getString(sessionId, null) ?: return null
        return runCatching { decode(JSONObject(encoded)) }.getOrNull()
    }

    override suspend fun save(checkpoint: OrchestratorCheckpoint) {
        inMemory[checkpoint.sessionId] = checkpoint
        if (checkpoint.phase != CheckpointPhase.INSTALLING ||
            checkpoint.installerTransactionId != null
        ) {
            preferences.edit()
                .putString(checkpoint.sessionId, encode(checkpoint).toString())
                .apply()
        }
    }

    private fun encode(checkpoint: OrchestratorCheckpoint): JSONObject = JSONObject().apply {
        put("sessionId", checkpoint.sessionId)
        put("phase", checkpoint.phase.name)
        put("source", encodeSource(checkpoint.request.source))
        put("launcher", encodeLauncher(checkpoint.request.launcherInstance))
        put("cacheDirectory", checkpoint.request.cacheDirectory.toString())
        put("downloadConfiguration", encodeConfiguration(checkpoint.request.downloadConfiguration))
        put("installerTransactionId", checkpoint.installerTransactionId)
        put("installedFiles", checkpoint.installedFiles)
        put("installedBytes", checkpoint.installedBytes)
        put("updatedAtEpochMillis", checkpoint.updatedAtEpochMillis)
    }

    private fun decode(json: JSONObject): OrchestratorCheckpoint {
        val request = InstallRequest(
            source = decodeSource(json.getJSONObject("source")),
            launcherInstance = decodeLauncher(json.getJSONObject("launcher")),
            cacheDirectory = File(json.getString("cacheDirectory")).toPath(),
            downloadConfiguration = decodeConfiguration(
                json.getJSONObject("downloadConfiguration"),
            ),
        )
        return OrchestratorCheckpoint(
            sessionId = json.getString("sessionId"),
            request = request,
            phase = CheckpointPhase.valueOf(json.getString("phase")),
            archivePath = null,
            deleteArchiveAfterParsing = false,
            parsedPlan = null,
            resolvedPlan = null,
            installerTransactionId = json.optString("installerTransactionId")
                .takeIf(String::isNotBlank),
            installedFiles = json.optInt("installedFiles"),
            installedBytes = json.optLong("installedBytes"),
            updatedAtEpochMillis = json.getLong("updatedAtEpochMillis"),
        )
    }

    private fun encodeSource(source: MrPackSource): JSONObject = JSONObject().apply {
        when (source) {
            is MrPackSource.FileSource -> {
                put("type", "file")
                put("value", source.file.absolutePath)
            }
            is MrPackSource.UriSource -> {
                put("type", "uri")
                put("value", source.uri)
            }
        }
    }

    private fun decodeSource(json: JSONObject): MrPackSource =
        when (json.getString("type")) {
            "file" -> MrPackSource.FileSource(java.io.File(json.getString("value")))
            else -> MrPackSource.UriSource(json.getString("value"))
        }

    private fun encodeLauncher(instance: LauncherInstance): JSONObject = JSONObject().apply {
        put("profileId", instance.profileId)
        put("name", instance.name)
        put("lastVersionId", instance.lastVersionId)
        put("gameDirectory", instance.gameDirectory)
        put("profileType", instance.profileType)
    }

    private fun decodeLauncher(json: JSONObject): LauncherInstance = LauncherInstance(
        profileId = json.getString("profileId"),
        name = json.getString("name"),
        lastVersionId = json.getString("lastVersionId"),
        gameDirectory = json.optString("gameDirectory").takeIf(String::isNotBlank),
        profileType = json.optString("profileType").takeIf(String::isNotBlank),
    )

    private fun encodeConfiguration(configuration: DownloadConfiguration): JSONObject =
        JSONObject().apply {
            put("workerCount", configuration.workerCount)
            put("maxRetries", configuration.maxRetries)
            put("initialBackoffMillis", configuration.initialBackoffMillis)
            put("maxBackoffMillis", configuration.maxBackoffMillis)
            put("connectTimeoutMillis", configuration.connectTimeoutMillis)
            put("readTimeoutMillis", configuration.readTimeoutMillis)
            put("callTimeoutMillis", configuration.callTimeoutMillis)
            put("progressIntervalMillis", configuration.progressIntervalMillis)
            put("diskSpaceReserveBytes", configuration.diskSpaceReserveBytes)
            put("userAgent", configuration.userAgent)
        }

    private fun decodeConfiguration(json: JSONObject): DownloadConfiguration =
        DownloadConfiguration(
            workerCount = json.getInt("workerCount"),
            maxRetries = json.getInt("maxRetries"),
            initialBackoffMillis = json.getLong("initialBackoffMillis"),
            maxBackoffMillis = json.getLong("maxBackoffMillis"),
            connectTimeoutMillis = json.getLong("connectTimeoutMillis"),
            readTimeoutMillis = json.getLong("readTimeoutMillis"),
            callTimeoutMillis = json.getLong("callTimeoutMillis"),
            progressIntervalMillis = json.getLong("progressIntervalMillis"),
            diskSpaceReserveBytes = json.getLong("diskSpaceReserveBytes"),
            userAgent = json.getString("userAgent"),
        )
}
