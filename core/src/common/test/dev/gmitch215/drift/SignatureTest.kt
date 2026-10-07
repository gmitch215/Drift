package dev.gmitch215.drift

import dev.gmitch215.drift.symptom.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SignatureTest {
	private fun norm(s: String) = Signature.normalize(s)

	@Test
	fun tmpPathsBecomeOneToken() {
		assertEquals(
			"at (<tmp>) in file://<tmp> and <tmp>",
			norm(
				"at (/tmp/drangler-e2e-pv-G6ZPBb/w/cli.js:180603:20) in " +
				"file:///tmp/x-y/z.js:5186:22 and /var/folders/ab/cd/T/q",
			),
		)
		assertEquals("/home/a/tmp/b /usr/tmp/x ./tmp/y", norm("/home/a/tmp/b /usr/tmp/x ./tmp/y"))
		assertEquals("<tmp>", norm("/private/var/db/x"))
		assertEquals("<tmp>", norm("/var/tmp/x"))
	}

	@Test
	fun timestampsBecomeOneToken() {
		assertEquals("<time> boom", norm("2026-10-02T10:33:43.1526554Z boom"))
		assertEquals("<time> x", norm("2026-10-02 10:32:07 x"))
		assertEquals("at <time> y", norm("at 10:33:43 y"))
		assertEquals("at <time> y", norm("at 10:33:43,250Z y"))
		assertEquals("on <time> z", norm("on 2026-10-02 z"))
		assertEquals("<time>T25 w", norm("2026-10-02T25 w"))
		assertEquals("<time>", norm("2026-10-02T10:00:00"))
		assertEquals("2026-1-02 10:3:43 12:30", norm("2026-1-02 10:3:43 12:30"))
		assertEquals("<time>.", norm("10:33:43."))
	}

	@Test
	fun idsAndHashesBecomeOneToken() {
		assertEquals("id <uuid> end", norm("id 123e4567-e89b-12d3-a456-426614174000 end"))
		assertEquals(
			"id 123e4567-e89b-12d3-a456-42661417400g",
			norm("id 123e4567-e89b-12d3-a456-42661417400g"),
		)
		assertEquals("<hex> <hex>", norm("0xdeadBEEF 0X1f"))
		assertEquals("sha <hex>.", norm("sha 0123456789abcdef0123456789abcdef01234567."))
		assertEquals("0x 0xzz abcdef 0123456789abg", norm("0x 0xzz abcdef 0123456789abg"))
		assertEquals("<hex>_", norm("0123456789ab_"))
		assertEquals("deadbeefdeadbeefzz", norm("deadbeefdeadbeefzz"))
	}

	@Test
	fun durationsBecomeOneToken() {
		assertEquals(
			"took <dur> and <dur>, <dur> <dur> <dur>",
			norm("took 87363ms and 197.78s, 5min 2hours 3h"),
		)
		assertEquals("5 ms 5msx 1.s 12", norm("5 ms 5msx 1.s 12"))
		assertEquals("<dur>", norm("90sec"))
	}

	@Test
	fun countersBecomeOneToken() {
		assertEquals("pid <n> pid=<n> process <n>", norm("pid 4242 pid=77 process 94"))
		assertEquals("attempt <n> of 5, retry: <n> try <n>", norm("attempt 3 of 5, retry: 2 try 9"))
		assertEquals(
			"\"port\":\"<n>\" port <n> line <n> uid <n> tid <n>",
			norm("\"port\":\"43697\" port 80 line 12 uid 5 tid 6"),
		)
		assertEquals("issue #<n> [<n>/<n>] (<n>/<n>)", norm("issue #12 [5/5] (3/5)"))
		assertEquals(
			"worker[<n>]: x [12] (8 tests) pid123 xpid 4",
			norm("worker[1234]: x [12] (8 tests) pid123 xpid 4"),
		)
		assertEquals("[5/6) (5/6] [5/ [5", norm("[5/6) (5/6] [5/ [5"))
		assertEquals("it 7 \"7", norm("it 7 \"7"))
		assertEquals("exit code 1", norm("exit code 1"))
	}

	@Test
	fun portsAndPositionsBecomeOneToken() {
		assertEquals(
			"localhost:<n>/admin <loopback>:<n> preview.ts:<n> x.js:<n> map]:<n>",
			norm("localhost:8902/admin <loopback>:43697 preview.ts:746:7 x.js:12 map]:3"),
		)
		assertEquals("a:1234567 a:12b :123 a: 5", norm("a:1234567 a:12b :123 a: 5"))
		assertEquals("a:<n>:1234567", norm("a:1:1234567"))
		assertEquals("a:<n>:3b", norm("a:1:3b"))
		assertEquals("12:5", norm("12:5"))
		assertEquals(
			"10.0.0.1:<n> 1.2.3:80 1.2.3.4.5:80",
			norm("10.0.0.1:8080 1.2.3:80 1.2.3.4.5:80"),
		)
	}

	@Test
	fun theSameFailureOnTwoRunsGetsTheSameText() {
		val a =
			norm(
				"2026-10-02T10:33:43.1Z Error: bind 127.0.0.1:43697 failed after 87363ms " +
					"(pid 41) in /tmp/e2e-aB3/x.js:12",
			)
		val b =
			norm(
				"2026-10-04T11:00:01.9Z Error: bind 127.0.0.1:39203 failed after 5ms " +
					"(pid 9) in /tmp/e2e-zZ9/x.js:77",
			)
		assertEquals(a, b)
		assertNotEquals(a, norm("2026-10-04T11:00:01.9Z Error: bind failed after 5ms (pid 9)"))
		assertEquals("Error: /rows answered 500", norm("Error: /rows answered 500"))
		assertNotEquals(norm("Error: /rows answered 500"), norm("Error: /rows answered 502"))
	}

	@Test
	fun secretsAreMaskedBeforeAnythingElse() {
		assertEquals(
			"token <redacted> failed",
			norm("token ghp_abcdefghijklmnopqrstuvwxyz0123 failed"),
		)
		assertEquals("https<redacted>host", norm("https://user:pw@host"))
	}

	@Test
	fun whitespaceCollapsesAndLengthIsCapped() {
		assertEquals("a b", norm("  a \t b\n\r\u000B\u000C "))
		assertEquals("", norm("  \n"))
		assertEquals(Signature.MAX_LENGTH, norm("w".repeat(500)).length)
		val pair = "\uD83D\uDE00"
		val cut = norm("w".repeat(Signature.MAX_LENGTH - 1) + pair)
		assertEquals(Signature.MAX_LENGTH - 1, cut.length)
		assertEquals("ab", Signature.clip("ab", 5))
	}

	@Test
	fun normalizingTwiceChangesNothing() {
		val sample =
			"2026-10-02T10:33:43Z pid 4 /tmp/x:1 [3/5] 12ms 0xff " +
			"123e4567-e89b-12d3-a456-426614174000 " +
			"ghp_abcdefghijklmnopqrstuvwxyz0123 localhost:80 #3 name[9]"
		assertEquals(norm(sample), norm(norm(sample)))
	}

	@Test
	fun asciiLowerLeavesNonAsciiAlone() {
		assertEquals("abc-\u00C9\u0130", Signature.asciiLower("ABC-\u00C9\u0130"))
	}
}
