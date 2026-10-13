package dev.gmitch215.drift.tools.corpus

import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.tools.LogSanitizer
import dev.gmitch215.drift.triage.Triage
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** An issue as the fetcher read it, before redaction. [author] is a raw login. */
class RawIssue(
    val number: Int,
    val created: String,
    val state: String,
    val title: String,
    val author: String?,
    val body: String,
)

/** An issue as stored: no raw login, and the title and body pass through [CorpusClean]. */
class CorpusIssue(
    val repo: String,
    val number: Int,
    val created: String,
    val state: String,
    val title: String,
    val author: String?,
    val body: String,
) {
    fun toJson(): JsonObject = obj(
        "author" to (author?.let(::JsonString) ?: JsonNull),
        "body" to JsonString(body),
        "created" to JsonString(created),
        "number" to JsonInt(number.toLong()),
        "repo" to JsonString(repo),
        "state" to JsonString(state),
        "title" to JsonString(title),
    )
}

/**
 * Redaction and hashing for stored issue text. A login becomes `u` and twelve hex digits of
 * HMAC-SHA256 keyed with the manifest salt over the lowercase login, so it cannot be looked up in
 * a table of unsalted hashes.
 */
class CorpusClean(salt: String) {
    private val key = SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "HmacSHA256")

    fun user(login: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val name = login.trim().removePrefix("@").lowercase()
        val digest = mac.doFinal(name.toByteArray(Charsets.UTF_8))
        return "u" + digest.joinToString("") { "%02x".format(it) }.take(HASH)
    }

    fun issue(repo: String, raw: RawIssue): CorpusIssue = CorpusIssue(
        repo,
        raw.number,
        raw.created,
        raw.state,
        title(raw.title),
        raw.author?.let(::user),
        body(raw.body),
    )

    fun title(text: String): String =
        LogSanitizer.sanitizeValue(mentions(text.replace(Regex("\\s+"), " ").trim())).text

    /**
     * Line endings become `\n`, `@login` mentions outside fenced code are hashed, lines longer
     * than the sanitizer limit are cut at spaces so the text on them is scrubbed, not dropped, and
     * the result goes through [LogSanitizer]. A body over [Triage.MAX_BODY] is cut at a line.
     */
    fun body(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        val clipped = if (unified.length > Triage.MAX_BODY) {
            unified.substring(0, Triage.MAX_BODY).substringBeforeLast('\n')
        } else {
            unified
        }
        val out = StringBuilder(clipped.length)
        var fenced = false
        for ((n, line) in clipped.split('\n').withIndex()) {
            val t = line.trim()
            val toggles = t.startsWith("```") || t.startsWith("~~~")
            if (toggles) fenced = !fenced
            val kept = if (fenced || toggles) line else mentions(line)
            if (n > 0) out.append('\n')
            out.append(wrap(kept))
        }
        return LogSanitizer.sanitize(out.toString()).text.removeSuffix("\n")
    }

    private fun wrap(line: String): String {
        if (line.length <= WRAP) return line
        val sb = StringBuilder()
        var rest = line
        while (rest.length > WRAP) {
            val at = rest.lastIndexOf(' ', WRAP)
            if (at <= 0) break
            sb.append(rest, 0, at).append('\n')
            rest = rest.substring(at + 1)
        }
        return sb.append(rest).toString()
    }

    private fun mentions(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val before = if (i == 0) ' ' else text[i - 1]
            if (c == '@' && !before.isLetterOrDigit() && before !in "_-./") {
                var e = i + 1
                while (e < text.length && (text[e].isLetterOrDigit() || text[e] in "-_")) e++
                val scoped = e < text.length && text[e] == '/'
                if (e > i + 1 && !scoped) {
                    sb.append('@').append(user(text.substring(i + 1, e)))
                    i = e
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private companion object {
        const val HASH = 12
        const val WRAP = 400
    }
}
