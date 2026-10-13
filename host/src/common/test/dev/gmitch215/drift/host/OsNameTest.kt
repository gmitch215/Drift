package dev.gmitch215.drift.host

import dev.gmitch215.drift.diff.Normalize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OsNameTest {
    private val vocabulary = setOf(
        "linux",
        "macos",
        "windows",
        "android",
        "ios",
        "freebsd",
        "unknown",
    )

    @Test
    fun mapsEachPlatformSpellingToTheVocabulary() {
        val cases = mapOf(
            // jvm os.name
            "Linux" to "linux",
            "Mac OS X" to "macos",
            "Windows 11" to "windows",
            "FreeBSD" to "freebsd",
            "SunOS" to "unknown",
            // kotlin/native OsFamily
            "LINUX" to "linux",
            "MACOSX" to "macos",
            "WINDOWS" to "windows",
            "IOS" to "ios",
            "ANDROID" to "android",
            "WASM" to "unknown",
            "UNKNOWN" to "unknown",
            "" to "unknown",
        )
        for ((raw, expected) in cases) assertEquals(expected, normalizeOs(raw), raw)
        for (raw in cases.keys) assertTrue(normalizeOs(raw) in vocabulary, raw)
    }

    @Test
    fun agreesWithTheDiffNormalizerOnTheVocabulary() {
        for (name in vocabulary) assertEquals(name, normalizeOs(name))
        for (raw in listOf("mac", "macosx", "darwin", "Linux", "Windows 11", "FreeBSD")) {
            assertEquals(Normalize.osName(raw), normalizeOs(raw), raw)
        }
    }
}
