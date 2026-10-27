package dev.gmitch215.drift.cli.why

import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.CleanMatch
import dev.gmitch215.drift.triage.CleanRun
import dev.gmitch215.drift.triage.Fact
import dev.gmitch215.drift.triage.NotEnvSignal
import dev.gmitch215.drift.triage.Side
import dev.gmitch215.drift.triage.TriageResult

/** The plain reading of an issue or error report: what environment fact it points at. */
internal object WhyIssue {
    private const val SHOWN_FACTS = 2
    private val SIDES = Sides("where it works", "where it fails", assumed = false)

    /**
     * [phrased]: the text names an environment failure phrase, which is a named cause. [asks] are
     * the checks still open (the first goes into the next steps), [session] the session file when
     * it is not the default one, and [replied] says the reader has already answered a check.
     */
    fun view(
        flow: String,
        result: TriageResult,
        phrased: Boolean = false,
        asks: List<Ask> = result.ask,
        session: String? = null,
        replied: Boolean = false,
        self: Boolean = false,
        worksFor: Ask? = null,
    ): PlainView {
        val failing = result.facts.filter { it.side == Side.FAILING && !it.key.startsWith("run:") }
            .distinctBy { it.key }.sortedBy { shownRank(it.key) }
        val working = result.facts.filter { it.side == Side.PASSING }.associateBy { it.key }
        val top = result.rules.maxByOrNull { it.confidence }
        val reading = result.notEnvironment?.takeIf {
            it.signal == NotEnvSignal.CLEAN_RUN || (top == null && !phrased)
        }
        val cleanWorks = result.facts.any {
            it.key == CleanRun.KEY && it.value == CleanMatch.WORKS.id
        }
        val verdict = when {
            top != null -> "The report points at ${top.title}: ${sentence(top.mechanism)}"

            reading != null -> NotEnvironmentWording.verdict(
                reading,
                NotEnvironmentWording.ISSUE_LEAD,
                "report",
            )

            cleanWorks -> "It works on a clean machine, so something on your machine differs."

            failing.isNotEmpty() && failing.first().key.endsWith(".installed") ->
                "The report says ${label(failing.first())} is ${value(failing.first())} on the " +
                    "machine where it fails. I have no recorded rule that explains this failure " +
                    "from that alone, so I cannot say whether it matters yet."

            failing.isNotEmpty() -> {
                val fact = "${label(failing.first())} ${value(failing.first())}"
                "The report names $fact on the machine where it fails. I have no recorded rule " +
                    "that explains this failure from $fact alone, so I cannot say whether it " +
                    "matters yet."
            }

            replied ->
                "With your answers I still find no environment fact that explains this, so I " +
                    "cannot say what it points at."

            else ->
                "I found no environment fact in this ${if (self) "error text" else "report"} (no " +
                    "tool version, operating system or setting), so I cannot say what it points at."
        }
        val evidence = if (reading != null) {
            listOf(NotEnvironmentWording.evidence(reading, "report"))
        } else {
            buildList {
                if (cleanWorks) add("The clean run printed none of the errors the report shows.")
                failing.take(SHOWN_FACTS).forEach { fact ->
                    val seen = working[fact.key]?.value ?: "not stated"
                    add("${label(fact)}: $seen ${SIDES.works}, ${value(fact)} ${SIDES.fails}")
                }
                result.claims.firstOrNull()?.let {
                    add("The report says ${it.says}; ${it.knowledge}")
                }
            }.take(SHOWN)
        }
        val next = if (reading != null) {
            listOf(NotEnvironmentWording.next(reading))
        } else if (worksFor != null) {
            PlainView.worksLines(worksFor, session)
        } else if (asks.isNotEmpty()) {
            PlainView.askLines(asks.first(), session)
        } else if (replied) {
            listOf(
                "To compare the two machines, run drift why --save there.json on the one where " +
                    "it fails and drift why --there there.json on the one where it works.",
            )
        } else {
            buildList {
                failing.firstOrNull()?.let {
                    add(
                        "Ask the reporter to check ${label(it)} (${it.value}) on the machine " +
                            "where it fails, and to send what is on the machine where it works.",
                    )
                }
                add(
                    "To compare the two machines, ask them to run drift why --save " +
                        "there.json on the machine where it fails and drift why --there " +
                        "there.json on the machine where it works.",
                )
            }
        }
        val certainty = if (top != null) {
            "cause"
        } else if (failing.isNotEmpty()) {
            "fact"
        } else {
            "none"
        }
        return PlainView(
            flow,
            null,
            certainty,
            verdict,
            evidence,
            next,
            kind = reading?.let { NotEnvironmentWording.KIND },
            ask = if (reading == null) listOfNotNull(worksFor) + asks else emptyList(),
            session = session,
        )
    }

    /** What a reader wants first: the tools and their versions, then settings, then the system. */
    private fun shownRank(key: String) = when {
        key.startsWith("tool.") && key.endsWith(".version") -> 0
        key.startsWith("tool.") -> 1
        key.startsWith("project.") || key.startsWith("limits.") || key.startsWith("env.") -> 2
        else -> 3
    }

    /** A yes or no answer to "is it installed" reads as words. */
    private fun value(fact: Fact) = when {
        !fact.key.endsWith(".installed") -> fact.value
        fact.value == "yes" -> "installed"
        else -> "not installed"
    }

    private fun label(fact: Fact) = Families.describe(fact.key, null, fact.value, SIDES).label

    private fun sentence(text: String): String {
        val clean = text.trim()
        return if (clean.endsWith('.')) clean else "$clean."
    }

    private const val SHOWN = 3
}
