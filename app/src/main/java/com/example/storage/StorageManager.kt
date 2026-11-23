package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.data.MediaDao
import com.example.data.MediaItem
import com.example.security.SecurityAction
import com.example.security.SecurityManager
import com.example.storage.data.StorageLocation
import com.example.storage.data.StorageLocationDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

class StorageManager(
    private val context: Context,
    private val storageLocationDao: StorageLocationDao,
    private val mediaDao: MediaDao,
    private val securityManager: SecurityManager
) {
    val storageLocations: Flow<List<StorageLocation>> = storageLocationDao.getAllLocations()

    suspend fun discoverAndRefreshLocations() {
        withContext(Dispatchers.IO) {
            updateLocationInfo(
                path = context.filesDir.absolutePath,
                displayName = "App Internal Storage",
                type = "APP_STORAGE",
                isReadOnly = false
            )
            context.getExternalFilesDir(null)?.let { externalFiles ->
                updateLocationInfo(
                    path = externalFiles.absolutePath,
                    displayName = "App External Storage",
                    type = "DEVICE_STORAGE",
                    isReadOnly = false
                )
            }
        }
    }

    private suspend fun updateLocationInfo(
        path: String,
        displayName: String,
        type: String,
        isReadOnly: Boolean
    ) {
        val file = File(path)
        if (!file.exists()) return

        val stat = StatFs(path)
        val blockSize = stat.blockSizeLong
        val totalSpace = stat.blockCountLong * blockSize
        val availableSpace = stat.availableBlocksLong * blockSize
        val existing = storageLocationDao.getLocationByPath(path)
        val location = StorageLocation(
            id = existing?.id ?: 0,
            displayName = displayName,
            path = path,
            uri = path,
            type = type,
            totalSpace = totalSpace,
            availableSpace = availableSpace,
            usedSpace = totalSpace - availableSpace,
            permissionState = "GRANTED",
            isAccessible = true,
            isReadOnly = isReadOnly,
            lastCheckedAt = System.currentTimeMillis()
        )
        if (existing == null) storageLocationDao.insertLocation(location)
        else storageLocationDao.updateLocation(location)
    }

    suspend fun clearCache(type: String): Result<Long> = withContext(Dispatchers.IO) {
        if (!securityManager.canPerformAction(SecurityAction.EDIT)) {
            return@withContext Result.failure(Exception("Cleanup restricted by security settings"))
        }

        val cacheDir = context.cacheDir
        val targetDir = when (type) {
            "THUMBNAILS" -> File(cacheDir, "image_manager_disk_cache")
            "TEMP" -> File(context.filesDir, "temp")
            "EXPORTS" -> File(context.filesDir, "exports/temp")
            else -> null
        } ?: return@withContext Result.failure(Exception("Unknown cache category"))

        var clearedBytes = 0L
        if (targetDir.exists()) {
            targetDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val size = file.length()
                if (file.delete()) clearedBytes += size
            }
        }
        Result.success(clearedBytes)
    }

    suspend fun getLibrarySize(): Long = withContext(Dispatchers.IO) {
        mediaDao.getAllMediaSync().sumOf { it.sizeBytes.coerceAtLeast(0L) }
    }

    /** Reconcile ordinary file paths and SAF content URIs without treating revoked grants as missing files. */
    suspend fun reconcileDatabase(): Map<String, Int> = withContext(Dispatchers.IO) {
        var missingCount = 0
        val allMedia = mediaDao.getAllMediaSync()
        for (media in allMedia) {
            when (pathAvailability(media.path)) {
                true -> if (media.isMissing) mediaDao.updateMedia(media.copy(isMissing = false))
                false -> {
                    missingCount++
                    if (!media.isMissing) mediaDao.updateMedia(media.copy(isMissing = true))
                }
                null -> Unit // Permission/provider errors are not proof that the file is gone.
            }
        }
        mapOf("missing" to missingCount, "orphaned" to 0)
    }

    suspend fun renameMedia(mediaId: Int, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!securityManager.canPerformAction(SecurityAction.EDIT)) {
            return@withContext Result.failure(Exception("Renaming is restricted by security settings"))
        }
        val media = mediaDao.getMediaByIdSync(mediaId)
            ?: return@withContext Result.failure(Exception("Media not found"))
        if (!hasProtectedMediaAccess(mediaId)) {
            return@withContext Result.failure(Exception("Unlock the protected item before renaming it"))
        }

        val renamedName = renamedDisplayName(media.name, newName)
            ?: return@withContext Result.failure(Exception("Invalid file name"))
        val uri = parseUri(media.path)
        if (uri?.scheme.equals("content", ignoreCase = true)) {
            return@withContext renameContentMedia(media, uri, renamedName)
        }

        val oldFile = fileForPath(media.path)
            ?: return@withContext Result.failure(Exception("Unsupported media location"))
        if (!oldFile.isFile) return@withContext Result.failure(Exception("File not found or inaccessible"))
        val parentDir = oldFile.parentFile
            ?: return@withContext Result.failure(Exception("Invalid parent directory"))
        val newFile = File(parentDir, renamedName)
        if (newFile.canonicalFile.parentFile != parentDir.canonicalFile) {
            return@withContext Result.failure(Exception("Invalid file name"))
        }
        if (newFile.exists()) return@withContext Result.failure(Exception("A file with this name already exists"))
        if (!oldFile.renameTo(newFile)) return@withContext Result.failure(Exception("Failed to rename file"))

        try {
            mediaDao.updateMedia(
                media.copy(
                    name = renamedName,
                    path = newFile.absolutePath,
                    dateModified = System.currentTimeMillis() / 1000
                )
            )
            Result.success(Unit)
        } catch (error: Exception) {
            newFile.renameTo(oldFile)
            Result.failure(Exception("The file was renamed but its library record could not be updated", error))
        }
    }

    private suspend fun renameContentMedia(media: MediaItem, uri: Uri, renamedName: String): Result<Unit> {
        val oldName = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: media.name
        val renamedUri = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.renameDocument(context.contentResolver, uri, renamedName)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, renamedName)
                }
                if (context.contentResolver.update(uri, values, null, null) > 0) uri else null
            }
        }.getOrNull() ?: runCatching {
            val document = DocumentFile.fromSingleUri(context, uri)
            if (document?.renameTo(renamedName) == true) document.uri else null
        }.getOrNull()

        if (renamedUri == null) {
            return Result.failure(Exception("The content provider does not allow renaming this item"))
        }
        return try {
            mediaDao.updateMedia(
                media.copy(
                    name = renamedName,
                    path = renamedUri.toString(),
                    dateModified = System.currentTimeMillis() / 1000
                )
            )
            Result.success(Unit)
        } catch (error: Exception) {
            rollbackContentRename(renamedUri, uri, oldName)
            Result.failure(Exception("The item was renamed but its library record could not be updated", error))
        }
    }

    private fun rollbackContentRename(currentUri: Uri, originalUri: Uri, oldName: String) {
        runCatching {
            if (DocumentsContract.isDocumentUri(context, currentUri)) {
                DocumentsContract.renameDocument(context.contentResolver, currentUri, oldName)
            } else {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, oldName) }
                context.contentResolver.update(currentUri, values, null, null)
            }
        }
        if (currentUri != originalUri) {
            runCatching { DocumentFile.fromSingleUri(context, currentUri)?.renameTo(oldName) }
        }
    }

    suspend fun deleteMedia(mediaId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (!securityManager.canPerformAction(SecurityAction.DELETE)) {
            return@withContext Result.failure(Exception("Deletion is restricted by security settings"))
        }
        val media = mediaDao.getMediaByIdSync(mediaId)
            ?: return@withContext Result.failure(Exception("Media not found"))
        if (!hasProtectedMediaAccess(mediaId)) {
            return@withContext Result.failure(Exception("Unlock the protected item before deleting it"))
        }

        if (!deleteMediaPath(media.path)) {
            return@withContext Result.failure(Exception("Could not delete the file. Its SAF or media-provider permission may be missing."))
        }
        mediaDao.deleteMedia(media)
        Result.success(Unit)
    }

    suspend fun moveMedia(mediaId: Int, destinationPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!securityManager.canPerformAction(SecurityAction.EDIT)) {
            return@withContext Result.failure(Exception("Moving is restricted by security settings"))
        }
        val media = mediaDao.getMediaByIdSync(mediaId)
            ?: return@withContext Result.failure(Exception("Media not found"))
        if (!hasProtectedMediaAccess(mediaId)) {
            return@withContext Result.failure(Exception("Unlock the protected item before moving it"))
        }

        val destinationUri = parseUri(destinationPath)
        if (destinationUri?.scheme.equals("content", ignoreCase = true)) {
            return@withContext moveToSafDirectory(media, destinationUri)
        }

        val destinationDir = fileForPath(destinationPath)
            ?: return@withContext Result.failure(Exception("Unsupported destination"))
        if (!destinationDir.isDirectory || !destinationDir.canWrite()) {
            return@withContext Result.failure(Exception("Invalid or inaccessible destination directory"))
        }
        val sourceFile = fileForPath(media.path)
        val fileName = sourceFile?.name ?: media.name
        val targetFile = File(destinationDir, safeFileName(fileName))
        if (targetFile.canonicalFile.parentFile != destinationDir.canonicalFile) {
            return@withContext Result.failure(Exception("Invalid destination file name"))
        }
        if (targetFile.exists()) return@withContext Result.failure(Exception("A file with the same name already exists"))
        if (sourceFile?.canonicalFile == targetFile.canonicalFile) {
            return@withContext Result.failure(Exception("Source and destination are the same"))
        }

        if (sourceFile != null && sourceFile.isFile && sourceFile.renameTo(targetFile)) {
            return@withContext updateAfterMove(
                media = media,
                newPath = targetFile.absolutePath,
                newName = targetFile.name,
                newFolder = destinationDir.name,
                rollback = { targetFile.renameTo(sourceFile) }
            )
        }

        try {
            copyMediaToFile(media.path, targetFile)
            val result = updateAfterMove(
                media = media,
                newPath = targetFile.absolutePath,
                newName = targetFile.name,
                newFolder = destinationDir.name,
                rollback = { targetFile.delete() }
            )
            if (result.isSuccess) deleteMediaPath(media.path)
            result
        } catch (error: Exception) {
            Result.failure(Exception("Could not copy the media to the destination", error))
        }
    }

    private suspend fun moveToSafDirectory(media: MediaItem, directoryUri: Uri): Result<Unit> {
        val directory = if (DocumentsContract.isTreeUri(directoryUri)) {
            DocumentFile.fromTreeUri(context, directoryUri)
        } else {
            DocumentFile.fromSingleUri(context, directoryUri)
        }
        if (directory?.isDirectory != true || !directory.canWrite()) {
            return Result.failure(Exception("The selected SAF URI is not a writable folder"))
        }

        val sourceFile = fileForPath(media.path)
        val fileName = safeFileName(sourceFile?.name ?: media.name)
        if (directory.findFile(fileName) != null) {
            return Result.failure(Exception("A file with the same name already exists"))
        }
        val target = directory.createFile(media.mimeType ?: "application/octet-stream", fileName)
            ?: return Result.failure(Exception("Could not create a file in the selected folder"))
        return try {
            copyMediaToUri(media.path, target.uri)
            val result = updateAfterMove(
                media = media,
                newPath = target.uri.toString(),
                newName = target.name ?: fileName,
                newFolder = directory.name ?: media.folder,
                rollback = { target.delete() }
            )
            if (result.isSuccess) deleteMediaPath(media.path)
            result
        } catch (error: Exception) {
            target.delete()
            Result.failure(Exception("Could not copy the media to the selected SAF folder", error))
        }
    }

    private suspend fun updateAfterMove(
        media: MediaItem,
        newPath: String,
        newName: String,
        newFolder: String,
        rollback: () -> Unit
    ): Result<Unit> = try {
        mediaDao.updateMedia(
            media.copy(
                path = newPath,
                name = newName,
                folder = newFolder,
                dateModified = System.currentTimeMillis() / 1000
            )
        )
        Result.success(Unit)
    } catch (error: Exception) {
        runCatching(rollback)
        Result.failure(Exception("The file was copied but its library record could not be updated", error))
    }

    private suspend fun hasProtectedMediaAccess(mediaId: Int): Boolean =
        securityManager.canAccessMedia(mediaId)

    private fun pathAvailability(path: String): Boolean? {
        val uri = parseUri(path)
        if (uri?.scheme.equals("content", ignoreCase = true)) {
            return try {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
                descriptor.close()
                true
            } catch (_: FileNotFoundException) {
                false
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }
        val file = fileForPath(path) ?: return null
        return file.isFile
    }

    private fun deleteMediaPath(path: String): Boolean {
        val uri = parseUri(path)
        if (uri?.scheme.equals("content", ignoreCase = true)) {
            val documentDeleted = runCatching {
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else false
            }.getOrDefault(false)
            if (documentDeleted) return true
            return runCatching { context.contentResolver.delete(uri, null, null) > 0 }
                .getOrDefault(false)
        }
        val file = fileForPath(path) ?: return false
        return !file.exists() || file.delete()
    }

    private fun copyMediaToFile(sourcePath: String, target: File) {
        if (!target.createNewFile()) throw IOException("Destination file already exists")
        try {
            val input = openInput(sourcePath) ?: throw FileNotFoundException("Source media is unavailable")
            input.use { source -> target.outputStream().use { output -> source.copyTo(output) } }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun copyMediaToUri(sourcePath: String, targetUri: Uri) {
        val input = openInput(sourcePath) ?: throw FileNotFoundException("Source media is unavailable")
        val output = context.contentResolver.openOutputStream(targetUri, "w")
            ?: throw IOException("Could not open the SAF destination")
        input.use { source -> output.use { destination -> source.copyTo(destination) } }
    }

    private fun openInput(path: String) = try {
        val uri = parseUri(path)
        when {
            uri?.scheme.equals("content", ignoreCase = true) -> context.contentResolver.openInputStream(uri)
            uri?.scheme.equals("file", ignoreCase = true) -> uri?.path?.let(::File)?.let(::FileInputStream)
            uri?.scheme == null -> FileInputStream(File(path))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun parseUri(path: String): Uri? = runCatching { Uri.parse(path) }.getOrNull()

    private fun fileForPath(path: String): File? {
        val uri = parseUri(path) ?: return null
        return when (uri.scheme?.lowercase()) {
            null -> File(path)
            "file" -> uri.path?.let(::File)
            "content" -> null
            else -> null
        }
    }

    private fun renamedDisplayName(currentName: String, requestedName: String): String? {
        val base = safeFileName(requestedName)
        if (base == "uploaded_media.bin" && requestedName.isBlank()) return null
        val extension = currentName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        return if (extension != null && !base.substringAfterLast('.', "").equals(extension, ignoreCase = true)) {
            "$base.$extension"
        } else base
    }

    private fun safeFileName(input: String): String {
        val leaf = input.substringAfterLast('/').substringAfterLast('\\')
        return leaf.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .trim()
            .trim('.')
            .take(180)
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "uploaded_media.bin"
    }
}
