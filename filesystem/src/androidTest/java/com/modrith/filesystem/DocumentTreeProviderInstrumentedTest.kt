package com.modrith.filesystem

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentTreeProviderInstrumentedTest {
    @Test
    fun documentTreeSupportsSafOperationsAndStructuredPermissionState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)
        val provider = DocumentTreeProvider(context, treeUri)
        val testRoot = StoragePath("test-${UUID.randomUUID()}")
        val file = StoragePath("${testRoot.value}/config/options.txt")
        val content = "fov:90".toByteArray()

        try {
            assertFalse(provider.permission().persisted)
            val persistenceResult = provider.persistPermission(read = true, write = true)
            assertTrue(
                persistenceResult is StorageResult.Success ||
                    persistenceResult.failure().code == StorageErrorCode.PERSIST_PERMISSION_FAILED,
            )
            assertEquals(
                StorageEntryType.DIRECTORY,
                provider.createDirectory(StoragePath("${testRoot.value}/config")).success().type,
            )

            val firstReplace = provider.replaceFile(file, "text/plain") {
                it.write("old".toByteArray())
            }.success()
            assertEquals(ReplaceGuarantee.PROVIDER_RENAME, firstReplace.guarantee)

            val secondReplace = provider.replaceFile(file, "text/plain") {
                it.write(content)
            }.success()
            assertEquals(ReplaceGuarantee.BEST_EFFORT, secondReplace.guarantee)
            assertTrue(provider.exists(file).success())
            assertEquals(
                "fov:90",
                provider.openInput(file).success().bufferedReader().use { it.readText() },
            )

            val walked = DirectoryWalker().walk(provider, testRoot, includeRoot = false).success()
            assertEquals(
                listOf("${testRoot.value}/config", file.value),
                walked.map { it.path.value },
            )

            val hash = FileHasher().hash(
                provider,
                file,
                setOf(StorageHashAlgorithm.SHA256),
            ).success()
            assertEquals(content.digest("SHA-256"), hash.hashes[StorageHashAlgorithm.SHA256])

            val deleted = provider.delete(testRoot, recursive = true).success()
            assertEquals(1, deleted.deletedFiles)
            assertEquals(2, deleted.deletedDirectories)
            assertFalse(provider.exists(testRoot).success())
        } finally {
            provider.delete(testRoot, recursive = true)
            if (provider.permission().persisted) {
                provider.releasePermission(read = true, write = true)
            }
        }
    }

    @Test
    fun constructorRejectsNonContentUri() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val result = runCatching {
            DocumentTreeProvider(context, Uri.parse("file:///tmp/not-a-tree"))
        }

        assertTrue(result.isFailure)
    }

    private fun ByteArray.digest(algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(this).joinToString("") { "%02x".format(it) }

    private fun <T> StorageResult<T>.success(): T =
        (this as? StorageResult.Success<T>)?.value
            ?: error("Expected success, got $this")

    private fun StorageResult<*>.failure(): StorageError =
        (this as? StorageResult.Failure)?.error
            ?: error("Expected failure, got $this")

    private companion object {
        const val AUTHORITY = "com.modrith.filesystem.test.documents"
        const val ROOT_ID = "root"
    }
}
