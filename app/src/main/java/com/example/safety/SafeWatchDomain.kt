package com.example.safety

import com.example.data.TimelineMarker
import java.util.Locale

/** Available playback responses for a reviewed family-safety interval. */
enum class SafeWatchAction(val key: String, val markerType: String, val label: String) {
    SKIP("SKIP", "SAFE_SKIP", "تخطٍ"),
    MUTE("MUTE", "SAFE_MUTE", "كتم الصوت"),
    BLUR("BLUR", "SAFE_BLUR", "تعتيم"),
    COVER("COVER", "SAFE_COVER", "حجب كامل");

    companion object {
        fun fromKey(value: String?): SafeWatchAction? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.key == normalized || it.markerType == normalized }
        }
    }
}

enum class SafeWatchCategory(val key: String, val label: String, val suggestedAction: SafeWatchAction) {
    CUSTOM("CUSTOM", "مخصص", SafeWatchAction.BLUR),
    LANGUAGE("LANGUAGE", "ألفاظ", SafeWatchAction.MUTE),
    VIOLENCE("VIOLENCE", "عنف", SafeWatchAction.SKIP),
    ADULT("ADULT", "مشاهد حميمة", SafeWatchAction.BLUR),
    SUBSTANCES("SUBSTANCES", "مواد مخدرة أو كحول", SafeWatchAction.BLUR),
    FLASHING("FLASHING", "ومضات ضوئية", SafeWatchAction.COVER);

    companion object {
        fun fromKey(value: String?): SafeWatchCategory? =
            entries.firstOrNull { it.key == value?.trim()?.uppercase(Locale.ROOT) }
    }
}

enum class SafeWatchProfile(val key: String, val label: String) {
    CHILDREN("CHILDREN", "الأطفال"),
    TEENS("TEENS", "المراهقون"),
    CUSTOM("CUSTOM", "مخصص");

    companion object {
        fun fromKey(value: String?): SafeWatchProfile =
            entries.firstOrNull { it.key == value?.trim()?.uppercase(Locale.ROOT) } ?: CHILDREN
    }
}

const val SAFE_WATCH_ALL_PROFILES = "ALL"
const val SAFE_WATCH_PENDING = "PENDING"
const val SAFE_WATCH_ACCEPTED = "ACCEPTED"
const val SAFE_WATCH_REJECTED = "REJECTED"

/** A timed text cue with no Android or Compose dependencies, used by the local subtitle scanner. */
data class TimedSafetyText(val startMs: Long, val endMs: Long, val text: String)

data class SubtitleSafetySuggestion(
    val startMs: Long,
    val endMs: Long,
    val category: SafeWatchCategory,
    val confidence: Float,
    val reason: String
)

data class SafeWatchRuleDraft(
    val startMs: Long,
    val endMs: Long,
    val action: SafeWatchAction,
    val category: SafeWatchCategory,
    val profileId: String,
    val name: String,
    val confidence: Float? = null,
    val source: String = "MANUAL"
)

data class SafeWatchImportPreview(
    val rules: List<SafeWatchRuleDraft> = emptyList(),
    val error: String? = null,
    val mediaId: Int? = null,
    val mediaFingerprint: String? = null
) {
    val canApply: Boolean get() =
        error == null && rules.size in 1..500 && (mediaId ?: 0) > 0 && !mediaFingerprint.isNullOrBlank()
}

fun TimelineMarker.safeWatchAction(): SafeWatchAction? =
    SafeWatchAction.fromKey(action) ?: SafeWatchAction.fromKey(type)

fun TimelineMarker.safeWatchEndTimeMs(): Long {
    val storedEnd = endTimeMs ?: description?.toLongOrNull()
    return storedEnd?.takeIf { it > timeMs } ?: (timeMs + 5_000L)
}

fun TimelineMarker.isSafeWatchSuggestion(): Boolean =
    type == "SAFE_SUGGESTION" && reviewStatus == SAFE_WATCH_PENDING

fun TimelineMarker.isSafeWatchRule(): Boolean =
    type != "SAFE_SUGGESTION" && safeWatchAction() != null && reviewStatus != SAFE_WATCH_REJECTED

fun TimelineMarker.isSafeWatchRelated(): Boolean =
    type.startsWith("SAFE_") || action != null || category != null

fun TimelineMarker.appliesToSafetyProfile(profile: SafeWatchProfile): Boolean =
    profileId.isNullOrBlank() || profileId == SAFE_WATCH_ALL_PROFILES || profileId == profile.key

fun formatSafeWatchTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

/** Accepts MM:SS or HH:MM:SS, with an optional fractional second. */
fun parseSafeWatchTime(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size !in 2..3) return null
    return try {
        val secondsPart = parts.last().replace(',', '.')
        val secondPieces = secondsPart.split('.', limit = 2)
        if (secondPieces.size > 2) return null
        val seconds = secondPieces[0].toLongOrNull() ?: return null
        val fraction = secondPieces.getOrNull(1)
        if (fraction != null && (fraction.isEmpty() || fraction.length > 3 || fraction.any { !it.isDigit() })) return null
        val millis = fraction?.padEnd(3, '0')?.toLongOrNull() ?: 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val maxMinutes = if (parts.size == 3) 59L else 1_000_000L
        if (hours !in 0L..1_000_000L || minutes !in 0L..maxMinutes || seconds !in 0L..59L || millis !in 0L..999L) return null
        ((hours * 3_600L + minutes * 60L + seconds) * 1_000L) + millis
    } catch (_: ArithmeticException) {
        null
    }
}

fun isValidSafeWatchInterval(startMs: Long, endMs: Long, durationMs: Long): Boolean =
    startMs >= 0L && endMs > startMs && (durationMs <= 0L || endMs <= durationMs)

/**
 * Conservative, on-device subtitle keyword scan. Results are only suggestions and never
 * activate playback filtering without an explicit review/approval.
 */
object SubtitleSafetyAnalyzer {
    private const val CONTEXT_PADDING_MS = 1_000L
    private const val MERGE_GAP_MS = 2_500L
    private const val MAX_CUES_TO_SCAN = 100_000
    private const val MAX_TEXT_PER_CUE = 4_000

    private val keywords = mapOf(
        SafeWatchCategory.LANGUAGE to setOf(
            "fuck", "fucking", "shit", "bullshit", "bitch", "bastard", "asshole", "damn", "crap",
            "لعنة", "اللعنة", "تباً", "تبا", "حقير", "حقيرة", "قذر", "قذرة"
        ),
        SafeWatchCategory.VIOLENCE to setOf(
            "violence", "violent", "blood", "bloody", "kill", "killed", "murder", "weapon", "gun", "shoot", "stab", "corpse",
            "عنف", "دم", "دماء", "قتل", "اقتل", "قتله", "سلاح", "رصاص", "طعن", "جثة", "ميت"
        ),
        SafeWatchCategory.ADULT to setOf(
            "nude", "nudity", "naked", "sexual", "sex", "porn", "rape", "explicit",
            "عاري", "عارية", "عري", "جنس", "جنسي", "اغتصاب"
        ),
        SafeWatchCategory.SUBSTANCES to setOf(
            "drug", "drugs", "cocaine", "heroin", "marijuana", "cannabis", "alcohol", "drunk", "beer", "whiskey",
            "مخدر", "مخدرات", "كوكايين", "هيروين", "حشيش", "كحول", "خمور", "سكران"
        )
    )

    private data class Hit(
        val startMs: Long,
        val endMs: Long,
        val category: SafeWatchCategory,
        val hitCount: Int
    )

    fun analyze(cues: List<TimedSafetyText>): List<SubtitleSafetySuggestion> {
        val hits = ArrayList<Hit>()
        cues.asSequence()
            .take(MAX_CUES_TO_SCAN)
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
            .forEach { cue ->
                val tokens = normalize(cue.text.take(MAX_TEXT_PER_CUE))
                keywords.forEach { (category, terms) ->
                    val count = tokens.count { token -> terms.any { term -> matches(token, term) } }
                    if (count > 0) {
                        hits += Hit(
                            startMs = (cue.startMs - CONTEXT_PADDING_MS).coerceAtLeast(0L),
                            endMs = cue.endMs + CONTEXT_PADDING_MS,
                            category = category,
                            hitCount = count
                        )
                    }
                }
            }

        val suggestions = ArrayList<SubtitleSafetySuggestion>()
        hits.groupBy { it.category }.forEach { (category, categoryHits) ->
            val sorted = categoryHits.sortedBy { it.startMs }
            var start = -1L
            var end = -1L
            var totalHits = 0
            fun flush() {
                if (start >= 0L && end > start) {
                    val confidence = (0.58f + (totalHits.coerceAtMost(5) * 0.06f)).coerceAtMost(0.88f)
                    suggestions += SubtitleSafetySuggestion(
                        startMs = start,
                        endMs = end,
                        category = category,
                        confidence = confidence,
                        reason = "اقتراح محلي من كلمات الترجمة؛ يلزم التحقق يدوياً"
                    )
                }
            }
            sorted.forEach { hit ->
                if (start < 0L) {
                    start = hit.startMs
                    end = hit.endMs
                    totalHits = hit.hitCount
                } else if (hit.startMs <= end + MERGE_GAP_MS) {
                    end = maxOf(end, hit.endMs)
                    totalHits += hit.hitCount
                } else {
                    flush()
                    start = hit.startMs
                    end = hit.endMs
                    totalHits = hit.hitCount
                }
            }
            flush()
        }
        return suggestions.sortedBy { it.startMs }.take(1_000)
    }

    private fun normalize(text: String): List<String> {
        val normalized = text.lowercase(Locale.ROOT)
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        return normalized.split(Regex("\\s+")).filter(String::isNotBlank)
    }

    private fun matches(token: String, term: String): Boolean {
        if (token == term) return true
        val isArabic = term.any { it in '\u0600'..'\u06FF' }
        return isArabic && token.startsWith(term) && token.length - term.length in 1..3
    }
}
