package dev.gmitch215.drift.cli.serve

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class Limits(
	val maxHead: Int = 16384,
	val maxLine: Int = 8192,
	val maxHeaders: Int = 64,
	val headMillis: Int = 10000,
	val maxConnections: Int = 128,
)

/** A GET or HEAD request; header names are lower case. */
internal class Request(val method: String, val target: String, val headers: Map<String, String>) {
	val head get() = method == "HEAD"
}

internal class Response(
	val status: Int,
	val headers: List<Pair<String, String>>,
	val body: ByteArray,
)

internal sealed interface Parsed {
	class Ok(val request: Request) : Parsed

	class Reject(val status: Int, val reason: String, val allow: Boolean = false) : Parsed

	data object Closed : Parsed
}

internal fun statusText(status: Int) = when (status) {
	200 -> "OK"
	400 -> "Bad Request"
	403 -> "Forbidden"
	404 -> "Not Found"
	405 -> "Method Not Allowed"
	408 -> "Request Timeout"
	414 -> "URI Too Long"
	431 -> "Request Header Fields Too Large"
	503 -> "Service Unavailable"
	505 -> "HTTP Version Not Supported"
	else -> "Error"
}

private const val TOKEN = "!#$%&'*+-.^_`|~"

private fun isToken(text: String) = text.isNotEmpty() &&
	text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN }

/** Reads one request head from [connection]; the body, if any, is never read. */
internal fun readRequest(connection: Connection, limits: Limits): Parsed {
	val buffer = ByteArray(limits.maxHead)
	var filled = 0
	val start = TimeSource.Monotonic.markNow()
	var end = -1
	while (end < 0) {
		if (filled == buffer.size) return Parsed.Reject(431, "request head too large")
		val n = connection.read(buffer, filled, buffer.size - filled)
		if (n <= 0) return Parsed.Closed
		val from = maxOf(0, filled - 3)
		filled += n
		end = endOfHead(buffer, from, filled)
		if (end >= 0) break
		if (firstLine(buffer, filled) > limits.maxLine) {
			return Parsed.Reject(414, "request line too long")
		}
		if (start.elapsedNow() > limits.headMillis.milliseconds) {
			return Parsed.Reject(408, "request head too slow")
		}
	}
	return parseHead(buffer.decodeToString(0, end), limits)
}

private fun endOfHead(buffer: ByteArray, from: Int, filled: Int): Int {
	var i = from
	while (i + 3 < filled) {
		if (buffer[i] == CR && buffer[i + 1] == LF && buffer[i + 2] == CR && buffer[i + 3] == LF) {
			return i
		}
		i++
	}
	return -1
}

private fun firstLine(buffer: ByteArray, filled: Int): Int {
	for (i in 0 until filled) if (buffer[i] == LF) return i
	return filled
}

private const val CR = '\r'.code.toByte()
private const val LF = '\n'.code.toByte()

internal fun parseHead(head: String, limits: Limits = Limits()): Parsed {
	if (head.any { it == '\u0000' || (it < ' ' && it != '\r' && it != '\n' && it != '\t') }) {
		return Parsed.Reject(400, "control character in the request head")
	}
	if (head.replace("\r\n", "").any { it == '\r' || it == '\n' }) {
		return Parsed.Reject(400, "bare line break in the request head")
	}
	val lines = head.split("\r\n")
	val line = lines.first()
	if (line.length > limits.maxLine) return Parsed.Reject(414, "request line too long")
	val parts = line.split(' ')
	if (parts.size != 3 || parts.any { it.isEmpty() }) {
		return Parsed.Reject(400, "malformed request line")
	}
	val (method, target, version) = parts
	if (!isToken(method)) return Parsed.Reject(400, "malformed method")
	if (version != "HTTP/1.1" && version != "HTTP/1.0") {
		return Parsed.Reject(if (version.startsWith("HTTP/")) 505 else 400, "unsupported version")
	}
	if (!target.startsWith("/")) return Parsed.Reject(400, "target must be an absolute path")
	if (lines.size - 1 > limits.maxHeaders) return Parsed.Reject(431, "too many headers")
	val headers = linkedMapOf<String, String>()
	for (raw in lines.drop(1)) {
		if (raw.isEmpty()) return Parsed.Reject(400, "empty header line")
		if (raw[0] == ' ' || raw[0] == '\t') return Parsed.Reject(400, "folded header")
		val colon = raw.indexOf(':')
		val name = if (colon > 0) raw.substring(0, colon) else ""
		if (!isToken(name)) return Parsed.Reject(400, "malformed header")
		val key = name.lowercase()
		val value = raw.substring(colon + 1).trim(' ', '\t')
		if (key == "host" && key in headers) return Parsed.Reject(400, "duplicate host")
		if (key !in headers) headers[key] = value
	}
	if (headers["host"].isNullOrEmpty()) return Parsed.Reject(400, "missing host")
	val length = headers["content-length"]
	if ("transfer-encoding" in headers || (length != null && length != "0")) {
		return Parsed.Reject(400, "request bodies are not accepted")
	}
	if (method != "GET" && method != "HEAD") return Parsed.Reject(405, "method not allowed", true)
	return Parsed.Ok(Request(method, target, headers))
}
