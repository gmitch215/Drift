package dev.gmitch215.drift.scan.why

import dev.gmitch215.drift.ci.Execution
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.scan.Replies
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomExtractor
import dev.gmitch215.drift.symptom.SymptomKind
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.Asks
import dev.gmitch215.drift.triage.CleanMatch
import dev.gmitch215.drift.triage.CleanRun
import dev.gmitch215.drift.triage.CleanRunReading
import dev.gmitch215.drift.triage.Side
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.triage.TriageOptions
import dev.gmitch215.drift.triage.TriageResult
import dev.gmitch215.drift.why.Certainty
import dev.gmitch215.drift.why.Explain
import dev.gmitch215.drift.why.FailurePhrases
import dev.gmitch215.drift.why.Families
import dev.gmitch215.drift.why.Inputs
import dev.gmitch215.drift.why.PlainView
import dev.gmitch215.drift.why.Session
import dev.gmitch215.drift.why.SessionAsk
import dev.gmitch215.drift.why.Sides
import dev.gmitch215.drift.why.WhyIssue

/**
 * The steps of `drift why` that read a report and the answers to its checks, with no file or
 * process access: the command and Studio both run these and show the result.
 */
object WhyFlow {
    const val ERROR_TEXT_BYTES = 64 * 1024
    private const val TAIL_LINES = 200
    private const val LAST_LINES = 30
    private const val COMPARED_SHOWN = 4
    private const val CAPTURE_KEEP =
        "tool. os. kernel. limits. runtime. project. ci. cgroup. tz. locale. env.TZ env.LANG " +
            "env.LC_ALL"

    /** What one step produced. */
    sealed interface Turn {
        /** [session] is what to keep for the next step, or null to keep nothing new. */
        class Shown(val view: PlainView, val session: Session?, val result: TriageResult?) : Turn

        /** The input is not the output of [ask] ([works]: on the machine where it works). */
        class Unreadable(val ask: Ask, val works: Boolean) : Turn

        /** No check waits for this kind of answer. */
        data object Closed : Turn
    }

    /** What the log reader finds, plus the last lines as they are: a shell's message is no log. */
    fun symptoms(output: String): List<Symptom> {
        val text = Execution.tail(output, TAIL_LINES).first
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(LAST_LINES)
        return SymptomExtractor.extract(text) +
            lines.mapIndexed { i, line -> Symptom(SymptomKind.SIGNATURE, line, i + 1) }
    }

    /**
     * Reads [text] as a report; [self] says it is the error text of the reporter's own machine.
     * When it leaves checks open the session to keep is in the result. [shown] is the session
     * file the next steps should name when it is not the default one.
     */
    fun report(
        flow: String,
        text: String,
        self: Boolean = false,
        note: String? = null,
        shown: String? = null,
    ): Turn.Shown {
        val result = Triage.run(text, TriageOptions(worksElsewhere = self))
        val asks = result.ask
        val phrased = FailurePhrases.pointers(symptoms(text)).isNotEmpty()
        val stated = if (self) null else statedCheck(result)
        val view = WhyIssue.view(
            flow,
            result,
            phrased,
            asks,
            shown,
            self = self,
            worksFor = stated?.ask,
        ).withNote(note)
        val keep = if ((asks.isNotEmpty() || stated != null) && view.kind == null) {
            Session(
                Triage.clean(text),
                listOfNotNull(stated) + asks.map { SessionAsk(it, null) },
                self,
            )
        } else {
            null
        }
        return Turn.Shown(view, keep, result)
    }

    /**
     * A report that states a tool version and gets no rule from it: that fact is the answer of the
     * failing machine to the version check, so the next step is the same check where it works.
     */
    private fun statedCheck(result: TriageResult): SessionAsk? {
        if (result.rules.isNotEmpty() || result.notEnvironment != null || result.draft != null) {
            return null
        }
        val fact = result.facts.filter {
            it.side == Side.FAILING && it.key.startsWith("tool.") && it.key.endsWith(".version")
        }.maxByOrNull { it.confidence.permille } ?: return null
        if (fact.value.firstOrNull()?.isDigit() != true) return null
        val family = fact.key.removeSuffix(".version")
        val ask = Asks.known.firstOrNull { it.family == family && it.supportsWorks } ?: return null
        return SessionAsk(ask, listOf(fact.key to fact.value))
    }

    /** True when [input] reads as the output of some check Drift asks for. */
    fun readable(input: String): Boolean =
        input.isNotBlank() && Asks.known.any { Replies.read(it, input) != null }

    /**
     * `--reply`: [input] answers the first open check of [session]. [shown] is the session file
     * the next steps should name when it is not the default one.
     */
    fun reply(session: Session, input: String, shown: String? = null): Turn {
        val waiting = session.open.firstOrNull() ?: return Turn.Closed
        val unreadable = Turn.Unreadable(waiting.ask, works = false)
        var base = session
        val facts = if (waiting.ask.family == CleanRun.ERROR_TEXT) {
            val text = input.take(ERROR_TEXT_BYTES)
            if (text.isBlank() || CleanRun.isBinary(text)) return unreadable
            base = session.withReport(Triage.clean(session.report + "\n\n```\n" + text + "\n```\n"))
            emptyList()
        } else if (waiting.ask.family == CleanRun.FAMILY) {
            val reading = Replies.cleanRun(session.report, input) ?: return unreadable
            if (reading.match == CleanMatch.DIFFERENT) return differed(waiting.ask, reading, shown)
            listOf(CleanRun.KEY to reading.match.id)
        } else {
            replyFacts(waiting.ask, input) ?: return unreadable
        }
        val answered = base.answered(waiting, facts)
        val paired = answered.asks.firstOrNull {
            it.ask.family == waiting.ask.family && it.reply != null && it.works != null
        }
        if (paired != null) return compare(answered, paired, shown)
        val result = Triage.run(
            answered.report,
            TriageOptions(worksElsewhere = answered.elsewhere),
            known = answered.known,
        )
        val taken = answered.asks.filter { it.reply != null }.map { it.ask.family }.toSet()
        val next = result.ask.filter { it.family !in taken }
        val final = answered.withOpen(next)
        val phrased = FailurePhrases.pointers(symptoms(answered.report)).isNotEmpty()
        val view = WhyIssue.view(
            "drift why: reading the report with your answers",
            result,
            phrased,
            next,
            shown,
            replied = true,
            worksFor = waiting.ask.takeIf {
                it.supportsWorks && result.rules.isEmpty() && result.notEnvironment == null
            },
        )
        return Turn.Shown(view, final, result)
    }

    /**
     * The check `--reply --works` answers: the first that has the failing side and no works side,
     * else the first that has neither.
     */
    fun worksTarget(session: Session): SessionAsk? = session.asks.firstOrNull {
        it.works == null && it.ask.supportsWorks && it.reply != null
    } ?: session.asks.firstOrNull { it.works == null && it.ask.supportsWorks }

    /** `--reply --works`: [input] is the answer from the machine where it works. */
    fun works(session: Session, input: String, shown: String? = null): Turn {
        val target = worksTarget(session) ?: return Turn.Closed
        val facts = replyFacts(target.ask, input) ?: return Turn.Unreadable(target.ask, true)
        val updated = session.withWorks(target, facts)
        val pair = updated.asks.first { it.ask.family == target.ask.family }
        if (pair.reply != null) return compare(updated, pair, shown)
        val view = PlainView(
            "drift why: reading the report with your answers",
            null,
            "none",
            "I have the answer from the machine where it works. The answer from the machine " +
                "where it fails is still missing.",
            emptyList(),
            PlainView.askLines(target.ask, shown),
            ask = listOf(target.ask),
            session = shown,
        )
        return Turn.Shown(view, updated, null)
    }

    /** Both sides of one check are known: compare them like `--there` compares two machines. */
    private fun compare(session: Session, pair: SessionAsk, shown: String?): Turn.Shown {
        fun capsule(label: String, facts: List<Pair<String, String>>) =
            Capsule(label, facts.map { (key, value) -> Attribute(key, value, "reply") })
        val sides = Sides("where it works", "where it fails", assumed = false)
        val works = pair.works.orEmpty()
        val fails = pair.reply.orEmpty()
        val failure = symptoms(session.report)
        val answer = Explain.run(
            Inputs(
                capsule("works", works),
                capsule("fails", fails),
                sides,
                failure = failure,
                errorText = failure.isNotEmpty(),
                looked = "the answers you gave",
            ),
        )
        val flow = "drift why: comparing the answers from where it works and where it fails"
        val differs = answer.certainty != Certainty.NONE
        val result = Triage.run(
            session.report,
            TriageOptions(worksElsewhere = session.elsewhere),
            known = session.known,
        )
        val taken = session.asks.filter { it.reply != null }.map { it.ask.family }.toSet()
        val next = if (differs) emptyList() else result.ask.filter { it.family !in taken }
        val view = if (differs) {
            PlainView.of(flow, answer)
        } else {
            same(flow, sides, pair, works, fails, next, shown)
        }
        return Turn.Shown(view, session.withOpen(next), result)
    }

    private fun same(
        flow: String,
        sides: Sides,
        pair: SessionAsk,
        works: List<Pair<String, String>>,
        fails: List<Pair<String, String>>,
        next: List<Ask>,
        shown: String?,
    ): PlainView {
        val there = works.toMap()
        val key = fails.firstOrNull()?.first ?: pair.ask.family
        val label = Families.describe(key, there[key], fails.toMap()[key], sides).label
        val lines = fails.map { (k, v) ->
            val d = Families.describe(k, there[k], v, sides)
            if (d.works == d.fails) {
                "${d.label}: ${d.fails} where it works and where it fails"
            } else {
                "${d.label}: ${d.works} where it works, ${d.fails} where it fails"
            }
        }
        return PlainView(
            flow,
            sides,
            "none",
            "$label is the same where it works and where it fails, so it is not what differs.",
            lines.take(COMPARED_SHOWN),
            if (next.isNotEmpty()) {
                PlainView.askLines(next.first(), shown)
            } else {
                listOf(
                    "To compare more, run drift why --save there.json where it fails and " +
                        "drift why --there there.json where it works.",
                )
            },
            ask = next,
            session = shown,
        )
    }

    /** The clean run failed, but not the way the report did: the check stays open. */
    private fun differed(ask: Ask, reading: CleanRunReading, shown: String?): Turn.Shown {
        val view = PlainView(
            "drift why: reading the report with your answers",
            null,
            "none",
            "The clean run failed differently from the report (it printed ${reading.seen}, the " +
                "report shows ${reading.wanted}), so I cannot compare them yet.",
            emptyList(),
            PlainView.askLines(ask, shown),
            ask = listOf(ask),
            session = shown,
        )
        return Turn.Shown(view, null, null)
    }

    /** The facts [input] gives for [ask]; null when it is not the output of that command. */
    fun replyFacts(ask: Ask, input: String): List<Pair<String, String>>? {
        if (ask.family != Ask.CAPTURE) {
            return Replies.read(ask, input)?.map { it.path to it.value }
        }
        val capsule = try {
            Capsule.parse(input)
        } catch (e: JsonException) {
            return null
        }
        val keep = CAPTURE_KEEP.split(' ')
        return capsule.attributes.filter { a -> keep.any { a.path.startsWith(it) } }
            .map { it.path to it.value }
    }
}
