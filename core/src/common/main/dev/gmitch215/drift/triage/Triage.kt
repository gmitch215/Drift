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

/**
 * [publishThreshold] is the confidence at which a draft rule is published instead of drafted
 * (null: drafts only). [author] is hashed before it is stored. [date] is the day the report was
 * read, `yyyy-mm-dd`, written into the provenance; a rule is never published without it.
 * [bases] replaces the starting confidence of a [Via] (see [ConfidenceTable]).
 */
data class TriageOptions(
    val publishThreshold: Confidence? = null,
    val author: String? = null,
    val date: String? = null,
    val bases: Map<Via, Int> = emptyMap(),
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
) {
    fun toJson(): JsonObject = obj(
        "schema" to JsonInt(SCHEMA),
        "body" to JsonString(body),
        "author" to (author?.let(::JsonString) ?: JsonNull),
        "truncated" to JsonBool(truncated),
        "facts" to JsonArray(facts.map { it.toJson() }),
        "rules" to JsonArray(rules.map { it.toJson() }),
        "claims" to JsonArray(claims.map { it.toJson() }),
        "draft" to (draft?.toJson() ?: JsonNull),
    )

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

    /** Reads [text] against [rules] and returns the facts, rule matches, claims and draft. */
    fun run(
        text: String,
        options: TriageOptions = TriageOptions(),
        rules: List<Rule> = Rules.all,
    ): TriageResult {
        val clipped = if (text.length > MAX_BODY) text.substring(0, MAX_BODY) else text
        val clean = clean(clipped)
        val doc = Doc(clean)
        val facts = Combine.facts(Extractor(doc, options.bases).run())
        val hits = RuleScan.scan(rules, facts, clean)
        val claims = Claims.check(doc, facts, rules)
        val body = Sha256.hex(clean)
        val draft = Drafts.suggest(facts, hits, body, options, rules)
        return TriageResult(
            body,
            options.author?.let(::hashUser),
            text.length > MAX_BODY,
            facts,
            hits,
            claims,
            draft,
        )
    }

    /**
     * Line endings become `\n`, secrets are masked by [Redactor], other users' home paths by
     * [Anonymizer], and an `@login` mention becomes `@` plus [hashUser]. A mention inside a
     * fenced code block stays, since it is usually an annotation. Overlong lines are cut at
     * [MAX_LINE]. Spans in a result refer to this text.
     */
    fun clean(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
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
