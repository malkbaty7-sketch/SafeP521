package com.example.safety

import com.example.data.TimelineMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeWatchDomainTest {
    @Test
    fun timeFormatterAndParserRoundTripMinutesAndHours() {
        val samples = listOf(0L, 12_000L, 3_723_000L)
        samples.forEach { millis ->
            assertEquals(millis, parseSafeWatchTime(formatSafeWatchTime(millis)) ?: -1L)
        }
    }

    @Test
    fun timeParserRejectsMalformedOrOutOfRangeFields() {
        assertNull(parseSafeWatchTime("01:75"))
        assertNull(parseSafeWatchTime("00:00:60"))
        assertNull(parseSafeWatchTime("00:00.1234"))
        assertNull(parseSafeWatchTime("00:00."))
        assertNull(parseSafeWatchTime("not-a-time"))
        assertNull(parseSafeWatchTime("999999999999999:00:00"))
        assertEquals(5_400_000L, parseSafeWatchTime("90:00") ?: -1L)
    }

    @Test
    fun intervalValidationRequiresAnOrderedRangeInsideKnownDuration() {
        assertTrue(isValidSafeWatchInterval(1_000L, 5_000L, 10_000L))
        assertFalse(isValidSafeWatchInterval(5_000L, 5_000L, 10_000L))
        assertFalse(isValidSafeWatchInterval(0L, 11_000L, 10_000L))
        assertTrue(isValidSafeWatchInterval(0L, 11_000L, 0L))
    }

    @Test
    fun subtitleScanCreatesGroupedLocalSuggestionsWithoutKeepingQuotedText() {
        val results = SubtitleSafetyAnalyzer.analyze(
            listOf(
                TimedSafetyText(10_000L, 12_000L, "A weapon is in the room."),
                TimedSafetyText(13_000L, 15_000L, "There is blood on the floor."),
                TimedSafetyText(40_000L, 42_000L, "Welcome back to the show.")
            )
        )

        assertEquals(1, results.size)
        assertEquals(SafeWatchCategory.VIOLENCE, results.single().category)
        assertTrue(results.single().startMs <= 9_000L)
        assertTrue(results.single().endMs >= 16_000L)
        assertTrue(results.single().confidence in 0f..1f)
        assertFalse(results.single().reason.contains("weapon", ignoreCase = true))
    }

    @Test
    fun englishMatchesAreTokenBasedRatherThanSubstringMatches() {
        val results = SubtitleSafetyAnalyzer.analyze(
            listOf(TimedSafetyText(1_000L, 2_000L, "The gunslinger arrived."))
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun importPreviewMustBeBoundToAValidLocalMediaFingerprint() {
        val rule = SafeWatchRuleDraft(
            startMs = 1_000L,
            endMs = 2_000L,
            action = SafeWatchAction.BLUR,
            category = SafeWatchCategory.CUSTOM,
            profileId = SafeWatchProfile.CHILDREN.key,
            name = "reviewed"
        )

        assertFalse(SafeWatchImportPreview(rules = listOf(rule)).canApply)
        assertFalse(SafeWatchImportPreview(rules = listOf(rule), mediaId = 0, mediaFingerprint = "abc").canApply)
        assertTrue(SafeWatchImportPreview(rules = listOf(rule), mediaId = 7, mediaFingerprint = "abc").canApply)
    }

    @Test
    fun ruleProfileScopeSupportsOneProfileOrAllProfiles() {
        val shared = TimelineMarker(
            mediaId = 1,
            timeMs = 1_000L,
            type = "SAFE_BLUR",
            profileId = SAFE_WATCH_ALL_PROFILES,
            reviewStatus = SAFE_WATCH_ACCEPTED
        )
        val childrenOnly = shared.copy(profileId = SafeWatchProfile.CHILDREN.key)

        assertTrue(shared.appliesToSafetyProfile(SafeWatchProfile.TEENS))
        assertTrue(childrenOnly.appliesToSafetyProfile(SafeWatchProfile.CHILDREN))
        assertFalse(childrenOnly.appliesToSafetyProfile(SafeWatchProfile.TEENS))
        assertTrue(shared.isSafeWatchRule())
    }
}
