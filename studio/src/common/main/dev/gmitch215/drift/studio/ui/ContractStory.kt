package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.contract.AssertionKind
import dev.gmitch215.drift.contract.AssertionResult
import dev.gmitch215.drift.contract.Contract
import dev.gmitch215.drift.contract.ContractReport
import dev.gmitch215.drift.contract.ContractResult
import dev.gmitch215.drift.contract.Holds
import dev.gmitch215.drift.contract.Role

const val CONTRACT_ABOUT =
    "A contract lists what a program needs from its environment, as requirements that every " +
        "known passing run meets. Drift checks a capsule, a saved snapshot of an environment, " +
        "against each requirement. Studio only reads the contract and the capsule."

/** One requirement as a person reads it: what it asks, what the capsule has, why it is there. */
class RequirementWords(val ask: String, val found: String, val why: String)

object ContractStory {
    const val EMPTY = "This contract has no requirements, so there is nothing to check."

    val groups = listOf(Holds.BREAKS, Holds.UNVERIFIABLE, Holds.HOLDS)

    fun title(status: Holds): String = when (status) {
        Holds.BREAKS -> "Requirements That Break"
        Holds.UNVERIFIABLE -> "Requirements That Could Not Be Checked"
        Holds.HOLDS -> "Requirements That Hold"
    }

    fun about(status: Holds): String = when (status) {
        Holds.BREAKS -> "The capsule has a different value than the contract requires."
        Holds.UNVERIFIABLE -> "The capsule does not record what the requirement needs."
        Holds.HOLDS -> "The capsule has the value the contract requires."
    }

    fun headline(report: ContractReport): String {
        val all = report.results.size
        if (all == 0) return EMPTY
        val breaks = report.breaks
        val unverifiable = report.unverifiable
        val checked = if (unverifiable > 0) " $unverifiable could not be checked." else ""
        return when {
            breaks > 0 ->
                "Of $all ${requirements(all)}, $breaks ${if (breaks == 1) "breaks" else "break"} " +
                    "in this capsule. The first is: ${sentence(report.first!!)}.$checked"

            unverifiable > 0 ->
                "No requirement breaks, but $unverifiable of $all could not be checked because " +
                    "the capsule does not record what they need."

            all == 1 -> "The only requirement holds in this capsule."

            else -> "All $all requirements hold in this capsule."
        }
    }

    private fun requirements(n: Int) = if (n == 1) "requirement" else "requirements"

    /** What a broken requirement says, as a clause that follows "the first is:". */
    private fun sentence(result: AssertionResult): String {
        val a = result.assertion
        return when (a.kind) {
            AssertionKind.ATTRIBUTE ->
                "the ${Plain.name(a.id)} is ${Plain.value(result.finding.found)} but the " +
                    "contract requires ${Plain.value(a.expect)}"

            AssertionKind.PROBE ->
                "probe ${a.id} gives a different result than the contract requires"
        }
    }

    fun words(result: AssertionResult): RequirementWords {
        val a = result.assertion
        val ask = when (a.kind) {
            AssertionKind.ATTRIBUTE -> "The ${Plain.name(a.id)} must be ${Plain.value(a.expect)}."
            AssertionKind.PROBE -> "Probe ${a.id} must give the same result as in the passing runs."
        }
        val found = when (result.finding.status) {
            Holds.HOLDS -> "This capsule meets it."

            Holds.BREAKS -> when (a.kind) {
                AssertionKind.ATTRIBUTE -> "This capsule has ${Plain.value(result.finding.found)}."
                AssertionKind.PROBE -> "In this capsule the probe gives a different result."
            }

            Holds.UNVERIFIABLE -> when (a.kind) {
                AssertionKind.ATTRIBUTE -> "This capsule does not record it."
                AssertionKind.PROBE -> "This capsule has no usable result for the probe."
            }
        }
        val why = when (a.role) {
            Role.DISCRIMINATING -> "It tells the failing runs from the passing ones."

            Role.COINCIDENTAL ->
                "Every passing run meets it; no failing run has shown that it matters."
        }
        return RequirementWords(ask, found, why)
    }

    /** A contract made from the sample runs: the passing run against the first failing one. */
    fun sampleContract(sample: Sample): Contract? =
        (Contract.create(listOf(sample.green), listOf(sample.red)) as? ContractResult.Created)
            ?.contract
}
