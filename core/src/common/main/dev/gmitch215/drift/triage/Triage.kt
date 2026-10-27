package dev.gmitch215.drift.triage

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.redact.Anonymizer
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.symptom.SymptomExtractor

/**
 * [publishThreshold] is the confidence at which a draft rule is published instead of drafted
 * (null: drafts only). [author] is hashed before it is stored. [date] is the day the report was
 * read, `yyyy-mm-dd`, written into the provenance; a rule is never published without it.
 * [bases] replaces the starting confidence of a [Via] (see [ConfidenceTable]). [worksElsewhere]
 * says the program is known to work on another machine, as for a reporter who has only the error
 * text: no program-side reading is made from the text alone and the capture is asked for.
 */
data class TriageOptions(
    val publishThreshold: Confidence? = null,
    val author: String? = null,
    val date: String? = null,
    val bases: Map<Via, Int> = emptyMap(),
    val worksElsewhere: Boolean = false,
)

/**
 * Everything `drift triage` found in one issue body. [body] is the SHA-256 of the redacted text
 * and [author] the hashed login; neither the text nor a login is kept. [truncated] is true when
 * the body was longer than [Triage.MAX_BODY].
 */
data class TriageResult(
    val body: String,
    val author: String?,
    val truncated: Boolean,
    val facts: List<Fact>,
    val rules: List<RuleHit>,
    val claims: List<ClaimHit>,
    val draft: DraftRule?,
    val notEnvironment: NotEnvironment? = null,
    val ask: List<Ask> = emptyList(),
) {
    /** `notEnvironment` and `ask` are present only when the reading or a check applies. */
    fun toJson(): JsonObject {
        val fields = obj(
            "schema" to JsonInt(SCHEMA),
            "body" to JsonString(body),
            "author" to (author?.let(::JsonString) ?: JsonNull),
            "truncated" to JsonBool(truncated),
            "facts" to JsonArray(facts.map { it.toJson() }),
            "rules" to JsonArray(rules.map { it.toJson() }),
            "claims" to JsonArray(claims.map { it.toJson() }),
            "draft" to (draft?.toJson() ?: JsonNull),
        ).fields
        val more = buildMap {
            notEnvironment?.let { put("notEnvironment", it.toJson()) }
            if (ask.isNotEmpty()) put("ask", JsonArray(ask.map { it.toJson() }))
        }
        return JsonObject(fields + more)
    }

    companion object {
        const val SCHEMA = 1L
    }
}

object Triage {
    const val MAX_BODY = 262_144
    const val MAX_LINE = 4096
    private const val USER_HASH = 10

    /** The stored form of a login: `u` and ten hex digits of the SHA-256 of the lowercase name. */
    fun hashUser(login: String): String =
        "u" + Sha256.hex(Tokens.lower(login.trim().removePrefix("@"))).take(USER_HASH)

    /**
     * Reads [text] against [rules] and returns the facts, rule matches, claims and draft. [known]
     * are facts the reader has since checked on the failing machine; they replace the same key
     * and side found in the text.
     */
    fun run(
        text: String,
        options: TriageOptions = TriageOptions(),
        rules: List<Rule> = Rules.all,
        known: List<Fact> = emptyList(),
    ): TriageResult {
        val clipped = if (text.length > MAX_BODY) text.substring(0, MAX_BODY) else text
        val clean = clean(clipped)
        val doc = Doc(clean)
        val read = Combine.facts(Extractor(doc, options.bases).run())
        val facts = read.filter { f -> known.none { it.key == f.key && it.side == f.side } } + known
        val hits = RuleScan.scan(rules, facts, clean)
        val claims = Claims.check(doc, facts, rules)
        val body = Sha256.hex(clean)
        val draft = Drafts.suggest(facts, hits, body, options, rules)
        val stated = facts.filter { it.side == Side.FAILING && !it.key.startsWith("run:") }
        val osOnly = stated.all { it.key.startsWith("os.") }
        val story = facts.any { it.storyOfChange() } || ProgramFault.contrast(clean)
        val program = draft == null && !story
        val cleaned = if (hits.isEmpty()) CleanRun.confirmed(doc, facts) else null
        val reading = if (cleaned != null) {
            cleaned
        } else if (!options.worksElsewhere && hits.isEmpty() && (osOnly || program)) {
            NotEnvironment.find(
                clean.lines(),
                strict = osOnly && stated.isEmpty(),
                crash = osOnly,
                program = program,
            )
        } else {
            null
        }
        val open = hits.isEmpty() && draft == null && reading == null
        return TriageResult(
            body,
            options.author?.let(::hashUser),
            text.length > MAX_BODY,
            facts,
            hits,
            claims,
            draft,
            reading,
            if (open) Asks.derive(doc, facts, options.worksElsewhere) else emptyList(),
        )
    }

    /**
     * Line endings become `\n`, secrets are masked by [Redactor], other users' home paths by
     * [Anonymizer], and an `@login` mention becomes `@` plus [hashUser]. A mention inside a
     * fenced code block stays, since it is usually an annotation. Overlong lines are cut at
     * [MAX_LINE]. Spans in a result refer to this text.
     */
    fun clean(text: String): String {
        val unified = ciLog(text.replace("\r\n", "\n").replace('\r', '\n'))
        val masked = Anonymizer.anonymize(Redactor.redactText(unified), emptyMap())
        val out = StringBuilder(masked.length)
        var fenced = false
        for ((n, line) in masked.split('\n').withIndex()) {
            val t = line.trim()
            val toggles = t.startsWith("```") || t.startsWith("~~~")
            if (toggles) fenced = !fenced
            val kept = if (fenced || toggles) line else mentions(line)
            if (n > 0) out.append('\n')
            out.append(if (kept.length > MAX_LINE) kept.substring(0, MAX_LINE) else kept)
        }
        return out.toString()
    }

    private const val LOG_MIN_LINES = 3

    /** The text of a log line after its `job<TAB>step<TAB>` and timestamp, or null. */
    private fun logText(line: String): String? {
        var at = 0
        var tabs = 0
        while (tabs < 2) {
            val tab = line.indexOf('\t', at)
            if (tab < 0) break
            at = tab + 1
            tabs++
        }
        for (from in listOf(at, 0).distinct()) {
            if (SymptomExtractor.epochAt(line, from) == null) continue
            val z = line.indexOf('Z', from + 19)
            return line.substring(minOf(z + 2, line.length))
        }
        return null
    }

    /**
     * A GitHub Actions log (most lines are `job<TAB>step<TAB>timestamp text` or `timestamp text`)
     * loses its prefixes and colour codes and becomes one code block, so its lines read like the
     * output they are. Any other text is returned as it is.
     */
    private fun ciLog(text: String): String {
        val lines = text.split('\n')
        val stripped = lines.map { logText(it) }
        val prefixed = stripped.count { it != null }
        if (prefixed < LOG_MIN_LINES || prefixed * 2 < lines.count { it.isNotBlank() }) return text
        val body = lines.indices.joinToString("\n") {
            SymptomExtractor.stripAnsi(stripped[it] ?: lines[it])
        }
        return "```\n$body\n```"
    }

    /** A passing side, a requirement or a limit that was hit: a story of change. */
    private fun Fact.storyOfChange() = !key.startsWith("run:") &&
        (side == Side.PASSING || key.endsWith(".required") || value == "exceeded")

    private fun mentions(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val before = if (i == 0) ' ' else text[i - 1]
            if (c == '@' && !Tokens.isAlnum(before) && before !in "_-./") {
                var e = i + 1
                while (e < text.length && (Tokens.isAlnum(text[e]) || text[e] in "-_")) e++
                val scoped = e < text.length && text[e] == '/'
                if (e > i + 1 && !scoped) {
                    sb.append('@').append(hashUser(text.substring(i + 1, e)))
                    i = e
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
