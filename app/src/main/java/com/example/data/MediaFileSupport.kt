package com.example.data

import java.util.Locale

/** Shared allow-list for items that may enter or appear in the media library. */
object MediaFileSupport {
    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "flv", "ts", "m4v")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "wma", "amr")
    private val ambiguousContainerExtensions = setOf("mp4", "3gp", "webm")
    private val knownNonMediaMimeTypes = setOf(
        "application/pdf", "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
        "application/rtf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation", "application/zip", "application/x-rar-compressed",
        "application/x-7z-compressed", "application/json", "application/xml", "application/x-msdownload",
        "application/vnd.android.package-archive", "text/csv", "text/html", "text/markdown", "text/xml"
    )
    private val knownNonMediaExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "csv", "txt", "md",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff", "heic", "zip", "rar", "7z", "tar", "gz",
        "apk", "exe", "dll", "html", "htm", "xml", "json", "srt", "vtt", "ass", "ssa", "sub", "idx", "sbv", "lrc"
    )
    private val subtitleExtensions = setOf("srt", "vtt")
    private val subtitleMimeTypes = setOf(
        "application/x-subrip",
        "text/vtt",
        "application/vtt",
        "text/plain",
        "application/octet-stream",
        "binary/octet-stream"
    )

    /**
     * Returns true only when the declared media type agrees with the MIME type and/or
     * a recognized media extension. Generic binary MIME types are accepted only with
     * a known extension; document and image MIME types are rejected.
     */
    fun isSupportedMedia(
        type: MediaType,
        name: String?,
        mimeType: String? = null,
        path: String? = null
    ): Boolean {
        val mime = normalizeMimeType(mimeType)
        val mimeMediaType = mediaTypeFromMime(mime)
        if (isExplicitlyNonMediaMime(mime)) return false

        val extensions = listOfNotNull(extensionOf(name), extensionOf(path))
        if (extensions.any { it in knownNonMediaExtensions }) return false
        if (mimeMediaType != null) {
            if (mimeMediaType != type) return false
            if (extensions.any { extensionContradictsType(type, it) }) return false
            return true
        }

        // Bare .mp4, .3gp and .webm files are treated as video; an explicit audio
        // MIME type is required before one of these shared containers is listed as audio.
        return extensions.any { extensionSupportsWithoutMime(type, it) }
    }

    /** True when a name/MIME pair identifies an audio or video file of either kind. */
    fun isSupportedMediaFile(name: String?, mimeType: String? = null): Boolean {
        val mime = normalizeMimeType(mimeType)
        val mimeMediaType = mediaTypeFromMime(mime)
        if (isExplicitlyNonMediaMime(mime)) return false
        if (mimeMediaType != null) {
            return isSupportedMedia(mimeMediaType, name, mime)
        }
        return extensionOf(name)?.let { it in videoExtensions || it in audioExtensions } == true
    }

    /** Allows local subtitle loading only for SRT and VTT files. */
    fun isSupportedSubtitleFile(name: String?, mimeType: String? = null): Boolean {
        val mime = normalizeMimeType(mimeType)
        if (mime != null && mime !in subtitleMimeTypes) return false
        val extension = extensionOf(name)
        return if (extension != null) {
            extension in subtitleExtensions
        } else {
            mime == "application/x-subrip" || mime == "text/vtt" || mime == "application/vtt"
        }
    }

    private fun extensionContradictsType(type: MediaType, extension: String): Boolean = when (type) {
        MediaType.VIDEO -> extension in audioExtensions
        MediaType.AUDIO -> extension in videoExtensions && extension !in ambiguousContainerExtensions
    }

    private fun extensionSupportsWithoutMime(type: MediaType, extension: String): Boolean = when (type) {
        MediaType.VIDEO -> extension in videoExtensions
        MediaType.AUDIO -> extension in audioExtensions
    }

    private fun isExplicitlyNonMediaMime(mimeType: String?): Boolean =
        mimeType != null && (
            mimeType.startsWith("image/") ||
                mimeType.startsWith("text/") ||
                mimeType in knownNonMediaMimeTypes ||
                mimeType.startsWith("application/vnd.openxmlformats-officedocument.") ||
                mimeType.startsWith("application/vnd.oasis.opendocument.")
            )

    private fun mediaTypeFromMime(mimeType: String?): MediaType? = when {
        mimeType?.startsWith("video/") == true -> MediaType.VIDEO
        mimeType?.startsWith("audio/") == true -> MediaType.AUDIO
        else -> null
    }

    private fun normalizeMimeType(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun extensionOf(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val fileName = value.substringBefore('?').substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('\\')
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.lastIndex) return null
        return fileName.substring(dot + 1).lowercase(Locale.ROOT)
    }
}

fun MediaItem.isSupportedMedia(): Boolean =
    MediaFileSupport.isSupportedMedia(mediaType, name, mimeType, path)
