package com.modrith.filesystem

class PathResolver(
    private val maxPathLength: Int = 4_096,
    private val maxSegmentLength: Int = 255,
) {
    fun resolve(rawPath: String): StorageResult<StoragePath> {
        if (rawPath.isEmpty()) return StorageResult.Success(StoragePath.ROOT)
        if (
            rawPath.isBlank() ||
            rawPath.length > maxPathLength ||
            rawPath.startsWith('/') ||
            rawPath.endsWith('/') ||
            '\\' in rawPath ||
            '\u0000' in rawPath ||
            DrivePrefix.matches(rawPath)
        ) {
            return invalid(rawPath)
        }
        val segments = rawPath.split('/')
        if (
            segments.any {
                it.isBlank() ||
                    it == "." ||
                    it == ".." ||
                    it.length > maxSegmentLength
            }
        ) {
            return invalid(rawPath)
        }
        return StorageResult.Success(StoragePath(segments.joinToString("/")))
    }

    fun child(
        parent: StoragePath,
        childName: String,
    ): StorageResult<StoragePath> {
        val child = resolve(childName)
        if (child !is StorageResult.Success || child.value.isRoot || '/' in child.value.value) {
            return invalid(childName)
        }
        return StorageResult.Success(
            if (parent.isRoot) child.value else StoragePath("${parent.value}/${child.value.value}"),
        )
    }

    fun isWithin(
        root: StoragePath,
        candidate: StoragePath,
    ): Boolean = root.isRoot ||
        candidate == root ||
        candidate.value.startsWith("${root.value}/")

    private fun invalid(rawPath: String) = StorageResult.Failure(
        StorageError(
            code = StorageErrorCode.INVALID_PATH,
            message = "Storage path is not a safe relative path.",
            path = rawPath.takeIf(String::isNotEmpty)?.let(::StoragePath),
            recoverable = false,
        ),
    )

    private companion object {
        val DrivePrefix = Regex("^[A-Za-z]:.*")
    }
}
