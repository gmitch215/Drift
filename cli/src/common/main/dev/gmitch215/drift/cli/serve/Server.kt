package dev.gmitch215.drift.cli.serve

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Accepts connections and answers each in its own task; one request per connection. */
@OptIn(ExperimentalAtomicApi::class)
internal class Server(
	private val system: ServeSystem,
	private val site: StaticSite,
	private val limits: Limits = Limits(),
	private val log: (String) -> Unit = {},
) {
	private val active = AtomicInt(0)
	private val stopped = AtomicBoolean(false)

	fun stop() = stopped.store(true)

	/** Serves until [Server.stop] or an interrupt, then waits up to [drainMillis] for requests. */
	fun run(acceptor: Acceptor, drainMillis: Int = 5000) {
		try {
			while (!stopped.load() && !system.interrupted()) {
				val connection = acceptor.accept(POLL_MILLIS) ?: continue
				if (active.load() >= limits.maxConnections) {
					write(connection, StaticSite.text(503, "too many connections"), false)
					connection.close()
					continue
				}
				active.fetchAndAdd(1)
				val started = system.spawn {
					try {
						handle(connection)
					} finally {
						connection.close()
						active.fetchAndAdd(-1)
					}
				}
				if (!started) {
					connection.close()
					active.fetchAndAdd(-1)
				}
			}
		} finally {
			acceptor.close()
		}
		var waited = 0
		while (active.load() > 0 && waited < drainMillis) {
			system.sleep(DRAIN_STEP)
			waited += DRAIN_STEP
		}
	}

	internal fun handle(connection: Connection) {
		val parsed = readRequest(connection, limits)
		val request = (parsed as? Parsed.Ok)?.request
		val response = when (parsed) {
			Parsed.Closed -> return
			is Parsed.Reject -> reject(parsed)
			is Parsed.Ok -> site.respond(parsed.request)
		}
		write(connection, response, request?.head == true)
		log(logLine(request, response))
	}

	private fun reject(parsed: Parsed.Reject): Response {
		val plain = StaticSite.text(parsed.status, parsed.reason)
		if (!parsed.allow) return plain
		return Response(plain.status, plain.headers + ("Allow" to "GET, HEAD"), plain.body)
	}

	private fun logLine(request: Request?, response: Response): String {
		val what = request?.let { "${it.method} ${it.target.take(MAX_LOGGED)}" } ?: "-"
		return "$what ${response.status} ${response.body.size}"
	}

	private fun write(connection: Connection, response: Response, head: Boolean) {
		val text = StringBuilder("HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n")
		val headers = response.headers + listOf(
			"Content-Length" to response.body.size.toString(),
			"Connection" to "close",
			"X-Content-Type-Options" to "nosniff",
			"Cross-Origin-Resource-Policy" to "same-origin",
		)
		for ((name, value) in headers) text.append(name).append(": ").append(value).append("\r\n")
		text.append("\r\n")
		val bytes = text.toString().encodeToByteArray()
		if (!connection.write(bytes, 0, bytes.size) || head) return
		var at = 0
		while (at < response.body.size) {
			val n = minOf(CHUNK, response.body.size - at)
			if (!connection.write(response.body, at, n)) return
			at += n
		}
	}

	private companion object {
		const val POLL_MILLIS = 200
		const val DRAIN_STEP = 25
		const val CHUNK = 65536
		const val MAX_LOGGED = 200
	}
}
