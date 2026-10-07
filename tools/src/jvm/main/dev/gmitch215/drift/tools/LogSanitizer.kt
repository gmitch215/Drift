package dev.gmitch215.drift.tools

import dev.gmitch215.drift.redact.Redactor

/**
 * [Sanitized.examples] holds the first sanitized line each removal kind appeared on, never the raw
 * one.
 */
class Sanitized(val text: String, val counts: Map<String, Int>, val examples: Map<String, String>)

object LogSanitizer {
	const val MAX_BODY = 500

	private const val TLDS = "com|org|net|io|dev|xyz|app|cloud|co|edu|gov|me|ai"
	private const val BOM = "\uFEFF"
	private val placeholders = mapOf(
		"email" to listOf("<email>"),
		"hex-id" to listOf("<hex>"),
		"high-entropy" to listOf("<token>"),
		"hostname" to listOf("<host>"),
		"integrity" to listOf("<integrity>"),
		"ipv4" to listOf("<ip>"),
		"ipv4-loopback" to listOf("<loopback>"),
		"ipv6" to listOf("<ip>", "<loopback>"),
		"jwt" to listOf("<jwt>"),
		"mac" to listOf("<mac>"),
		"organization" to listOf("<org>"),
		"person-name" to listOf("<name>"),
		"private-package" to listOf("<private-pkg>"),
		"redactor" to listOf("<redacted>"),
		"secret-name" to listOf("<redacted>"),
		"server-uid" to listOf("<id>"),
		"token-prefix" to listOf("<token>"),
		"url-host" to listOf("<host>"),
		"url-query" to listOf("<query>"),
		"username" to listOf("<user>"),
		"uuid" to listOf("<uuid>"),
	)
	private val DIFF_KEPT = listOf("diff --git", "+", "-")
	private const val OBJECT_DUMP_START = "=> Error contextual data: {"

	private val publicHosts = listOf(
		"github.com",
		"githubusercontent.com",
		"codecov.io",
		"cloudflare.com",
		"mariadb.org",
		"npmjs.org",
		"npmjs.com",
		"nodejs.org",
		"bun.sh",
		"ubuntu.com",
		"debian.org",
		"drupal.org",
		"php.net",
		"apache.org",
		"docker.com",
		"docker.io",
		"microsoft.com",
	)

	private val timestamp = Regex("^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z(?: |$)")
	private val secretName = Regex(
		"([A-Za-z0-9_.-]*(?:key|token|secret|passw(?:or)?d|credential|auth|cookie)" +
			"[A-Za-z0-9_.-]*)(\\s*[:=]\\s*)(['\"]?)((?:basic|bearer|token)\\s+)?([^\\s'\",;]+)",
		RegexOption.IGNORE_CASE,
	)
	private val url =
		Regex("\\b([A-Za-z][A-Za-z0-9+.-]*)://([^\\s/?#'\"<>)\\]]*)([^\\s'\"<>)\\]]*)")
	private val host = Regex(
		"(?<![A-Za-z0-9-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+" +
			"(?:$TLDS)\\b",
	)
	private val email =
		Regex("[A-Za-z0-9._%+-]+@[A-Za-z][A-Za-z0-9-]*(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
	private val uuid = Regex(
		"\\b[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b|" +
			"(?:\\\\-[0-9a-fA-F]{1,12})+)",
	)
	private val integrity = Regex("\\bsha(?:1|256|384|512)-[A-Za-z0-9+/]{20,}={0,2}")
	private val hex = Regex("(?<![0-9A-Za-z])[0-9a-fA-F]{32,}(?![0-9A-Za-z])")
	private val mac = Regex("\\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\\b")
	private val ipv6 = Regex("(?<![\\w:.])[0-9a-fA-F:]*:[0-9a-fA-F:]*(?![\\w:.])")
	private val octet = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
	private val ipv4 = Regex("(?<![\\w.])(?:$octet\\.){3}$octet(?![\\w]|\\.\\d)")
	private val privatePackage = Regex("@drupflare/[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE)
	private val organization = Regex("drupflare|bytebox", RegexOption.IGNORE_CASE)
	private val username = Regex("gmitch215", RegexOption.IGNORE_CASE)
	private val personName = Regex("Gregory Mitchell|Mitchell|Gregory")
	private val serverUid = Regex("server_uid\\s+\\S+")
	private val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]*)?")
	private val tokenPrefixes = Regex(
		"\\b(?:npm_[A-Za-z0-9]{20,}|glpat-[A-Za-z0-9_-]{16,}|sk-[A-Za-z0-9_-]{20,}|" +
			"AIza[0-9A-Za-z_-]{30,}|ya29\\.[0-9A-Za-z_-]{20,}|SG\\.[A-Za-z0-9_-]{16,})",
	)
	private val entropy = Regex(
		"(?<![A-Za-z0-9+_=-])(?=[A-Za-z0-9+_=-]*\\d)(?=[A-Za-z0-9+_=-]*[a-z])" +
			"(?=[A-Za-z0-9+_=-]*[A-Z])[A-Za-z0-9+_=-]{32,}(?![A-Za-z0-9+_=-])",
	)
	private val safeValues = setOf("true", "false", "null", "undefined", "***", "0", "1")

	fun sanitize(raw: String, keep: Set<String> = emptySet()): Sanitized {
		val run = Run(keep)
		val out = StringBuilder()
		var dumpLines = 0
		var inDump = false
		var inKey = false
		val lines = raw.removeSuffix("\n").split('\n')
		for (rawLine in lines) {
			val line = rawLine.removeSuffix("\r")
			val (prefix, body) = split(line)
			when {
				inDump -> {
					dumpLines++
					if (body == "}") {
						inDump = false
						out.append(prefix).append("<<removed: object dump, ").append(dumpLines)
							.append(" lines>>\n")
						run.count("object-dump", dumpLines, "<<removed: object dump>>")
					}
				}

				inKey -> {
					if (body.startsWith("-----END ")) inKey = false
				}

				body == OBJECT_DUMP_START -> {
					inDump = true
					dumpLines = 1
				}

				body.startsWith("-----BEGIN ") && body.contains("PRIVATE KEY-----") -> {
					inKey = true
					out.append(prefix).append("<<removed: private key block>>\n")
					run.count("private-key-block", 1, "<<removed: private key block>>")
				}

				body.length > MAX_BODY -> {
					out.append(prefix).append("<<removed: line of ").append(body.length)
						.append(" characters>>\n")
					run.count("long-line", 1, "<<removed: long line>>")
				}

				else -> out.append(prefix).append(run.scrub(body)).append('\n')
			}
		}
		if (inDump) {
			out.append("<<removed: unterminated object dump, ").append(dumpLines)
				.append(" lines>>\n")
			run.count("object-dump", dumpLines, "<<removed: object dump>>")
		}
		return Sanitized(out.toString(), run.counts.toSortedMap(), run.examples.toSortedMap())
	}

	/**
	 * Keeps file headers, hunk positions and changed lines of a unified diff; context lines, blob
	 * ids and the text after a hunk header are dropped before the same rules run.
	 */
	fun sanitizeDiff(raw: String, keep: Set<String> = emptySet()): Sanitized {
		var context = 0
		val kept = raw.removeSuffix("\n").split('\n').mapNotNull { line ->
			when {
				line.startsWith("@@") -> hunkHeader(line)

				DIFF_KEPT.any { line.startsWith(it) } -> line

				else -> {
					context++
					null
				}
			}
		}
		val cleaned = sanitize(kept.joinToString("\n", postfix = "\n"), keep)
		if (context == 0) return cleaned
		return Sanitized(
			cleaned.text,
			(cleaned.counts + ("diff-context" to context)).toSortedMap(),
			(cleaned.examples + ("diff-context" to "<<removed: diff context>>")).toSortedMap(),
		)
	}

	/** Sanitizes one text value, such as a step name or a JSON string, with the same rules. */
	fun sanitizeValue(value: String, keep: Set<String> = emptySet()): Sanitized {
		val run = Run(keep)
		val text = run.scrub(value)
		return Sanitized(text, run.counts.toSortedMap(), run.examples.toSortedMap())
	}

	/** Findings left in [text] by a second pass written apart from the rules that clean it. */
	fun residue(text: String, keep: Set<String> = emptySet()): List<String> {
		val findings = mutableListOf<String>()
		fun scan(kind: String, re: Regex, ignore: (String) -> Boolean = { false }) {
			for (m in re.findAll(text)) {
				if (!ignore(m.value) && m.value !in keep) {
				findings += "$kind: ${m.value.take(60)}"
			}
			}
		}
		scan("ipv4", Regex("(?<![\\w.])\\d{1,3}(?:\\.\\d{1,3}){3}(?![\\w]|\\.\\d)"))
		scan("email", Regex("[\\w.%+-]+@[A-Za-z][\\w-]*(?:\\.[\\w-]+)*\\.[A-Za-z]{2,}"))
		scan("uuid", Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
		scan("uuid", Regex("[0-9a-fA-F]{8}\\\\-[0-9a-fA-F]{4}"))
		scan("hex", Regex("[0-9a-fA-F]{32,}"))
		scan("mac", Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))
		scan("secret", Regex("gh[pousr]_[A-Za-z0-9]{20,}|github_pat_\\w{20,}|AKIA[0-9A-Z]{16}"))
		scan("secret", Regex("xox[abprs]-[A-Za-z0-9-]{10,}|eyJ[\\w-]{8,}\\.[\\w-]{8,}"))
		scan("secret", Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"))
		scan("secret", Regex("(?i)bearer\\s+[\\w.~+/=-]{16,}"))
		scan("userinfo", Regex("://[^/\\s:@]+:[^/\\s@]+@"))
		scan("name", Regex("(?i)drupflare|bytebox|gmitch215|gregory mitchell"))
		scan("token", Regex("[A-Za-z0-9+_=-]{32,}")) {
			!(it.any(Char::isDigit) && it.any(Char::isLowerCase) && it.any(Char::isUpperCase))
		}
		scan("host", Regex("(?i)https?://[^\\s/'\"<>]+")) { host ->
			val h = host.substringAfter("://").substringBefore(':')
			h.startsWith("<") || h == "localhost" || isPublic(h) || h.isEmpty()
		}
		scan(
			"host",
			Regex("(?<![\\w-])(?:[\\w-]+\\.)+(?:$TLDS)\\b"),
		) {
			isPublic(it)
		}
		return findings
	}

	private fun hunkHeader(line: String): String {
		val end = line.indexOf("@@", 2)
		return if (end < 0) line else line.substring(0, end + 2)
	}

	private fun split(line: String): Pair<String, String> {
		val first = line.indexOf('\t')
		val second = if (first < 0) -1 else line.indexOf('\t', first + 1)
		if (second < 0) return "" to line.removePrefix(BOM)
		val head = line.substring(0, second + 1)
		val rest = line.substring(second + 1).removePrefix(BOM)
		val stamp = timestamp.find(rest) ?: return head to rest
		return head + stamp.value to rest.substring(stamp.value.length)
	}

	private fun isPublic(host: String): Boolean {
		val h = host.lowercase().trimEnd('.')
		return publicHosts.any { h == it || h.endsWith(".$it") }
	}

	private class Run(val keep: Set<String>) {
		val counts = mutableMapOf<String, Int>()
		val examples = mutableMapOf<String, String>()
		private var touched = mutableListOf<String>()

		fun count(kind: String, n: Int, example: String) {
			counts[kind] = (counts[kind] ?: 0) + n
			examples.getOrPut(kind) { example }
		}

		fun scrub(body: String): String {
			touched = mutableListOf()
			var s = body
			val before = masks(s)
			s = Redactor.redactText(s)
			val added = masks(s) - before
			if (added > 0) mark("redactor", added)
			s = replace(s, "jwt", jwt) { "<jwt>" }
			s = replace(s, "token-prefix", tokenPrefixes) { "<token>" }
			s = replace(s, "secret-name", secretName) { m ->
				val value = m.groupValues[5]
				if (value in safeValues || value.startsWith("<") || value.startsWith("$")) {
					null
				} else {
					"${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}<redacted>"
				}
			}
			s = url.replace(s) { m -> rewriteUrl(m) ?: m.value }
			s = replace(s, "email", email) { "<email>" }
			s = replace(s, "uuid", uuid) { "<uuid>" }
			s = replace(s, "integrity", integrity) { "<integrity>" }
			s = replace(s, "hex-id", hex) { m -> if (m.value in keep) null else "<hex>" }
			s = replace(s, "mac", mac) { "<mac>" }
			s = replace(s, "ipv6", ipv6) { m ->
				when {
					m.value == "::1" -> "<loopback>"
					isIpv6(m.value) -> "<ip>"
					else -> null
				}
			}
			s = replace(s, "ipv4-loopback", ipv4) { m ->
				if (m.value.startsWith("127.")) "<loopback>" else null
			}
			s = replace(s, "ipv4", ipv4) { "<ip>" }
			s = replace(s, "hostname", host) { m -> if (isPublic(m.value)) null else "<host>" }
			s = replace(s, "private-package", privatePackage) { "<private-pkg>" }
			s = replace(s, "organization", organization) { "<org>" }
			s = replace(s, "username", username) { "<user>" }
			s = replace(s, "person-name", personName) { "<name>" }
			s = replace(s, "server-uid", serverUid) { "server_uid <id>" }
			s = replace(s, "high-entropy", entropy) { m ->
				if (m.value in keep) null else "<token>"
			}
			for (kind in touched) examples.getOrPut(kind) { window(s, kind) }
			return s
		}

		private fun window(line: String, kind: String): String {
			val found = placeholders[kind].orEmpty().map { line.indexOf(it) }.filter { it >= 0 }
			val at = found.minOrNull()
			return line.drop(((at ?: 0) - 90).coerceAtLeast(0)).take(200)
		}

		private fun masks(s: String): Int = s.split(Redactor.MASK).size - 1

		private fun mark(kind: String, n: Int) {
			counts[kind] = (counts[kind] ?: 0) + n
			touched += kind
		}

		private fun replace(
			input: String,
			kind: String,
			re: Regex,
			transform: (MatchResult) -> String?,
		): String = re.replace(input) { m ->
			val out = transform(m)
			if (out == null) {
				m.value
			} else {
				mark(kind, 1)
				out
			}
		}

		private fun rewriteUrl(m: MatchResult): String? {
			val scheme = m.groupValues[1]
			var authority = m.groupValues[2]
			var rest = m.groupValues[3]
			var changed = false
			val at = authority.lastIndexOf('@')
			if (at >= 0) {
				authority = authority.substring(at + 1)
				mark("url-userinfo", 1)
				changed = true
			}
			val port = Regex(":\\d+$").find(authority)?.value.orEmpty()
			val name = authority.removeSuffix(port)
			val plain = name.isEmpty() || name == "localhost" || name.startsWith("[") ||
				ipv4.matches(name) || isPublic(name)
			val shown = if (plain) {
				name
			} else {
				mark("url-host", 1)
				changed = true
				"<host>"
			}
			val cut = rest.indexOfAny(charArrayOf('?', '#'))
			if (cut >= 0) {
				val tail = rest.substring(cut)
				rest = rest.substring(0, cut) + if (tail.startsWith("?")) "?<query>" else ""
				mark("url-query", 1)
				changed = true
			}
			return if (changed) "$scheme://$shown$port$rest" else null
		}

		private fun isIpv6(token: String): Boolean {
			if (token.length < 3) return false
			val colons = token.count { it == ':' }
			return token.contains("::") || colons == 7
		}
	}
}
