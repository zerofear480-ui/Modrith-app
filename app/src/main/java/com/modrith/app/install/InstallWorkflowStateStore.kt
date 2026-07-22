package com.modrith.app.install

import android.content.Context
import com.modrith.launcher.LauncherInstance
import com.modrith.ui.install.InstallErrorUi
import com.modrith.ui.install.InstallLogUi
import com.modrith.ui.install.InstallProgressUi
import com.modrith.ui.install.InstallResultUi
import com.modrith.ui.install.InstallScreen
import com.modrith.ui.install.InstallUiState
import com.modrith.ui.install.LauncherInstanceUi
import com.modrith.ui.install.LauncherSummary
import com.modrith.ui.install.PackSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class InstallWorkflowStateStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(
        "install_workflow_state",
        Context.MODE_PRIVATE,
    )

    fun save(
        state: InstallUiState,
        sourceUri: String?,
        launcherTreeUri: String?,
        launcherInstances: List<LauncherInstance>,
    ) {
        val json = JSONObject().apply {
            put("screen", state.screen.name)
            put("busy", state.busy)
            put("sourceUri", sourceUri)
            put("launcherTreeUri", launcherTreeUri)
            put("selectedLauncherProfileId", state.selectedLauncherProfileId)
            put("resumableSessionId", state.resumableSessionId)
            put("pack", state.pack?.toJson())
            put("launcher", state.launcher?.toJson())
            put("progress", state.progress.toJson())
            put("logs", JSONArray(state.logs.map { log -> log.toJson() }))
            put("result", state.result?.toJson())
            put("error", state.error?.toJson())
            put(
                "launcherInstances",
                JSONArray(launcherInstances.map { instance -> instance.toJson() }),
            )
        }
        preferences.edit().putString(KEY_STATE, json.toString()).apply()
    }

    fun setActiveSession(sessionId: String?) {
        preferences.edit().putString(KEY_ACTIVE_SESSION, sessionId).apply()
    }

    fun load(): RestoredInstallWorkflow {
        val encoded = preferences.getString(KEY_STATE, null)
        val state = encoded?.let { runCatching { decodeState(JSONObject(it)) }.getOrNull() }
            ?: InstallUiState()
        val json = encoded?.let { runCatching { JSONObject(it) }.getOrNull() }
        return RestoredInstallWorkflow(
            state = state,
            sourceUri = json?.nullableString("sourceUri"),
            launcherTreeUri = json?.nullableString("launcherTreeUri"),
            launcherInstances = json?.optJSONArray("launcherInstances")
                ?.objects()
                ?.map(::decodeLauncherInstance)
                .orEmpty(),
            activeSessionId = preferences.getString(KEY_ACTIVE_SESSION, null),
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun decodeState(json: JSONObject): InstallUiState = InstallUiState(
        screen = InstallScreen.valueOf(json.optString("screen", InstallScreen.HOME.name)),
        busy = json.optBoolean("busy"),
        pack = json.optJSONObject("pack")?.let(::decodePack),
        launcher = json.optJSONObject("launcher")?.let(::decodeLauncher),
        selectedLauncherProfileId = json.nullableString("selectedLauncherProfileId"),
        progress = json.optJSONObject("progress")?.let(::decodeProgress) ?: InstallProgressUi(),
        logs = json.optJSONArray("logs")?.objects()?.map(::decodeLog).orEmpty(),
        result = json.optJSONObject("result")?.let(::decodeResult),
        error = json.optJSONObject("error")?.let(::decodeError),
        resumableSessionId = json.nullableString("resumableSessionId"),
    )

    private fun PackSummary.toJson() = JSONObject().apply {
        put("displayName", displayName)
        put("name", name)
        put("versionId", versionId)
        put("minecraftVersion", minecraftVersion)
        put("loader", loader)
        put("loaderVersion", loaderVersion)
        put("modCount", modCount)
        put("totalFiles", totalFiles)
        put("totalDownloadBytes", totalDownloadBytes)
        put("warnings", JSONArray(warnings))
    }

    private fun decodePack(json: JSONObject) = PackSummary(
        displayName = json.getString("displayName"),
        name = json.getString("name"),
        versionId = json.getString("versionId"),
        minecraftVersion = json.getString("minecraftVersion"),
        loader = json.getString("loader"),
        loaderVersion = json.getString("loaderVersion"),
        modCount = json.getInt("modCount"),
        totalFiles = json.getInt("totalFiles"),
        totalDownloadBytes = json.optLongOrNull("totalDownloadBytes"),
        warnings = json.optJSONArray("warnings")?.strings().orEmpty(),
    )

    private fun LauncherSummary.toJson() = JSONObject().apply {
        put("displayName", displayName)
        put("instances", JSONArray(instances.map { instance -> instance.toJson() }))
        put("warnings", JSONArray(warnings))
    }

    private fun LauncherInstanceUi.toJson() = JSONObject().apply {
        put("profileId", profileId)
        put("name", name)
        put("versionId", versionId)
    }

    private fun decodeLauncher(json: JSONObject) = LauncherSummary(
        displayName = json.getString("displayName"),
        instances = json.getJSONArray("instances").objects().map {
            LauncherInstanceUi(
                profileId = it.getString("profileId"),
                name = it.getString("name"),
                versionId = it.getString("versionId"),
            )
        },
        warnings = json.optJSONArray("warnings")?.strings().orEmpty(),
    )

    private fun InstallProgressUi.toJson() = JSONObject().apply {
        put("title", title)
        put("message", message)
        put("percentage", percentage.toDouble())
        put("filesCompleted", filesCompleted)
        put("totalFiles", totalFiles)
        put("downloadedBytes", downloadedBytes)
        put("totalBytes", totalBytes)
        put("bytesPerSecond", bytesPerSecond)
        put("estimatedRemainingMillis", estimatedRemainingMillis)
        put("currentFile", currentFile)
    }

    private fun decodeProgress(json: JSONObject) = InstallProgressUi(
        title = json.getString("title"),
        message = json.getString("message"),
        percentage = json.getDouble("percentage").toFloat(),
        filesCompleted = json.getInt("filesCompleted"),
        totalFiles = json.getInt("totalFiles"),
        downloadedBytes = json.getLong("downloadedBytes"),
        totalBytes = json.optLongOrNull("totalBytes"),
        bytesPerSecond = json.getLong("bytesPerSecond"),
        estimatedRemainingMillis = json.optLongOrNull("estimatedRemainingMillis"),
        currentFile = json.nullableString("currentFile"),
    )

    private fun InstallLogUi.toJson() = JSONObject().apply {
        put("timestampEpochMillis", timestampEpochMillis)
        put("level", level)
        put("source", source)
        put("message", message)
    }

    private fun decodeLog(json: JSONObject) = InstallLogUi(
        timestampEpochMillis = json.getLong("timestampEpochMillis"),
        level = json.getString("level"),
        source = json.getString("source"),
        message = json.getString("message"),
    )

    private fun InstallResultUi.toJson() = JSONObject().apply {
        put("packName", packName)
        put("launcherInstanceName", launcherInstanceName)
        put("installedFiles", installedFiles)
        put("installedBytes", installedBytes)
    }

    private fun decodeResult(json: JSONObject) = InstallResultUi(
        packName = json.getString("packName"),
        launcherInstanceName = json.getString("launcherInstanceName"),
        installedFiles = json.getInt("installedFiles"),
        installedBytes = json.getLong("installedBytes"),
    )

    private fun InstallErrorUi.toJson() = JSONObject().apply {
        put("title", title)
        put("message", message)
        put("action", action)
        put("recoverable", recoverable)
        put("code", code)
    }

    private fun decodeError(json: JSONObject) = InstallErrorUi(
        title = json.getString("title"),
        message = json.getString("message"),
        action = json.getString("action"),
        recoverable = json.getBoolean("recoverable"),
        code = json.getString("code"),
    )

    private fun LauncherInstance.toJson() = JSONObject().apply {
        put("profileId", profileId)
        put("name", name)
        put("lastVersionId", lastVersionId)
        put("gameDirectory", gameDirectory)
        put("profileType", profileType)
    }

    private fun decodeLauncherInstance(json: JSONObject) = LauncherInstance(
        profileId = json.getString("profileId"),
        name = json.getString("name"),
        lastVersionId = json.getString("lastVersionId"),
        gameDirectory = json.nullableString("gameDirectory"),
        profileType = json.nullableString("profileType"),
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else getLong(key)

    private fun JSONArray.objects(): List<JSONObject> =
        List(length()) { index -> getJSONObject(index) }

    private fun JSONArray.strings(): List<String> =
        List(length()) { index -> getString(index) }

    private companion object {
        const val KEY_STATE = "state"
        const val KEY_ACTIVE_SESSION = "active_session"
    }
}

data class RestoredInstallWorkflow(
    val state: InstallUiState,
    val sourceUri: String?,
    val launcherTreeUri: String?,
    val launcherInstances: List<LauncherInstance>,
    val activeSessionId: String?,
)
