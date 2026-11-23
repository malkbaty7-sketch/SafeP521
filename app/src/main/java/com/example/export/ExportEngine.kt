package com.example.export

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.data.EditingOperation
import com.example.data.EditingProject
import com.example.data.ExportTask
import com.example.data.ExportTaskDao
import com.example.data.MediaDao
import com.example.data.MediaItem
import com.example.data.MediaType
import com.example.security.SecurityAction
import com.example.security.SecurityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

@UnstableApi
class ExportEngine(
    private val context: Context,
    private val exportTaskDao: ExportTaskDao,
    private val mediaDao: MediaDao,
    private val securityManager: SecurityManager
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val exportMutex = Mutex()
    private val cancelledExportIds = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var currentTransformer: Transformer? = null

    private val _currentExportId = MutableStateFlow<Int?>(null)
    val currentExportId: StateFlow<Int?> = _currentExportId

    suspend fun startExport(
        project: EditingProject,
        operations: List<EditingOperation>,
        outputFormat: String = "MP4",
        videoCodec: String = "H.264",
        quality: String = "High"
    ): Int = exportMutex.withLock {
        if (_currentExportId.value != null) throw IllegalStateException("An export is already running")
        if (outputFormat != "MP4") throw IllegalArgumentException("Only MP4 exports are currently supported")
        qualityBitrates(quality)
        videoMimeType(videoCodec)
        requireCanExport(project.mediaId)

        val sourceMedia = mediaDao.getMediaByIdSync(project.mediaId)
            ?: throw IllegalArgumentException("Source media not found")
        if (sourceMedia.path.isBlank()) throw IllegalArgumentException("Source media location is empty")
        validateOperations(operations)

        val exportName = safeFileName(project.name)
        val exportDir = (context.getExternalFilesDir("exported_videos")
            ?: File(context.filesDir, "exported_videos")).apply {
            if (!exists() && !mkdirs()) throw IllegalStateException("Could not create export directory")
        }
        val suffix = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val tempFile = File(context.cacheDir, "safeplayer_export_$suffix.mp4")
        val finalFile = File(exportDir, "${exportName}_$suffix.mp4")

        val task = ExportTask(
            projectId = project.id,
            sourceMediaId = project.mediaId,
            status = "PREPARING",
            outputFormat = outputFormat,
            videoCodec = videoCodec,
            quality = quality,
            tempFilePath = tempFile.absolutePath,
            finalFilePath = finalFile.absolutePath
        )
        val taskId = exportTaskDao.insert(task).toInt()
        _currentExportId.value = taskId
        scope.launch {
            executeExport(taskId, task, tempFile, finalFile, project, sourceMedia, operations)
        }
        taskId
    }

    private suspend fun executeExport(
        taskId: Int,
        task: ExportTask,
        tempFile: File,
        finalFile: File,
        project: EditingProject,
        sourceMedia: MediaItem,
        operations: List<EditingOperation>
    ) {
        try {
            requireCanExport(sourceMedia.id)
            if (_currentExportId.value != taskId) return
            exportTaskDao.update(task.copy(id = taskId, status = "PROCESSING"))

            val quality = qualityBitrates(task.quality)
            val composition = buildComposition(sourceMedia, operations)
            val transformer = withContext(Dispatchers.Main.immediate) {
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(quality.videoBitrate).build()
                    )
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(quality.audioBitrate).build()
                    )
                    .build()
                val nextTransformer = Transformer.Builder(context)
                    .setEncoderFactory(encoderFactory)
                    .setVideoMimeType(videoMimeType(task.videoCodec))
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (_currentExportId.value == taskId) {
                                scope.launch {
                                    finalizeExport(taskId, tempFile, finalFile, sourceMedia)
                                }
                            } else {
                                tempFile.delete()
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            scope.launch {
                                try {
                                    if (taskId in cancelledExportIds) markExportCancelled(taskId)
                                    else markExportFailed(taskId, exportException.message ?: "Media3 export failed")
                                } finally {
                                    tempFile.delete()
                                    clearCurrentExport(taskId)
                                }
                            }
                        }
                    })
                    .build()
                currentTransformer = nextTransformer
                if (_currentExportId.value != taskId) {
                    nextTransformer.cancel()
                    currentTransformer = null
                    null
                } else {
                    nextTransformer.start(composition, tempFile.absolutePath)
                    nextTransformer
                }
            } ?: return

            val progressHolder = ProgressHolder()
            while (_currentExportId.value == taskId) {
                if (!canExportNow(sourceMedia.id)) {
                    withContext(Dispatchers.Main.immediate) {
                        if (currentTransformer === transformer) transformer.cancel()
                    }
                    throw SecurityException("Export was stopped by the current security policy")
                }
                val progressState = withContext(Dispatchers.Main.immediate) {
                    if (_currentExportId.value == taskId) transformer.getProgress(progressHolder)
                    else Transformer.PROGRESS_STATE_UNAVAILABLE
                }
                when (progressState) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> {
                        exportTaskDao.getTaskById(taskId)?.takeIf { it.status == "PROCESSING" }?.let { current ->
                            exportTaskDao.update(current.copy(progress = progressHolder.progress.coerceIn(0, 99)))
                        }
                    }
                    Transformer.PROGRESS_STATE_NOT_STARTED,
                    Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> Unit
                    Transformer.PROGRESS_STATE_UNAVAILABLE -> break
                }
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        } catch (error: Exception) {
            try {
                if (taskId in cancelledExportIds || exportTaskDao.getTaskById(taskId)?.status == "CANCELLED") {
                    markExportCancelled(taskId)
                } else {
                    markExportFailed(taskId, error.message ?: "Export failed")
                }
            } finally {
                tempFile.delete()
                clearCurrentExport(taskId)
            }
        }
    }

    private suspend fun finalizeExport(
        taskId: Int,
        tempFile: File,
        finalFile: File,
        sourceMedia: MediaItem
    ) {
        var mediaInserted = false
        var committed = false
        try {
            if (_currentExportId.value != taskId || taskId in cancelledExportIds) {
                tempFile.delete()
                return
            }
            requireCanExport(sourceMedia.id)
            val task = exportTaskDao.getTaskById(taskId) ?: throw IllegalStateException("Export task not found")
            if (task.status == "CANCELLED") throw CancellationException("Export was cancelled")
            exportTaskDao.update(task.copy(status = "VALIDATING", progress = 99))
            if (!tempFile.isFile || tempFile.length() <= 0L) {
                throw IllegalStateException("Export output is empty or missing")
            }
            if (finalFile.exists()) throw IllegalStateException("Export destination already exists")
            tempFile.copyTo(finalFile, overwrite = false)

            if (_currentExportId.value != taskId || taskId in cancelledExportIds) {
                throw CancellationException("Export was cancelled")
            }
            requireCanExport(sourceMedia.id)
            val newMedia = MediaItem(
                path = finalFile.absolutePath,
                name = finalFile.name,
                folder = finalFile.parentFile?.name ?: "Exported",
                mediaType = MediaType.VIDEO,
                sizeBytes = finalFile.length(),
                dateModified = System.currentTimeMillis() / 1000,
                durationMs = readDurationMs(finalFile),
                mimeType = "video/mp4",
                sourceMediaId = sourceMedia.id
            )
            mediaDao.insertMedia(newMedia)
            mediaInserted = true

            if (_currentExportId.value != taskId || taskId in cancelledExportIds) {
                throw CancellationException("Export was cancelled")
            }
            val currentTask = exportTaskDao.getTaskById(taskId)
                ?: throw IllegalStateException("Export task disappeared during finalization")
            if (currentTask.status == "CANCELLED") throw CancellationException("Export was cancelled")
            exportTaskDao.update(
                currentTask.copy(status = "COMPLETED", progress = 100, endTime = System.currentTimeMillis())
            )
            if (!_currentExportId.compareAndSet(taskId, null)) {
                throw CancellationException("Export was cancelled during finalization")
            }
            currentTransformer = null
            committed = true
            tempFile.delete()
        } catch (error: Exception) {
            if (!committed) {
                if (mediaInserted) {
                    try {
                        mediaDao.deleteByPath(finalFile.absolutePath)
                    } catch (_: Exception) {
                        // Remove the output file even if database rollback itself fails.
                    }
                }
                finalFile.delete()
            }
            tempFile.delete()
            if (taskId in cancelledExportIds || error is CancellationException) {
                markExportCancelled(taskId)
            } else {
                markExportFailed(taskId, "Finalization failed: ${error.message ?: "unknown error"}")
            }
        } finally {
            clearCurrentExport(taskId)
        }
    }

    fun cancelCurrentExport() {
        val taskId = _currentExportId.value ?: return
        if (!_currentExportId.compareAndSet(taskId, null)) return
        cancelledExportIds += taskId
        val transformer = currentTransformer
        currentTransformer = null
        scope.launch(Dispatchers.Main.immediate) {
            runCatching { transformer?.cancel() }
        }
        scope.launch {
            try {
                markExportCancelled(taskId)
            } finally {
                exportTaskDao.getTaskById(taskId)?.tempFilePath?.let(::File)?.delete()
            }
        }
    }

    private suspend fun markExportCancelled(taskId: Int) {
        exportTaskDao.getTaskById(taskId)?.let { task ->
            if (task.status != "CANCELLED" &&
                (taskId in cancelledExportIds || (task.status != "COMPLETED" && task.status != "FAILED"))
            ) {
                exportTaskDao.update(task.copy(status = "CANCELLED", endTime = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun markExportFailed(taskId: Int, message: String) {
        if (taskId in cancelledExportIds) {
            markExportCancelled(taskId)
            return
        }
        exportTaskDao.getTaskById(taskId)?.let { current ->
            if (current.status != "CANCELLED" && current.status != "COMPLETED") {
                exportTaskDao.update(
                    current.copy(status = "FAILED", errorMessage = message, endTime = System.currentTimeMillis())
                )
            }
        }
    }

    private fun clearCurrentExport(taskId: Int) {
        if (_currentExportId.value == taskId) {
            _currentExportId.value = null
            currentTransformer = null
        }
    }

    private suspend fun requireCanExport(mediaId: Int) {
        if (!securityManager.canPerformAction(SecurityAction.EXPORT) || !canExportProtectedMedia(mediaId)) {
            throw SecurityException("Export is blocked by app lock, vault access, or parental controls")
        }
    }

    private suspend fun canExportNow(mediaId: Int): Boolean =
        securityManager.canPerformAction(SecurityAction.EXPORT) && canExportProtectedMedia(mediaId)

    private suspend fun canExportProtectedMedia(mediaId: Int): Boolean =
        securityManager.canAccessMedia(mediaId)

    private data class QualityBitrates(val videoBitrate: Int, val audioBitrate: Int)

    private fun qualityBitrates(quality: String): QualityBitrates = when (quality.lowercase()) {
        "high" -> QualityBitrates(videoBitrate = 8_000_000, audioBitrate = 192_000)
        "medium" -> QualityBitrates(videoBitrate = 4_000_000, audioBitrate = 128_000)
        "low" -> QualityBitrates(videoBitrate = 1_500_000, audioBitrate = 96_000)
        else -> throw IllegalArgumentException("Unsupported export quality: $quality")
    }

    private fun videoMimeType(codec: String): String = when (codec.replace(".", "").uppercase()) {
        "H264", "AVC" -> MimeTypes.VIDEO_H264
        "H265", "HEVC" -> MimeTypes.VIDEO_H265
        else -> throw IllegalArgumentException("Unsupported video codec: $codec")
    }

    private fun validateOperations(operations: List<EditingOperation>) {
        operations.filter { it.isEnabled }.forEach { operation ->
            require(operation.startTimeMs >= 0L && operation.endTimeMs > operation.startTimeMs) {
                "Invalid time range for ${operation.type}"
            }
        }
    }

    private fun buildComposition(sourceMedia: MediaItem, operations: List<EditingOperation>): Composition {
        val duration = sourceMedia.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val activeOps = operations.filter { it.isEnabled }
        val normalizedOps = activeOps.mapNotNull { operation ->
            val start = operation.startTimeMs.coerceAtMost(duration)
            val end = operation.endTimeMs.coerceAtMost(duration)
            if (end <= start) null else operation.copy(startTimeMs = start, endTimeMs = end)
        }
        val deletes = normalizedOps.filter { it.type == "DELETE" }.sortedBy { it.startTimeMs }
        val mutes = normalizedOps.filter { it.type == "MUTE" }
        val censors = normalizedOps.filter { it.type.startsWith("CENSOR") }

        val retainedSegments = mutableListOf<Pair<Long, Long>>()
        var currentStart = 0L
        for (delete in deletes) {
            if (delete.startTimeMs > currentStart) retainedSegments += currentStart to delete.startTimeMs
            currentStart = max(currentStart, delete.endTimeMs)
        }
        if (currentStart < duration) retainedSegments += currentStart to duration
        if (retainedSegments.isEmpty()) throw IllegalArgumentException("The edit removes the entire source media")

        val editedItems = mutableListOf<EditedMediaItem>()
        for ((segmentStart, segmentEnd) in retainedSegments) {
            val segmentMutes = mutes.filter { it.startTimeMs < segmentEnd && it.endTimeMs > segmentStart }
            if (segmentMutes.isEmpty()) {
                editedItems += createEditedMediaItem(sourceMedia.path, segmentStart, segmentEnd, false, censors)
                continue
            }
            val mutePoints = mutableSetOf(segmentStart, segmentEnd)
            segmentMutes.forEach { mute ->
                if (mute.startTimeMs > segmentStart) mutePoints += mute.startTimeMs
                if (mute.endTimeMs < segmentEnd) mutePoints += mute.endTimeMs
            }
            val points = mutePoints.sorted()
            for (index in 0 until points.lastIndex) {
                val start = points[index]
                val end = points[index + 1]
                if (end <= start) continue
                val muted = segmentMutes.any { it.startTimeMs <= start && it.endTimeMs >= end }
                editedItems += createEditedMediaItem(sourceMedia.path, start, end, muted, censors)
            }
        }
        return Composition.Builder(listOf(EditedMediaItemSequence(editedItems))).build()
    }

    private fun createEditedMediaItem(
        path: String,
        startMs: Long,
        endMs: Long,
        isMuted: Boolean,
        censors: List<EditingOperation>
    ): EditedMediaItem {
        val mediaItemBuilder = androidx.media3.common.MediaItem.Builder().setUri(Uri.parse(path))
        if (startMs > 0L || endMs < Long.MAX_VALUE) {
            mediaItemBuilder.setClippingConfiguration(
                androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(if (endMs == Long.MAX_VALUE) C.TIME_END_OF_SOURCE else endMs)
                    .build()
            )
        }
        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
        if (isMuted) builder.setRemoveAudio(true)
        val segmentCensors = censors.filter { it.startTimeMs < endMs && it.endTimeMs > startMs }
        if (segmentCensors.isNotEmpty()) {
            builder.setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(CensorEffect(segmentCensors))))
        }
        return builder.build()
    }

    private fun safeFileName(name: String): String = name
        .replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        .trim()
        .trim('.')
        .take(100)
        .ifBlank { "project" }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}
