package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.diff.DiffKind
import dev.gmitch215.drift.diff.DiffResult
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.rank.Candidate
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.studio.Facts
import dev.gmitch215.drift.studio.StudioState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlainTest {
    private fun candidate(path: String, tier: Tier) =
        Candidate(path, tier, 0, emptyList(), emptyList(), Basis.INFERRED, null, null, null)

    private fun change(path: String, before: String?, after: String?) =
        AttributeDiff(path, null, DiffKind.CHANGED, before, after, Stability.STATIC, null)

    private fun state(vararg ranked: Pair<String, Tier>, tied: Int = 0): StudioState {
        val candidates = ranked.map { candidate(it.first, it.second) }
        return StudioState(
            Facts(10, 2),
            DiffResult(
                listOf(
                    change("env.TZ", "UTC", "America/Chicago"),
                    change("os.name", null, "linux"),
                ),
                emptyList(),
                emptyList(),
            ),
            candidates,
            candidates.take(tied),
            emptyList(),
            emptyList(),
        )
    }

    @Test
    fun everyTierHasItsOwnLevelLabelAndPhrase() {
        val words = Tier.entries.map { Plain.tier(it) }
        assertEquals(listOf(1, 2, 3, 4), words.map { it.level })
        assertEquals(Tier.entries.map { it.name.lowercase() }, words.map { it.label.lowercase() })
        assertEquals(words.size, words.map { it.phrase }.toSet().size)
        assertEquals("Evidence level 1 of 4 (Difference)", Plain.level(Tier.DIFFERENCE))
        assertEquals("Evidence level 4 of 4 (Known)", Plain.level(Tier.KNOWN))
    }

    @Test
    fun pathsReadAsWhatTheyName() {
        assertEquals("time zone (TZ)", Plain.name("env.TZ"))
        assertEquals("environment variable NODE_VERSION", Plain.name("env.NODE_VERSION"))
        assertEquals("@types/node package version", Plain.name("deps.@types/node.version"))
        assertEquals("node version", Plain.name("tool.node.version"))
        assertEquals("CI setting runner.image", Plain.name("ci.runner.image"))
        assertEquals("operating system setting name", Plain.name("os.name"))
        assertEquals("setting kernel.release", Plain.name("kernel.release"))
        assertEquals("Time zone (TZ)", Plain.title("env.TZ"))
    }

    @Test
    fun valuesAreShortenedAndAbsenceIsSaidInWords() {
        assertEquals("not set", Plain.value(null))
        assertEquals("UTC", Plain.value("UTC"))
        assertEquals("a".repeat(48) + "...", Plain.value("a".repeat(60)))
        assertEquals("not set -> linux", Plain.change(change("os.name", null, "linux")))
    }

    @Test
    fun aSingleLeaderIsNamedWithItsValuesAndItsEvidenceLevel() {
        val reading = Plain.reading(state("env.TZ" to Tier.DIFFERENCE))
        assertEquals(
            "The likeliest cause is the time zone (TZ): it was set to UTC on the passing run " +
                "and set to America/Chicago on the failing run.",
            reading.headline,
        )
        assertEquals(
            "Evidence level 1 of 4 (Difference). ${Plain.tier(Tier.DIFFERENCE).phrase}",
            reading.evidence,
        )
        assertEquals(Plain.CAUTION, reading.caution)
    }

    @Test
    fun aTieIsNeverPresentedAsOneCause() {
        val tied = state("env.TZ" to Tier.PLAUSIBLE, "os.name" to Tier.PLAUSIBLE, tied = 2)
        val reading = Plain.reading(tied)
        assertTrue(reading.headline.startsWith("Drift cannot name one cause: 2 differences tie"))
        assertTrue(reading.headline.contains("The first in the list is the time zone (TZ)"))
    }

    @Test
    fun anAddedValueReadsAsNotSet() {
        val reading = Plain.reading(state("os.name" to Tier.KNOWN))
        assertTrue(reading.headline.contains("it was not set on the passing run and set to linux"))
    }

    @Test
    fun noRankedChangeMeansNoSuspect() {
        val reading = Plain.reading(state())
        assertTrue(reading.headline.startsWith("No difference between these two runs was ranked"))
        assertNull(reading.evidence)
        assertTrue(reading.caution.contains("does not mean the runs are identical"))
    }

    @Test
    fun theSampleRunsHaveAReadingThatNamesTheTopDifference() {
        for (sample in samples()) {
            val top = sample.state.relevant.first()
            val reading = Plain.reading(sample.state)
            assertTrue(reading.headline.contains("the ${Plain.name(top.path)}"), reading.headline)
            assertTrue(reading.evidence!!.contains(Plain.tier(top.tier).phrase))
        }
    }
}
