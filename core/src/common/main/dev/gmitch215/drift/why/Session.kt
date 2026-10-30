package dev.gmitch215.drift.why

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.Confidence
import dev.gmitch215.drift.triage.Fact
import dev.gmitch215.drift.triage.Side
import dev.gmitch215.drift.triage.Span
import dev.gmitch215.drift.triage.Via

/**
 * One check the session asked for. [reply] is null while it waits and holds the facts of the
 * machine where it fails after; [works] holds the facts the same check gave where it works.
 */
class SessionAsk(
    val ask: Ask,
    val reply: List<Pair<String, String>>?,
    val works: List<Pair<String, String>>? = null,
)

/**
 * What `drift why` knows between two runs: the report text (redacted), the checks it asked for
 * and the facts each reply gave. The reply text itself is never kept, only the facts read from
 * it. It is written to [PATH] whenever `drift why` asks.
 */
class Session(val report: String, val asks: List<SessionAsk>, val elsewhere: Boolean = false) {
    val open: List<SessionAsk> get() = asks.filter { it.reply == null }

    /** The facts the replies gave, as facts of the failing machine. */
    val known: List<Fact>
        get() = asks.flatMap { it.reply.orEmpty() }.map { (key, value) ->
            Fact(key, value, Side.FAILING, Confidence(REPLY_CONFIDENCE), Via.TOOL, Span(0, 0, 0))
        }

    fun withReport(text: String) = Session(text, asks, elsewhere)

    fun answered(ask: SessionAsk, facts: List<Pair<String, String>>) = Session(
        report,
        asks.map { if (it === ask) SessionAsk(it.ask, facts, it.works) else it },
        elsewhere,
    )

    /** The check [ask] answered with the facts of the machine where it works. */
    fun withWorks(ask: SessionAsk, facts: List<Pair<String, String>>) = Session(
        report,
        asks.map { if (it === ask) SessionAsk(it.ask, it.reply, facts) else it },
        elsewhere,
    )

    /** The answered checks, then [open] as the checks still waiting. */
    fun withOpen(open: List<Ask>) = Session(
        report,
        asks.filter { it.reply != null } + open.map { o ->
            SessionAsk(o, null, asks.firstOrNull { it.ask.family == o.family }?.works)
        },
        elsewhere,
    )

    fun toJson(): JsonObject = obj(
        "schema" to JsonInt(SCHEMA),
        "report" to JsonString(report),
        "asks" to JsonArray(
            asks.map {
                val entry = obj(
                    "family" to JsonString(it.ask.family),
                    "argv" to JsonArray(it.ask.command.map(::JsonString)),
                    "command" to JsonString(it.ask.line),
                    "why" to JsonString(it.ask.why),
                    "reply" to (it.reply?.let(::facts) ?: JsonNull),
                )
                it.works?.let { works -> JsonObject(entry.fields + ("works" to facts(works))) }
                    ?: entry
            },
        ),
    ).let { if (elsewhere) JsonObject(it.fields + ("elsewhere" to JsonBool(true))) else it }

    fun text(): String = CanonicalJson.encode(toJson())

    companion object {
        const val PATH = ".drift/why/session.json"
        const val SCHEMA = 1L
        private const val REPLY_CONFIDENCE = 900

        fun start(report: String, asks: List<Ask>, elsewhere: Boolean = false) =
            Session(report, asks.map { SessionAsk(it, null) }, elsewhere)

        private fun facts(list: List<Pair<String, String>>) = obj(
            "facts" to JsonArray(
                list.map { (k, v) -> obj("key" to JsonString(k), "value" to JsonString(v)) },
            ),
        )

        private fun readFacts(value: JsonValue?): List<Pair<String, String>>? =
            (value as? JsonObject)?.require("facts")?.array()?.map { f ->
                val fact = f as JsonObject
                fact.require("key").string() to fact.require("value").string()
            }

        /** Null when [text] is not a session of this schema. */
        fun parse(text: String): Session? = try {
            val root = CanonicalJson.parse(text) as? JsonObject
            if (root == null || root.require("schema").long() != SCHEMA) {
                null
            } else {
                Session(
                    root.require("report").string(),
                    root.require("asks").array().map { item ->
                        val entry = item as JsonObject
                        val ask = Ask(
                            entry.require("family").string(),
                            entry.require("argv").array().map { it.string() },
                            entry.require("why").string(),
                        )
                        SessionAsk(
                            ask,
                            readFacts(entry["reply"]),
                            readFacts(entry["works"]),
                        )
                    },
                    (root["elsewhere"] as? JsonBool)?.value == true,
                )
            }
        } catch (e: JsonException) {
            null
        } catch (e: ClassCastException) {
            null
        }
    }
}
