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
