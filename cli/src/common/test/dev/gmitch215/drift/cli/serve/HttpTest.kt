package dev.gmitch215.drift.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpTest {
	private fun parse(head: String) = parseHead(head.replace("\n", "\r\n"))

	private fun reject(head: String): Parsed.Reject = assertIs(parse(head))

	@Test
	fun getAndHeadParseWithLowerCaseHeaderNames() {
		val head = "GET /a?x=1 HTTP/1.1\nHost: localhost:8080\nAccept: text/html"
		val ok = assertIs<Parsed.Ok>(parse(head))
		assertEquals("GET", ok.request.method)
		assertEquals("/a?x=1", ok.request.target)
		assertEquals("localhost:8080", ok.request.headers["host"])
		assertEquals("text/html", ok.request.headers["accept"])
		val bare = assertIs<Parsed.Ok>(parse("HEAD / HTTP/1.0\nHost: localhost"))
		assertTrue(bare.request.head)
	}

	@Test
	fun malformedHeadsAreRejectedWithTheRightStatus() {
		val cases = mapOf(
			"GET / HTTP/1.1" to 400,
			"GET / HTTP/1.1\nHost: a\nHost: b" to 400,
			"GET /  HTTP/1.1\nHost: a" to 400,
			"GET / HTTP/1.1 extra\nHost: a" to 400,
			"GET http://localhost/ HTTP/1.1\nHost: a" to 400,
			"GET * HTTP/1.1\nHost: a" to 400,
			"G@T / HTTP/1.1\nHost: a" to 400,
			"GET / HTTP/2.0\nHost: a" to 505,
			"GET / FTP/1.1\nHost: a" to 400,
			"GET / HTTP/1.1\nHost: a\n folded" to 400,
			"GET / HTTP/1.1\nHost : a" to 400,
			"GET / HTTP/1.1\nHost: a\nnocolon" to 400,
			"GET / HTTP/1.1\nHost: a\n: empty" to 400,
			"GET / HTTP/1.1\nHost: a\nX: \u0001" to 400,
			"GET / HTTP/1.1\nHost: a\nContent-Length: 5" to 400,
			"GET / HTTP/1.1\nHost: a\nTransfer-Encoding: chunked" to 400,
			"POST / HTTP/1.1\nHost: a" to 405,
			"DELETE / HTTP/1.1\nHost: a" to 405,
		)
		for ((head, status) in cases) assertEquals(status, reject(head).status, head)
	}

	@Test
	fun aMethodOtherThanGetOrHeadAdvertisesAllow() {
		assertTrue(reject("PUT / HTTP/1.1\nHost: a").allow)
	}

	@Test
	fun aBareLineFeedOrCarriageReturnIsRefused() {
		assertEquals(400, assertIs<Parsed.Reject>(parseHead("GET / HTTP/1.1\nHost: a")).status)
		val bare = parseHead("GET / HTTP/1.1\r\nHost: a\rX: b")
		assertEquals(400, assertIs<Parsed.Reject>(bare).status)
	}

	@Test
	fun contentLengthZeroIsFine() {
		assertIs<Parsed.Ok>(parse("GET / HTTP/1.1\nHost: a\nContent-Length: 0"))
	}

	@Test
	fun tooManyHeadersAndALongLineAreRefused() {
		val many = (1..70).joinToString("\n") { "X$it: v" }
		assertEquals(431, reject("GET / HTTP/1.1\nHost: a\n$many").status)
		assertEquals(414, reject("GET /${"a".repeat(9000)} HTTP/1.1\nHost: a").status)
	}

	@Test
	fun readRequestWorksOneByteAtATime() {
		val connection = FakeConnection("GET /x HTTP/1.1\r\nHost: localhost\r\n\r\nleftover")
		connection.chunk = 1
		val ok = assertIs<Parsed.Ok>(readRequest(connection, Limits()))
		assertEquals("/x", ok.request.target)
	}

	@Test
	fun readRequestReportsTheShapeOfAFailure() {
		assertEquals(Parsed.Closed, readRequest(FakeConnection(""), Limits()))
		assertEquals(Parsed.Closed, readRequest(FakeConnection("GET / HTT"), Limits()))
		val huge = "GET / HTTP/1.1\r\nHost: a\r\nX: ${"v".repeat(20000)}\r\n\r\n"
		val big = assertIs<Parsed.Reject>(readRequest(FakeConnection(huge), Limits(maxHead = 1024)))
		assertEquals(431, big.status)
		val line = "GET /${"a".repeat(9000)}"
		val long = assertIs<Parsed.Reject>(readRequest(FakeConnection(line), Limits()))
		assertEquals(414, long.status)
	}

	@Test
	fun aHeadThatNeverEndsTimesOut() {
		val connection = FakeConnection("GET / HTTP/1.1\r\nHost: a\r\n")
		val timed = assertIs<Parsed.Reject>(readRequest(connection, Limits(headMillis = -1)))
		assertEquals(408, timed.status)
		assertEquals(Parsed.Closed, readRequest(FakeConnection(""), Limits(headMillis = -1)))
	}

	@Test
	fun statusTextCoversEveryStatusTheServerSends() {
		val sent = listOf(200, 400, 403, 404, 405, 408, 414, 431, 503, 505)
		assertTrue(sent.none { statusText(it) == "Error" })
		assertEquals("Error", statusText(418))
	}
}
