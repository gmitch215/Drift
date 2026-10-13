package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.delta.BisectState
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BisectStoryTest {
    private val sample = samples().first()

    private fun capsule(vararg values: Pair<String, String>) =
        Capsule("c", values.map { Attribute(it.first, it.second, "test") })

    @Test
    fun aPairBecomesAListOfSettingsAndAFirstTest() {
        val view = assertNotNull(BisectStory.of(sample.green, sample.red))
        val n = view.plan.flips.size
        assertTrue(n >= 2)
        assertEquals(n, view.settings.size)
        assertTrue(
            view.headline.startsWith("$n settings differ between the passing and the failing"),
            view.headline,
        )
        val test = assertNotNull(view.first)
        val next = view.plan.state() as BisectState.Next
        assertEquals(next.set.size, test.settings.size)
        assertTrue(test.sentence.startsWith("Test 1: start from the passing environment"))
        assertTrue(view.headline.contains("changes ${next.set.size} of them"))
    }

    @Test
    fun theFirstTestListsTheCommandLineSteps() {
        val test = assertNotNull(BisectStory.of(sample.green, sample.red)?.first)
        assertEquals(
            listOf(
                "drift bisect plan <passing capsule> <failing capsule> --out plan.json",
                "drift bisect apply plan.json --step 1 --red",
                "drift bisect apply plan.json --step 1 --green",
            ),
            test.commands,
        )
    }

    @Test
    fun settingsReadAsWhatTheyNameWithBothValues() {
        val view = assertNotNull(
            BisectStory.of(
                capsule("env.TZ" to "UTC", "env.A" to "1"),
                capsule("env.TZ" to "America/Chicago", "env.A" to "2"),
            ),
        )
        val tz = view.settings.first { it.title == "Time zone (TZ)" }
        assertEquals("UTC -> America/Chicago", tz.change)
        assertEquals("Environment variable A", view.settings.first { it.change == "1 -> 2" }.title)
    }

    @Test
    fun oneDifferingSettingNeedsNoTest() {
        val view = assertNotNull(
            BisectStory.of(capsule("env.TZ" to "UTC"), capsule("env.TZ" to "America/Chicago")),
        )
        assertEquals(
            "One setting differs between the passing and the failing capsule: time zone (TZ). " +
                "No test is needed to find the cause.",
            view.headline,
        )
        assertNull(view.first)
    }

    @Test
    fun aSettingMissingOnOneSideReadsAsNotSet() {
        val view = assertNotNull(BisectStory.of(capsule(), capsule("env.TZ" to "UTC")))
        assertEquals("not set -> UTC", view.settings.single().change)
    }

    @Test
    fun capsulesWithNothingDifferingHaveNoPlan() {
        assertNull(BisectStory.of(sample.green, sample.green))
    }
}
