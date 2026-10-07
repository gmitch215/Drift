package dev.gmitch215.drift

import dev.gmitch215.drift.symptom.Signature
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomExtractor
import dev.gmitch215.drift.symptom.SymptomKind
import dev.gmitch215.drift.symptom.SymptomKind.DURATION
import dev.gmitch215.drift.symptom.SymptomKind.EXIT
import dev.gmitch215.drift.symptom.SymptomKind.SIGNAL
import dev.gmitch215.drift.symptom.SymptomKind.SIGNATURE
import dev.gmitch215.drift.symptom.SymptomKind.STEP
import dev.gmitch215.drift.symptom.SymptomKind.TRANSPORT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymptomTest {
	private fun extract(vararg lines: String) = SymptomExtractor.extract(lines.joinToString("\n"))

	private fun stamp(second: Int, ms: Int = 0) =
		"2026-10-02T10:00:${second.toString().padStart(2, '0')}.${ms.toString().padStart(3, '0')}Z"

	private fun at(second: Int, text: String, ms: Int = 0) = "${stamp(second, ms)} $text"

	private fun job(name: String, second: Int, text: String, ms: Int = 0) =
		"$name\tUNKNOWN STEP\t${stamp(second, ms)} $text"

	private fun pick(symptoms: List<Symptom>, kind: SymptomKind) =
		symptoms.filter { it.kind == kind }.map { it.value }

	@Test
	fun annotationsGiveSignaturesStepsDurationsAndExit() {
		val symptoms = extract(
			at(0, "##[group]Run bun test"),
			at(1, "running"),
			at(3, "##[error]Error: boom at 10:00:03", ms = 100),
			at(3, "##[error]Process completed with exit code 2.", ms = 500),
			at(4, "##[group]Run codecov"),
			at(5, "done"),
		)
		assertEquals(
			listOf(
				Symptom(STEP, "bun test", 1),
				Symptom(DURATION, "3500", 1),
				Symptom(SIGNATURE, "Error: boom at <time>", 3),
				Symptom(EXIT, "2", 4),
			),
			symptoms,
		)
	}

	@Test
	fun jobPrefixesKeepJobsApart() {
		val symptoms = extract(
			job("A", 0, "##[group]Run one"),
			job("B", 0, "##[group]Run two"),
			job("A", 1, "##[error]Error: first"),
			"A\tUNKNOWN STEP\tcontinuation epipe",
			job("B", 2, "network connection lost"),
			job("B", 3, "##[error]Error: second"),
			job("A", 4, "Post job cleanup."),
		)
		assertEquals(
			listOf(
				Symptom(STEP, "one", 1, "A"),
				Symptom(DURATION, "1000", 1, "A"),
				Symptom(STEP, "two", 2, "B"),
				Symptom(DURATION, "3000", 2, "B"),
				Symptom(SIGNATURE, "Error: first", 3, "A"),
				Symptom(TRANSPORT, "broken pipe", 4, "A"),
				Symptom(TRANSPORT, "network connection lost", 5, "B"),
				Symptom(SIGNATURE, "Error: second", 6, "B"),
			),
			symptoms,
		)
	}

	@Test
	fun aTabInOrdinaryOutputIsNotAJobPrefix() {
		val symptoms = extract(at(0, "a\tb\tconnection reset by peer"), "x\ty\tepipe")
		assertEquals(listOf(Symptom(TRANSPORT, "connection reset", 1)), symptoms.take(1))
		assertEquals(listOf(Symptom(TRANSPORT, "broken pipe", 2)), symptoms.drop(1))
		val prefixed = "J\tUNKNOWN STEP\tepipe"
		val empty = "\tUNKNOWN STEP\tepipe\ttwo"
		val known = extract(job("J", 0, "x"), prefixed, empty)
		assertEquals(listOf(Symptom(TRANSPORT, "broken pipe", 2, "J")), known.take(1))
		assertEquals(Symptom(TRANSPORT, "broken pipe", 3), known[1])
		val early = extract("J\tUNKNOWN STEP\tepipe", job("J", 0, "x"), "J\tUNKNOWN STEP\tepipe")
		assertEquals(listOf(null, "J"), early.map { it.job })
	}

	@Test
	fun linesShapedLikeErrorsCountOnlyWithoutAnnotations() {
		val lines = arrayOf(
			"TypeError: x is not a function",
			"  error: script failed",
			"fatal: bad ref",
			"Error",
			"Some Error: spaced",
			"Odd-Error: dashed",
			"MyException: odd",
			"note: fine",
			"^[[36;1mecho Error: shown^[[0m",
		)
		assertEquals(
			listOf(
				"TypeError: x is not a function",
				"error: script failed",
				"fatal: bad ref",
				"MyException: odd",
			),
			pick(extract(*lines), SIGNATURE),
		)
		assertEquals(
			listOf("Error: real"),
			pick(extract(*lines, "##[error]Error: real"), SIGNATURE),
		)
		val far = "x".repeat(90) + "Error: far"
		assertEquals(emptyList(), pick(extract(": no head", far), SIGNATURE))
	}

	@Test
	fun exitCodesAreNonZeroNumbers() {
		val symptoms = extract(
			"script exited with code 137",
			"exit status -1",
			"exit code 0",
			"exit code 00",
			"exit code 12345678901",
			"exit code x",
			"Exit Code 3 and exit code 9",
			"exit code 007",
		)
		assertEquals(
			listOf("137", "-1", "3", "7"),
			pick(symptoms, EXIT),
		)
	}

	@Test
	fun signalsNeedTheirOwnWordsAndTokenShape() {
		val symptoms = extract(
			"wrangler dev closed code=1 signal=SIGTERM",
			"wrangler dev closed code=1 signal=null",
			"Killed by SIGKILL",
			"SIGSEGV happened",
			"terminated SIGINT and SIGHUP",
			"XSIGTERM signal",
			"SIGA signal",
			"SIG signal",
			"signal SIGAB",
		)
		assertEquals(
			listOf("SIGTERM", "SIGKILL", "SIGHUP", "SIGINT", "SIGAB"),
			pick(symptoms, SIGNAL),
		)
	}

	@Test
	fun everyTransportPhraseHasACanonicalValue() {
		for ((needle, value) in SymptomExtractor.transport) {
			val found = pick(extract("xx ${needle.uppercase()} yy"), TRANSPORT)
			assertEquals(listOf(value), found, needle)
		}
		val two = extract("stream disconnected prematurely after read ETIMEDOUT")
		assertEquals(listOf("stream disconnected", "timeout"), pick(two, TRANSPORT))
		assertEquals(
			emptyList(),
			pick(extract("timeout 5", "x-systemd.device-timeout ignored"), TRANSPORT),
		)
	}

	@Test
	fun scriptEchoAndColorCodes() {
		val symptoms = extract(
			at(0, "^[[36;1mecho \"connection reset by peer\"^[[0m"),
			at(1, "\u001B[36;1mecho epipe\u001B[0m"),
			at(2, "^[[31m^[[1mconnection reset by peer^[[0m"),
			at(3, "\u001B[1;31mepipe\u001B[0m"),
		)
		assertEquals(
			listOf(Symptom(TRANSPORT, "connection reset", 3), Symptom(TRANSPORT, "broken pipe", 4)),
			symptoms,
		)
		assertEquals("red", SymptomExtractor.stripAnsi("^[[31mred^[[0m"))
		assertEquals("green", SymptomExtractor.stripAnsi("\u001B[1;32mgreen\u001B[0m"))
		assertEquals("^[[31 x^[[", SymptomExtractor.stripAnsi("^[[31 x^[["))
		assertEquals("\u001B[?25", SymptomExtractor.stripAnsi("\u001B[?25"))
		assertEquals("plain", SymptomExtractor.stripAnsi("plain"))
		assertEquals("ab", SymptomExtractor.stripAnsi("a\u001B[?25lb"))
	}

	@Test
	fun lineEndingsAndNumbers() {
		val symptoms = SymptomExtractor.extract("x\rnetwork connection lost\r\ny\n\nepipe")
		assertEquals(
			listOf(
				Symptom(TRANSPORT, "network connection lost", 2),
				Symptom(TRANSPORT, "broken pipe", 5),
			),
			symptoms,
		)
		assertEquals(emptyList(), SymptomExtractor.extract(""))
		assertEquals(emptyList(), SymptomExtractor.extract("\n\r\n\r"))
		assertEquals(emptyList(), SymptomExtractor.extract("no steps, nothing wrong"))
		assertEquals(3, SymptomExtractor.extract("\n\nepipe\n")[0].line)
	}

	@Test
	fun equalSymptomsMergeIntoOneWithACount() {
		val symptoms = extract("epipe", "x", "epipe again", job("J", 0, "epipe"))
		assertEquals(
			listOf(
				Symptom(TRANSPORT, "broken pipe", 1, count = 2),
				Symptom(TRANSPORT, "broken pipe", 4, "J"),
			),
			symptoms,
		)
	}

	@Test
	fun theNumberOfDistinctSymptomsIsCapped() {
		fun word(n: Int) = (0 until 4).map { ('a' + (n / pow26(it)) % 26) }.joinToString("")
		val prefixes = (0 until 70).joinToString("\n") { "j${word(it)}\tS\t${stamp(0)} x" }
		val late = "j${word(69)}\tS\t${stamp(1)} epipe"
		val first = "j${word(0)}\tS\t${stamp(1)} epipe"
		val capped = SymptomExtractor.extract("$prefixes\n$late\n$first")
		assertEquals(listOf(null, "j${word(0)}"), capped.map { it.job })
		val lines = (0 until 700).map { "##[error]Error: failure ${word(it)}" }
		val symptoms = SymptomExtractor.extract(lines.joinToString("\n"))
		assertEquals(SymptomExtractor.MAX_SYMPTOMS, symptoms.size)
		assertEquals(1, symptoms[0].line)
		val repeated = SymptomExtractor.extract((0 until 700).joinToString("\n") { "epipe" })
		assertEquals(700, repeated.single().count)
		val fallback = (0 until 700).joinToString("\n") { "Error: failure ${word(it)}" }
		assertEquals(SymptomExtractor.MAX_SYMPTOMS, SymptomExtractor.extract(fallback).size)
	}

	private fun pow26(n: Int): Int = if (n == 0) 1 else 26 * pow26(n - 1)

	@Test
	fun stepsEndAtTheNextHeaderOrPostCleanup() {
		val symptoms = extract(
			at(0, "##[group]Run first"),
			at(2, "##[error]Error: a"),
			at(5, "tail of first"),
			at(6, "##[group]Run second"),
			at(7, "##[error]Error: b"),
			at(9, "Post job cleanup."),
			at(20, "late"),
			at(21, "##[group]Run third"),
			at(22, "##[error]Error: c"),
		)
		assertEquals(
			listOf("first", "second", "third"),
			pick(symptoms, STEP),
		)
		assertEquals(listOf("5000", "1000", "1000"), pick(symptoms, DURATION))
	}

	@Test
	fun stepsWithoutUsableTimestampsHaveNoDuration() {
		val plain = extract("##[group]Run build", "##[error]Error: x")
		assertEquals(listOf("build"), pick(plain, STEP))
		assertEquals(emptyList(), pick(plain, DURATION))
		val backwards = extract(at(5, "##[group]Run build"), at(2, "x"), at(1, "##[error]Error: x"))
		assertEquals(emptyList(), pick(backwards, DURATION))
		val passing = extract(at(0, "##[group]Run build"), at(1, "fine"))
		assertEquals(emptyList(), passing)
	}

	@Test
	fun timestampsParseToTheSameMillisecondsAnywhere() {
		fun ms(s: String) = SymptomExtractor.epochAt(s, 0)
		assertEquals(0L, ms("1970-01-01T00:00:00Z"))
		assertEquals(1_000L, ms("1970-01-01T00:00:01Z"))
		assertEquals(500L, ms("1970-01-01T00:00:00.5Z"))
		assertEquals(123L, ms("1970-01-01T00:00:00.1234567Z"))
		assertEquals(120L, ms("1970-01-01T00:00:00.12Z"))
		assertEquals(86_400_000L, ms("1970-01-02T00:00:00Z"))
		assertEquals(1_790_936_988_000L, ms("2026-10-02T10:29:48Z"))
		assertEquals(2_000L, ms("2024-03-01T00:00:01Z")!! - ms("2024-02-29T23:59:59Z")!!)
		assertEquals(2_000L, ms("2026-01-01T00:00:01Z")!! - ms("2025-12-31T23:59:59Z")!!)
		assertEquals(1_000L, ms("2000-03-01T00:00:00Z")!! - ms("2000-02-29T23:59:59Z")!!)
		assertEquals(null, ms("2026-10-02T10:29:48"))
		assertEquals(null, ms("2026-10-02T10:29:48Zx"))
		assertEquals(null, ms("2026-10-02 10:29:48Z"))
		assertEquals(null, ms("2026-10-02T10:29:4xZ"))
		assertEquals(null, ms("2026/10/02T10:29:48Z"))
		assertEquals(null, ms("2026-10-02T10-29-48Z"))
		assertEquals(null, ms("2026-10-02T10:29:48.123"))
		assertEquals(null, ms("short"))
		assertEquals(1_790_936_988_000L, ms("2026-10-02T10:29:48Z text"))
	}

	@Test
	fun malformedTextIsKeptWellFormed() {
		val lone = "\uD83D"
		val low = Char(0xDE00)
		val text = "##[error]Error: bad $lone and $low end\nepipe $lone"
		val symptoms = SymptomExtractor.extract(text)
		assertEquals("Error: bad \uFFFD and \uFFFD end", symptoms[0].value)
		assertEquals(2, symptoms.size)
		val pair = "\uD83D\uDE00"
		val line = "x".repeat(SymptomExtractor.MAX_LINE - 1) + pair + "epipe"
		val cut = SymptomExtractor.extract(line)
		assertEquals(emptyList(), cut)
		assertEquals("a\uFFFDb", SymptomExtractor.wellFormed("a\uD83Db"))
		assertEquals("a${pair}b", SymptomExtractor.wellFormed("a${pair}b"))
		assertEquals("\uFFFD", SymptomExtractor.wellFormed("\uDE00"))
		val replaced = SymptomExtractor.extract("##[error]Error: \uFFFD\uFFFD bad")
		assertEquals("Error: \uFFFD\uFFFD bad", replaced.single().value)
	}

	@Test
	fun aVeryLongLineIsReadAtItsStartOnly() {
		val head = "epipe "
		val tail = "\nconnection reset by peer\n"
		val chars = CharArray(50_000_000) { 'x' }
		head.forEachIndexed { i, c -> chars[i] = c }
		tail.forEachIndexed { i, c -> chars[chars.size - tail.length + i] = c }
		val symptoms = SymptomExtractor.extract(chars.concatToString())
		assertEquals(
			listOf(Symptom(TRANSPORT, "broken pipe", 1), Symptom(TRANSPORT, "connection reset", 2)),
			symptoms,
		)
		val message = SymptomExtractor.extract("##[error]Error: " + "y".repeat(5000))
		assertEquals(Signature.MAX_LENGTH, message.single().value.length)
		val text = "##[group]Run " + "z".repeat(5000) + "\n##[error]E: x"
		val header = SymptomExtractor.extract(text)
		assertEquals(200, pick(header, STEP).single().length)
	}

	@Test
	fun secretsInAnnotationsAndCommandsAreMasked() {
		val token = "ghp_abcdefghijklmnopqrstuvwxyz0123"
		val symptoms = extract("##[group]Run curl -H $token x", "##[error]Error: bad $token")
		assertEquals(listOf("curl -H <redacted> x"), pick(symptoms, STEP))
		assertEquals(listOf("Error: bad <redacted>"), pick(symptoms, SIGNATURE))
		assertTrue(symptoms.none { token in it.value })
	}

	@Test
	fun randomTextNeverThrows() {
		var state = 99L
		fun next(n: Int): Int {
			state = (state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
			return ((state ushr 33) % n).toInt()
		}
		val parts = listOf(
			"##[error]", "##[group]Run ", "\t", "\n", "\r", "UNKNOWN STEP", "2026-10-02T10:00:00Z ",
			"^[[31m", "\u001B[",
			"exit code ", "SIGTERM signal", "epipe", "/tmp/a", "12:30:01", "[3/5]",
			"\uD83D", "\uFFFD", "Post job cleanup.", "x", " ", "9",
		)
		repeat(300) {
			val text = (0 until next(40)).joinToString("") { parts[next(parts.size)] }
			SymptomExtractor.extract(text)
			Signature.normalize(text)
		}
	}
}
