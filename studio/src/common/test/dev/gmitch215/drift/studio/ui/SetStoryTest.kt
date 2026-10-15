package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetStoryTest {
    private val toolchain = setSamples().first { "Node.js" in it.name }
    private val proxy = setSamples().first { "proxy" in it.name }

    private fun entry(failing: Boolean, vararg attrs: Pair<String, String>) = SetEntry(
        "c",
        failing,
        Capsule("c", attrs.map { Attribute(it.first, it.second, "test") }),
    )

    @Test
    fun theSamplesAreTheTwoFixtureSetsInOrder() {
        assertEquals(
            listOf(
                "Use the Sample Set for a proxy setting",
                "Use the Sample Set for Node.js and npm versions",
            ),
            setSamples().map { it.name },
        )
        val outcomes = toolchain.entries.map { it.failing }.sorted()
        assertEquals(listOf(false, false, true, true), outcomes)
        assertEquals(4, proxy.entries.size)
    }

    @Test
    fun theHeadlineNamesTheOneSeparatingSetting() {
        val view = assertNotNull(SetStory.of(toolchain.entries))
        assertEquals(
            "One setting separates the failing capsules from the passing ones: the node version, " +
                "set to 22.4.0. All 2 failing capsules hold it and none of the 2 passing " +
                "capsules do.",
            view.headline,
        )
        val top = view.rows.first()
        assertEquals("Node version", top.title)
        assertEquals("22.4.0", top.value)
        assertEquals("Held by 2 of 2 failing and 0 of 2 passing capsules.", top.counts)
        assertTrue(top.separates)
        assertTrue(view.rows.drop(1).none { it.separates })
    }

    @Test
    fun theProxySetNamesTheProxyVariableFirst() {
        val view = assertNotNull(SetStory.of(proxy.entries))
        assertTrue("environment variable HTTP_PROXY" in view.headline, view.headline)
        assertEquals("Environment variable HTTP_PROXY", view.rows.first().title)
    }

    @Test
    fun severalSeparatingSettingsAreSaidToBeIndistinguishable() {
        val view = assertNotNull(
            SetStory.of(
                listOf(
                    entry(false, "a" to "1", "b" to "1"),
                    entry(true, "a" to "2", "b" to "2"),
                    entry(true, "a" to "2", "b" to "2"),
                ),
            ),
        )
        val start = "2 settings separate the failing capsules"
        assertTrue(view.headline.startsWith(start), view.headline)
        assertTrue("cannot tell them apart" in view.headline)
    }

    @Test
    fun aPartialSplitIsNamedAsTheClosest() {
        val view = assertNotNull(
            SetStory.of(
                listOf(
                    entry(false, "a" to "1"),
                    entry(true, "a" to "2"),
                    entry(true, "a" to "3"),
                ),
            ),
        )
        assertEquals(
            "No single setting separates the failing capsules from the passing ones. The " +
                "closest is the setting a, set to 2, held by 1 of 2 failing and 0 of 1 passing " +
                "capsules.",
            view.headline,
        )
    }

    @Test
    fun aSetWithNothingToSplitSaysSo() {
        val view = assertNotNull(
            SetStory.of(listOf(entry(false, "a" to "1"), entry(true, "a" to "1"))),
        )
        assertEquals(SetStory.NONE, view.headline)
        assertTrue(view.rows.isEmpty())
    }

    @Test
    fun aSetWithOneSideOnlyHasNoView() {
        assertNull(SetStory.of(emptyList()))
        assertNull(SetStory.of(toolchain.entries.filter { it.failing }))
        assertNull(SetStory.of(toolchain.entries.filter { !it.failing }))
    }
}
