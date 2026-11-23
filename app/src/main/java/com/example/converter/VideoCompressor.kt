package com.example.converter

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.MediaType
import com.example.security.SecurityAction
import com.example.security.SecurityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

@OptIn(UnstableApi::class)
object VideoCompressor {
    enum class CompressionPreset(
        val label: String,
        val scaleDesc: String,
        val targetHeight: Int,
        val videoBitrate: Int
    ) {
        BALANCED("متوازن (720p)", "توفير ~50% مع جودة عالية", 720, 4_000_000),
        MAX_SAVINGS("أقصى توفير (480p)", "توفير ~75% للمشاركة السريعة", 480, 1_500_000),
        HIGH_QUALITY("جودة ممتازة (1080p)", "ضغط خفيف ~30% مع تفاصيل كاملة", 1080, 8_000_000)
    }

    data class CompressionState(
        val isCompressing: Boolean = false,
        val progress: Float = 0f,
        val statusMessage: String = "",
        val originalSizeBytes: Long = 0L,
        val compressedSizeBytes: Long = 0L,
        val outputPath: String? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(CompressionState())
    val state: StateFlow<CompressionState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var activeTransformer: Transformer? = null
    private var progressJob: Job? = null
    private val requestIds = AtomicLong(0L)
    private var activeRequestId = 0L
    private var activeOutputFile: File? = null

    fun compressVideo(
        context: Context,
        repository: MediaRepository,
        securityManager: SecurityManager,
        videoItem: MediaItem,
        preset: CompressionPreset = CompressionPreset.BALANCED,
        onComplete: (Boolean, String?) -> Unit
    ) {
        scope.launch {
            if (_state.value.isCompressing) {
                onComplete(false, "A compression task is already running")
                return@launch
            }

            val requestId = requestIds.incrementAndGet()
            activeRequestId = requestId
            _state.value = CompressionState(
                isCompressing = true,
                statusMessage = "جاري التحقق من صلاحيات الوصول...",
                originalSizeBytes = videoItem.sizeBytes
            )

            var outputFile: File? = null
            try {
                ensureCanExport(securityManager, videoItem.id)
                if (activeRequestId != requestId) return@launch

                val inputUri = mediaUri(videoItem.path)
                val outputDir = withContext(Dispatchers.IO) {
                    (context.getExternalFilesDir("compressed_videos")
                        ?: File(context.filesDir, "compressed_videos")).apply {
                        if (!exists() && !mkdirs()) {
                            throw IllegalStateException("تعذر إنشاء مجلد الملفات المضغوطة")
                        }
                    }
                }
                val baseName = safeFileName(videoItem.name.substringBeforeLast('.', videoItem.name))
                val targetFile = File(outputDir, "${baseName}_compressed_${UUID.randomUUID()}.mp4")
                outputFile = targetFile
                activeOutputFile = targetFile
                if (targetFile.exists()) throw IllegalStateException("A compression output already exists")

                val sourceHeight = withContext(Dispatchers.IO) { readVideoHeight(context, inputUri) }
                if (activeRequestId != requestId) return@launch
                ensureCanExport(securityManager, videoItem.id)

                val effects = if (sourceHeight == null || sourceHeight > preset.targetHeight) {
                    listOf(Presentation.createForHeight(preset.targetHeight))
                } else {
                    emptyList()
                }
                val editedItem = EditedMediaItem.Builder(Media3Item.fromUri(inputUri))
                    .setEffects(Effects(emptyList(), effects))
                    .build()
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(preset.videoBitrate).build()
                    )
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(AUDIO_BITRATE).build()
                    )
                    .build()

                val transformer = Transformer.Builder(context)
                    .setEncoderFactory(encoderFactory)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: ExportResult
                        ) {
                            if (activeRequestId != requestId) {
                                targetFile.delete()
                                return
                            }
                            activeTransformer = null
                            progressJob?.cancel()
                            scope.launch {
                                try {
                                    ensureCanExport(securityManager, videoItem.id)
                                    if (activeRequestId != requestId) {
                                        targetFile.delete()
                                        return@launch
                                    }
                                    if (!targetFile.isFile || targetFile.length() <= 0L) {
                                        throw IllegalStateException("ملف الضغط الناتج فارغ أو غير موجود")
                                    }

                                    val compressedItem = MediaItem(
                                        name = "${baseName} (مضغوط).mp4",
                                        path = targetFile.absolutePath,
                                        folder = targetFile.parentFile?.name ?: "compressed",
                                        mediaType = MediaType.VIDEO,
                                        sizeBytes = targetFile.length(),
                                        dateModified = System.currentTimeMillis() / 1000,
                                        durationMs = videoItem.durationMs,
                                        isNew = true,
                                        mimeType = "video/mp4",
                                        sourceMediaId = videoItem.id
                                    )
                                    repository.insertOrUpdate(compressedItem)
                                    if (activeRequestId != requestId) {
                                        repository.deleteByPath(targetFile.absolutePath)
                                        targetFile.delete()
                                        return@launch
                                    }

                                    activeRequestId = 0L
                                    activeOutputFile = null
                                    _state.value = CompressionState(
                                        isCompressing = false,
                                        progress = 1f,
                                        statusMessage = "اكتمل الضغط بنجاح!",
                                        originalSizeBytes = videoItem.sizeBytes,
                                        compressedSizeBytes = targetFile.length(),
                                        outputPath = targetFile.absolutePath
                                    )
                                    runCatching { onComplete(true, targetFile.absolutePath) }
                                } catch (error: Exception) {
                                    failRequest(requestId, targetFile, error.message ?: "فشل ضغط الفيديو", onComplete)
                                }
                            }
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            failRequest(
                                requestId,
                                targetFile,
                                exportException.localizedMessage ?: "فشل ضغط الفيديو",
                                onComplete
                            )
                        }
                    })
                    .build()

                if (activeRequestId != requestId) {
                    targetFile.delete()
                    return@launch
                }
                activeTransformer = transformer
                _state.value = _state.value.copy(statusMessage = "جاري ضغط الفيديو...")
                transformer.start(editedItem, targetFile.absolutePath)

                val progressHolder = ProgressHolder()
                progressJob = scope.launch {
                    while (activeRequestId == requestId && activeTransformer === transformer) {
                        delay(PROGRESS_UPDATE_INTERVAL_MS)
                        if (activeRequestId != requestId || activeTransformer !== transformer) break
                        if (!canExportNow(securityManager, videoItem.id)) {
                            transformer.cancel()
                            failRequest(
                                requestId,
                                targetFile,
                                "تم إيقاف الضغط بسبب تغيير صلاحيات الوصول",
                                onComplete
                            )
                            break
                        }
                        if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val progress = (progressHolder.progress / 100f).coerceIn(0f, 0.99f)
                            _state.value = _state.value.copy(
                                progress = progress,
                                statusMessage = "جاري ضغط الفيديو... ${(progress * 100).toInt()}%"
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                failRequest(
                    requestId,
                    outputFile,
                    error.localizedMessage ?: "حدث خطأ غير متوقع أثناء الضغط",
                    onComplete
                )
            }
        }
    }

    fun cancel() {
        scope.launch {
            if (activeRequestId == 0L) return@launch
            val transformer = activeTransformer
            activeRequestId = 0L
            activeTransformer = null
            progressJob?.cancel()
            progressJob = null
            activeOutputFile?.delete()
            activeOutputFile = null
            transformer?.cancel()
            _state.value = CompressionState(isCompressing = false, statusMessage = "تم إلغاء الضغط")
        }
    }

    private suspend fun ensureCanExport(securityManager: SecurityManager, mediaId: Int) {
        if (!canExportNow(securityManager, mediaId)) {
            throw SecurityException("ضغط أو تصدير الوسائط محظور بسبب إعدادات الأمان")
        }
    }

    private suspend fun canExportNow(securityManager: SecurityManager, mediaId: Int): Boolean =
        securityManager.canPerformAction(SecurityAction.EXPORT) && securityManager.canAccessMedia(mediaId)

    private fun failRequest(requestId: Long, outputFile: File?, message: String, onComplete: (Boolean, String?) -> Unit) {
        if (activeRequestId != requestId) {
            outputFile?.delete()
            return
        }
        activeRequestId = 0L
        val transformer = activeTransformer
        activeTransformer = null
        progressJob?.cancel()
        progressJob = null
        runCatching { transformer?.cancel() }
        outputFile?.delete()
        activeOutputFile = null
        _state.value = CompressionState(isCompressing = false, error = message)
        runCatching { onComplete(false, message) }
    }

    private fun mediaUri(path: String): Uri {
        val uri = Uri.parse(path)
        return when (uri.scheme?.lowercase()?.takeIf { it.length > 1 }) {
            null -> Uri.fromFile(File(path))
            "file", "content", "android.resource" -> uri
            else -> throw IllegalArgumentException("Unsupported media URI scheme: ${uri.scheme}")
        }
    }

    private fun readVideoHeight(context: Context, uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme.equals("content", ignoreCase = true) ||
                uri.scheme.equals("android.resource", ignoreCase = true)
            ) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(uri.path ?: uri.toString())
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun safeFileName(name: String): String = name
        .replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        .trim()
        .trim('.')
        .take(100)
        .ifBlank { "video" }

    private const val AUDIO_BITRATE = 128_000
    private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
}
