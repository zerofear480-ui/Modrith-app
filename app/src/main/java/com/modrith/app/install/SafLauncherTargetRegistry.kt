package com.modrith.app.install

import android.content.Context
import android.net.Uri
import com.modrith.filesystem.DocumentTreeProvider
import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.SubtreeStorageProvider
import com.modrith.launcher.LauncherInstance
import com.modrith.orchestrator.InstallError
import com.modrith.orchestrator.InstallErrorSource
import com.modrith.orchestrator.LauncherTarget
import com.modrith.orchestrator.LauncherTargetProvider
import com.modrith.orchestrator.LauncherTargetResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class SafLauncherTargetRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) : LauncherTargetProvider {
    private val preferences = context.getSharedPreferences(
        "launcher_targets",
        Context.MODE_PRIVATE,
    )

    fun remember(
        treeUri: String,
        launcherRoot: StoragePath,
        instances: List<LauncherInstance>,
    ) {
        preferences.edit().apply {
            instances.forEach { instance ->
                putString(instance.profileId, treeUri)
                putString(rootKey(instance.profileId), launcherRoot.value)
            }
        }.apply()
        Timber.tag(TAG).i(
            "launcher.discovery.target_remembered {selectedTreeUri=%s, launcherRoot=%s, instances=%d}",
            treeUri,
            launcherRoot.value.ifEmpty { "<selected-tree>" },
            instances.size,
        )
    }

    override suspend fun targetFor(instance: LauncherInstance): LauncherTargetResult {
        val rawUri = preferences.getString(instance.profileId, null)
            ?: return failure(
                "DESTINATION_NOT_SELECTED",
                "Select the launcher storage tree again.",
            )
        val provider = runCatching {
            DocumentTreeProvider(context, Uri.parse(rawUri))
        }.getOrElse {
            return failure(
                "DESTINATION_UNAVAILABLE",
                "The selected launcher storage tree is no longer available.",
            )
        }
        val permission = provider.permission()
        if (!permission.persisted || !permission.read || !permission.write) {
            return failure(
                "DESTINATION_PERMISSION_REVOKED",
                "Access to the launcher storage tree was revoked. Select it again.",
            )
        }
        val launcherRoot = StoragePath(
            preferences.getString(rootKey(instance.profileId), null).orEmpty(),
        )
        Timber.tag(TAG).i(
            "launcher.discovery.target_restored {selectedTreeUri=%s, launcherRoot=%s}",
            rawUri,
            launcherRoot.value.ifEmpty { "<selected-tree>" },
        )
        val targetStorage = if (launcherRoot.isRoot) {
            provider
        } else {
            SubtreeStorageProvider(provider, launcherRoot)
        }
        return LauncherTargetResult.Success(LauncherTarget(targetStorage))
    }

    private fun rootKey(profileId: String): String = "$profileId:launcher-root"

    private fun failure(
        code: String,
        message: String,
    ) = LauncherTargetResult.Failure(
        InstallError(
            source = InstallErrorSource.FILESYSTEM,
            code = code,
            message = message,
            recoverable = true,
        ),
    )

    private companion object {
        const val TAG = "LauncherDiscovery"
    }
}
