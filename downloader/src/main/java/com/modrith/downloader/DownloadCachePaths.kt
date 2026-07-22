package com.modrith.downloader

import com.modrith.models.ResolvedDownloadEntry
import java.nio.file.Path

internal data class DownloadCachePaths(
    val finalPath: Path,
    val temporaryPath: Path,
)

internal fun ResolvedDownloadEntry.cachePaths(cacheRoot: Path): DownloadCachePaths {
    val algorithm = preferredHash.algorithm.name.lowercase()
    val hash = preferredHash.value.lowercase()
    val directory = cacheRoot.resolve("artifacts").resolve(algorithm).resolve(hash.take(2))
    val finalPath = directory.resolve(hash)
    return DownloadCachePaths(
        finalPath = finalPath,
        temporaryPath = finalPath.resolveSibling("$hash.part"),
    )
}
