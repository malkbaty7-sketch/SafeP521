package com.example.ui.player

import android.app.Application
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.R
import com.example.data.MediaFileSupport
import com.example.data.MediaRepository
import com.example.data.MediaType
import com.example.data.WatchedState
import com.example.security.SecurityAction
import com.example.safety.SAFE_WATCH_ACCEPTED
import com.example.safety.SAFE_WATCH_ALL_PROFILES
import com.example.safety.SAFE_WATCH_PENDING
import com.example.safety.SAFE_WATCH_REJECTED
import com.example.safety.SafeWatchAction
import com.example.safety.SafeWatchCategory
import com.example.safety.SafeWatchImportPreview
import com.example.safety.SafeWatchProfile
import com.example.safety.SafeWatchRuleDraft
import com.example.safety.SubtitleSafetyAnalyzer
import com.example.safety.TimedSafetyText
import com.example.safety.appliesToSafetyProfile
import com.example.safety.isSafeWatchRelated
import com.example.safety.isSafeWatchRule
import com.example.safety.isSafeWatchSuggestion
import com.example.safety.safeWatchAction
import com.example.safety.safeWatchEndTimeMs
import com.example.safety.isValidSafeWatchInterval
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class AspectRatioMode(val labelRes: Int) {
    FIT(R.string.player_aspect_fit),
    FILL(R.string.player_aspect_fill),
    ZOOM(R.string.player_aspect_zoom)
}

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

class PlayerViewModel(
    application: Application,
    private val repository: MediaRepository
) : AndroidViewModel(application) {

    private val safeWatchPreferences = application.getSharedPreferences("safe_watch_preferences", Context.MODE_PRIVATE)
    private val initialSafeWatchProfile = SafeWatchProfile.fromKey(
        safeWatchPreferences.getString("active_profile", SafeWatchProfile.CHILDREN.key)
    )

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? = null
        private set

    // Liquid Glass UI Overlay Controller (Real-time blurRadius, glassOpacity/surfaceAlpha, tintColor)
    val liquidGlassController = com.example.ui.glass.LiquidGlassController()

    private val _isPlayerReady = MutableStateFlow(false)
    val isPlayerReady: StateFlow<Boolean> = _isPlayerReady.asStateFlow()

    var currentMediaId: Int? = null
        private set
    private var pendingMediaItem: com.example.data.MediaItem? = null
    private var pendingStartMs: Long? = null

    private val _currentMedia = MutableStateFlow<com.example.data.MediaItem?>(null)
    val currentMedia: StateFlow<com.example.data.MediaItem?> = _currentMedia.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _markers = MutableStateFlow<List<com.example.data.TimelineMarker>>(emptyList())
    val markers: StateFlow<List<com.example.data.TimelineMarker>> = _markers.asStateFlow()

    // Enhanced Player State
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isMirrored = MutableStateFlow(false)
    val isMirrored: StateFlow<Boolean> = _isMirrored.asStateFlow()

    private val _isNightMode = MutableStateFlow(false)
    val isNightMode: StateFlow<Boolean> = _isNightMode.asStateFlow()

    private val _isControlsLocked = MutableStateFlow(false)
    val isControlsLocked: StateFlow<Boolean> = _isControlsLocked.asStateFlow()

    // Smart Safe-Watch & Family Guard State
    private val _activeSafeWatchProfile = MutableStateFlow(initialSafeWatchProfile)
    val activeSafeWatchProfile: StateFlow<SafeWatchProfile> = _activeSafeWatchProfile.asStateFlow()

    private val _isSafeModeEnabled = MutableStateFlow(
        safeWatchPreferences.getBoolean("safe_mode_${initialSafeWatchProfile.key}", true)
    )
    val isSafeModeEnabled: StateFlow<Boolean> = _isSafeModeEnabled.asStateFlow()

    private val _isSafeBlurActive = MutableStateFlow(false)
    val isSafeBlurActive: StateFlow<Boolean> = _isSafeBlurActive.asStateFlow()

    private val _isSafeCoverActive = MutableStateFlow(false)
    val isSafeCoverActive: StateFlow<Boolean> = _isSafeCoverActive.asStateFlow()

    private val _isSafeMuteActive = MutableStateFlow(false)
    val isSafeMuteActive: StateFlow<Boolean> = _isSafeMuteActive.asStateFlow()

    private val _safeWatchBannerMessage = MutableStateFlow<String?>(null)
    val safeWatchBannerMessage: StateFlow<String?> = _safeWatchBannerMessage.asStateFlow()

    private val _safeWatchAuthorizationRequired = MutableStateFlow(false)
    val safeWatchAuthorizationRequired: StateFlow<Boolean> = _safeWatchAuthorizationRequired.asStateFlow()
    private var pendingSafeWatchAuthorizationAction: (() -> Unit)? = null
    private var pendingSafeWatchAuthorizationMediaId: Int? = null

    // Live Video Filters & Color Adjustments State (Track 2)
    private val _videoFilterPreset = MutableStateFlow("Normal")
    val videoFilterPreset: StateFlow<String> = _videoFilterPreset.asStateFlow()

    private val _filterBrightness = MutableStateFlow(0f) // -0.5f to +0.5f
    val filterBrightness: StateFlow<Float> = _filterBrightness.asStateFlow()

    private val _filterContrast = MutableStateFlow(1f) // 0.5f to 2.0f
    val filterContrast: StateFlow<Float> = _filterContrast.asStateFlow()

    private val _filterSaturation = MutableStateFlow(1f) // 0f to 2.0f
    val filterSaturation: StateFlow<Float> = _filterSaturation.asStateFlow()

    // Video Background Play (Enabled by default for Mini-Player & Background Playback)
    private val _isVideoBgPlayEnabled = MutableStateFlow(true)
    val isVideoBgPlayEnabled: StateFlow<Boolean> = _isVideoBgPlayEnabled.asStateFlow()

    // Orientation Lock (Fixed screen angle lock)
    private val _isOrientationLocked = MutableStateFlow(false)
    val isOrientationLocked: StateFlow<Boolean> = _isOrientationLocked.asStateFlow()

    private val _aspectRatioMode = MutableStateFlow(AspectRatioMode.FIT)
    val aspectRatioMode: StateFlow<AspectRatioMode> = _aspectRatioMode.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _isAbRepeatActive = MutableStateFlow(false)
    val isAbRepeatActive: StateFlow<Boolean> = _isAbRepeatActive.asStateFlow()

    private val _abPointA = MutableStateFlow<Long?>(null)
    val abPointA: StateFlow<Long?> = _abPointA.asStateFlow()

    private val _abPointB = MutableStateFlow<Long?>(null)
    val abPointB: StateFlow<Long?> = _abPointB.asStateFlow()

    // Smart Sleep Timer State
    private val _sleepTimerMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerMinutes: StateFlow<Int?> = _sleepTimerMinutes.asStateFlow()

    private val _sleepTimerRemainingSeconds = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingSeconds: StateFlow<Long?> = _sleepTimerRemainingSeconds.asStateFlow()
    private var sleepTimerJob: Job? = null
    private var markersJob: Job? = null
    private val safeWatchImportMutex = Mutex()

    private val _playlistItems = MutableStateFlow<List<com.example.data.MediaItem>>(emptyList())
    val playlistItems: StateFlow<List<com.example.data.MediaItem>> = _playlistItems.asStateFlow()

    // Equalizer & Audio Boost State (5 Bands: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz)
    private val _isEqualizerEnabled = MutableStateFlow(true)
    val isEqualizerEnabled: StateFlow<Boolean> = _isEqualizerEnabled.asStateFlow()

    private val _equalizerPreset = MutableStateFlow("Flat")
    val equalizerPreset: StateFlow<String> = _equalizerPreset.asStateFlow()

    private val _eqBandLevels = MutableStateFlow(listOf(0f, 0f, 0f, 0f, 0f))
    val eqBandLevels: StateFlow<List<Float>> = _eqBandLevels.asStateFlow()

    private val _bassBoostLevel = MutableStateFlow(0)
    val bassBoostLevel: StateFlow<Int> = _bassBoostLevel.asStateFlow()

    private val _volumeBoostLevel = MutableStateFlow(0)
    val volumeBoostLevel: StateFlow<Int> = _volumeBoostLevel.asStateFlow()

    // External Subtitles (SRT / VTT) State
    private val _subtitlesEnabled = MutableStateFlow(false)
    val subtitlesEnabled: StateFlow<Boolean> = _subtitlesEnabled.asStateFlow()

    private val _subtitleFileName = MutableStateFlow<String?>(null)
    val subtitleFileName: StateFlow<String?> = _subtitleFileName.asStateFlow()

    private val _subtitleCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    val subtitleCues: StateFlow<List<SubtitleCue>> = _subtitleCues.asStateFlow()

    private val _currentSubtitleText = MutableStateFlow<String?>(null)
    val currentSubtitleText: StateFlow<String?> = _currentSubtitleText.asStateFlow()

    private val _subtitleDelayMs = MutableStateFlow(0L)
    val subtitleDelayMs: StateFlow<Long> = _subtitleDelayMs.asStateFlow()

    private val _subtitleFontSizeSp = MutableStateFlow(18f)
    val subtitleFontSizeSp: StateFlow<Float> = _subtitleFontSizeSp.asStateFlow()

    private val _subtitleColorIndex = MutableStateFlow(0) // 0=White, 1=Yellow, 2=Cyan, 3=Green
    val subtitleColorIndex: StateFlow<Int> = _subtitleColorIndex.asStateFlow()

    private val _subtitleBgOpacity = MutableStateFlow(0.65f)
    val subtitleBgOpacity: StateFlow<Float> = _subtitleBgOpacity.asStateFlow()

    private val _subtitleFontFamilyIndex = MutableStateFlow(0) // 0=Default, 1=Serif (نسخ), 2=Monospace (عريض), 3=Cursive (رقعة)
    val subtitleFontFamilyIndex: StateFlow<Int> = _subtitleFontFamilyIndex.asStateFlow()

    fun setSubtitleFontFamilyIndex(index: Int) {
        _subtitleFontFamilyIndex.value = index.coerceIn(0, 3)
    }

    private val safeApp = application as? com.example.SafePlayerApp
    private val mediaLoadRequestIds = AtomicLong(0L)
    private val _bookmarks = MutableStateFlow<List<com.example.bookmarks.VideoBookmark>>(emptyList())
    val bookmarks: StateFlow<List<com.example.bookmarks.VideoBookmark>> = _bookmarks.asStateFlow()

    fun refreshBookmarks() {
        val mid = currentMediaId ?: return
        if (mid <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            safeApp?.let {
                _bookmarks.value = it.videoBookmarkDao.getBookmarksForMediaSync(mid)
            }
        }
    }

    fun addBookmark(title: String, note: String = "") {
        val mid = currentMediaId ?: return
        if (mid <= 0) return
        val pos = _currentPosition.value
        viewModelScope.launch(Dispatchers.IO) {
            safeApp?.let {
                it.videoBookmarkDao.insertBookmark(
                    com.example.bookmarks.VideoBookmark(
                        mediaId = mid,
                        timestampMs = pos,
                        title = title.ifBlank { "علامة عند " + formatTimeDisplay(pos) },
                        note = note
                    )
                )
                refreshBookmarks()
            }
        }
    }

    fun deleteBookmark(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            safeApp?.let {
                it.videoBookmarkDao.deleteBookmarkById(id)
                refreshBookmarks()
            }
        }
    }

    fun loadStreamUrl(url: String, title: String) {
        clearSubtitles()
        markersJob?.cancel()
        _markers.value = emptyList()
        resetSafeWatchPlaybackState()
        currentMediaId = -1
        _currentMedia.value = com.example.data.MediaItem(
            id = -1,
            name = title,
            path = url,
            folder = "بث مباشر",
            mediaType = com.example.data.MediaType.VIDEO,
            sizeBytes = 0L,
            dateModified = System.currentTimeMillis() / 1000,
            durationMs = 0L
        )
        _bookmarks.value = emptyList()
        _currentPosition.value = 0L
        _duration.value = 0L
        val p = player
        if (p != null) {
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            p.prepare()
            p.play()
        } else {
            pendingMediaItem = _currentMedia.value
        }
    }

    fun loadSubtitleText(name: String, content: String) {
        if (_currentMedia.value?.mediaType != MediaType.VIDEO) return
        val cues = parseSubtitles(content)
        if (cues.isNotEmpty()) {
            _subtitleFileName.value = name
            _subtitleCues.value = cues
            _subtitlesEnabled.value = true
            updateSubtitleForPosition(_currentPosition.value)
        }
    }

    private fun formatTimeDisplay(ms: Long): String {
        val totalSecs = ms / 1000
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        val hours = totalSecs / 3600
        return if (hours > 0) String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, mins, secs)
        else String.format(java.util.Locale.getDefault(), "%02d:%02d", mins, secs)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _duration.value = player?.duration ?: 0L
                syncAudioEffects()
            } else if (playbackState == Player.STATE_ENDED) {
                savePosition(true)
                if (_sleepTimerMinutes.value == -1) {
                    player?.pause()
                    _sleepTimerMinutes.value = null
                    _sleepTimerRemainingSeconds.value = null
                }
            }
        }
    }

    init {
        val sessionToken = SessionToken(
            application,
            ComponentName(application, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture?.addListener({
            player = controllerFuture?.get()
            player?.addListener(playerListener)
            _isPlayerReady.value = true
            syncAudioEffects()

            pendingMediaItem?.let {
                loadMediaItem(it, pendingStartMs)
                pendingMediaItem = null
                pendingStartMs = null
            }
        }, MoreExecutors.directExecutor())
    }

    fun loadMediaItem(dbItem: com.example.data.MediaItem, startMs: Long? = null) {
        if (dbItem.id >= 0 && !MediaFileSupport.isSupportedMedia(dbItem.mediaType, dbItem.name, dbItem.mimeType, dbItem.path)) {
            stopAndClearMedia()
            return
        }
        val requestId = mediaLoadRequestIds.incrementAndGet()
        viewModelScope.launch {
            val securityManager = safeApp?.securityManager
            if (securityManager != null &&
                (!securityManager.canPerformAction(SecurityAction.PLAY) ||
                    !securityManager.canAccessMedia(dbItem.id))
            ) {
                if (requestId == mediaLoadRequestIds.get()) stopAndClearMedia()
                return@launch
            }
            if (requestId != mediaLoadRequestIds.get()) return@launch
            loadAuthorizedMediaItem(dbItem, startMs)
        }
    }

    private fun loadAuthorizedMediaItem(dbItem: com.example.data.MediaItem, startMs: Long?) {
        val isSameMedia = currentMediaId == dbItem.id && player?.currentMediaItem != null
        if (!isSameMedia) {
            clearSubtitles()
            markersJob?.cancel()
            _markers.value = emptyList()
            resetSafeWatchPlaybackState()
        }
        _currentMedia.value = dbItem
        currentMediaId = dbItem.id
        if (!isSameMedia) {
            _currentPosition.value = (startMs ?: dbItem.lastPositionMs).coerceAtLeast(0L)
            _duration.value = dbItem.durationMs.coerceAtLeast(0L)
        }

        viewModelScope.launch {
            try {
                repository.allMedia.collect { all ->
                    _playlistItems.value = all.filter { it.mediaType == dbItem.mediaType }
                }
            } catch (_: Exception) {}
        }

        if (player == null) {
            pendingMediaItem = dbItem
            pendingStartMs = startMs
            return
        }

        if (isSameMedia) {
            if (startMs != null) {
                player?.seekTo(startMs)
            }
            if (player?.isPlaying != true) {
                player?.play()
            }
            return
        }

        val uri = when {
            dbItem.path.startsWith("content://") || dbItem.path.startsWith("file://") -> Uri.parse(dbItem.path)
            else -> Uri.fromFile(java.io.File(dbItem.path))
        }
        val mediaItem = MediaItem.fromUri(uri)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        if (startMs != null) {
            player?.seekTo(startMs)
        } else if (dbItem.lastPositionMs > 0) {
            player?.seekTo(dbItem.lastPositionMs)
        }
        player?.playWhenReady = true

        markersJob?.cancel()
        markersJob = viewModelScope.launch {
            repository.getMarkersForMedia(dbItem.id).collect { mediaMarkers ->
                if (currentMediaId == dbItem.id) {
                    _markers.value = mediaMarkers
                    evaluateSafeWatchRules(_currentPosition.value)
                }
            }
        }
        refreshBookmarks()
    }

    fun stopAndClearMedia() {
        mediaLoadRequestIds.incrementAndGet()
        clearSubtitles()
        markersJob?.cancel()
        _markers.value = emptyList()
        resetSafeWatchPlaybackState()
        savePosition(false)
        player?.pause()
        player?.clearMediaItems()
        _currentMedia.value = null
        currentMediaId = null
        _currentPosition.value = 0L
        _duration.value = 0L
        _isPlaying.value = false
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        val boundedPosition = if (_duration.value > 0L) positionMs.coerceIn(0L, _duration.value) else positionMs.coerceAtLeast(0L)
        player?.seekTo(boundedPosition)
        _currentPosition.value = boundedPosition
        evaluateSafeWatchRules(boundedPosition)
        updateSubtitleForPosition(_currentPosition.value)
    }

    fun seekRelative(offsetMs: Long) {
        val cur = player?.currentPosition ?: 0L
        val maxDur = _duration.value.coerceAtLeast(1L)
        val target = (cur + offsetMs).coerceIn(0L, maxDur)
        seekTo(target)
    }

    fun savePosition(finished: Boolean = false) {
        currentMediaId?.let { id ->
            viewModelScope.launch {
                val position = if (finished) 0L else (player?.currentPosition ?: 0L)
                val state = if (finished) WatchedState.WATCHED else WatchedState.IN_PROGRESS
                repository.updatePlaybackState(id, state, position)
            }
        }
    }

    fun updatePosition() {
        var cur = player?.currentPosition ?: 0L
        _currentPosition.value = cur

        // Handle AB Repeat
        if (_isAbRepeatActive.value) {
            val a = _abPointA.value
            val b = _abPointB.value
            if (a != null && b != null && cur >= b) {
                player?.seekTo(a)
                cur = a
            }
        }

        // Apply guard rules before choosing the subtitle for the displayed playback position.
        evaluateSafeWatchRules(cur)
        updateSubtitleForPosition(_currentPosition.value)
    }

    private fun resetSafeWatchPlaybackState() {
        _isSafeBlurActive.value = false
        _isSafeCoverActive.value = false
        _isSafeMuteActive.value = false
        _safeWatchBannerMessage.value = null
        player?.volume = if (_isMuted.value) 0f else 1f
    }

    private fun evaluateSafeWatchRules(positionMs: Long) {
        if (!_isSafeModeEnabled.value) {
            resetSafeWatchPlaybackState()
            return
        }

        val activeProfile = _activeSafeWatchProfile.value
        val rules = _markers.value.filter { marker ->
            marker.isSafeWatchRule() && marker.appliesToSafetyProfile(activeProfile)
        }
        var playbackPosition = positionMs
        var lastSkippedRule: com.example.data.TimelineMarker? = null
        while (true) {
            val skipRule = rules.asSequence()
                .filter { marker ->
                    marker.safeWatchAction() == SafeWatchAction.SKIP &&
                        playbackPosition >= marker.timeMs && playbackPosition < marker.safeWatchEndTimeMs()
                }
                .maxByOrNull { it.safeWatchEndTimeMs() }
                ?: break
            val targetEndMs = skipRule.safeWatchEndTimeMs()
                .coerceAtMost(_duration.value.takeIf { it > 0L } ?: Long.MAX_VALUE)
            if (targetEndMs <= playbackPosition) break
            player?.seekTo(targetEndMs)
            playbackPosition = targetEndMs
            _currentPosition.value = targetEndMs
            lastSkippedRule = skipRule
        }

        val activeRules = rules.filter { playbackPosition >= it.timeMs && playbackPosition < it.safeWatchEndTimeMs() }
        val muteRule = activeRules.firstOrNull { it.safeWatchAction() == SafeWatchAction.MUTE }
        val blurRule = activeRules.firstOrNull { it.safeWatchAction() == SafeWatchAction.BLUR }
        val coverRule = activeRules.firstOrNull { it.safeWatchAction() == SafeWatchAction.COVER }

        _isSafeMuteActive.value = muteRule != null
        _isSafeBlurActive.value = blurRule != null
        _isSafeCoverActive.value = coverRule != null
        player?.volume = if (_isMuted.value || muteRule != null) 0f else 1f

        val displayedRule = coverRule ?: blurRule ?: muteRule
        _safeWatchBannerMessage.value = displayedRule?.let { rule ->
            val actionLabel = when (rule.safeWatchAction()) {
                SafeWatchAction.MUTE -> "كتم الصوت"
                SafeWatchAction.COVER -> "حجب الصورة"
                else -> "تعتيم الصورة"
            }
            "الحارس العائلي: $actionLabel (${rule.name ?: "مقطع محمي"})"
        } ?: lastSkippedRule?.let { rule ->
            "الحارس العائلي: تم تخطي المقطع (${rule.name ?: "مشهد محمي"})"
        }
        updateSubtitleForPosition(playbackPosition)
    }

    private fun updateSubtitleForPosition(positionMs: Long) {
        if (!_subtitlesEnabled.value || _subtitleCues.value.isEmpty()) {
            if (_currentSubtitleText.value != null) {
                _currentSubtitleText.value = null
            }
            return
        }
        val adjustedTime = positionMs - _subtitleDelayMs.value
        val activeCue = _subtitleCues.value.firstOrNull { cue ->
            adjustedTime in cue.startMs..cue.endMs
        }
        _currentSubtitleText.value = activeCue?.text
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        player?.setPlaybackSpeed(speed)
    }

    fun cyclePlaybackSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val currentIndex = speeds.indexOfFirst { kotlin.math.abs(it - _playbackSpeed.value) < 0.05f }
        val nextIndex = if (currentIndex >= 0 && currentIndex < speeds.size - 1) currentIndex + 1 else 0
        setPlaybackSpeed(speeds[nextIndex])
    }

    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        player?.volume = if (newMuted || _isSafeMuteActive.value) 0f else 1f
    }

    fun toggleMirror() {
        _isMirrored.value = !_isMirrored.value
    }

    fun toggleNightMode() {
        _isNightMode.value = !_isNightMode.value
    }

    fun toggleControlsLock() {
        _isControlsLocked.value = !_isControlsLocked.value
    }

    fun toggleSafeMode() {
        setSafeModeEnabled(!_isSafeModeEnabled.value)
    }

    // --- Track 2: Live Video Filters & Instant Snapshot ---
    fun setVideoFilterPreset(preset: String) {
        _videoFilterPreset.value = preset
        when (preset) {
            "Normal" -> {
                _filterBrightness.value = 0f
                _filterContrast.value = 1f
                _filterSaturation.value = 1f
            }
            "Cinema HDR" -> {
                _filterBrightness.value = 0.05f
                _filterContrast.value = 1.32f
                _filterSaturation.value = 1.30f
            }
            "Vivid" -> {
                _filterBrightness.value = 0.07f
                _filterContrast.value = 1.20f
                _filterSaturation.value = 1.60f
            }
            "Ultra Clarity 4K" -> {
                _filterBrightness.value = 0.06f
                _filterContrast.value = 1.45f
                _filterSaturation.value = 1.20f
            }
            "AMOLED True Black" -> {
                _filterBrightness.value = -0.04f
                _filterContrast.value = 1.40f
                _filterSaturation.value = 1.25f
            }
            "Warm EyeCare" -> {
                _filterBrightness.value = -0.05f
                _filterContrast.value = 0.95f
                _filterSaturation.value = 0.85f
            }
            "Sharp" -> {
                _filterBrightness.value = 0.05f
                _filterContrast.value = 1.38f
                _filterSaturation.value = 1.1f
            }
            "B&W" -> {
                _filterBrightness.value = 0.02f
                _filterContrast.value = 1.25f
                _filterSaturation.value = 0f
            }
        }
    }

    fun setFilterBrightness(value: Float) {
        _filterBrightness.value = value.coerceIn(-0.5f, 0.5f)
        _videoFilterPreset.value = "Custom"
    }

    fun setFilterContrast(value: Float) {
        _filterContrast.value = value.coerceIn(0.5f, 2.0f)
        _videoFilterPreset.value = "Custom"
    }

    fun setFilterSaturation(value: Float) {
        _filterSaturation.value = value.coerceIn(0f, 2.0f)
        _videoFilterPreset.value = "Custom"
    }

    fun resetVideoFilters() {
        setVideoFilterPreset("Normal")
    }

    fun captureVideoSnapshot(context: Context, onResult: (Boolean, String?, Uri?) -> Unit) {
        val media = _currentMedia.value
        if (media == null) {
            onResult(false, null, null)
            return
        }
        val posMs = _currentPosition.value
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    val uri = when {
                        media.path.startsWith("content://") || media.path.startsWith("file://") -> Uri.parse(media.path)
                        else -> Uri.fromFile(File(media.path))
                    }
                    retriever.setDataSource(context, uri)
                    val frameBitmap = retriever.getFrameAtTime(posMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime()
                        ?: return@withContext Pair<String?, Uri?>(null, null)

                    val fileName = "SafePlayer_${System.currentTimeMillis()}.jpg"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SafePlayer")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val imgUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (imgUri != null) {
                            resolver.openOutputStream(imgUri)?.use { out ->
                                frameBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                            }
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(imgUri, values, null, null)
                            return@withContext Pair(fileName, imgUri)
                        }
                    }
                    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SafePlayer")
                    if (!dir.exists()) dir.mkdirs()
                    val outFile = File(dir, fileName)
                    FileOutputStream(outFile).use { out ->
                        frameBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    Pair(fileName, Uri.fromFile(outFile))
                } catch (_: Exception) {
                    Pair(null, null)
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }
            if (saved.first != null && saved.second != null) {
                onResult(true, saved.first, saved.second)
            } else {
                onResult(false, null, null)
            }
        }
    }

    // --- Family Safe-Watch Rules ---
    private suspend fun canManageSafeWatch(retryAfterAuthorization: (() -> Unit)? = null): Boolean {
        val manager = safeApp?.securityManager ?: return true
        val allowed = manager.canManageSafeWatch()
        if (!allowed) {
            if (retryAfterAuthorization != null) {
                pendingSafeWatchAuthorizationAction = retryAfterAuthorization
                pendingSafeWatchAuthorizationMediaId = currentMediaId
            }
            _safeWatchAuthorizationRequired.value = true
        } else if (_safeWatchAuthorizationRequired.value) {
            pendingSafeWatchAuthorizationAction = null
            pendingSafeWatchAuthorizationMediaId = null
            _safeWatchAuthorizationRequired.value = false
        }
        return allowed
    }

    fun dismissSafeWatchAuthorizationRequest() {
        pendingSafeWatchAuthorizationAction = null
        pendingSafeWatchAuthorizationMediaId = null
        _safeWatchAuthorizationRequired.value = false
    }

    fun verifySafeWatchParentPin(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val verified = safeApp?.securityManager?.verifyParentPin(pin) == true
            _safeWatchAuthorizationRequired.value = !verified
            if (verified) {
                val retry = pendingSafeWatchAuthorizationAction
                val requestedForMediaId = pendingSafeWatchAuthorizationMediaId
                pendingSafeWatchAuthorizationAction = null
                pendingSafeWatchAuthorizationMediaId = null
                onResult(true)
                if (requestedForMediaId == currentMediaId) retry?.invoke()
            } else {
                onResult(false)
            }
        }
    }

    fun setActiveSafeWatchProfile(profile: SafeWatchProfile) {
        if (profile == _activeSafeWatchProfile.value) return
        viewModelScope.launch {
            if (!canManageSafeWatch { setActiveSafeWatchProfile(profile) }) return@launch
            safeWatchPreferences.edit().putString("active_profile", profile.key).apply()
            _activeSafeWatchProfile.value = profile
            _isSafeModeEnabled.value = safeWatchPreferences.getBoolean("safe_mode_${profile.key}", true)
            evaluateSafeWatchRules(_currentPosition.value)
        }
    }

    fun setSafeModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // Enabling protection is always allowed; disabling it is parent-authorized when controls are on.
            if (!enabled && !canManageSafeWatch { setSafeModeEnabled(enabled) }) return@launch
            safeWatchPreferences.edit()
                .putBoolean("safe_mode_${_activeSafeWatchProfile.value.key}", enabled)
                .apply()
            _isSafeModeEnabled.value = enabled
            evaluateSafeWatchRules(_currentPosition.value)
        }
    }

    fun saveSafeWatchRule(
        draft: SafeWatchRuleDraft,
        existingMarkerId: Int? = null,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            if (!canManageSafeWatch { saveSafeWatchRule(draft, existingMarkerId, onResult) }) {
                onResult(false)
                return@launch
            }
            val mediaId = currentMediaId
            val media = _currentMedia.value
            if (mediaId == null || mediaId <= 0 || media == null || media.id != mediaId || media.mediaType != MediaType.VIDEO) {
                onResult(false)
                return@launch
            }
            val durationMs = _duration.value.takeIf { it > 0L } ?: media.durationMs
            if (!isValidSafeWatchInterval(draft.startMs, draft.endMs, durationMs) || !isValidSafeWatchProfileId(draft.profileId)) {
                onResult(false)
                return@launch
            }

            val previous = existingMarkerId?.let { id -> _markers.value.firstOrNull { it.id == id } }
            if (existingMarkerId != null && (previous == null || !previous.isSafeWatchRelated())) {
                onResult(false)
                return@launch
            }
            val marker = com.example.data.TimelineMarker(
                id = previous?.id ?: 0,
                mediaId = mediaId,
                timeMs = draft.startMs,
                name = draft.name.trim().take(100).ifBlank { "${draft.category.label} • ${formatTimeDisplay(draft.startMs)}" },
                type = draft.action.markerType,
                createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                endTimeMs = draft.endMs,
                category = draft.category.key,
                action = draft.action.key,
                profileId = draft.profileId,
                confidence = draft.confidence?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
                source = draft.source,
                reviewStatus = SAFE_WATCH_ACCEPTED
            )
            if (previous == null) repository.insertMarker(marker) else repository.updateMarker(marker)
            enableSafeWatchForProfile(draft.profileId)
            evaluateSafeWatchRules(_currentPosition.value)
            onResult(true)
        }
    }

    fun rejectSafeWatchSuggestion(markerId: Int) {
        viewModelScope.launch {
            if (!canManageSafeWatch { rejectSafeWatchSuggestion(markerId) }) return@launch
            val suggestion = _markers.value.firstOrNull { it.id == markerId && it.isSafeWatchSuggestion() } ?: return@launch
            repository.updateMarker(suggestion.copy(reviewStatus = SAFE_WATCH_REJECTED))
        }
    }

    fun skipCurrentSafeWatchSegment() {
        val position = _currentPosition.value
        val activeEndMs = _markers.value.asSequence()
            .filter { marker ->
                marker.isSafeWatchRule() && marker.appliesToSafetyProfile(_activeSafeWatchProfile.value) &&
                    position >= marker.timeMs && position < marker.safeWatchEndTimeMs()
            }
            .map { it.safeWatchEndTimeMs() }
            .maxOrNull()
        if (activeEndMs == null) seekRelative(5_000L) else seekTo(activeEndMs)
    }

    private fun isValidSafeWatchProfileId(profileId: String): Boolean =
        profileId == SAFE_WATCH_ALL_PROFILES || SafeWatchProfile.entries.any { it.key == profileId }

    private fun enableSafeWatchForProfile(profileId: String) {
        val profiles = if (profileId == SAFE_WATCH_ALL_PROFILES) {
            SafeWatchProfile.entries
        } else {
            SafeWatchProfile.entries.filter { it.key == profileId }
        }
        if (profiles.isEmpty()) return
        val editor = safeWatchPreferences.edit()
        profiles.forEach { profile -> editor.putBoolean("safe_mode_${profile.key}", true) }
        editor.apply()
        if (profiles.any { it == _activeSafeWatchProfile.value }) {
            _isSafeModeEnabled.value = true
        }
    }

    private suspend fun persistSafetySuggestions(
        mediaId: Int,
        source: String,
        suggestions: List<com.example.safety.SubtitleSafetySuggestion>,
        profile: SafeWatchProfile
    ): Int {
        val media = _currentMedia.value ?: return 0
        if (currentMediaId != mediaId || media.id != mediaId || _activeSafeWatchProfile.value != profile) return 0
        val durationMs = _duration.value.takeIf { it > 0L } ?: media.durationMs
        repository.clearPendingSafetySuggestions(mediaId, source, profile.key)
        if (currentMediaId != mediaId || _activeSafeWatchProfile.value != profile || suggestions.isEmpty()) return 0

        val mediaMarkers = try {
            repository.getMarkersForMedia(mediaId).first()
        } catch (_: Exception) {
            _markers.value.filter { it.mediaId == mediaId }
        }
        if (currentMediaId != mediaId || _activeSafeWatchProfile.value != profile) return 0
        val reviewed = mediaMarkers.filter { marker ->
            marker.category != null && marker.appliesToSafetyProfile(profile) &&
                (marker.reviewStatus == SAFE_WATCH_ACCEPTED ||
                    (marker.type == "SAFE_SUGGESTION" && marker.reviewStatus == SAFE_WATCH_REJECTED && marker.source == source))
        }
        val addedIntervals = mutableListOf<Triple<SafeWatchCategory, Long, Long>>()
        var added = 0
        for (suggestion in suggestions.take(1_000)) {
            if (currentMediaId != mediaId || _activeSafeWatchProfile.value != profile) return added
            val startMs = suggestion.startMs.coerceAtLeast(0L)
            val endMs = if (durationMs > 0L) suggestion.endMs.coerceAtMost(durationMs) else suggestion.endMs
            if (!isValidSafeWatchInterval(startMs, endMs, durationMs)) continue
            val alreadyReviewed = reviewed.any { existing ->
                existing.category == suggestion.category.key &&
                    startMs < existing.safeWatchEndTimeMs() && endMs > existing.timeMs
            }
            val alreadySuggested = addedIntervals.any { (category, start, end) ->
                category == suggestion.category && startMs < end && endMs > start
            }
            if (alreadyReviewed || alreadySuggested) continue
            val confidence = suggestion.confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            repository.insertMarker(
                com.example.data.TimelineMarker(
                    mediaId = mediaId,
                    timeMs = startMs,
                    name = suggestion.reason.take(100),
                    type = "SAFE_SUGGESTION",
                    endTimeMs = endMs,
                    category = suggestion.category.key,
                    action = suggestion.category.suggestedAction.key,
                    profileId = profile.key,
                    confidence = confidence,
                    source = source,
                    reviewStatus = SAFE_WATCH_PENDING
                )
            )
            addedIntervals += Triple(suggestion.category, startMs, endMs)
            added++
        }
        return added
    }

    fun analyzeLoadedSubtitlesForSafety(onResult: (Int) -> Unit) {
        val mediaId = currentMediaId
        if (mediaId == null || mediaId <= 0 || _subtitleCues.value.isEmpty()) {
            onResult(0)
            return
        }
        val cuesSnapshot = _subtitleCues.value.toList()
        viewModelScope.launch {
            if (!canManageSafeWatch { analyzeLoadedSubtitlesForSafety(onResult) }) {
                onResult(0)
                return@launch
            }
            val profile = _activeSafeWatchProfile.value
            val suggestions = withContext(Dispatchers.Default) {
                SubtitleSafetyAnalyzer.analyze(cuesSnapshot.map { TimedSafetyText(it.startMs, it.endMs, it.text) })
            }
            if (currentMediaId != mediaId || _activeSafeWatchProfile.value != profile) {
                onResult(0)
                return@launch
            }
            onResult(persistSafetySuggestions(mediaId, "SUBTITLE_KEYWORD", suggestions, profile))
        }
    }

    fun createVisualSafetySuggestions(onResult: (Int) -> Unit) {
        val mediaId = currentMediaId
        if (mediaId == null || mediaId <= 0 || _currentMedia.value?.mediaType != MediaType.VIDEO) {
            onResult(0)
            return
        }
        viewModelScope.launch {
            if (!canManageSafeWatch { createVisualSafetySuggestions(onResult) }) {
                onResult(0)
                return@launch
            }
            val profile = _activeSafeWatchProfile.value
            val detections = try {
                repository.getDetectionResultsForMedia(mediaId).first()
            } catch (_: Exception) {
                emptyList()
            }
            val suggestions = detections.asSequence()
                .filter { it.status != "REJECTED" && it.modality.equals("VISUAL", ignoreCase = true) }
                .mapNotNull { result ->
                    val category = when {
                        result.category.contains(Regex("violence|blood|weapon|gun|fight", RegexOption.IGNORE_CASE)) -> SafeWatchCategory.VIOLENCE
                        result.category.contains(Regex("skin|flesh|underwear|undergarment|swimwear|nudity", RegexOption.IGNORE_CASE)) -> SafeWatchCategory.ADULT
                        else -> null
                    } ?: return@mapNotNull null
                    com.example.safety.SubtitleSafetySuggestion(
                        startMs = (result.startTimeMs - 1_000L).coerceAtLeast(0L),
                        endMs = maxOf(result.endTimeMs, result.startTimeMs) + 1_000L,
                        category = category,
                        confidence = result.confidence,
                        reason = "اقتراح من الفحص البصري المحلي: ${result.category.take(40)}"
                    )
                }
                .toList()
            if (currentMediaId != mediaId || _activeSafeWatchProfile.value != profile) {
                onResult(0)
                return@launch
            }
            onResult(persistSafetySuggestions(mediaId, "LOCAL_VISUAL_AI", suggestions, profile))
        }
    }

    fun exportSafeWatchRulesJson(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            if (!canManageSafeWatch { exportSafeWatchRulesJson(onResult) }) {
                onResult(null)
                return@launch
            }
            val media = _currentMedia.value
            val mediaId = currentMediaId
            if (media == null || media.mediaType != MediaType.VIDEO || mediaId == null || mediaId <= 0 || media.id != mediaId) {
                onResult(null)
                return@launch
            }
            val markersForMedia = try {
                repository.getMarkersForMedia(mediaId).first()
            } catch (_: Exception) {
                _markers.value.filter { it.mediaId == mediaId }
            }
            if (currentMediaId != mediaId || _currentMedia.value?.let { safetyMediaFingerprint(it) } != safetyMediaFingerprint(media)) {
                onResult(null)
                return@launch
            }
            val safeRules = markersForMedia.filter { it.isSafeWatchRule() }
            if (safeRules.isEmpty() || safeRules.size > 500) {
                onResult(null)
                return@launch
            }
            val root = JSONObject().apply {
                put("app", "SafePlayer")
                put("version", 2)
                put("mediaName", media.name)
                put("mediaFingerprint", safetyMediaFingerprint(media))
                put("rules", JSONArray().apply {
                    safeRules.sortedBy { it.timeMs }.forEach { marker ->
                        val action = marker.safeWatchAction() ?: return@forEach
                        put(JSONObject().apply {
                            put("startMs", marker.timeMs)
                            put("endMs", marker.safeWatchEndTimeMs())
                            put("action", action.key)
                            put("category", SafeWatchCategory.fromKey(marker.category)?.key ?: SafeWatchCategory.CUSTOM.key)
                            put("profileId", marker.profileId ?: SAFE_WATCH_ALL_PROFILES)
                            put("name", marker.name ?: "قاعدة حماية")
                            put("source", marker.source ?: "MANUAL")
                            marker.confidence?.takeIf { it.isFinite() && it in 0f..1f }?.let { put("confidence", it) }
                        })
                    }
                })
            }
            onResult(root.toString(2))
        }
    }

    fun previewSafeWatchRulesJson(rawJson: String): SafeWatchImportPreview {
        val media = _currentMedia.value ?: return SafeWatchImportPreview(error = "لا يوجد فيديو مفتوح حالياً")
        if (media.mediaType != MediaType.VIDEO) return SafeWatchImportPreview(error = "قواعد الحماية متاحة للفيديوهات فقط")
        if (media.id <= 0) return SafeWatchImportPreview(error = "استيراد القواعد متاح للفيديوهات المحلية فقط")
        if (currentMediaId != media.id) return SafeWatchImportPreview(error = "تغيّر الفيديو الحالي؛ أعد فتح المعاينة")
        if (rawJson.length > 1_000_000) return SafeWatchImportPreview(error = "حجم ملف الحماية أكبر من الحد المسموح")
        if (hasExcessiveSafetyJsonNesting(rawJson)) return SafeWatchImportPreview(error = "بنية JSON أعمق من الحد المسموح")
        return try {
            val root = JSONObject(rawJson.trim())
            if (root.optString("app") != "SafePlayer") return SafeWatchImportPreview(error = "الملف ليس حزمة حماية من SafePlayer")
            val version = root.optInt("version", 1)
            if (version !in 1..2) return SafeWatchImportPreview(error = "إصدار ملف الحماية غير مدعوم")
            if (version >= 2) {
                if (root.optString("mediaFingerprint") != safetyMediaFingerprint(media)) {
                    return SafeWatchImportPreview(error = "بصمة بيانات الملف لا تطابق الفيديو المفتوح؛ لم تُطبّق أي قاعدة")
                }
            } else if (!root.optString("mediaName").trim().equals(media.name.trim(), ignoreCase = true)) {
                return SafeWatchImportPreview(error = "اسم الفيديو في الملف القديم لا يطابق الفيديو المفتوح")
            }

            val arr = root.optJSONArray("rules") ?: return SafeWatchImportPreview(error = "لا توجد قائمة قواعد صالحة في الملف")
            if (arr.length() == 0 || arr.length() > 500) {
                return SafeWatchImportPreview(error = "يجب أن يحتوي الملف على قاعدة واحدة إلى 500 قاعدة")
            }
            val durationMs = _duration.value.takeIf { it > 0L } ?: media.durationMs
            val drafts = LinkedHashMap<String, SafeWatchRuleDraft>()
            for (index in 0 until arr.length()) {
                val item = arr.optJSONObject(index) ?: return SafeWatchImportPreview(error = "القاعدة رقم ${index + 1} غير صالحة")
                val startMs = item.optLong("startMs", -1L)
                val legacyEndDefault = if (version == 1 && startMs >= 0L && startMs <= Long.MAX_VALUE - 5_000L) {
                    startMs + 5_000L
                } else {
                    -1L
                }
                val endMs = item.optLong("endMs", legacyEndDefault)
                val action = SafeWatchAction.fromKey(item.optString("action", item.optString("type", "")))
                    ?: return SafeWatchImportPreview(error = "إجراء غير معروف في القاعدة رقم ${index + 1}")
                val category = SafeWatchCategory.fromKey(item.optString("category", SafeWatchCategory.CUSTOM.key))
                    ?: return SafeWatchImportPreview(error = "تصنيف غير معروف في القاعدة رقم ${index + 1}")
                val rawProfileId = item.optString("profileId", SAFE_WATCH_ALL_PROFILES).trim()
                val profileId = if (rawProfileId.isBlank() || rawProfileId.equals(SAFE_WATCH_ALL_PROFILES, ignoreCase = true)) {
                    SAFE_WATCH_ALL_PROFILES
                } else {
                    rawProfileId.uppercase(Locale.ROOT)
                }
                if (!isValidSafeWatchProfileId(profileId)) return SafeWatchImportPreview(error = "ملف عائلي غير معروف في القاعدة رقم ${index + 1}")
                if (!isValidSafeWatchInterval(startMs, endMs, durationMs)) {
                    return SafeWatchImportPreview(error = "توقيت القاعدة رقم ${index + 1} خارج مدة الفيديو أو غير صحيح")
                }
                val confidence = if (item.has("confidence") && !item.isNull("confidence")) {
                    val value = item.optDouble("confidence", Double.NaN)
                    if (!value.isFinite() || value !in 0.0..1.0) {
                        return SafeWatchImportPreview(error = "قيمة الثقة غير صالحة في القاعدة رقم ${index + 1}")
                    }
                    value.toFloat()
                } else null
                val name = item.optString("name", "قاعدة مستوردة")
                    .filterNot { it.code < 0x20 || it.code in 0x7f..0x9f }
                    .take(100)
                    .ifBlank { "قاعدة مستوردة" }
                val draft = SafeWatchRuleDraft(
                    startMs = startMs,
                    endMs = endMs,
                    action = action,
                    category = category,
                    profileId = profileId,
                    name = name,
                    confidence = confidence,
                    source = "IMPORTED"
                )
                val key = "${draft.startMs}:${draft.endMs}:${draft.action.key}:${draft.category.key}:${draft.profileId}"
                drafts[key] = draft
            }
            SafeWatchImportPreview(
                rules = drafts.values.toList(),
                mediaId = media.id,
                mediaFingerprint = safetyMediaFingerprint(media)
            )
        } catch (_: Exception) {
            SafeWatchImportPreview(error = "تعذر قراءة ملف الحماية؛ تحقق من صحة JSON")
        }
    }

    fun importSafeWatchRules(preview: SafeWatchImportPreview, onResult: (Boolean, Int) -> Unit) {
        viewModelScope.launch {
            safeWatchImportMutex.withLock {
                if (!preview.canApply || preview.rules.size > 500 ||
                    !canManageSafeWatch { importSafeWatchRules(preview, onResult) }
                ) {
                    onResult(false, 0)
                    return@withLock
                }

                val media = _currentMedia.value
                val mediaId = currentMediaId
                if (media == null || media.mediaType != MediaType.VIDEO || mediaId == null || mediaId <= 0 ||
                    media.id != mediaId || preview.mediaId != mediaId ||
                    preview.mediaFingerprint != safetyMediaFingerprint(media)
                ) {
                    onResult(false, 0)
                    return@withLock
                }

                val durationMs = _duration.value.takeIf { it > 0L } ?: media.durationMs
                val markersForMedia = (try {
                    repository.getMarkersForMedia(mediaId).first()
                } catch (_: Exception) {
                    _markers.value.filter { it.mediaId == mediaId }
                }).toMutableList()
                if (currentMediaId != mediaId ||
                    _currentMedia.value?.let { safetyMediaFingerprint(it) } != preview.mediaFingerprint
                ) {
                    onResult(false, 0)
                    return@withLock
                }
                var imported = 0
                val importedProfiles = linkedSetOf<String>()

                for (draft in preview.rules) {
                    if (!isValidSafeWatchInterval(draft.startMs, draft.endMs, durationMs) ||
                        !isValidSafeWatchProfileId(draft.profileId)
                    ) continue

                    val matchingScopes = markersForMedia.asSequence()
                        .filter { existing ->
                            existing.isSafeWatchRule() && existing.timeMs == draft.startMs &&
                                existing.safeWatchEndTimeMs() == draft.endMs &&
                                existing.safeWatchAction() == draft.action && existing.category == draft.category.key
                        }
                        .map { existing -> existing.profileId.takeUnless { it.isNullOrBlank() } ?: SAFE_WATCH_ALL_PROFILES }
                        .toSet()
                    val duplicate = if (draft.profileId == SAFE_WATCH_ALL_PROFILES) {
                        SAFE_WATCH_ALL_PROFILES in matchingScopes || SafeWatchProfile.entries.all { it.key in matchingScopes }
                    } else {
                        SAFE_WATCH_ALL_PROFILES in matchingScopes || draft.profileId in matchingScopes
                    }
                    if (duplicate) continue

                    val importedMarker = com.example.data.TimelineMarker(
                        mediaId = mediaId,
                        timeMs = draft.startMs,
                        name = draft.name,
                        type = draft.action.markerType,
                        endTimeMs = draft.endMs,
                        category = draft.category.key,
                        action = draft.action.key,
                        profileId = draft.profileId,
                        confidence = draft.confidence?.coerceIn(0f, 1f),
                        source = "IMPORTED",
                        reviewStatus = SAFE_WATCH_ACCEPTED
                    )
                    repository.insertMarker(importedMarker)
                    markersForMedia += importedMarker
                    imported++
                    importedProfiles += draft.profileId
                }

                if (imported > 0) {
                    importedProfiles.forEach(::enableSafeWatchForProfile)
                    evaluateSafeWatchRules(_currentPosition.value)
                }
                onResult(imported > 0, imported)
            }
        }
    }

    private fun safetyMediaFingerprint(media: com.example.data.MediaItem): String {
        val input = "${media.name.trim().lowercase(Locale.ROOT)}|${media.sizeBytes}|${media.durationMs}|${media.mediaType.name}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun hasExcessiveSafetyJsonNesting(json: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > 64) return true
                    }
                    '}', ']' -> depth--
                }
            }
        }
        return false
    }

    fun toggleVideoBgPlay(): Boolean {
        val newState = !_isVideoBgPlayEnabled.value
        _isVideoBgPlayEnabled.value = newState
        return newState
    }

    fun setVideoBgPlay(enabled: Boolean) {
        _isVideoBgPlayEnabled.value = enabled
    }

    fun toggleOrientationLock(): Boolean {
        val newState = !_isOrientationLocked.value
        _isOrientationLocked.value = newState
        return newState
    }

    fun setOrientationLock(locked: Boolean) {
        _isOrientationLocked.value = locked
    }

    fun cycleAspectRatio() {
        val values = AspectRatioMode.values()
        val nextOrdinal = (_aspectRatioMode.value.ordinal + 1) % values.size
        _aspectRatioMode.value = values[nextOrdinal]
    }

    fun cycleRepeatMode() {
        val next = when (_repeatMode.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        _repeatMode.value = next
        player?.repeatMode = next
    }

    fun setAbRepeatPoint() {
        val cur = player?.currentPosition ?: 0L
        if (_abPointA.value == null) {
            _abPointA.value = cur
            _isAbRepeatActive.value = false
        } else if (_abPointB.value == null) {
            if (cur > (_abPointA.value ?: 0L)) {
                _abPointB.value = cur
                _isAbRepeatActive.value = true
                player?.seekTo(_abPointA.value!!)
            } else {
                _abPointA.value = cur
            }
        } else {
            clearAbRepeat()
        }
    }

    fun clearAbRepeat() {
        _abPointA.value = null
        _abPointB.value = null
        _isAbRepeatActive.value = false
    }

    // --- Smart Sleep Timer ---
    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        _sleepTimerMinutes.value = minutes
        if (minutes == null) {
            _sleepTimerRemainingSeconds.value = null
            return
        }
        if (minutes == -1) {
            _sleepTimerRemainingSeconds.value = null
            return
        }
        if (minutes > 0) {
            var remainingSec = minutes * 60L
            _sleepTimerRemainingSeconds.value = remainingSec
            sleepTimerJob = viewModelScope.launch {
                while (remainingSec > 0) {
                    delay(1000L)
                    remainingSec -= 1L
                    _sleepTimerRemainingSeconds.value = remainingSec
                }
                player?.pause()
                _sleepTimerMinutes.value = null
                _sleepTimerRemainingSeconds.value = null
            }
        }
    }

    fun extendSleepTimer(extraMinutes: Int = 5) {
        val currentRemSec = _sleepTimerRemainingSeconds.value ?: 0L
        val newTotalSeconds = currentRemSec + (extraMinutes * 60L)
        val approxMinutes = ((newTotalSeconds + 59) / 60).toInt()
        sleepTimerJob?.cancel()
        _sleepTimerMinutes.value = approxMinutes
        var remainingSec = newTotalSeconds
        _sleepTimerRemainingSeconds.value = remainingSec
        sleepTimerJob = viewModelScope.launch {
            while (remainingSec > 0) {
                delay(1000L)
                remainingSec -= 1L
                _sleepTimerRemainingSeconds.value = remainingSec
            }
            player?.pause()
            _sleepTimerMinutes.value = null
            _sleepTimerRemainingSeconds.value = null
        }
    }

    // --- Equalizer & Audio Boost ---
    fun setEqualizerEnabled(enabled: Boolean) {
        _isEqualizerEnabled.value = enabled
        syncAudioEffects()
    }

    fun setEqualizerPreset(preset: String) {
        _equalizerPreset.value = preset
        val bands = when (preset) {
            "Flat" -> listOf(0f, 0f, 0f, 0f, 0f)
            "Cinema" -> listOf(6f, 4f, 0f, 3f, 6f)
            "Podcast" -> listOf(-3f, 2f, 7f, 5f, 1f)
            "Music" -> listOf(5f, 3f, -1f, 3f, 5f)
            "Bass Boost" -> listOf(9f, 6f, 1f, 0f, 1f)
            "Vocal" -> listOf(-4f, 1f, 6f, 5f, 2f)
            "Rock" -> listOf(6f, 3f, -2f, 4f, 7f)
            "Classical" -> listOf(4f, 2f, -1f, 3f, 5f)
            else -> _eqBandLevels.value
        }
        _eqBandLevels.value = bands
        if (preset == "Bass Boost" && _bassBoostLevel.value < 60) {
            _bassBoostLevel.value = 70
        }
        syncAudioEffects()
    }

    fun setEqBandLevel(bandIndex: Int, levelDb: Float) {
        if (bandIndex !in 0..4) return
        val mutable = _eqBandLevels.value.toMutableList()
        mutable[bandIndex] = levelDb.coerceIn(-15f, 15f)
        _eqBandLevels.value = mutable
        _equalizerPreset.value = "Custom"
        syncAudioEffects()
    }

    fun setBassBoost(level: Int) {
        _bassBoostLevel.value = level.coerceIn(0, 100)
        syncAudioEffects()
    }

    fun setVolumeBoost(level: Int) {
        _volumeBoostLevel.value = level.coerceIn(0, 100)
        syncAudioEffects()
    }

    fun resetEqualizer() {
        _isEqualizerEnabled.value = true
        _equalizerPreset.value = "Flat"
        _eqBandLevels.value = listOf(0f, 0f, 0f, 0f, 0f)
        _bassBoostLevel.value = 0
        _volumeBoostLevel.value = 0
        syncAudioEffects()
    }

    private fun syncAudioEffects() {
        PlaybackService.AudioEffectsController.updateSettings(
            enabled = _isEqualizerEnabled.value,
            bandsDb = _eqBandLevels.value,
            bassPercent = _bassBoostLevel.value,
            volumeBoostPercent = _volumeBoostLevel.value
        )
    }

    // --- External Subtitles (SRT / VTT) ---
    fun toggleSubtitles(enabled: Boolean) {
        _subtitlesEnabled.value = enabled
        updateSubtitleForPosition(_currentPosition.value)
    }

    fun clearSubtitles() {
        _subtitlesEnabled.value = false
        _subtitleCues.value = emptyList()
        _subtitleFileName.value = null
        _currentSubtitleText.value = null
        _subtitleDelayMs.value = 0L
    }

    fun adjustSubtitleDelay(deltaMs: Long) {
        _subtitleDelayMs.value = (_subtitleDelayMs.value + deltaMs).coerceIn(-15000L, 15000L)
        updateSubtitleForPosition(_currentPosition.value)
    }

    fun resetSubtitleDelay() {
        _subtitleDelayMs.value = 0L
        updateSubtitleForPosition(_currentPosition.value)
    }

    fun setSubtitleFontSize(sizeSp: Float) {
        _subtitleFontSizeSp.value = sizeSp.coerceIn(12f, 32f)
    }

    fun setSubtitleColorIndex(index: Int) {
        _subtitleColorIndex.value = index.coerceIn(0, 3)
    }

    fun setSubtitleBgOpacity(opacity: Float) {
        _subtitleBgOpacity.value = opacity.coerceIn(0f, 1f)
    }

    fun loadExternalSubtitleFromUri(context: Context, uri: Uri, onResult: (Boolean, Int) -> Unit) {
        if (_currentMedia.value?.mediaType != MediaType.VIDEO) {
            onResult(false, 0)
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val fileName = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
                    } ?: uri.lastPathSegment.orEmpty()
                    val mimeType = context.contentResolver.getType(uri)
                    if (!MediaFileSupport.isSupportedSubtitleFile(fileName, mimeType)) {
                        return@withContext Pair<String?, List<SubtitleCue>>(null, emptyList())
                    }
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: return@withContext Pair<String?, List<SubtitleCue>>(null, emptyList())
                    val cues = parseSubtitles(content)
                    Pair(fileName, cues)
                } catch (_: Exception) {
                    Pair(null, emptyList())
                }
            }
            val fileName = result.first
            val cues = result.second
            if (cues.isNotEmpty()) {
                _subtitleFileName.value = fileName
                _subtitleCues.value = cues
                _subtitlesEnabled.value = true
                updateSubtitleForPosition(_currentPosition.value)
                onResult(true, cues.size)
            } else {
                onResult(false, 0)
            }
        }
    }

    fun loadSampleArabicSubtitles() {
        if (_currentMedia.value?.mediaType != MediaType.VIDEO) return
        val totalDur = (_duration.value).coerceAtLeast(180_000L)
        val demoCues = mutableListOf<SubtitleCue>()
        var t = 0L
        val messages = listOf(
            "مرحباً بك في مشغل SafePlayer الاحترافي 🎬",
            "يمكنك التحكم في حجم ولون ومزامنة الترجمة بسهولة",
            "جرب تعديل توقيت الترجمة (+0.1s / -0.1s) لمطابقة الصوت",
            "كما يمكنك تحميل أي ملف ترجمة خارجي بصيغة SRT أو VTT",
            "استمتع بمشاهدة آمنة وصوت نقي مع موازن الصوت المدمج 🎧"
        )
        var idx = 0
        while (t < totalDur) {
            val msg = messages[idx % messages.size]
            demoCues.add(SubtitleCue(startMs = t, endMs = t + 4500L, text = msg))
            t += 5000L
            idx++
        }
        _subtitleFileName.value = "ترجمة تجريبية بالعربية (Arabic Demo.srt)"
        _subtitleCues.value = demoCues
        _subtitlesEnabled.value = true
        updateSubtitleForPosition(_currentPosition.value)
    }

    private fun parseSubtitles(rawContent: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val normalized = rawContent.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val blocks = normalized.split(Regex("\n\\s*\n"))
        val timeArrowRegex = Regex("""(\d{1,2}:)?\d{2}:\d{2}[,\.]\d{3}\s*-->\s*(\d{1,2}:)?\d{2}:\d{2}[,\.]\d{3}""")

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timeLineIndex = lines.indexOfFirst { timeArrowRegex.containsMatchIn(it) }
            if (timeLineIndex == -1) continue

            val timeLine = lines[timeLineIndex]
            val parts = timeLine.split("-->")
            if (parts.size < 2) continue

            val startMs = parseTimestampToMs(parts[0].trim())
            val endMs = parseTimestampToMs(parts[1].trim().split(" ").first())
            if (startMs < 0L || endMs <= startMs) continue

            val textLines = lines.subList(timeLineIndex + 1, lines.size)
                .map { it.replace(Regex("<[^>]*>"), "") }
                .filter { it.isNotBlank() }

            if (textLines.isNotEmpty()) {
                cues.add(
                    SubtitleCue(
                        startMs = startMs,
                        endMs = endMs,
                        text = textLines.joinToString("\n")
                    )
                )
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTimestampToMs(timestamp: String): Long {
        return try {
            val clean = timestamp.replace(',', '.').trim()
            val segments = clean.split(":")
            when (segments.size) {
                3 -> {
                    val hours = segments[0].toLong()
                    val minutes = segments[1].toLong()
                    val secParts = segments[2].split(".")
                    val seconds = secParts[0].toLong()
                    val millis = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                    ((hours * 3600) + (minutes * 60) + seconds) * 1000L + millis
                }
                2 -> {
                    val minutes = segments[0].toLong()
                    val secParts = segments[1].split(".")
                    val seconds = secParts[0].toLong()
                    val millis = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                    ((minutes * 60) + seconds) * 1000L + millis
                }
                else -> -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }

    fun playNext() {
        val items = _playlistItems.value
        val curId = currentMediaId ?: return
        val idx = items.indexOfFirst { it.id == curId }
        if (idx in 0 until items.size - 1) {
            savePosition(false)
            loadMediaItem(items[idx + 1])
        }
    }

    fun playPrevious() {
        val items = _playlistItems.value
        val curId = currentMediaId ?: return
        val idx = items.indexOfFirst { it.id == curId }
        if (idx > 0) {
            savePosition(false)
            loadMediaItem(items[idx - 1])
        } else {
            seekTo(0L)
        }
    }

    fun addMarker(timeMs: Long, name: String? = null, type: String = "STANDARD") {
        currentMediaId?.let { mediaId ->
            viewModelScope.launch {
                val marker = com.example.data.TimelineMarker(
                    mediaId = mediaId,
                    timeMs = timeMs,
                    name = name ?: "Marker at ${timeMs / 1000}s",
                    type = type
                )
                repository.insertMarker(marker)
            }
        }
    }

    fun removeMarker(markerId: Int) {
        viewModelScope.launch {
            val marker = _markers.value.firstOrNull { it.id == markerId } ?: return@launch
            if (marker.isSafeWatchRelated() && !canManageSafeWatch { removeMarker(markerId) }) return@launch
            repository.deleteMarker(markerId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        savePosition()
        sleepTimerJob?.cancel()
        player?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
