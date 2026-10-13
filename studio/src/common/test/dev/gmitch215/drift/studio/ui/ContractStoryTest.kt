package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.contract.Assertion
import dev.gmitch215.drift.contract.AssertionKind
import dev.gmitch215.drift.contract.AssertionResult
import dev.gmitch215.drift.contract.Contract
import dev.gmitch215.drift.contract.ContractResult
import dev.gmitch215.drift.contract.Finding
import dev.gmitch215.drift.contract.Holds
import dev.gmitch215.drift.contract.Role
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContractStoryTest {
    private val sample = samples().first()
    private val contract = assertNotNull(ContractStory.sampleContract(sample))

    private fun capsule(vararg values: Pair<String, String>) =
        Capsule("c", values.map { Attribute(it.first, it.second, "test") })

    private fun attribute(id: String, expect: String, role: Role = Role.DISCRIMINATING) =
        Assertion(AssertionKind.ATTRIBUTE, id, expect, role)

    private fun result(a: Assertion, status: Holds, found: String? = null) =
        AssertionResult(a, Finding(status, found))

    @Test
    fun theSampleContractHoldsForThePassingRunAndBreaksForTheFailingOne() {
        val passing = contract.verify(sample.green)
        assertEquals(0, passing.breaks)
        assertEquals(1, passing.results.size)
        assertEquals("The only requirement holds in this capsule.", ContractStory.headline(passing))
        val failing = contract.verify(sample.red)
        val head = ContractStory.headline(failing)
        assertTrue(
            head.startsWith("Of 1 requirement, 1 breaks in this capsule. The first is: "),
            head,
        )
    }

    @Test
    fun severalRequirementsPluralize() {
        val greens = Contract.create(listOf(sample.green)) as ContractResult.Created
        val report = greens.contract.verify(sample.green)
        assertTrue(report.results.size > 1)
        assertEquals(
            "All ${report.results.size} requirements hold in this capsule.",
            ContractStory.headline(report),
        )
        val other = greens.contract.verify(sample.red)
        val head = ContractStory.headline(other)
        assertTrue(head.startsWith("Of ${report.results.size} requirements, "), head)
    }

    @Test
    fun theFirstBrokenAttributeIsNamedWithBothValues() {
        val c = Contract(emptyList(), emptyList(), listOf(attribute("env.TZ", "UTC")))
        assertEquals(
            "Of 1 requirement, 1 breaks in this capsule. The first is: the time zone (TZ) is " +
                "America/Chicago but the contract requires UTC.",
            ContractStory.headline(c.verify(capsule("env.TZ" to "America/Chicago"))),
        )
        assertEquals(
            "The only requirement holds in this capsule.",
            ContractStory.headline(c.verify(capsule("env.TZ" to "UTC"))),
        )
    }

    @Test
    fun anEmptyContractAndUncheckableRequirementsSaySoPlainly() {
        val empty = Contract(emptyList(), emptyList(), emptyList())
        assertEquals(ContractStory.EMPTY, ContractStory.headline(empty.verify(capsule())))
        val c = Contract(emptyList(), emptyList(), listOf(attribute("env.TZ", "UTC")))
        assertEquals(
            "No requirement breaks, but 1 of 1 could not be checked because the capsule " +
                "does not record what they need.",
            ContractStory.headline(c.verify(capsule())),
        )
        val mixed = Contract(
            emptyList(),
            emptyList(),
            listOf(attribute("env.TZ", "UTC"), attribute("env.LANG", "C")),
        )
        assertEquals(
            "Of 2 requirements, 1 breaks in this capsule. The first is: the language setting " +
                "(LANG) is en_US but the contract requires C. 1 could not be checked.",
            ContractStory.headline(mixed.verify(capsule("env.LANG" to "en_US"))),
        )
    }

    @Test
    fun everyKindStatusAndRoleHasFixedWords() {
        val tz = attribute("env.TZ", "UTC")
        val probe = Assertion(AssertionKind.PROBE, "kotlin.double.parse", "abc", Role.COINCIDENTAL)
        val held = ContractStory.words(result(tz, Holds.HOLDS, "UTC"))
        assertEquals("The time zone (TZ) must be UTC.", held.ask)
        assertEquals("This capsule meets it.", held.found)
        assertEquals("It tells the failing runs from the passing ones.", held.why)
        assertEquals(
            "This capsule has America/Chicago.",
            ContractStory.words(result(tz, Holds.BREAKS, "America/Chicago")).found,
        )
        assertEquals(
            "This capsule does not record it.",
            ContractStory.words(result(tz, Holds.UNVERIFIABLE)).found,
        )
        val p = ContractStory.words(result(probe, Holds.BREAKS, "def"))
        assertEquals(
            "Probe kotlin.double.parse must give the same result as in the passing runs.",
            p.ask,
        )
        assertEquals("In this capsule the probe gives a different result.", p.found)
        assertEquals(
            "Every passing run meets it; no failing run has shown that it matters.",
            p.why,
        )
        assertEquals(
            "This capsule has no usable result for the probe.",
            ContractStory.words(result(probe, Holds.UNVERIFIABLE)).found,
        )
    }

    @Test
    fun groupsComeBrokenFirstAndEachHasItsOwnWords() {
        assertEquals(listOf(Holds.BREAKS, Holds.UNVERIFIABLE, Holds.HOLDS), ContractStory.groups)
        assertEquals(3, ContractStory.groups.map { ContractStory.title(it) }.toSet().size)
        assertEquals(3, ContractStory.groups.map { ContractStory.about(it) }.toSet().size)
    }
}
