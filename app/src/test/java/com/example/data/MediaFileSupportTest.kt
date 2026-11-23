package com.example.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileSupportTest {
    @Test
    fun `recognizes media extensions and MIME types`() {
        assertTrue(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "clip.MKV"))
        assertTrue(MediaFileSupport.isSupportedMedia(MediaType.AUDIO, "song.flac"))
        assertTrue(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "clip", "video/quicktime"))
        assertTrue(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "recording.mts", "video/mp2t"))
        assertTrue(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "legacy.flv", "application/x-shockwave-flash"))
        assertTrue(MediaFileSupport.isSupportedMedia(MediaType.AUDIO, "track", "audio/mpeg"))
        assertTrue(MediaFileSupport.isSupportedMedia(MediaType.AUDIO, "audio.mp4", "audio/mp4"))
        assertTrue(MediaFileSupport.isSupportedMedia(MediaType.AUDIO, "audio.webm", "audio/webm"))
    }

    @Test
    fun `rejects documents and media type mismatches`() {
        assertFalse(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "report.pdf", "application/pdf"))
        assertFalse(MediaFileSupport.isSupportedMedia(MediaType.AUDIO, "photo.jpg", "image/jpeg"))
        assertFalse(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "song.mp3", "video/mp4"))
        assertFalse(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "document.pdf", "video/mp4"))
        assertFalse(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "captions.srt", "video/mp4"))
        assertFalse(MediaFileSupport.isSupportedMedia(MediaType.AUDIO, "clip.mp4"))
    }

    @Test
    fun `accepts generic binary MIME only for a known media extension`() {
        assertTrue(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "clip.mp4", "application/octet-stream"))
        assertFalse(MediaFileSupport.isSupportedMedia(MediaType.VIDEO, "report.pdf", "application/octet-stream"))
    }

    @Test
    fun `only recognizes SRT and VTT subtitle files`() {
        assertTrue(MediaFileSupport.isSupportedSubtitleFile("captions.srt", "text/plain"))
        assertTrue(MediaFileSupport.isSupportedSubtitleFile("captions.vtt", "text/vtt"))
        assertFalse(MediaFileSupport.isSupportedSubtitleFile("notes.txt", "text/plain"))
        assertFalse(MediaFileSupport.isSupportedSubtitleFile("report.pdf", "application/pdf"))
    }
}
