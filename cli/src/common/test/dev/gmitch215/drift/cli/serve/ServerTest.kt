package dev.gmitch215.drift.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
	private val files = FakeFiles().apply {
		put("/dist/index.html", "<title>Drift Studio</title>")
		put("/dist/big.bin", "x".repeat(200000))
	}

	private fun request(target: String, host: String = "localhost:8080", method: String = "GET") =
		"$method $target HTTP/1.1\r\nHost: $host\r\nAccept: */*\r\n\r\n"

	private fun server(
		system: ServeSystem = FakeServeSystem(files),
		limits: Limits = Limits(),
		log: (String) -> Unit = {},
	) = Server(system, StaticSite("/dist", files, HostPolicy()), limits, log)

	private fun answer(text: String): FakeConnection {
		val connection = FakeConnection(text)
		server().handle(connection)
		return connection
	}

	@Test
	fun aGetAnswersWithTheSecurityHeadersAndClosesTheConnection() {
		val connection = answer(request("/"))
		assertEquals("HTTP/1.1 200 OK", connection.status)
		val head = connection.head
		assertTrue("Content-Type: text/html; charset=utf-8" in head, head)
		assertTrue("Content-Length: 27" in head, head)
		assertTrue("Connection: close" in head, head)
		assertTrue("X-Content-Type-Options: nosniff" in head, head)
		assertTrue("Cross-Origin-Resource-Policy: same-origin" in head, head)
		assertTrue("Access-Control-Allow-Origin" !in head, head)
		assertEquals("<title>Drift Studio</title>", connection.body)
	}

	@Test
	fun aHeadSendsTheHeadersAndNoBody() {
		val connection = answer(request("/", method = "HEAD"))
		assertEquals("HTTP/1.1 200 OK", connection.status)
		assertTrue("Content-Length: 27" in connection.head)
		assertEquals("", connection.body)
	}

	@Test
	fun aLargeBodyIsWrittenWhole() {
		assertEquals(200000, answer(request("/big.bin")).body.length)
	}

	@Test
	fun errorsKeepTheirStatusAndAnAllowHeaderOnA405() {
		assertEquals("HTTP/1.1 404 Not Found", answer(request("/nope")).status)
		assertEquals("HTTP/1.1 403 Forbidden", answer(request("/", host = "evil.example")).status)
		val post = answer(request("/", method = "POST"))
		assertEquals("HTTP/1.1 405 Method Not Allowed", post.status)
		assertTrue("Allow: GET, HEAD" in post.head)
		assertEquals("HTTP/1.1 400 Bad Request", answer("GARBAGE\r\n\r\n").status)
		assertEquals("HTTP/1.1 400 Bad Request", answer(request("/../x")).status)
	}

	@Test
	fun aConnectionThatSendsNothingGetsNoAnswer() {
		val connection = answer("")
		assertEquals("", connection.output.toString())
	}

	@Test
	fun everyRequestIsLoggedWithItsStatusAndSize() {
		val lines = mutableListOf<String>()
		val connection = FakeConnection(request("/"))
		server(log = { lines += it }).handle(connection)
		server(log = { lines += it }).handle(FakeConnection("GARBAGE\r\n\r\n"))
		assertEquals(listOf("GET / 200 27", "- 400 23"), lines)
	}

	@Test
	fun runAnswersEveryQueuedConnectionThenStopsOnInterruptAndClosesTheListener() {
		val queued = listOf(request("/"), request("/nope"), request("/"))
		val system = FakeServeSystem(files, connections = queued)
		val acceptor = system.listen(Bind.LOOPBACK, 8080, null) as FakeAcceptor
		server(system).run(acceptor)
		assertEquals(listOf(200, 404, 200), system.served.map { it.status.split(' ')[1].toInt() })
		assertEquals(1, acceptor.closed)
		assertTrue(system.served.all { it.closed })
	}

	@Test
	fun aFlagStopsTheLoop() {
		val system = FakeServeSystem(files)
		val acceptor = FakeAcceptor()
		val server = server(system)
		server.stop()
		server.run(acceptor)
		assertEquals(1, acceptor.closed)
	}

	@Test
	fun connectionsOverTheLimitAreTurnedAwayWith503() {
		val queued = List(3) { FakeConnection(request("/")) }
		val held = mutableListOf<() -> Unit>()
		val system = object : ServeSystem by FakeServeSystem(files) {
			override fun spawn(block: () -> Unit): Boolean {
				held += block
				return true
			}
		}
		val acceptor = FakeAcceptor(queue = queued.toMutableList<Connection>(), onEmpty = { })
		var polls = 0
		val limited = object : ServeSystem by system {
			override fun interrupted() = ++polls > 4
		}
		val site = StaticSite("/dist", files, HostPolicy())
		Server(limited, site, Limits(maxConnections = 2)).run(acceptor, 0)
		assertEquals(2, held.size)
		assertEquals("HTTP/1.1 503 Service Unavailable", queued[2].status)
		assertTrue(queued[2].closed)
	}

	@Test
	fun runWaitsForOpenRequestsToFinishBeforeReturning() {
		val held = mutableListOf<() -> Unit>()
		var sleeps = 0
		val base = FakeServeSystem(files)
		var polls = 0
		val system = object : ServeSystem by base {
			override fun spawn(block: () -> Unit): Boolean {
				held += block
				return true
			}

			override fun interrupted() = ++polls > 1

			override fun sleep(millis: Int) {
				sleeps++
				if (sleeps == 2) held.removeAt(0).invoke()
			}
		}
		val acceptor = FakeAcceptor(queue = mutableListOf<Connection>(FakeConnection(request("/"))))
		Server(system, StaticSite("/dist", files, HostPolicy())).run(acceptor, 5000)
		assertEquals(2, sleeps)
		assertTrue(held.isEmpty())
	}

	@Test
	fun runGivesUpOnARequestThatNeverFinishesAfterTheDrainTime() {
		val system = object : ServeSystem by FakeServeSystem(files) {
			override fun spawn(block: () -> Unit) = true

			override fun interrupted() = true
		}
		val acceptor = FakeAcceptor(queue = mutableListOf<Connection>(FakeConnection(request("/"))))
		Server(system, StaticSite("/dist", files, HostPolicy())).run(acceptor, 100)
		assertEquals(1, acceptor.closed)
	}

	@Test
	fun aConnectionIsClosedWhenNoThreadCanBeStarted() {
		var loops = 0
		val system = object : ServeSystem by FakeServeSystem(files) {
			override fun spawn(block: () -> Unit) = false

			override fun interrupted() = ++loops > 3
		}
		val first = FakeConnection(request("/"))
		val second = FakeConnection(request("/"))
		val acceptor = FakeAcceptor(queue = mutableListOf<Connection>(first, second))
		val site = StaticSite("/dist", files, HostPolicy())
		Server(system, site, Limits(maxConnections = 1)).run(acceptor, 0)
		assertTrue(first.closed && second.closed)
		assertEquals("", first.output.toString())
		assertEquals("", second.output.toString())
	}
}
