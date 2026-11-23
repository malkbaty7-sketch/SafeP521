package com.example.storage

import android.Manifest
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.data.MediaDao
import com.example.data.MediaItem
import com.example.data.MediaFileSupport
import com.example.data.MediaType
import com.example.data.isSupportedMedia
import com.example.security.SecurityAction
import com.example.security.SecurityManager
import com.example.storage.data.StorageLocation
import com.example.storage.data.StorageLocationDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

class StorageManager(
    private val context: Context,
    private val storageLocationDao: StorageLocationDao,
    private val mediaDao: MediaDao,
    private val securityManager: SecurityManager
) {
    data class CacheClearReport(
        val bytesCleared: Long,
        val filesDeleted: Int,
        val failures: Int
    )

    data class ReconciliationReport(
        val missing: Int,
        /** Count of MediaStore items visible to the app but not linked to a library row; null means unavailable. */
        val untracked: Int?,
        val unverified: Int
    )

    val storageLocations: Flow<List<StorageLocation>> = storageLocationDao.getAllLocations()
    private val mediaOperationLocks = Array(64) { Mutex() }

    private suspend fun <T> withMediaOperation(mediaId: Int, action: suspend () -> T): T {
        // A fixed set of stripes serializes operations on the same item without retaining one lock per item forever.
        val mutex = mediaOperationLocks[mediaId and (mediaOperationLocks.size - 1)]
        mutex.lock()
        return try {
            action()
        } finally {
            mutex.unlock()
        }
    }

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

    suspend fun clearCache(type: String): Result<Long> = clearCacheDetailed(type).fold(
        onSuccess = { report ->
            if (report.failures == 0) {
                Result.success(report.bytesCleared)
            } else {
                Result.failure(IOException("${report.failures} cache item(s) could not be removed"))
            }
        },
        onFailure = { Result.failure(it) }
    )

    suspend fun clearCacheDetailed(type: String): Result<CacheClearReport> = withContext(Dispatchers.IO) {
        if (!securityManager.canPerformAction(SecurityAction.EDIT)) {
            return@withContext Result.failure(SecurityException("Cleanup restricted by security settings"))
        }

        val (allowedRoot, relativePath) = when (type) {
            "THUMBNAILS" -> context.cacheDir to "image_manager_disk_cache"
            "TEMP" -> context.filesDir to "temp"
            "EXPORTS" -> context.filesDir to "exports${File.separator}temp"
            else -> return@withContext Result.failure(IllegalArgumentException("Unknown cache category"))
        }
        val targetDir = File(allowedRoot, relativePath)
        if (!targetDir.exists()) return@withContext Result.success(CacheClearReport(0L, 0, 0))
        if (!targetDir.isDirectory) {
            return@withContext Result.failure(IllegalArgumentException("The cache location is not a directory"))
        }

        val (canonicalRoot, canonicalTarget, expectedTarget) = try {
            val canonicalRoot = allowedRoot.canonicalPath
            Triple(
                canonicalRoot,
                targetDir.canonicalPath,
                File(canonicalRoot, relativePath).absolutePath
            )
        } catch (error: Exception) {
            return@withContext Result.failure(error)
        }
        if (canonicalTarget != expectedTarget) {
            return@withContext Result.failure(SecurityException("Refusing to clear a redirected cache directory"))
        }

        var clearedBytes = 0L
        var filesDeleted = 0
        var failures = 0
        val targetPrefix = canonicalTarget + File.separator
        targetDir.walkTopDown()
            .onFail { _, _ -> failures++ }
            .filter { it.isFile }
            .forEach { file ->
                val canonicalFile = runCatching { file.canonicalPath }.getOrNull()
                if (canonicalFile == null || !canonicalFile.startsWith(targetPrefix)) {
                    failures++
                    return@forEach
                }
                // A symlink entry can be removed without freeing the bytes of its target.
                val size = if (canonicalFile == file.absolutePath) file.length().coerceAtLeast(0L) else 0L
                if (file.delete()) {
                    clearedBytes += size
                    filesDeleted++
                } else {
                    failures++
                }
            }
        targetDir.walkBottomUp().filter { it.isDirectory && it != targetDir }.forEach { directory ->
            if (directory.list()?.isEmpty() == true && !directory.delete() && directory.exists()) {
                failures++
            }
        }
        Result.success(CacheClearReport(clearedBytes, filesDeleted, failures))
    }

    suspend fun getLibrarySize(): Long = withContext(Dispatchers.IO) {
        mediaDao.getAllMediaSync().asSequence()
            .filter { it.isSupportedMedia() }
            .sumOf { it.sizeBytes.coerceAtLeast(0L) }
    }

    /** Reconcile local paths and SAF URIs without treating revoked grants as missing files. */
    suspend fun reconcileDatabase(): ReconciliationReport = withContext(Dispatchers.IO) {
        var missingCount = 0
        var unverifiedCount = 0
        val snapshot = mediaDao.getAllMediaSync().filter { it.isSupportedMedia() }
        for (item in snapshot) {
            withMediaOperation(item.id) {
                val media = mediaDao.getMediaByIdSync(item.id) ?: return@withMediaOperation
                when (pathAvailability(media.path)) {
                    true -> if (media.isMissing) mediaDao.updateMedia(media.copy(isMissing = false))
                    false -> {
                        missingCount++
                        if (!media.isMissing) mediaDao.updateMedia(media.copy(isMissing = true))
                    }
                    null -> unverifiedCount++ // Permission/provider errors are not proof that the file is gone.
                }
            }
        }

        val knownPaths = mediaDao.getAllMediaSync()
            .asSequence()
            .filter { it.isSupportedMedia() }
            .mapTo(mutableSetOf()) { it.path }
        ReconciliationReport(
            missing = missingCount,
            untracked = countUntrackedMediaStoreItems(knownPaths),
            unverified = unverifiedCount
        )
    }

    private fun countUntrackedMediaStoreItems(knownPaths: Set<String>): Int? {
        val hasMediaReadAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasMediaReadAccess) return null

        val collections = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MediaStore.Video.Media._ID,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to MediaStore.Audio.Media._ID
        )
        return try {
            var untrackedCount = 0
            for ((collection, idColumnName) in collections) {
                val cursor = context.contentResolver.query(
                    collection,
                    arrayOf(idColumnName),
                    null,
                    null,
                    null
                ) ?: return null
                cursor.use { rows ->
                    val idColumn = rows.getColumnIndex(idColumnName)
                    if (idColumn < 0) return null
                    while (rows.moveToNext()) {
                        val path = ContentUris.withAppendedId(collection, rows.getLong(idColumn)).toString()
                        if (path !in knownPaths) untrackedCount++
                    }
                }
            }
            untrackedCount
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun relinkMedia(mediaId: Int, newPath: String): Result<Unit> = withMediaOperation(mediaId) {
        withContext(Dispatchers.IO) {
            if (!securityManager.canPerformAction(SecurityAction.EDIT)) {
                return@withContext Result.failure(SecurityException("Relinking is restricted by security settings"))
            }
            val media = mediaDao.getMediaByIdSync(mediaId)
                ?: return@withContext Result.failure(IllegalArgumentException("Media not found"))
            if (!hasProtectedMediaAccess(mediaId)) {
                return@withContext Result.failure(SecurityException("Unlock the protected item before relinking it"))
            }

            val candidatePath = newPath.trim()
            if (candidatePath.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Select a valid file"))
            }
            val candidateUri = parseUri(candidatePath)
            val candidateName = mediaDisplayName(candidatePath, candidateUri)
            val candidateMimeType = candidateUri
                ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
                ?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
            if (!MediaFileSupport.isSupportedMedia(media.mediaType, candidateName, candidateMimeType, candidatePath)) {
                val expected = if (media.mediaType == MediaType.VIDEO) "video" else "audio"
                return@withContext Result.failure(IllegalArgumentException("Select a supported $expected file, not a document or another media type"))
            }
            when (pathAvailability(candidatePath)) {
                true -> Unit
                false -> return@withContext Result.failure(FileNotFoundException("The selected file cannot be opened"))
                null -> return@withContext Result.failure(SecurityException("The app does not have permission to read the selected file"))
            }
            val existing = try {
                mediaDao.getMediaByPath(candidatePath)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withContext Result.failure(IOException("Could not verify whether the selected file is already linked", error))
            }
            if (existing != null && existing.id != mediaId) {
                return@withContext Result.failure(IllegalArgumentException("That file is already linked to another library item"))
            }

            try {
                mediaDao.updateMedia(
                    media.copy(
                        path = candidatePath,
                        name = candidateName ?: media.name,
                        mimeType = candidateMimeType ?: media.mimeType,
                        isMissing = false
                    )
                )
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(Exception("Could not update the library link", error))
            }
        }
    }

    suspend fun renameMedia(mediaId: Int, newName: String): Result<Unit> = withMediaOperation(mediaId) {
        withContext(Dispatchers.IO) {
            if (!securityManager.canPerformAction(SecurityAction.EDIT)) {
                return@withContext Result.failure(SecurityException("Renaming is restricted by security settings"))
            }
            val media = mediaDao.getMediaByIdSync(mediaId)
                ?: return@withContext Result.failure(IllegalArgumentException("Media not found"))
            if (!hasProtectedMediaAccess(mediaId)) {
                return@withContext Result.failure(SecurityException("Unlock the protected item before renaming it"))
            }
            val sharedPath = try {
                mediaDao.isPathReferencedByAnotherMedia(mediaId, media.path)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withContext Result.failure(IOException("Could not verify whether this file is shared", error))
            }
            if (sharedPath) {
                return@withContext Result.failure(IllegalStateException("This file is linked to another library item and cannot be renamed safely"))
            }

            val renamedName = renamedDisplayName(media.name, newName)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid file name"))
            val uri = parseUri(media.path)
            if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
                return@withContext renameContentMedia(media, uri, renamedName)
            }

            val oldFile = fileForPath(media.path)
                ?: return@withContext Result.failure(IllegalArgumentException("Unsupported media location"))
            if (!oldFile.isFile) {
                return@withContext Result.failure(FileNotFoundException("File not found or inaccessible"))
            }
            val parentDir = oldFile.parentFile
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid parent directory"))
            val newFile = File(parentDir, renamedName)
            if (newFile.canonicalFile.parentFile != parentDir.canonicalFile) {
                return@withContext Result.failure(IllegalArgumentException("Invalid file name"))
            }
            if (newFile.canonicalFile == oldFile.canonicalFile) {
                return@withContext Result.success(Unit)
            }
            if (newFile.exists()) {
                return@withContext Result.failure(IllegalArgumentException("A file with this name already exists"))
            }
            if (!oldFile.renameTo(newFile)) {
                return@withContext Result.failure(IOException("Failed to rename file"))
            }

            try {
                mediaDao.updateMedia(
                    media.copy(
                        name = renamedName,
                        path = newFile.absolutePath,
                        isMissing = false,
                        dateModified = System.currentTimeMillis() / 1000
                    )
                )
                Result.success(Unit)
            } catch (error: CancellationException) {
                newFile.renameTo(oldFile)
                throw error
            } catch (error: Exception) {
                val rolledBack = newFile.renameTo(oldFile)
                val detail = if (rolledBack) {
                    "The file rename was rolled back"
                } else {
                    "The renamed file remains at ${newFile.absolutePath}"
                }
                Result.failure(Exception("$detail; the library record could not be updated", error))
            }
        }
    }

    private suspend fun renameContentMedia(media: MediaItem, uri: Uri, renamedName: String): Result<Unit> {
        val oldName = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: media.name
        val renamedUri = renameContentItem(uri, renamedName)
            ?: return Result.failure(Exception("The content provider does not allow renaming this item"))

        return try {
            mediaDao.updateMedia(
                media.copy(
                    name = renamedName,
                    path = renamedUri.toString(),
                    isMissing = false,
                    dateModified = System.currentTimeMillis() / 1000
                )
            )
            Result.success(Unit)
        } catch (error: CancellationException) {
            rollbackContentRename(renamedUri, oldName)
            throw error
        } catch (error: Exception) {
            val restored = rollbackContentRename(renamedUri, oldName)
            val detail = if (restored) "The item rename was rolled back" else "The provider item remains renamed to $renamedName"
            Result.failure(Exception("$detail; its library record could not be updated", error))
        }
    }

    private fun renameContentItem(uri: Uri, newName: String): Uri? = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.renameDocument(context.contentResolver, uri, newName)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            if (context.contentResolver.update(uri, values, null, null) > 0) uri else null
        }
    }.getOrNull() ?: runCatching {
        val document = DocumentFile.fromSingleUri(context, uri)
        if (document?.renameTo(newName) == true) document.uri else null
    }.getOrNull()

    private fun stageContentItem(uri: Uri, newName: String): Uri? =
        renameContentItem(uri, newName)

    private fun rollbackContentRename(currentUri: Uri, oldName: String): Boolean =
        renameContentItem(currentUri, oldName) != null

    suspend fun deleteMedia(mediaId: Int): Result<Unit> = withMediaOperation(mediaId) {
        withContext(Dispatchers.IO) {
            if (!securityManager.canPerformAction(SecurityAction.DELETE)) {
                return@withContext Result.failure(SecurityException("Deletion is restricted by security settings"))
            }
            val media = mediaDao.getMediaByIdSync(mediaId)
                ?: return@withContext Result.failure(IllegalArgumentException("Media not found"))
            if (!hasProtectedMediaAccess(mediaId)) {
                return@withContext Result.failure(SecurityException("Unlock the protected item before deleting it"))
            }
            val sharedPath = try {
                mediaDao.isPathReferencedByAnotherMedia(mediaId, media.path)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withContext Result.failure(IOException("Could not verify whether this file is shared", error))
            }
            if (sharedPath) {
                return@withContext try {
                    mediaDao.deleteMedia(media)
                    Result.success(Unit)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(Exception("The shared library entry could not be removed", error))
                }
            }

            val uri = parseUri(media.path)
            if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
                deleteContentMedia(media, uri)
            } else {
                val file = fileForPath(media.path)
                    ?: return@withContext Result.failure(IllegalArgumentException("Unsupported media location"))
                deleteLocalMedia(media, file)
            }
        }
    }

    private suspend fun deleteLocalMedia(media: MediaItem, file: File): Result<Unit> {
        if (!file.exists()) {
            return try {
                mediaDao.deleteMedia(media)
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(Exception("The missing file's library record could not be removed", error))
            }
        }
        if (!file.isFile) return Result.failure(Exception("The selected path is not a regular file"))
        val parent = file.parentFile ?: return Result.failure(Exception("Invalid media parent directory"))
        val quarantine = File(parent, ".safeplayer-${UUID.randomUUID()}.pending-delete")
        if (quarantine.exists() || !file.renameTo(quarantine)) {
            return Result.failure(Exception("Could not safely stage this file for deletion"))
        }

        try {
            mediaDao.deleteMedia(media)
        } catch (error: CancellationException) {
            val restored = restoreStagedLocalFile(quarantine, file)
            if (!restored) {
                runCatching { mediaDao.updateMedia(media.copy(path = quarantine.absolutePath, isMissing = false)) }
            }
            throw error
        } catch (error: Exception) {
            val restored = restoreStagedLocalFile(quarantine, file)
            if (!restored) {
                runCatching { mediaDao.updateMedia(media.copy(path = quarantine.absolutePath, isMissing = false)) }
            }
            val detail = if (restored) "The file was restored" else "The file remains recoverable at ${quarantine.absolutePath}"
            return Result.failure(Exception("Database deletion failed; $detail", error))
        }

        return if (quarantine.delete() || !quarantine.exists()) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("The library entry was removed, but file cleanup is pending at ${quarantine.absolutePath}"))
        }
    }

    private fun restoreStagedLocalFile(stagedFile: File, originalFile: File): Boolean =
        !originalFile.exists() && stagedFile.renameTo(originalFile)

    private suspend fun deleteContentMedia(media: MediaItem, uri: Uri): Result<Unit> {
        val oldName = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: media.name
        val quarantineName = ".safeplayer-${UUID.randomUUID()}.pending-delete"
        val stagedUri = if (DocumentsContract.isDocumentUri(context, uri)) {
            stageContentItem(uri, quarantineName)
        } else {
            null
        }

        if (stagedUri != null) {
            try {
                mediaDao.deleteMedia(media)
            } catch (error: CancellationException) {
                rollbackContentRename(stagedUri, oldName)
                throw error
            } catch (error: Exception) {
                val restored = rollbackContentRename(stagedUri, oldName)
                val detail = if (restored) "The provider item was restored" else "The provider item may remain named $quarantineName"
                return Result.failure(Exception("Database deletion failed; $detail", error))
            }
            return if (deleteMediaPath(stagedUri.toString())) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("The library entry was removed, but the provider file remains at $stagedUri"))
            }
        }

        if (!deleteMediaPath(uri.toString())) {
            return Result.failure(Exception("Could not delete the file. Its SAF or media-provider permission may be missing."))
        }
        return try {
            mediaDao.deleteMedia(media)
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(Exception("The provider file was deleted, but its library record could not be removed", error))
        }
    }

    suspend fun moveMedia(mediaId: Int, destinationPath: String): Result<Unit> = withMediaOperation(mediaId) {
        withContext(Dispatchers.IO) {
            if (!securityManager.canPerformAction(SecurityAction.EDIT)) {
                return@withContext Result.failure(SecurityException("Moving is restricted by security settings"))
            }
            val media = mediaDao.getMediaByIdSync(mediaId)
                ?: return@withContext Result.failure(IllegalArgumentException("Media not found"))
            if (!hasProtectedMediaAccess(mediaId)) {
                return@withContext Result.failure(SecurityException("Unlock the protected item before moving it"))
            }

            try {
                val sourceIsShared = mediaDao.isPathReferencedByAnotherMedia(mediaId, media.path)
                val destinationUri = parseUri(destinationPath)
                if (destinationUri != null && destinationUri.scheme.equals("content", ignoreCase = true)) {
                    return@withContext moveToSafDirectory(media, destinationUri)
                }

                val destinationDir = fileForPath(destinationPath)
                    ?: return@withContext Result.failure(IllegalArgumentException("Unsupported destination"))
                if (!destinationDir.isDirectory || !destinationDir.canWrite()) {
                    return@withContext Result.failure(IllegalArgumentException("Invalid or inaccessible destination directory"))
                }
                val sourceFile = fileForPath(media.path)
                val fileName = sourceFile?.name ?: media.name
                val targetFile = File(destinationDir, safeFileName(fileName))
                if (targetFile.canonicalFile.parentFile != destinationDir.canonicalFile) {
                    return@withContext Result.failure(IllegalArgumentException("Invalid destination file name"))
                }
                if (sourceFile?.canonicalFile == targetFile.canonicalFile) {
                    return@withContext Result.failure(IllegalArgumentException("Source and destination are the same"))
                }
                if (targetFile.exists()) {
                    return@withContext Result.failure(IllegalArgumentException("A file with the same name already exists"))
                }

                if (!sourceIsShared && sourceFile != null && sourceFile.isFile && sourceFile.renameTo(targetFile)) {
                    return@withContext updateAfterMove(
                        media = media,
                        newPath = targetFile.absolutePath,
                        newName = targetFile.name,
                        newFolder = destinationDir.name,
                        rollback = { targetFile.renameTo(sourceFile) }
                    )
                }

                copyMediaToFile(media.path, targetFile)
                val databaseUpdate = updateAfterMove(
                    media = media,
                    newPath = targetFile.absolutePath,
                    newName = targetFile.name,
                    newFolder = destinationDir.name,
                    rollback = { targetFile.delete() }
                )
                completeCopiedMove(media, databaseUpdate, targetFile.absolutePath)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(Exception("Could not move the media to the destination", error))
            }
        }
    }

    private suspend fun moveToSafDirectory(media: MediaItem, directoryUri: Uri): Result<Unit> {
        val directory = if (DocumentsContract.isTreeUri(directoryUri)) {
            DocumentFile.fromTreeUri(context, directoryUri)
        } else {
            DocumentFile.fromSingleUri(context, directoryUri)
        }
        if (directory?.isDirectory != true || !directory.canWrite()) {
            return Result.failure(IllegalArgumentException("The selected SAF URI is not a writable folder"))
        }

        val sourceFile = fileForPath(media.path)
        val fileName = safeFileName(sourceFile?.name ?: media.name)
        if (directory.findFile(fileName) != null) {
            return Result.failure(IllegalArgumentException("A file with the same name already exists"))
        }
        val target = directory.createFile(media.mimeType ?: "application/octet-stream", fileName)
            ?: return Result.failure(IOException("Could not create a file in the selected folder"))

        try {
            copyMediaToUri(media.path, target.uri)
        } catch (error: CancellationException) {
            runCatching { target.delete() }
            throw error
        } catch (error: Exception) {
            val destinationRemoved = runCatching { target.delete() }.getOrDefault(false)
            val detail = if (destinationRemoved) "" else "; the partial file remains at ${target.uri}"
            return Result.failure(Exception("Could not copy the media to the selected SAF folder$detail", error))
        }

        val databaseUpdate = updateAfterMove(
            media = media,
            newPath = target.uri.toString(),
            newName = target.name ?: fileName,
            newFolder = directory.name ?: media.folder,
            rollback = { target.delete() }
        )
        return completeCopiedMove(media, databaseUpdate, target.uri.toString())
    }

    private suspend fun updateAfterMove(
        media: MediaItem,
        newPath: String,
        newName: String,
        newFolder: String,
        rollback: () -> Boolean
    ): Result<Unit> = try {
        mediaDao.updateMedia(
            media.copy(
                path = newPath,
                name = newName,
                folder = newFolder,
                isMissing = false,
                dateModified = System.currentTimeMillis() / 1000
            )
        )
        Result.success(Unit)
    } catch (error: CancellationException) {
        runCatching { rollback() }
        throw error
    } catch (error: Exception) {
        val rolledBack = runCatching { rollback() }.getOrDefault(false)
        val detail = if (rolledBack) {
            "The move was rolled back"
        } else {
            "The destination file remains at $newPath"
        }
        Result.failure(Exception("$detail; the library record could not be updated", error))
    }

    private suspend fun completeCopiedMove(
        media: MediaItem,
        databaseUpdate: Result<Unit>,
        destinationPath: String
    ): Result<Unit> {
        if (databaseUpdate.isFailure) return databaseUpdate
        if (media.path == destinationPath) return Result.success(Unit)
        val sourceIsShared = try {
            mediaDao.isPathReferencedByAnotherMedia(media.id, media.path)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.failure(
                IOException("The library now uses $destinationPath, but the source was kept because its other references could not be checked", error)
            )
        }
        if (sourceIsShared) return Result.success(Unit)
        if (deleteMediaPath(media.path)) return Result.success(Unit)
        return when (pathAvailability(media.path)) {
            false -> Result.success(Unit) // The provider may report zero affected rows after a successful deletion.
            true -> Result.failure(
                IOException("The library now uses $destinationPath, but the original remains at ${media.path}")
            )
            null -> Result.failure(
                IOException("The library now uses $destinationPath, but removal of the original at ${media.path} could not be confirmed")
            )
        }
    }

    private suspend fun hasProtectedMediaAccess(mediaId: Int): Boolean =
        securityManager.canAccessMedia(mediaId)

    private fun pathAvailability(path: String): Boolean? {
        val uri = parseUri(path)
        if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
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
        if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
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
            val cleaned = target.delete() || !target.exists()
            if (!cleaned) throw IOException("An incomplete file remains at ${target.absolutePath}", error)
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
            uri != null && uri.scheme.equals("content", ignoreCase = true) -> context.contentResolver.openInputStream(uri)
            uri != null && uri.scheme.equals("file", ignoreCase = true) -> uri.path?.let(::File)?.let(::FileInputStream)
            uri?.scheme == null -> FileInputStream(File(path))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun parseUri(path: String): Uri? = runCatching { Uri.parse(path) }.getOrNull()

    private fun mediaDisplayName(path: String, uri: Uri?): String? {
        if (uri?.scheme?.equals("content", ignoreCase = true) == true) {
            val queriedName = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
                }
            }.getOrNull()
            return queriedName
                ?: runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment
        }
        return File(uri?.path ?: path).name.takeIf { it.isNotBlank() }
    }

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
