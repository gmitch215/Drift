package dev.gmitch215.drift.symptom

import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.symptom.Signature.asciiLower
import dev.gmitch215.drift.symptom.Signature.isAlnum

/**
 * Reads symptoms from normalized log text; never throws.
 *
 * The text is GitHub Actions output, either raw (`<timestamp> <text>` per line) or as printed by
 * `gh run view --log` (`<job>\t<step>\t<timestamp> <text>`). Lines end at `\n`, `\r\n` or a lone
 * `\r`, and only the first [MAX_LINE] characters of a line are read. A `job<TAB>step` prefix counts
 * from the first timestamped line that carries it (at most 64 distinct prefixes), so a tab inside
 * ordinary output is not taken for one. Color codes (ESC or the literal `^[[`) are dropped, and
 * lines the runner echoes in cyan (script bodies) are skipped by every matcher below.
 *
 * Matching is plain string scanning on ASCII-lowercased text, no regex:
 * - SIGNATURE: the first line of a `##[error]` annotation, except `Process completed with exit
 *   code`; with no annotation in the whole text, lines shaped like `XError: ...`,
 *   `XException: ...`, `error: ...` or `fatal: ...` instead. Both go through
 *   [Signature.normalize].
 * - EXIT: a non-zero number after `exit code `, `exited with code ` or `exit status `.
 * - SIGNAL: a `SIG` plus uppercase token on a line that also says signal, killed or terminated.
 * - STEP and DURATION: for each step (a `##[group]Run ` header up to the next header or
 *   `Post job cleanup.`) that holds an annotation, the header command and the milliseconds from the
 *   header's timestamp to the step's last timestamped line.
 * - TRANSPORT: the phrases in `transport`, each mapped to one canonical value.
 *
 * Equal job, kind and value merge into one symptom with a count (a STEP or DURATION also needs
 * the same header line, so two steps stay two symptoms). At most [MAX_SYMPTOMS] distinct symptoms
 * are kept, in line order.
 */
object SymptomExtractor {
	const val MAX_LINE = 4096
	const val MAX_SYMPTOMS = 500
	private const val MAX_PREFIXES = 64
	private const val ERROR_TAG = "##[error]"
	private const val GROUP_TAG = "##[group]Run "
	private const val COMPLETED = "Process completed with exit code"

	internal val transport = listOf(
		"connection reset by peer" to "connection reset",
		"econnreset" to "connection reset",
		"socket hang up" to "connection reset",
		"stream disconnected prematurely" to "stream disconnected",
		"network connection lost" to "network connection lost",
		"epipe" to "broken pipe",
		"broken pipe" to "broken pipe",
		"econnrefused" to "connection refused",
		"connection refused" to "connection refused",
		"etimedout" to "timeout",
		"connection timed out" to "timeout",
		"request timed out" to "timeout",
		"read timed out" to "timeout",
		"i/o timeout" to "timeout",
		"deadline exceeded" to "timeout",
	)

	private val exitMarkers = listOf("exit code ", "exited with code ", "exit status ")

	fun extract(text: String): List<Symptom> {
		val prefixes = HashSet<String>()
		val found = Collector()
		val steps = LinkedHashMap<String?, Open>()
		val fallback = ArrayList<Triple<String, Int, String?>>()
		var annotated = false
		eachLine(text) { number, raw ->
			val line = parse(raw, prefixes)
			val t = line.text
			val job = line.job
			val current = steps[job]
			if (t.startsWith(GROUP_TAG)) {
				current?.let { found.finish(it, job) }
				steps[job] = Open(number, line.epoch, command(t))
			} else if (t == "Post job cleanup.") {
				current?.let { found.finish(it, job) }
				steps.remove(job)
			} else if (line.epoch != null) {
				current?.last = line.epoch
			}
			if (line.echo) return@eachLine
			if (t.startsWith(ERROR_TAG)) {
				annotated = true
				current?.failed = true
				val message = t.substring(ERROR_TAG.length)
				if (!message.startsWith(COMPLETED)) found.signature(message, number, job)
			} else if (fallback.size < MAX_SYMPTOMS && looksLikeError(t)) {
				fallback.add(Triple(t, number, job))
			}
			scan(t, number, job, found)
		}
		for ((job, step) in steps) found.finish(step, job)
		if (!annotated) for ((t, number, job) in fallback) found.signature(t, number, job)
		return found.result()
	}

	private class Open(val line: Int, val start: Long?, val command: String) {
		var last: Long? = start
		var failed = false
	}

	private class Line(val job: String?, val epoch: Long?, val text: String, val echo: Boolean)

	private class Collector {
		private val items = LinkedHashMap<List<Any?>, Symptom>()

		fun add(kind: SymptomKind, value: String, line: Int, job: String?) {
			val perStep = kind == SymptomKind.STEP || kind == SymptomKind.DURATION
			val key = listOf(kind, value, job, if (perStep) line else 0)
			val old = items[key]
			if (old != null) {
				items[key] = old.copy(count = old.count + 1)
			} else if (items.size < MAX_SYMPTOMS) {
				items[key] = Symptom(kind, value, line, job)
			}
		}

		fun signature(message: String, line: Int, job: String?) {
			val value = Signature.normalize(message)
			if (value.isNotEmpty()) add(SymptomKind.SIGNATURE, value, line, job)
		}

		fun finish(step: Open, job: String?) {
			if (!step.failed) return
			val command = Signature.clip(Redactor.redactText(step.command), 200)
			add(SymptomKind.STEP, command, step.line, job)
			val start = step.start
			val last = step.last
			if (start != null && last != null && last >= start) {
				add(SymptomKind.DURATION, (last - start).toString(), step.line, job)
			}
		}

		fun result(): List<Symptom> =
			items.values.sortedWith(compareBy({ it.line }, { it.kind.ordinal }, { it.value }))
	}

	private fun command(t: String) = t.substring(GROUP_TAG.length).trim()

	private fun scan(t: String, number: Int, job: String?, found: Collector) {
		val lower = asciiLower(t)
		for (marker in exitMarkers) {
			exitCode(lower, marker)?.let {
			found.add(SymptomKind.EXIT, it, number, job)
		}
		}
		if ("signal" in lower || "killed" in lower || "terminated" in lower) {
			for (signal in signals(t)) found.add(SymptomKind.SIGNAL, signal, number, job)
		}
		for ((needle, value) in transport) {
			if (needle in lower) found.add(SymptomKind.TRANSPORT, value, number, job)
		}
	}

	private fun exitCode(lower: String, marker: String): String? {
		val at = lower.indexOf(marker)
		if (at < 0) return null
		var i = at + marker.length
		val negative = i < lower.length && lower[i] == '-'
		if (negative) i++
		var j = i
		while (j < lower.length && lower[j] in '0'..'9') j++
		if (j == i || j - i > 10) return null
		val digits = lower.substring(i, j).trimStart('0')
		return if (digits.isEmpty()) null else (if (negative) "-" else "") + digits
	}

	private fun signals(t: String): List<String> {
		val out = ArrayList<String>()
		var i = t.indexOf("SIG")
		while (i >= 0) {
			var j = i + 3
			while (j < t.length && (t[j] in 'A'..'Z' || t[j] in '0'..'9')) j++
			val bounded = (i == 0 || !isAlnum(t[i - 1])) && (j == t.length || !isAlnum(t[j]))
			if (bounded && j - i >= 5) out.add(t.substring(i, j))
			i = t.indexOf("SIG", j)
		}
		return out
	}

	private fun looksLikeError(t: String): Boolean {
		val at = t.indexOf(": ")
		if (at < 1) return false
		val from = t.indexOfFirst { it != ' ' && it != '\t' }
		if (at - from !in 1..80) return false
		val head = t.substring(from, at)
		if (head.any { !isAlnum(it) && it != '.' && it != '_' }) return false
		return head.endsWith("Error") || head.endsWith("Exception") ||
			asciiLower(head) == "error" || asciiLower(head) == "fatal"
	}

	private fun parse(raw: String, prefixes: MutableSet<String>): Line {
		var job: String? = null
		var body = raw
		val tab = raw.indexOf('\t')
		val second = if (tab > 0) raw.indexOf('\t', tab + 1) else -1
		if (second > tab) {
			val prefix = raw.substring(0, second)
			val stamped = prefixes.size < MAX_PREFIXES && epochAt(raw, second + 1) != null
			if (prefix in prefixes || (stamped && prefixes.add(prefix))) {
				job = raw.substring(0, tab)
				body = raw.substring(second + 1)
			}
		}
		val epoch = epochAt(body, 0)
		if (epoch != null) body = body.substring(minOf(body.indexOf('Z', 19) + 2, body.length))
		val echo = body.startsWith("^[[36;1m") || body.startsWith("\u001B[36;1m")
		return Line(job, epoch, stripAnsi(body), echo)
	}

	private fun eachLine(text: String, block: (Int, String) -> Unit) {
		var start = 0
		var number = 1
		var lf = text.indexOf('\n')
		var cr = text.indexOf('\r')
		while (start <= text.length) {
			val end = minOf(if (lf < 0) text.length else lf, if (cr < 0) text.length else cr)
			val terminated = end < text.length
			if (end > start || terminated) {
				block(number, wellFormed(text.substring(start, minOf(end, start + MAX_LINE))))
				number++
			}
			if (!terminated) return
			val crlf = end + 1 < text.length && text[end] == '\r' && text[end + 1] == '\n'
			start = if (crlf) end + 2 else end + 1
			if (lf in 0 until start) lf = text.indexOf('\n', start)
			if (cr in 0 until start) cr = text.indexOf('\r', start)
		}
	}

	internal fun wellFormed(s: String): String {
		var out: StringBuilder? = null
		for (i in s.indices) {
			val c = s[i]
			val bad = if (c.isHighSurrogate()) {
				i + 1 >= s.length || !s[i + 1].isLowSurrogate()
			} else {
				c.isLowSurrogate() && (i == 0 || !s[i - 1].isHighSurrogate())
			}
			if (bad && out == null) out = StringBuilder(s.length).append(s, 0, i)
			out?.append(if (bad) '\uFFFD' else c)
		}
		return out?.toString() ?: s
	}

	internal fun stripAnsi(s: String): String {
		if (s.indexOf('\u001B') < 0 && s.indexOf("^[[") < 0) return s
		var out: StringBuilder? = null
		var i = 0
		while (i < s.length) {
			val skip = if (s.startsWith("\u001B[", i) || s.startsWith("^[[", i)) {
				escapeEnd(s, i + if (s[i] == '^') 3 else 2)
			} else {
				-1
			}
			if (skip > 0 && out == null) out = StringBuilder(s.length).append(s, 0, i)
			if (skip > 0) {
				i = skip
			} else {
				out?.append(s[i])
				i++
			}
		}
		return out?.toString() ?: s
	}

	private fun escapeEnd(s: String, from: Int): Int {
		var j = from
		while (j < s.length && (s[j] in '0'..'9' || s[j] == ';' || s[j] == '?')) j++
		return if (j < s.length && (s[j] in 'a'..'z' || s[j] in 'A'..'Z')) j + 1 else -1
	}

	internal fun epochAt(s: String, at: Int): Long? {
		if (s.length < at + 20) return null
		if (s[at + 4] != '-' || s[at + 7] != '-' || s[at + 10] != 'T') return null
		if (s[at + 13] != ':' || s[at + 16] != ':') return null
		fun num(from: Int, n: Int): Int? {
			var v = 0
			for (k in from until from + n) {
				if (s[k] !in '0'..'9') return null
				v = v * 10 + (s[k] - '0')
			}
			return v
		}
		val y = num(at, 4) ?: return null
		val mo = num(at + 5, 2) ?: return null
		val d = num(at + 8, 2) ?: return null
		val h = num(at + 11, 2) ?: return null
		val mi = num(at + 14, 2) ?: return null
		val sec = num(at + 17, 2) ?: return null
		var j = at + 19
		var ms = 0
		if (s[j] == '.') {
			var digits = 0
			j++
			while (j < s.length && s[j] in '0'..'9') {
				if (digits < 3) ms = ms * 10 + (s[j] - '0')
				digits++
				j++
			}
			repeat(3 - minOf(digits, 3)) { ms *= 10 }
		}
		if (j >= s.length || s[j] != 'Z' || (j + 1 < s.length && s[j + 1] != ' ')) return null
		return (days(y, mo, d) * 86_400L + h * 3_600L + mi * 60L + sec) * 1000L + ms
	}

	private fun days(year: Int, month: Int, day: Int): Long {
		val y = if (month <= 2) year - 1 else year
		val era = y / 400
		val yoe = y - era * 400
		val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
		val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
		return era * 146_097L + doe - 719_468L
	}
}
