package com.modrith.filesystem

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesystemEngineTest {
    @Test
    fun pathResolverAcceptsSafeRelativePathsAndRejectsTraversal() {
        val resolver = PathResolver()

        assertEquals("mods/example.jar", resolver.resolve("mods/example.jar").success().value)
        assertEquals(StoragePath.ROOT, resolver.resolve("").success())
        assertEquals(StorageErrorCode.INVALID_PATH, resolver.resolve("../outside").failure().code)
        assertEquals(StorageErrorCode.INVALID_PATH, resolver.resolve("/absolute").failure().code)
        assertEquals(StorageErrorCode.INVALID_PATH, resolver.resolve("a\\b").failure().code)
        assertEquals(StorageErrorCode.INVALID_PATH, resolver.resolve("C:escape").failure().code)
        assertEquals(StorageErrorCode.INVALID_PATH, resolver.resolve("a//b").failure().code)
    }

    @Test
    fun cacheProviderSupportsDirectoriesBufferedStreamsExistenceAndAtomicReplace() =
        withTempDirectories(1) { (root) ->
            val provider = CacheProvider(root)
            val directory = StoragePath("instances/example")
            val file = StoragePath("instances/example/options.txt")

            assertEquals(StorageEntryType.DIRECTORY, provider.createDirectory(directory).success().type)
            provider.openOutput(file).success().use { it.write("old".toByteArray()) }
            assertTrue(provider.exists(file).success())
            assertEquals("old", provider.openInput(file).success().bufferedReader().use { it.readText() })

            val replacement = provider.replaceFile(file, "text/plain") {
                it.write("new-value".toByteArray())
            }.success()

            assertTrue(
                replacement.guarantee == ReplaceGuarantee.ATOMIC ||
                    replacement.guarantee == ReplaceGuarantee.BEST_EFFORT,
            )
            assertEquals(
                "new-value",
                provider.openInput(file).success().bufferedReader().use { it.readText() },
            )
            assertEquals(listOf("options.txt"), provider.list(directory).success().map { it.name })
        }

    @Test
    fun fileHasherCalculatesSha1Sha256AndSha512WithProgress() =
        withTempDirectories(1) { (root) ->
            val provider = CacheProvider(root)
            val path = StoragePath("artifact.bin")
            val content = "hash this content".toByteArray()
            provider.replaceFile(path) { it.write(content) }.success()
            val progress = mutableListOf<StorageProgress>()

            val result = FileHasher(bufferSize = 4).hash(
                provider,
                path,
                StorageHashAlgorithm.entries.toSet(),
            ) { update -> progress += update }.success()

            assertEquals(content.size.toLong(), result.sizeBytes)
            StorageHashAlgorithm.entries.forEach { algorithm ->
                assertEquals(content.digest(algorithm.jcaName), result.hashes[algorithm])
            }
            assertTrue(progress.isNotEmpty())
            assertEquals(content.size.toLong(), progress.last().processedBytes)
        }

    @Test
    fun directoryWalkerAndCopyEngineCopyCompleteTreeWithProgress() =
        withTempDirectories(2) { (sourceRoot, destinationRoot) ->
            val source = CacheProvider(sourceRoot)
            val destination = CacheProvider(destinationRoot)
            source.createDirectory(StoragePath("pack/config")).success()
            source.replaceFile(StoragePath("pack/config/a.json")) {
                it.write("""{"a":1}""".toByteArray())
            }.success()
            source.replaceFile(StoragePath("pack/options.txt")) {
                it.write("options".toByteArray())
            }.success()

            val walked = DirectoryWalker().walk(
                source,
                StoragePath("pack"),
                includeRoot = false,
            ).success()
            assertEquals(
                listOf("pack/config", "pack/options.txt", "pack/config/a.json"),
                walked.map { it.path.value },
            )

            val progress = mutableListOf<StorageProgress>()
            val summary = CopyEngine().copy(
                source,
                StoragePath("pack"),
                destination,
                StoragePath("copied"),
            ) { update -> progress += update }.success()

            assertEquals(2, summary.copiedFiles)
            assertEquals(2, summary.copiedDirectories)
            assertEquals(
                """{"a":1}""",
                destination.openInput(StoragePath("copied/config/a.json"))
                    .success()
                    .bufferedReader()
                    .use { it.readText() },
            )
            assertEquals(
                "options",
                destination.openInput(StoragePath("copied/options.txt"))
                    .success()
                    .bufferedReader()
                    .use { it.readText() },
            )
            assertTrue(progress.any { it.completedFiles == 2 })
        }

    @Test
    fun recursiveDeleteRemovesTreeAndReportsBytes() = withTempDirectories(1) { (root) ->
        val provider = CacheProvider(root)
        val content = "delete-me".toByteArray()
        provider.replaceFile(StoragePath("tree/a/b.txt")) { it.write(content) }.success()
        provider.replaceFile(StoragePath("tree/c.txt")) { it.write(content) }.success()
        val progress = mutableListOf<StorageProgress>()

        val result = provider.delete(
            StoragePath("tree"),
            recursive = true,
        ) { update -> progress += update }.success()

        assertEquals(2, result.deletedFiles)
        assertEquals(2, result.deletedDirectories)
        assertEquals(content.size.toLong() * 2, result.deletedBytes)
        assertFalse(provider.exists(StoragePath("tree")).success())
        assertTrue(progress.isNotEmpty())
    }

    @Test
    fun cleanupDeletesOldestUnprotectedFilesUntilBudgetIsMet() =
        withTempDirectories(1) { (root) ->
            val provider = CacheProvider(root)
            val old = StoragePath("old.bin")
            val protected = StoragePath("protected.bin")
            val recent = StoragePath("recent.bin")
            provider.replaceFile(old) { it.write(ByteArray(5) { 1 }) }.success()
            provider.replaceFile(protected) { it.write(ByteArray(5) { 2 }) }.success()
            provider.replaceFile(recent) { it.write(ByteArray(5) { 3 }) }.success()
            Files.setLastModifiedTime(root.resolve("old.bin"), java.nio.file.attribute.FileTime.fromMillis(1))
            Files.setLastModifiedTime(
                root.resolve("protected.bin"),
                java.nio.file.attribute.FileTime.fromMillis(2),
            )
            Files.setLastModifiedTime(
                root.resolve("recent.bin"),
                java.nio.file.attribute.FileTime.fromMillis(3),
            )

            val result = provider.cleanup(
                CacheCleanupPolicy(
                    maxBytes = 10,
                    protectedPaths = setOf(protected),
                ),
            ).success()

            assertEquals(15L, result.bytesBefore)
            assertEquals(10L, result.bytesAfter)
            assertFalse(provider.exists(old).success())
            assertTrue(provider.exists(protected).success())
            assertTrue(provider.exists(recent).success())
        }

    @Test
    fun cacheProviderRejectsSymbolicLinkTraversal() = withTempDirectories(2) { (root, outside) ->
        val outsideFile = outside.resolve("secret.txt")
        Files.write(outsideFile, "secret".toByteArray())
        val link = root.resolve("link")
        val symlinkCreated = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        if (!symlinkCreated) return@withTempDirectories

        val provider = CacheProvider(root)
        val result = provider.openInput(StoragePath("link/secret.txt"))

        assertEquals(StorageErrorCode.INVALID_PATH, result.failure().code)
        assertArrayEquals("secret".toByteArray(), Files.readAllBytes(outsideFile))
    }

    private fun ByteArray.digest(algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(this).joinToString("") { "%02x".format(it) }

    private fun <T> withTempDirectories(
        count: Int,
        block: suspend (List<Path>) -> T,
    ): T = runBlocking {
        val roots = List(count) { Files.createTempDirectory("filesystem-test") }
        try {
            block(roots)
        } finally {
            roots.forEach { root ->
                if (Files.exists(root)) {
                    Files.walk(root).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    }
                }
            }
        }
    }

    private fun <T> StorageResult<T>.success(): T =
        (this as? StorageResult.Success<T>)?.value
            ?: error("Expected success, got $this")

    private fun StorageResult<*>.failure(): StorageError =
        (this as? StorageResult.Failure)?.error
            ?: error("Expected failure, got $this")
}
