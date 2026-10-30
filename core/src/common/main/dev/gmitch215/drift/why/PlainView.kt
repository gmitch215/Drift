package dev.gmitch215.drift.why

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.case.Render
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.triage.Ask

/** The default view: the flow line, then the verdict, the evidence and the next step. */
class PlainView(
    val flow: String,
    val sides: Sides?,
    val certainty: String,
    val verdict: String,
    val evidence: List<String>,
    val next: List<String>,
    val findings: List<Finding> = emptyList(),
    val kind: String? = null,
    val ask: List<Ask> = emptyList(),
    val session: String? = null,
) {
    /** The same view with [note] as a last line of the next steps. */
    fun withNote(note: String?): PlainView = if (note == null) {
        this
    } else {
        PlainView(
            flow, sides, certainty, verdict, evidence, next + note, findings, kind, ask, session,
        )
    }

    /** The same view with [asks] added: the first in the next steps, all of them in the JSON. */
    fun withAsk(asks: List<Ask>, session: String?): PlainView = if (asks.isEmpty()) {
        this
    } else {
        PlainView(
            flow,
            sides,
            certainty,
            verdict,
            evidence,
            next + askLines(asks.first(), session),
            findings,
            kind,
            asks,
            session,
        )
    }

    /** The same view with [text] as the verdict, or this view when [text] is null. */
    fun withVerdict(text: String?): PlainView = if (text == null) {
        this
    } else {
        PlainView(
            flow, sides, certainty, text, evidence, next, findings, kind, ask, session,
        )
    }

    fun text(): String {
        val out = mutableListOf<String>()
        out += flow
        out += ""
        out += wrap(verdict, 0)
        if (evidence.isNotEmpty()) {
            out += ""
            out += "What I found:"
            evidence.forEach { out += wrap(it, 2) }
        }
        out += ""
        out += "What to try next:"
        next.forEach { out += wrap(it, 2) }
        return out.joinToString("\n") + "\n"
    }

    fun plain(): JsonObject {
        val more = buildMap {
            kind?.let { put("kind", JsonString(it)) }
            if (ask.isNotEmpty()) {
                put("ask", JsonArray(ask.map { it.toJson(session, works = true) }))
            }
        }
        return JsonObject(plainFields().fields + more)
    }

    private fun plainFields(): JsonObject = obj(
        "flow" to JsonString(flow),
        "direction" to (
            sides?.let {
                obj(
                    "works" to JsonString(it.works),
                    "fails" to JsonString(it.fails),
                    "assumed" to JsonBool(it.assumed),
                )
            } ?: JsonNull
            ),
        "certainty" to JsonString(certainty),
        "verdict" to JsonString(verdict),
        "evidence" to JsonArray(evidence.map(::JsonString)),
        "findings" to JsonArray(
            findings.map {
                obj(
                    "path" to JsonString(it.path),
                    "label" to JsonString(it.described.label),
                    "works" to JsonString(it.described.works),
                    "fails" to JsonString(it.described.fails),
                )
            },
        ),
        "next" to JsonArray(next.map(::JsonString)),
    )

    companion object {
        const val WIDTH = 100

        /** The works-side step of [ask]: the same check where the program works. */
        fun worksLines(ask: Ask, session: String?): List<String> =
            listOf(ask.pasteWorks(session), ask.sshWorks(session))

        /** The sentence that asks for [ask] and its one-step form. */
        fun askLines(ask: Ask, session: String?): List<String> =
            listOf(ask.sentence(), "In one step: ${ask.piped(session)}")

        fun of(flow: String, a: Answer): PlainView {
            val reading = a.notEnvironment
            return PlainView(
                flow,
                a.sides,
                a.certainty.id,
                a.verdict,
                a.evidence,
                a.next,
                if (reading == null) a.findings.take(SHOWN) else emptyList(),
                kind = reading?.let { NotEnvironmentWording.KIND },
            )
        }

        fun detail(a: Answer): String = "\nThe full ranking and plan behind this answer:\n\n" +
            Render.render(a.ranking, a.plan, Detail.DETAIL)

        private const val SHOWN = 3

        fun wrap(text: String, indent: Int): List<String> {
            val pad = " ".repeat(indent)
            val lines = mutableListOf<String>()
            var line = pad
            for (word in text.split(' ').filter { it.isNotEmpty() }) {
                if (line.length > indent && line.length + 1 + word.length > WIDTH) {
                    lines += line
                    line = "$pad  "
                }
                line += if (line.trim().isEmpty()) word else " $word"
            }
            return lines + line
        }
    }
}
