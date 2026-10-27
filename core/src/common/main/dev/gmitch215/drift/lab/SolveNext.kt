package dev.gmitch215.drift.lab

import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.Asks

/**
 * One thing that would settle the cause. [kind] names the family of step, [text] says what and
 * why, [command] is the exact command to run (null when the step is something to change by hand)
 * and [reply] is the form that feeds the answer back (null when no answer is parseable). [ask] is
 * set when the step is a check from the ask table that `drift why` prints too; its [command] and
 * [reply] are then the ask's command and piped form, and the JSON adds the ask's `family` and
 * `piped`.
 */
data class NextStep(
    val kind: String,
    val text: String,
    val command: String?,
    val reply: String?,
    val ask: Ask? = null,
) {
    fun toJson(): JsonObject = obj(
        "kind" to JsonString(kind),
        "text" to JsonString(text),
        "command" to (command?.let { JsonString(it) } ?: JsonNull),
        "reply" to (reply?.let { JsonString(it) } ?: JsonNull),
    ).let {
        if (ask == null) it else JsonObject(it.fields + ask.toJson().fields)
    }

    companion object {
        /** The step that runs [ask], with the command and the piped form the ask prints. */
        fun of(kind: String, text: String, ask: Ask) =
            NextStep(kind, text, ask.line, ask.piped(), ask)
    }
}

/** What [SolveNext] needs to know about a solve that could not settle the cause. */
class NextContext(
    val reason: StuckWhy,
    val detail: String,
    val options: SolveOptions,
    val caseDir: String,
    val unmirrored: List<String>,
    val candidates: List<String>,
)

/**
 * Maps the reason a solve stopped to the steps that would settle the cause. A solve that stops
 * without a verdict on the cause says what to run next; STUCK is kept for a certificate that has no
 * step to give, and [SolveNext.none] says why.
 */
object SolveNext {
    const val CAPTURE = "capture"
    const val MANUAL = "manual-experiment"
    const val PILOT = "longer-pilot"
    const val BUDGET = "raise-budget"
    const val RERUN = "rerun"
    const val UNMIRRORED = "unmirrored"

    private const val CAPTURE_WHY = "it records the tools, settings and limits of the machine " +
        "where it fails, which the container built from the capsule does not show"
    private const val LISTED = 8
    private val QUIET = listOf("env.", "drift.", "kotlin.", "runtime.")

    fun steps(c: NextContext): List<NextStep> = when (c.reason) {
        StuckWhy.NOT_REPRODUCED -> capture(c) + unmirrored(c) + pilot(c)
        StuckWhy.NO_CANDIDATES -> capture(c)
        StuckWhy.UNCONTROLLABLE -> manual(c)
        StuckWhy.BUDGET -> if (sizing(c.detail)) pilot(c) else budget(c)
        StuckWhy.EXECUTOR -> rerun(c)
    }

    /** Why a STUCK certificate has no step, for a reason that [steps] answered with none. */
    fun none(c: NextContext): String = when {
        c.reason == StuckWhy.BUDGET && sizing(c.detail) ->
            "the pilot already runs the most trials it allows " +
                "(${SolveOptions.MAX_PILOT} per arm) and no arm of that size reaches the target " +
                "power, so no option raises it"

        else ->
            "no step settles it: ${c.reason.id} stopped the run and nothing in the " +
                "certificate names a command that would change that"
    }

    /** [v] as a NEEDS verdict with its steps, or as STUCK with the reason there is none. */
    fun resolve(v: SolveVerdict, c: NextContext): SolveVerdict {
        if (v.kind != VerdictKind.STUCK) return v
        val steps = steps(c)
        if (steps.isEmpty()) return v.copy(statement = v.statement + ". " + none(c))
        return v.copy(kind = VerdictKind.NEEDS, steps = steps)
    }

    private fun capture(c: NextContext) = listOf(
        NextStep.of(
            CAPTURE,
            "The container built from the capsule does not show the cause. Capture the failing " +
                "machine with its tools and project files, which the capsule may lack, and " +
                "compare it with the passing one.",
            Asks.captureAsk(CAPTURE_WHY),
        ),
    )

    private fun unmirrored(c: NextContext): List<NextStep> {
        val names = c.unmirrored.filter { n -> QUIET.none { n.startsWith(it) } }
        if (names.isEmpty()) return emptyList()
        val shown = names.take(LISTED).joinToString(", ")
        val more = names.size - LISTED
        val tail = if (more > 0) " and $more more" else ""
        return listOf(
            NextStep(
                UNMIRRORED,
                "A container does not mirror $shown$tail. If one of them differs on the failing " +
                    "machine, change it there to the passing value and run the failing command " +
                    "again.",
                null,
                null,
            ),
        )
    }

    private fun pilot(c: NextContext): List<NextStep> {
        if (c.options.pilot >= SolveOptions.MAX_PILOT) return emptyList()
        return listOf(
            NextStep(
                PILOT,
                "The pilot of ${c.options.pilot} trials per arm may be too short to see or size " +
                    "a failure that does not happen every time; run it at the most it allows, " +
                    "${SolveOptions.MAX_PILOT}.",
                solve(c, "--pilot ${SolveOptions.MAX_PILOT}"),
                null,
            ),
        )
    }

    private fun manual(c: NextContext): List<NextStep> {
        val which = if (c.candidates.isEmpty()) "" else " (${c.candidates.joinToString(", ")})"
        return listOf(
            NextStep(
                MANUAL,
                "No container can set what differs$which. Change one of them on the failing " +
                    "machine to the passing value, run the failing command again and note " +
                    "whether it passes; the case lists the experiments.",
                "drift next ${c.caseDir}",
                null,
            ),
        )
    }

    private fun budget(c: NextContext): List<NextStep> {
        val b = c.options.budget
        val trials = b.maxTrials * 2
        val minutes = b.maxMinutes * 2
        return listOf(
            NextStep(
                BUDGET,
                "The budget of ${b.maxTrials} trials and ${b.maxMinutes} minutes ran out " +
                    "(${c.detail}); double it.",
                solve(c, "--budget trials=$trials,minutes=$minutes"),
                null,
            ),
        )
    }

    private fun rerun(c: NextContext) = listOf(
        NextStep(
            RERUN,
            "The executor failed: ${c.detail}. Fix that step and run the solve again into a new " +
                "case directory.",
            solve(c, ""),
            null,
        ),
    )

    private fun sizing(detail: String) = "no arm of up to" in detail || "no count up to" in detail

    private fun solve(c: NextContext, extra: String): String {
        val o = c.options
        val parts = mutableListOf(
            "drift solve ${o.passing} ${o.failing}",
            "--run ${quote(o.command)}",
            "--fail-when ${quote(o.failWhen.text())}",
        )
        if (extra.isNotEmpty()) parts += extra
        parts += "--case ${c.caseDir}-next"
        return parts.joinToString(" ")
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
}
