package dev.gmitch215.drift.cli.serve

/** Which Host header values are served; anything else is refused to stop DNS rebinding. */
internal class HostPolicy(
	domains: List<String> = emptyList(),
	private val anyAddress: Boolean = false,
) {
	private val exact = (listOf("localhost", "127.0.0.1", "[::1]") + domains.filter { '*' !in it })
		.map { it.lowercase().trimEnd('.') }.toSet()
	private val suffixes = domains.filter { it.startsWith("*.") }
		.map { it.substring(1).lowercase().trimEnd('.') }

	fun allows(header: String): Boolean {
		val name = hostName(header).lowercase().trimEnd('.')
		if (name in exact) return true
		if (suffixes.any { name.endsWith(it) && name.length > it.length }) return true
		return anyAddress && isAddress(name)
	}

	private fun hostName(header: String): String {
		if (header.startsWith("[")) return header.substringBefore(']') + "]"
		return header.substringBefore(':')
	}

	private fun isAddress(name: String): Boolean {
		if (name.startsWith("[") && name.endsWith("]")) return true
		val octets = name.split('.')
		return octets.size == 4 && octets.all { it.length in 1..3 && it.all(Char::isDigit) }
	}
}

internal object Mime {
	private val types = mapOf(
		"html" to "text/html; charset=utf-8",
		"js" to "text/javascript; charset=utf-8",
		"mjs" to "text/javascript; charset=utf-8",
		"css" to "text/css; charset=utf-8",
		"json" to "application/json",
		"map" to "application/json",
		"wasm" to "application/wasm",
		"webmanifest" to "application/manifest+json",
		"txt" to "text/plain; charset=utf-8",
		"xml" to "application/xml",
		"svg" to "image/svg+xml",
		"png" to "image/png",
		"jpg" to "image/jpeg",
		"jpeg" to "image/jpeg",
		"gif" to "image/gif",
		"webp" to "image/webp",
		"ico" to "image/x-icon",
		"woff" to "font/woff",
		"woff2" to "font/woff2",
		"ttf" to "font/ttf",
		"otf" to "font/otf",
	)

	fun of(name: String): String =
		types[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}

/** Serves a directory read-only: no listing, no traversal, no writes. */
internal class StaticSite(
	root: String,
	private val files: SiteFiles,
	private val hosts: HostPolicy,
) {
	private val root = files.canonical(root)
		?: throw ServeFailure("$root does not exist")

	fun respond(request: Request): Response {
		if (!hosts.allows(request.headers.getValue("host"))) return text(403, "forbidden host")
		val segments = segments(request.target) ?: return text(400, "bad path")
		if (segments.any { it.startsWith(".") }) return text(404, "not found")
		val found = lookup(segments) ?: if (isNavigation(request, segments)) {
			lookup(listOf("index.html"))
		} else {
			null
		}
		val path = found ?: return text(404, "not found")
		val body = files.read(path) ?: return text(404, "not found")
		return Response(
			200,
			listOf(
				"Content-Type" to Mime.of(path.substringAfterLast(files.separator)),
				"Cache-Control" to cacheControl(path.substringAfterLast(files.separator)),
			),
			body,
		)
	}

	private fun lookup(segments: List<String>): String? {
		val joined = root + files.separator + segments.joinToString("/")
		var path = canonicalInside(joined) ?: return null
		if (files.isDirectory(path)) {
			path = canonicalInside(path + files.separator + "index.html") ?: return null
		}
		return path.takeUnless { files.isDirectory(it) }
	}

	private fun canonicalInside(path: String): String? {
		val real = files.canonical(path) ?: return null
		val prefix = root.trimEnd(files.separator) + files.separator
		return real.takeIf { it.startsWith(prefix, ignoreCase = files.ignoreCase) || it == root }
	}

	private fun isNavigation(request: Request, segments: List<String>): Boolean {
		val last = segments.lastOrNull().orEmpty()
		val mode = request.headers["sec-fetch-mode"]
		return "text/html" in request.headers["accept"].orEmpty() &&
			(mode == null || mode == "navigate") && '.' !in last
	}

	private fun cacheControl(name: String): String {
		val stem = name.substringBeforeLast('.')
		val hashed = stem.length >= 16 && stem.all { it in '0'..'9' || it in 'a'..'f' }
		return if (hashed) "public, max-age=31536000, immutable" else "no-cache"
	}

	companion object {
		private const val HEX = "0123456789abcdefABCDEF"

		fun text(status: Int, message: String) = Response(
			status,
			listOf("Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store"),
			(message + "\n").encodeToByteArray(),
		)

		/** The decoded path segments of an origin-form target, or null when it is not safe. */
		fun segments(target: String): List<String>? {
			val path = target.substringBefore('?').substringBefore('#')
			if (path.any { it.code <= 0x20 || it.code >= 0x7f }) return null
			val bytes = ArrayList<Byte>(path.length)
			var i = 0
			while (i < path.length) {
				val c = path[i]
				if (c == '%') {
					val hex = path.substring(i + 1, minOf(i + 3, path.length))
					if (hex.length != 2 || !hex.all { it in HEX }) return null
					bytes += hex.toInt(16).toByte()
					i += 3
				} else {
					bytes += c.code.toByte()
					i++
				}
			}
			val decoded = try {
				bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
			} catch (_: CharacterCodingException) {
				return null
			}
			if (decoded.any { it.code < 0x20 || it.code == 0x7f }) return null
			val parts = decoded.split('/').filter { it.isNotEmpty() && it != "." }
			for (part in parts) {
				if (part == ".." || '\\' in part || ':' in part) return null
				if (part.endsWith(".") || part.endsWith(" ")) return null
			}
			return parts
		}
	}
}
