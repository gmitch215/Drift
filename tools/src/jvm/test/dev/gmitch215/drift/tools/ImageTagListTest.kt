package dev.gmitch215.drift.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageTagListTest {
    @Test
    fun onlyTheTagsTheTableComposesAreKeptAndGroupedByTheirSuffix() {
        val node = listOf(
            "18.20.4-bookworm-slim",
            "18.20.4-buster-slim",
            "18.9.0-bookworm-slim",
            "18.20.4-alpine3.20",
            "18.20.4",
            "lts-slim",
            "latest",
            "22.12.0-bookworm",
        )
        val grouped = ImageTagList.group("node", node)
        assertEquals(
            mapOf(
                "-alpine3.20" to listOf("18.20.4"),
                "-bookworm-slim" to listOf("18.9.0", "18.20.4"),
                "-buster-slim" to listOf("18.20.4"),
            ),
            grouped,
        )
        assertEquals(
            mapOf("" to listOf("13.2", "13.2.0", "13.2.1")),
            ImageTagList.group("gcc", listOf("13.2.1", "13.2", "13.2.0", "latest", "13-bookworm")),
        )
    }

    @Test
    fun theRAndElixirAndRubyTagsAreGroupedByTheirSuffix() {
        assertEquals(
            mapOf("" to listOf("4.0.5", "4.6.1")),
            ImageTagList.group("r-base", listOf("4.6.1", "4.0.5", "latest", "4.6.1-bookworm")),
        )
        assertEquals(
            mapOf(
                "" to listOf("1.16.3"),
                "-otp-26" to listOf("1.16.3"),
                "-otp-29" to listOf("1.20.4"),
            ),
            ImageTagList.group(
                "elixir",
                listOf(
                    "1.16.3",
                    "1.16.3-otp-26",
                    "1.16.3-otp-26-slim",
                    "1.16.3-alpine",
                    "1.16",
                    "1.20.4-otp-29",
                ),
            ),
        )
        assertEquals(
            mapOf("-slim-trixie" to listOf("3.4.11"), "-alpine3.22" to listOf("3.4.11")),
            ImageTagList.group(
                "ruby",
                listOf("3.4.11-slim-trixie", "3.4.11-alpine3.22", "3.4.11", "3.4-slim-trixie"),
            ),
        )
    }

    @Test
    fun versionsSortNumericallyAndAShortOneComesFirst() {
        assertTrue(ImageTagList.compareVersions("9.0.0", "10.0.0") < 0)
        assertTrue(ImageTagList.compareVersions("13.2", "13.2.0") < 0)
        assertEquals(0, ImageTagList.compareVersions("1.2.3", "1.2.3"))
    }

    @Test
    fun theRenderedSourceHoldsEveryVersionOnItsOwnLinesWithinTheLineLimit() {
        val versions = (0 until 60).map { "20.$it.0-bookworm-slim" }
        val text = ImageTagList.render(mapOf("node" to versions))
        assertTrue(text.startsWith("package dev.gmitch215.drift.lab\n"))
        assertTrue("\"node\" to mapOf(" in text)
        assertTrue("\"-bookworm-slim\" to versions(" in text)
        assertTrue(text.lines().all { it.length <= 100 })
        assertEquals(60, Regex("20\\.\\d+\\.0").findAll(text).count())
        assertTrue(text.endsWith("}\n"))
    }
}
