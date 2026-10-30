package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.scan.why.WhyFlow
import dev.gmitch215.drift.symptom.SymptomExtractor
import dev.gmitch215.drift.symptom.SymptomKind
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.why.Explain
import dev.gmitch215.drift.why.Inputs
import dev.gmitch215.drift.why.MemorySessionStore
import dev.gmitch215.drift.why.NotEnvironmentWording
import dev.gmitch215.drift.why.PlainView
import dev.gmitch215.drift.why.Session
import dev.gmitch215.drift.why.SessionStore
import dev.gmitch215.drift.why.Sides

const val WHY_LIMIT = CiDiagnosis.MAX_LOG

const val WHY_EMPTY = "Paste an error message, an issue text, a CI log or a saved capture first."

const val WHY_TOO_BIG = "That text is larger than 16 MB, so I did not read it."

const val WHY_NO_CONVERSATION =
    "There is no conversation yet, so I cannot compare this with anything. Paste the error " +
        "message or the report first, then answer the check it asks for."

const val WHY_CLOSED =
    "No check is waiting for that answer. Paste a new error message or report to start again."

const val WHY_SECOND_CAPTURE =
    "Paste the capture of the machine where it works too. Make it there with: " +
        "drift why --save works.json"

private const val ERROR_ONLY =
    "I read only the error text. To compare two machines, paste a capture of each; " +
        "drift why --save there.json makes one."
private const val FLOW = "Reading what you pasted"
private const val PREVIEW_LINES = 8
private const val PREVIEW_COLUMNS = 160
private const val SINGLE_LINE = 200
private val FAMILIES = listOf("tool.", "project.", "limits.", "cgroup.", "tz.")
private val SIDES = Sides("where it works", "where it fails", assumed = false)
private val HEADING = Regex("(?m)^#{1,6} \\S")
private val PROSE = Regex("(?m)^\\S+( \\S+){11,}[.?!]$")
private val ERRORS = listOf(
    "Traceback (most recent call last)" to "a Python traceback",
    "Exception in thread" to "a Java stack trace",
    "goroutine " to "a Go panic",
    "npm ERR!" to "npm error output",
    "error[E" to "a Rust compiler error",
    "    at " to "a stack trace",
)

/** What a paste can be taken for. */
enum class Paste(val label: String, val instead: String) {
    ERROR("an error message", "Read It as an Error Message Instead"),
    ISSUE("an issue text", "Read It as an Issue Text Instead"),
    CI_LOG("a CI log", "Read It as a CI Log Instead"),
    CAPTURE("a saved capture of one machine", "Read It as a Saved Capture Instead"),
}

/**
 * The check Drift waits on: what to run and which answers it takes. [later] is the next open
 * check, offered while this one only waits for the machine where it works.
 */
class AskCard(
    val ask: Ask,
    val sentence: String,
    val command: String,
    val reply: Boolean,
    val works: Boolean,
    val later: Ask? = null,
)

sealed interface ChatMessage {
    /** A paste, masked and cut to a preview, with what Drift took it for. */
    class You(
        val preview: String,
        val hidden: Int,
        val understood: String?,
        val rereadAs: Paste? = null,
    ) : ChatMessage

    class Drift(
        val verdict: String,
        val certainty: String,
        val found: List<String>,
        val next: List<String>,
        val card: AskCard? = null,
        val ci: CiResult.Done? = null,
    ) : ChatMessage

    class Note(val text: String) : ChatMessage
}

/**
 * A conversation with the `drift why` logic: what a paste means, how the session advances and
 * what Drift says next. The session stays in [store] (memory by default); nothing is written
 * anywhere.
 */
class WhyChat(private val store: SessionStore = MemorySessionStore()) {
    private class Held(val text: String, val failing: Boolean)

    private class Before(
        val size: Int,
        val session: Session?,
        val held: Held?,
        val capture: Capsule?,
        val card: AskCard?,
    )

    private val log = mutableListOf<ChatMessage>()
    private var held: Held? = null
    private var capture: Capsule? = null
    private var before: Before? = null
    private var pasted: String? = null

    val messages: List<ChatMessage> get() = log

    /** The check waiting for an answer, or null. */
    var card: AskCard? = null
        private set

    /** A new message; what it is gets detected unless [read] forces it. */
    fun send(text: String, read: Paste? = null) {
        if (!usable(text)) return
        before = Before(log.size, store.load(), held, capture, card)
        pasted = text
        if (read == null) {
            val waiting = card?.let { if (it.reply) it.ask else it.later }
            if (waiting != null && WhyFlow.replyFacts(waiting, text) != null) {
                answer(text, works = false)
                return
            }
            if (store.load() == null && oneLine(text) && WhyFlow.readable(text)) {
                log += you(text, null)
                log += ChatMessage.Note(WHY_NO_CONVERSATION)
                return
            }
        }
        card = null
        when (read ?: detect(text)) {
            Paste.CAPTURE -> capture(text)
            Paste.CI_LOG -> ciLog(text)
            Paste.ERROR -> report(text, Paste.ERROR)
            Paste.ISSUE -> report(text, Paste.ISSUE)
        }
    }

    /** The answer to the waiting check; [works] says it came from the machine where it works. */
    fun reply(text: String, works: Boolean) {
        if (!usable(text)) return
        before = null
        pasted = null
        answer(text, works)
    }

    /** Reads the last paste again as [read], as if it had been sent that way. */
    fun reread(read: Paste) {
        val text = pasted ?: return
        val back = before ?: return
        while (log.size > back.size) log.removeAt(log.size - 1)
        back.session?.let(store::save) ?: store.clear()
        held = back.held
        capture = back.capture
        card = back.card
        send(text, read)
    }

    /** Forgets the conversation. */
    fun restart() {
        log.clear()
        store.clear()
        held = null
        capture = null
        card = null
        before = null
        pasted = null
    }

    /** The conversation as text, as shown: pastes are masked and cut like on screen. */
    fun export(): String = buildString {
        append("Drift conversation\n")
        for (message in log) {
            append('\n')
            when (message) {
                is ChatMessage.You -> {
                    append("You:\n").append(message.preview).append('\n')
                    if (message.hidden > 0) append("(${message.hidden} more lines not shown)\n")
                    message.understood?.let { append(it).append('\n') }
                }

                is ChatMessage.Drift -> {
                    append("Drift:\n").append(message.verdict).append('\n')
                    append(message.certainty).append('\n')
                    if (message.found.isNotEmpty()) {
                        append("\nWhat I found:\n")
                        message.found.forEach { append("  ").append(it).append('\n') }
                    }
                    append("\nWhat to try next:\n")
                    message.next.forEach { append("  ").append(it).append('\n') }
                    message.card?.let { append("  Check to run: ").append(it.command).append('\n') }
                }

                is ChatMessage.Note -> append("Drift:\n").append(message.text).append('\n')
            }
        }
    }

    // #region reading a paste

    private fun usable(text: String): Boolean {
        if (text.isBlank()) {
            log += ChatMessage.Note(WHY_EMPTY)
        } else if (text.length > WHY_LIMIT) {
            log += ChatMessage.Note(WHY_TOO_BIG)
        } else {
            return true
        }
        return false
    }

    private fun oneLine(text: String) = text.trim().length <= SINGLE_LINE && '\n' !in text.trim()

    private fun answer(text: String, works: Boolean) {
        val session = store.load()
        val waiting = session?.let { if (works) WhyFlow.worksTarget(it) else it.open.firstOrNull() }
        if (session == null || waiting == null) {
            log += you(text, null)
            log += ChatMessage.Note(if (session == null) WHY_NO_CONVERSATION else WHY_CLOSED)
            return
        }
        val side = if (works) "where it works" else "where it fails"
        log += you(text, "I read this as the output of ${waiting.ask.line} from the machine $side.")
        val turn = if (works) WhyFlow.works(session, text) else WhyFlow.reply(session, text)
        when (turn) {
            is WhyFlow.Turn.Shown -> {
                turn.session?.let(store::save)
                show(turn.view)
            }

            is WhyFlow.Turn.Unreadable -> log += ChatMessage.Note(
                "I could not read that as the output of ${turn.ask.line}" +
                    (if (turn.works) " from the machine where it works" else "") +
                    ", so the check is still open. Run it again and paste what it prints.",
            )

            WhyFlow.Turn.Closed -> log += ChatMessage.Note(WHY_CLOSED)
        }
    }

    private fun report(text: String, kind: Paste) {
        log += you(text, "I read this as ${label(kind, text)}.", other(kind))
        held = null
        val turn = WhyFlow.report(
            FLOW,
            Redactor.redactText(text),
            self = kind == Paste.ERROR,
            note = if (kind == Paste.ERROR) ERROR_ONLY else null,
        )
        turn.session?.let(store::save) ?: store.clear()
        show(turn.view)
    }

    private fun ciLog(text: String) {
        val format = CiDiagnosis.detect(text) ?: return report(text, Paste.ERROR)
        val safe = Redactor.redactText(text)
        val failing = looksFailed(safe)
        val first = held
        if (first == null) {
            val run = if (failing) "failed" else "passed"
            log += you(text, "I read this as a ${format.label} log of a run that $run.")
            held = Held(safe, failing)
            store.clear()
            log += ChatMessage.Note(
                "Paste the log of a run that ${if (failing) "passed" else "failed"} as well, " +
                    "from the same CI system, and I will compare the two.",
            )
            return
        }
        val role = if (first.failing) "passed" else "failed"
        log += you(text, "I read this as a ${format.label} log of the run that $role.")
        val read = if (first.failing) {
            CiDiagnosis.run(safe, first.text, null, null)
        } else {
            CiDiagnosis.run(first.text, safe, null, null)
        }
        when (read) {
            is CiResult.Bad -> log += ChatMessage.Note(read.message)

            is CiResult.Done -> {
                held = null
                store.clear()
                val reading = Plain.reading(read.state)
                val id = when {
                    read.state.relevant.isEmpty() -> "none"
                    read.state.competing.size > 1 -> "tie"
                    else -> "likely"
                }
                log += ChatMessage.Drift(
                    reading.headline,
                    certainty(id, null),
                    listOfNotNull(reading.evidence),
                    listOf(reading.caution),
                    ci = read,
                )
            }
        }
    }

    private fun capture(text: String) {
        val read = capsule(text) ?: return report(text, Paste.ERROR)
        val fails = capture
        if (fails == null) {
            log += you(text, "I read this as the capture of the machine where it fails.")
            capture = read
            log += ChatMessage.Note(WHY_SECOND_CAPTURE)
            return
        }
        log += you(text, "I read this as the capture of the machine where it works.")
        capture = null
        fun has(c: Capsule, family: String) = c.attributes.any { it.path.startsWith(family) }
        val absent = FAMILIES.filter { has(fails, it) != has(read, it) }
        fun kept(c: Capsule) =
            c.copy(attributes = c.attributes.filter { a -> absent.none { a.path.startsWith(it) } })
        val failure = store.load()?.let { WhyFlow.symptoms(it.report) }
        val names = absent.joinToString(", ") { it.trimEnd('.') }
        val answer = Explain.run(
            Inputs(
                kept(read),
                kept(fails),
                SIDES,
                failure = failure,
                errorText = !failure.isNullOrEmpty(),
                closing = if (absent.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        "One of the captures has no $names facts, so I did not compare them. " +
                            "Make a full one with: drift why --save there.json",
                    )
                },
                looked = "the two captures",
            ),
        )
        show(PlainView.of(FLOW, answer))
    }

    // #endregion

    private fun show(view: PlainView) {
        card = cardOf(view)
        log += ChatMessage.Drift(
            view.verdict,
            certainty(view.certainty, view.kind),
            view.evidence,
            nextOf(view),
            card,
        )
    }

    private fun cardOf(view: PlainView): AskCard? {
        val ask = view.ask.firstOrNull() ?: return null
        val entry = store.load()?.asks?.firstOrNull { it.ask.family == ask.family }
        val reply = entry?.reply == null
        val works = ask.supportsWorks && entry?.works == null
        val open = store.load()?.open?.firstOrNull()?.ask
        val later = open?.takeIf { !reply && it.family != ask.family }
        if (!reply && !works && later == null) return null
        val sentence = if (reply) {
            ask.sentence()
        } else {
            "To compare, run ${ask.line} where the program works and paste what it prints."
        }
        return AskCard(ask, sentence, ask.line, reply, works, later)
    }

    /** The next steps without the lines that tell a command line how to answer a check. */
    private fun nextOf(view: PlainView): List<String> {
        val forCli = view.ask.flatMap {
            PlainView.askLines(it, view.session) + PlainView.worksLines(it, view.session)
        }.toSet()
        return view.next.filter { it !in forCli }
    }

    private fun you(text: String, understood: String?, rereadAs: Paste? = null): ChatMessage.You {
        val head = text.lineSequence().take(PREVIEW_LINES * 2).joinToString("\n")
        val shown = Redactor.redactText(head).lines().take(PREVIEW_LINES)
        return ChatMessage.You(
            shown.joinToString("\n") { it.take(PREVIEW_COLUMNS) },
            (text.lines().size - shown.size).coerceAtLeast(0),
            understood,
            rereadAs,
        )
    }

    private fun other(kind: Paste) = if (kind == Paste.ERROR) Paste.ISSUE else Paste.ERROR

    private fun label(kind: Paste, text: String): String = if (kind != Paste.ERROR) {
        kind.label
    } else {
        ERRORS.firstOrNull { it.first in text }?.second
            ?: kind.label
    }

    private fun looksFailed(text: String): Boolean = SymptomExtractor.extract(text).any {
        it.kind == SymptomKind.SIGNATURE || it.kind == SymptomKind.EXIT
    }

    companion object {
        /** What a paste is, by the cues logs and captures carry; the rest is text to read. */
        fun detect(text: String): Paste = when {
            capsule(text) != null -> Paste.CAPTURE

            CiDiagnosis.detect(text) != null -> Paste.CI_LOG

            "```" in text || HEADING.containsMatchIn(text) || PROSE.containsMatchIn(text) ->
                Paste.ISSUE

            else -> Paste.ERROR
        }

        private fun capsule(text: String): Capsule? {
            if (!text.trimStart().startsWith("{")) return null
            return try {
                Capsule.parse(text)
            } catch (e: JsonException) {
                null
            }
        }

        /**
         * The certainty of a verdict in plain words. [kind] is set for the reading that it is
         * not an environment difference, which stays a hint.
         */
        fun certainty(id: String, kind: String?): String = when {
            kind == NotEnvironmentWording.KIND -> "A hint only: Drift is not certain of this."
            id == "cause" -> "Cause: the data points at one difference."
            id == "likely" -> "Likely: this is the best fit, but it is not proven."
            id == "one-of" -> "One of several: the data cannot say which one."
            id == "tie" -> "Tied: these differences change together, so they cannot be told apart."
            id == "fact" -> "A fact only: the report names it, but no rule says it explains this."
            else -> "Not sure: nothing I can read explains it."
        }
    }
}
