package com.example.converter

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.MediaType
import com.example.security.SecurityAction
import com.example.security.SecurityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.UUID

object AudioExtractor {
    data class ExtractionResult(
        val isSuccess: Boolean,
        val outputPath: String? = null,
        val outputSizeBytes: Long = 0L,
        val errorMessage: String? = null
    )

    /** Copies the compressed audio track into a private M4A file without decoding/re-encoding it. */
    suspend fun extractAudio(
        context: Context,
        repository: MediaRepository,
        securityManager: SecurityManager,
        videoItem: MediaItem,
        onProgress: (Float) -> Unit = {}
    ): ExtractionResult = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var outputFile: File? = null
        var savedToLibrary = false

        try {
            if (!securityManager.canPerformAction(SecurityAction.EXPORT) ||
                !securityManager.canAccessMedia(videoItem.id)
            ) {
                return@withContext ExtractionResult(false, errorMessage = "استخراج الصوت محظور بسبب إعدادات الأمان")
            }

            val activeExtractor = MediaExtractor()
            extractor = activeExtractor
            setExtractorDataSource(activeExtractor, context, videoItem.path)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until activeExtractor.trackCount) {
                val format = activeExtractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = index
                    audioFormat = format
                    break
                }
            }
            val trackFormat = audioFormat
                ?: return@withContext ExtractionResult(false, errorMessage = "لم يتم العثور على مسار صوتي داخل هذا الفيديو")
            if (audioTrackIndex < 0) {
                return@withContext ExtractionResult(false, errorMessage = "لم يتم العثور على مسار صوتي داخل هذا الفيديو")
            }

            activeExtractor.selectTrack(audioTrackIndex)
            val musicDir = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "Music")).apply {
                if (!exists() && !mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد الصوتيات")
            }
            val cleanName = safeFileName(videoItem.name.substringBeforeLast('.', videoItem.name))
            val output = File(musicDir, "${cleanName}_Audio_${UUID.randomUUID()}.m4a")
            outputFile = output

            val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val muxerTrackIndex = activeMuxer.addTrack(trackFormat)
            activeMuxer.start()

            val requestedBufferSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                DEFAULT_BUFFER_SIZE_BYTES
            }
            val buffer = ByteBuffer.allocate(requestedBufferSize.coerceIn(1024, MAX_BUFFER_SIZE_BYTES))
            val bufferInfo = MediaCodec.BufferInfo()
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                videoItem.durationMs * 1000
            }

            var sawEndOfStream = false
            var sampleCount = 0
            while (!sawEndOfStream) {
                if (sampleCount % SECURITY_RECHECK_INTERVAL_SAMPLES == 0 &&
                    (!securityManager.canPerformAction(SecurityAction.EXPORT) ||
                        !securityManager.canAccessMedia(videoItem.id))
                ) {
                    throw SecurityException("تم إيقاف استخراج الصوت بسبب تغيير صلاحيات الوصول")
                }

                buffer.clear()
                bufferInfo.offset = 0
                bufferInfo.size = activeExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    sawEndOfStream = true
                    bufferInfo.size = 0
                } else {
                    bufferInfo.presentationTimeUs = activeExtractor.sampleTime
                    val sampleFlags = activeExtractor.sampleFlags
                    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                        throw IllegalStateException("لا يمكن استخراج عينات صوتية مشفرة دون فك تشفيرها")
                    }
                    bufferInfo.flags = if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    activeMuxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    activeExtractor.advance()
                    sampleCount++
                    if (sampleCount % PROGRESS_INTERVAL_SAMPLES == 0 && durationUs > 0L) {
                        onProgress((bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                }
            }
            if (sampleCount == 0) throw IllegalStateException("لم يحتوي المسار الصوتي على عينات قابلة للاستخراج")
            if (!securityManager.canPerformAction(SecurityAction.EXPORT) ||
                !securityManager.canAccessMedia(videoItem.id)
            ) {
                throw SecurityException("تم إيقاف استخراج الصوت بسبب تغيير صلاحيات الوصول")
            }

            activeMuxer.stop()
            activeMuxer.release()
            muxer = null
            activeExtractor.release()
            extractor = null
            onProgress(1f)

            val audioItem = MediaItem(
                name = "${cleanName}_Audio.m4a",
                path = output.absolutePath,
                folder = output.parentFile?.name ?: "Music",
                mediaType = MediaType.AUDIO,
                sizeBytes = output.length(),
                dateModified = System.currentTimeMillis() / 1000,
                durationMs = videoItem.durationMs,
                isNew = true,
                mimeType = "audio/mp4",
                sourceMediaId = videoItem.id
            )
            repository.insertOrUpdate(audioItem)
            savedToLibrary = true

            ExtractionResult(true, output.absolutePath, output.length())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ExtractionResult(
                isSuccess = false,
                errorMessage = error.localizedMessage ?: "حدث خطأ أثناء استخراج الصوت"
            )
        } finally {
            runCatching { extractor?.release() }
            runCatching { muxer?.release() }
            if (!savedToLibrary) outputFile?.delete()
        }
    }

    private fun setExtractorDataSource(extractor: MediaExtractor, context: Context, path: String) {
        val uri = Uri.parse(path)
        when (uri.scheme?.lowercase()?.takeIf { it.length > 1 }) {
            "content", "android.resource" -> extractor.setDataSource(context, uri, null)
            "file" -> extractor.setDataSource(uri.path ?: throw FileNotFoundException("Invalid file URI"))
            null -> {
                val file = File(path)
                if (!file.isFile) throw FileNotFoundException("ملف الفيديو غير موجود أو لا يمكن الوصول إليه")
                extractor.setDataSource(file.absolutePath)
            }
            else -> throw IllegalArgumentException("Unsupported media URI scheme: ${uri.scheme}")
        }
    }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        .trim()
        .trim('.')
        .take(100)
        .ifBlank { "audio" }

    private const val DEFAULT_BUFFER_SIZE_BYTES = 512 * 1024
    private const val MAX_BUFFER_SIZE_BYTES = 16 * 1024 * 1024
    private const val PROGRESS_INTERVAL_SAMPLES = 50
    private const val SECURITY_RECHECK_INTERVAL_SAMPLES = 500
}
