package com.modrith.launcher

import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageProvider

interface LauncherProvider {
    suspend fun inspect(
        storage: StorageProvider,
        root: StoragePath = StoragePath.ROOT,
    ): LauncherResult
}

interface LauncherScanner {
    suspend fun scan(
        storage: StorageProvider,
        root: StoragePath = StoragePath.ROOT,
    ): LauncherScanResult
}

interface LauncherValidator {
    fun validate(snapshot: LauncherScanSnapshot): LauncherResult
}

sealed interface LauncherScanResult {
    data class Success(
        val snapshot: LauncherScanSnapshot,
    ) : LauncherScanResult

    data class Failure(
        val errors: List<LauncherError>,
        val warnings: List<LauncherWarning> = emptyList(),
    ) : LauncherScanResult
}
