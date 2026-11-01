package dev.gmitch215.drift.why

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExplainTest {
    private fun finding(path: String = "tool.node.version") = Finding(
        path,
        Described(
            "Node version",
            "22.4.0",
            "20.11.1",
            "Node is 20.11.1 there and 22.4.0 here",
            "Use Node 22.4.0 there.",
        ),
    )

    private fun verdict(certainty: Certainty, findings: List<Finding>, pointed: Boolean = false) =
        Explain.verdict(certainty, findings, Sides.HERE_THERE, pointed)

    @Test
    fun aStrongCandidateWithWeakerOnesIsStillMostLikely() {
        assertEquals(
            "Assuming it works here and fails there: it most likely fails there because Node is " +
                "20.11.1 there and 22.4.0 here; it is the strongest difference I found.",
            verdict(Certainty.CAUSE, listOf(finding(), finding("tool.npm.version"))),
        )
    }

    @Test
    fun aLoneStrongCandidateIsStillMostLikely() {
        assertEquals(
            "Assuming it works here and fails there: it most likely fails there because Node is " +
                "20.11.1 there and 22.4.0 here; it is the only difference I found.",
            verdict(Certainty.CAUSE, listOf(finding())),
        )
    }

    @Test
    fun theErrorTextIsNamedAsTheReasonWhenItPoints() {
        val sentence = verdict(Certainty.CAUSE, listOf(finding()), pointed = true)
        assertTrue(sentence.endsWith("the error text points at it."), sentence)
        assertEquals(sentence, verdict(Certainty.LIKELY, listOf(finding()), pointed = true))
    }

    @Test
    fun noVerdictStatesACauseWithoutHedging() {
        val findings = listOf(finding(), finding("tool.npm.version"))
        for (certainty in Certainty.entries) {
            val sentence = verdict(certainty, findings)
            assertFalse("fails there because" in sentence && "most likely" !in sentence, sentence)
        }
    }
}
