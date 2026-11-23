package com.example.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.MediaItem
import com.example.data.MediaType
import com.example.security.SecurityManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class StorageManagerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var securityManager: SecurityManager
    private lateinit var storageManager: StorageManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        securityManager = SecurityManager(context, database.securityDao())
        storageManager = StorageManager(
            context,
            database.storageLocationDao(),
            database.mediaDao(),
            securityManager
        )
    }

    @After
    fun teardown() {
        securityManager.close()
        database.close()
    }

    @Test
    fun `test clearCache clears cache directory`() = runTest {
        val tempDir = File(context.filesDir, "temp")
        tempDir.mkdirs()
        val tempFile = File(tempDir, "export_tmp_123.mp4")
        tempFile.writeText("temp")

        assertTrue(tempFile.exists())
        val result = storageManager.clearCache("TEMP")
        assertTrue(result.isSuccess)
        assertFalse(tempFile.exists())
    }

    @Test
    fun `detailed cache clear reports deleted bytes and files`() = runTest {
        val tempDir = File(context.filesDir, "temp")
        tempDir.deleteRecursively()
        val nestedDir = File(tempDir, "nested")
        assertTrue(nestedDir.mkdirs())
        File(tempDir, "one.tmp").writeText("abc")
        File(nestedDir, "two.tmp").writeText("12345")

        val result = storageManager.clearCacheDetailed("TEMP")

        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(8L, report.bytesCleared)
        assertEquals(2, report.filesDeleted)
        assertEquals(0, report.failures)
        assertTrue("The cache root itself should be retained", tempDir.exists())
        assertFalse("Empty nested directories should be removed", nestedDir.exists())
    }

    @Test
    fun `moveMedia updates the library path after moving a local file`() = runTest {
        val testRoot = newTestDirectory()
        val source = File(testRoot, "source/clip.mp4").apply {
            parentFile?.mkdirs()
            writeText("video bytes")
        }
        val destination = File(testRoot, "destination").apply { mkdirs() }
        val mediaId = insertMedia(source.absolutePath, source.name)

        val result = storageManager.moveMedia(mediaId, destination.absolutePath)

        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        val moved = File(destination, source.name)
        assertTrue(moved.isFile)
        assertFalse(source.exists())
        assertEquals(moved.absolutePath, database.mediaDao().getMediaByIdSync(mediaId)?.path)
        testRoot.deleteRecursively()
    }

    @Test
    fun `moving one of several references copies without moving the shared source`() = runTest {
        val testRoot = newTestDirectory()
        val source = File(testRoot, "shared/clip.mp4").apply {
            parentFile?.mkdirs()
            writeText("shared video")
        }
        val destination = File(testRoot, "destination").apply { mkdirs() }
        val selectedId = insertMedia(source.absolutePath, source.name)
        val remainingId = insertMedia(source.absolutePath, source.name)

        val result = storageManager.moveMedia(selectedId, destination.absolutePath)

        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        val moved = File(destination, source.name)
        assertTrue(source.isFile)
        assertTrue(moved.isFile)
        assertEquals(moved.absolutePath, database.mediaDao().getMediaByIdSync(selectedId)?.path)
        assertEquals(source.absolutePath, database.mediaDao().getMediaByIdSync(remainingId)?.path)
        testRoot.deleteRecursively()
    }

    @Test
    fun `deleteMedia removes a local file and its library row`() = runTest {
        val testRoot = newTestDirectory()
        val mediaFile = File(testRoot, "clip.mp4").apply { writeText("video bytes") }
        val mediaId = insertMedia(mediaFile.absolutePath, mediaFile.name)

        val result = storageManager.deleteMedia(mediaId)

        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        assertFalse(mediaFile.exists())
        assertNull(database.mediaDao().getMediaByIdSync(mediaId))
        testRoot.deleteRecursively()
    }

    @Test
    fun `renaming a shared file is rejected without changing either reference`() = runTest {
        val testRoot = newTestDirectory()
        val mediaFile = File(testRoot, "shared.mp4").apply { writeText("shared video") }
        val selectedId = insertMedia(mediaFile.absolutePath, mediaFile.name)
        val remainingId = insertMedia(mediaFile.absolutePath, mediaFile.name)

        val result = storageManager.renameMedia(selectedId, "renamed")

        assertTrue(result.isFailure)
        assertTrue(mediaFile.isFile)
        assertEquals(mediaFile.absolutePath, database.mediaDao().getMediaByIdSync(selectedId)?.path)
        assertEquals(mediaFile.absolutePath, database.mediaDao().getMediaByIdSync(remainingId)?.path)
        testRoot.deleteRecursively()
    }

    @Test
    fun `deleting one of several references preserves their shared file`() = runTest {
        val testRoot = newTestDirectory()
        val mediaFile = File(testRoot, "shared.mp4").apply { writeText("shared video") }
        val selectedId = insertMedia(mediaFile.absolutePath, mediaFile.name)
        val remainingId = insertMedia(mediaFile.absolutePath, mediaFile.name)

        val result = storageManager.deleteMedia(selectedId)

        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        assertTrue(mediaFile.isFile)
        assertNull(database.mediaDao().getMediaByIdSync(selectedId))
        assertEquals(mediaFile.absolutePath, database.mediaDao().getMediaByIdSync(remainingId)?.path)
        testRoot.deleteRecursively()
    }

    @Test
    fun `relinkMedia requires an accessible path and clears missing state`() = runTest {
        val testRoot = newTestDirectory()
        val missingItemId = insertMedia(
            path = File(testRoot, "missing.mp4").absolutePath,
            name = "missing.mp4",
            isMissing = true
        )
        val replacement = File(testRoot, "replacement.mp4").apply { writeText("replacement") }

        val result = storageManager.relinkMedia(missingItemId, replacement.absolutePath)

        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        val relinked = database.mediaDao().getMediaByIdSync(missingItemId)
        assertEquals(replacement.absolutePath, relinked?.path)
        assertFalse(relinked?.isMissing ?: true)

        val invalidResult = storageManager.relinkMedia(missingItemId, File(testRoot, "not-found.mp4").absolutePath)
        assertTrue(invalidResult.isFailure)
        assertEquals(replacement.absolutePath, database.mediaDao().getMediaByIdSync(missingItemId)?.path)
        testRoot.deleteRecursively()
    }

    @Test
    fun `reconcileDatabase marks confirmed missing local files`() = runTest {
        val testRoot = newTestDirectory()
        val mediaId = insertMedia(File(testRoot, "gone.mp4").absolutePath, "gone.mp4")

        val report = storageManager.reconcileDatabase()

        assertEquals(1, report.missing)
        assertEquals(true, database.mediaDao().getMediaByIdSync(mediaId)?.isMissing)
        assertEquals(0, report.unverified)
        testRoot.deleteRecursively()
    }

    private suspend fun insertMedia(path: String, name: String, isMissing: Boolean = false): Int =
        database.mediaDao().insertMedia(
            MediaItem(
                name = name,
                path = path,
                folder = "Storage tests",
                mediaType = MediaType.VIDEO,
                sizeBytes = 0L,
                dateModified = System.currentTimeMillis() / 1000,
                durationMs = 1_000L,
                isMissing = isMissing
            )
        ).toInt()

    private fun newTestDirectory(): File =
        File(context.filesDir, "storage-manager-test-${UUID.randomUUID()}").apply { mkdirs() }
}
