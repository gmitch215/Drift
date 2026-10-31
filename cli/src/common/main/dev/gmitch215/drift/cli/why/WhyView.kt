package dev.gmitch215.drift.cli.why

import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.why.Answer
import dev.gmitch215.drift.why.Certainty
import dev.gmitch215.drift.why.Inputs
import dev.gmitch215.drift.why.PlainView
import dev.gmitch215.drift.why.Session
import dev.gmitch215.drift.why.Sides

internal object WhyView {
    const val CASE = ".drift/why/case"
    const val ERROR_TEXT_FLOW = "drift why: reading the error text"
    const val ERROR_ONLY = "No CI run or second machine was available, so I read only the " +
        "error text. To compare with another machine, add --there PLACE."

    fun savedCaptureFlow(path: String) =
        "drift why: this machine against the saved capture in $path"

    fun errorHint(target: String) =
        "Paste the error message you saw: drift why --there $target --error \"...\""

    /** The inputs of a comparison of two captures with no command run on either side. */
    fun compareInputs(
        works: Capsule,
        fails: Capsule,
        sides: Sides,
        failure: List<Symptom>?,
        pin: (String) -> String?,
        hint: String,
        closing: List<String>,
        looked: String,
    ) = Inputs(
        works,
        fails,
        sides,
        failure = failure,
        pin = pin,
        errorText = failure != null,
        failureHint = hint,
        caseDir = CASE,
        closing = closing,
        looked = looked,
    )

    fun failureLines(answer: Answer) =
        answer.failure.orEmpty().filter { it.kind == SymptomKind.SIGNATURE }.map { it.value }

    /**
     * The checks an answer of "cannot tell" can still ask for: those the error text calls for
     * and the comparison did not already cover.
     */
    fun openChecks(answer: Answer): List<Ask> {
        if (answer.certainty != Certainty.NONE || answer.notEnvironment != null) return emptyList()
        val lines = failureLines(answer)
        if (lines.isEmpty()) return emptyList()
        val compared = (answer.works.attributes + answer.fails.attributes).map { it.path }
        return Triage.run(lines.joinToString("\n")).ask.filter { ask ->
            ask.family != Ask.CAPTURE &&
                compared.none { it == ask.family || it.startsWith(ask.family + ".") }
        }
    }

    fun view(flow: String, answer: Answer, checks: List<Ask>, verdict: String?, shown: String?) =
        PlainView.of(flow, answer).withVerdict(verdict).withAsk(checks, shown)

    fun session(answer: Answer, checks: List<Ask>): Session =
        Session.start(Triage.clean(failureLines(answer).joinToString("\n")), checks)
}
