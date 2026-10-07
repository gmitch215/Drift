package dev.gmitch215.drift

import dev.gmitch215.drift.diff.Order
import dev.gmitch215.drift.diff.Version
import dev.gmitch215.drift.diff.VersionScheme
import dev.gmitch215.drift.fixtures.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
	private fun describe(text: String, scheme: VersionScheme = VersionScheme.GENERIC): String {
		val v = Version.parse(text, scheme) ?: return "unparsed"
		val pre = v.pre?.let { " pre=${it.rank}:${it.number}" }.orEmpty()
		val rev = if (v.revision.isEmpty()) "" else " rev=${v.revision}"
		return v.nums.joinToString(".") + pre + rev
	}

	private fun parse(text: String, scheme: VersionScheme = VersionScheme.GENERIC) =
		assertNotNull(Version.parse(text, scheme), text)

	@Test
	fun zooOfRealVersionStrings() {
		val java = VersionScheme.JAVA
		val rows = listOf(
			Triple(java, "1.8.0_504", "8.0.504"),
			Triple(java, "21.0.12.1", "21.0.12.1"),
			Triple(java, "8.0.504+1", "8.0.504 rev=+1"),
			Triple(java, "11.0.32+1 (default)", "11.0.32 rev=+1"),
			Triple(java, "1.8.0_504-b01", "8.0.504 rev=-b01"),
			Triple(java, "25.504-b01", "25.504 rev=-b01"),
			Triple(java, "21-ea+10", "21 pre=1:0 rev=+10"),
			Triple(java, "1.0.0", "0.0"),
			Triple(java, "1.9.0", "1.9.0"),
			Triple(VersionScheme.GENERIC, "go1.21.13", "1.21.13"),
			Triple(VersionScheme.GENERIC, "1.24.13", "1.24.13"),
			Triple(VersionScheme.GENERIC, "1.22rc2", "1.22 pre=3:2"),
			Triple(VersionScheme.GENERIC, "v18.20.8", "18.20.8"),
			Triple(VersionScheme.GENERIC, "18.20.8", "18.20.8"),
			Triple(VersionScheme.GENERIC, "3.9.25", "3.9.25"),
			Triple(VersionScheme.GENERIC, "3.13.0rc1", "3.13.0 pre=3:1"),
			Triple(VersionScheme.GENERIC, "3.14.0a1", "3.14.0 pre=1:1"),
			Triple(VersionScheme.GENERIC, "3.11.15 [PyPy 7.3.23]", "3.11.15"),
			Triple(VersionScheme.GENERIC, "7.4.33", "7.4.33"),
			Triple(VersionScheme.GENERIC, "8.1.2-1ubuntu2.14", "8.1.2 rev=-1ubuntu2.14"),
			Triple(VersionScheme.GENERIC, "8.4.0RC1", "8.4.0 pre=3:1"),
			Triple(VersionScheme.GENERIC, "1.80.1", "1.80.1"),
			Triple(VersionScheme.GENERIC, "1.81.0-beta.1", "1.81.0 pre=2:1"),
			Triple(VersionScheme.GENERIC, "1.9.0-stable", "1.9.0 rev=-stable"),
			Triple(VersionScheme.GENERIC, "2.45.4", "2.45.4"),
			Triple(VersionScheme.GENERIC, "2.55.0.windows.5", "2.55.0 rev=.windows.5"),
			Triple(VersionScheme.GENERIC, "12.2.1", "12.2.1"),
			Triple(VersionScheme.GENERIC, "14.2.0", "14.2.0"),
			Triple(VersionScheme.GENERIC, "9.1", "9.1"),
			Triple(VersionScheme.GENERIC, "6.17.0-1022-azure", "6.17.0 rev=-1022-azure"),
			Triple(VersionScheme.GENERIC, "Darwin 25.5.0", "unparsed"),
			Triple(VersionScheme.GENERIC, "24.04.4 LTS", "24.4.4"),
			Triple(VersionScheme.GENERIC, "10.0.26100 Build 33296", "10.0.26100"),
			Triple(VersionScheme.GENERIC, "macOS 26.6.2 (25G83)", "unparsed"),
			Triple(VersionScheme.GENERIC, "20260927.320.1", "20260927.320.1"),
			Triple(VersionScheme.GENERIC, "5.2.21(1)-release", "5.2.21 rev=(1)-release"),
			Triple(VersionScheme.GENERIC, "2.4.10-release-377", "2.4.10 rev=-release-377"),
			Triple(VersionScheme.GENERIC, "3.0.13-0ubuntu3.15", "3.0.13 rev=-0ubuntu3.15"),
			Triple(VersionScheme.GENERIC, "3.6.4 25 Aug 2026 (Library: OpenSSL)", "3.6.4"),
			Triple(VersionScheme.GENERIC, "1.1.1w", "unparsed"),
			Triple(VersionScheme.GENERIC, "1.9.15p5-3ubuntu5.24.04.3", "unparsed"),
			Triple(VersionScheme.GENERIC, "2:21.1.12-1ubuntu1.8", "unparsed"),
			Triple(VersionScheme.GENERIC, "(build from commit 07f4812200)", "unparsed"),
			Triple(VersionScheme.GENERIC, "3.10.91+e05abbcae4", "3.10.91 rev=+e05abbcae4"),
			Triple(VersionScheme.GENERIC, "8.0.130, 8.0.424, 9.0.120", "unparsed"),
			Triple(VersionScheme.GENERIC, "unparsed", "unparsed"),
			Triple(VersionScheme.GENERIC, "", "unparsed"),
			Triple(VersionScheme.GENERIC, "   ", "unparsed"),
			Triple(VersionScheme.GENERIC, "HEAD", "unparsed"),
			Triple(VersionScheme.GENERIC, "99999999999999999999.1", "unparsed"),
			Triple(VersionScheme.GENERIC, "max 100000", "unparsed"),
			Triple(VersionScheme.GENERIC, "200000 100000", "200000"),
			Triple(VersionScheme.GENERIC, "v", "unparsed"),
			Triple(VersionScheme.GENERIC, "go", "unparsed"),
			Triple(VersionScheme.GENERIC, "\t 4.2\n", "4.2"),
			Triple(VersionScheme.GENERIC, "4.2 beta", "unparsed"),
		)
		for ((scheme, text, expected) in rows) {
			assertEquals(expected, describe(text, scheme), "$scheme '$text'")
		}
	}

	@Test
	fun preReleaseMarkers() {
		val rows = mapOf(
			"1.0.0-alpha" to "1.0.0 pre=1:0",
			"1.0.0-alpha.7" to "1.0.0 pre=1:7",
			"1.0.0-alpha." to "1.0.0 pre=1:0 rev=.",
			"1.0.0-alpha-1" to "1.0.0 pre=1:0 rev=-1",
			"1.0.0.dev3" to "1.0.0 pre=0:3",
			"1.0.0~snapshot" to "1.0.0 pre=0:0",
			"1.0.0_nightly" to "1.0.0 pre=0:0",
			"1.0.0PREVIEW2" to "1.0.0 pre=3:2",
			"1.0.0-pre" to "1.0.0 pre=3:0",
			"1.0.0b2" to "1.0.0 pre=2:2",
			"1.0.0c1" to "1.0.0 pre=3:1",
			"1.0.0-rc99999999999999999999" to "1.0.0 pre=3:0",
			"1.0.0a99999999999999999999" to "1.0.0 pre=1:0",
			"1.0.0-development" to "1.0.0 rev=-development",
			"1.0.0-rc1x" to "1.0.0 rev=-rc1x",
			"1.0.0rc1x" to "unparsed",
			"1.0.0a" to "unparsed",
			"1.0.0a1x" to "unparsed",
			"1.0.0x1" to "unparsed",
			"1.0.0-b01" to "1.0.0 rev=-b01",
			"1.0.0-release" to "1.0.0 rev=-release",
		)
		for ((text, expected) in rows) assertEquals(expected, describe(text), text)
	}

	@Test
	fun coreOrder() {
		fun core(a: String, b: String, scheme: VersionScheme = VersionScheme.GENERIC) =
			parse(a, scheme).compareCore(parse(b, scheme))

		assertEquals(0, core("1.8.0_504", "8.0.504+1", VersionScheme.JAVA))
		assertEquals(-1, core("3.13.0rc1", "3.13.0"))
		assertEquals(1, core("3.13.0", "3.13.0rc1"))
		assertEquals(-1, core("3.14.0a1", "3.14.0b1"))
		assertEquals(-1, core("3.14.0b1", "3.14.0b2"))
		assertEquals(1, core("3.14.0rc1", "3.14.0b9"))
		assertEquals(0, core("3.14.0rc1", "3.14.0-pre1"))
		assertEquals(0, core("1.80", "1.80.0"))
		assertEquals(1, core("2.10", "2.9"))
		assertEquals(-1, core("2.9", "2.10"))
		assertEquals(-1, core("1.0.0-dev", "1.0.0-alpha"))
		assertEquals(0, core("1.2.3-azure", "1.2.3-gcp"))
	}

	@Test
	fun totalOrderAddsTheRevisionText() {
		assertEquals(-1, parse("1.2.3-azure").compare(parse("1.2.3-gcp")))
		assertEquals(1, parse("1.2.3-gcp").compare(parse("1.2.3-azure")))
		assertEquals(0, parse("1.2.3-azure").compare(parse("1.2.3-azure")))
		assertEquals(-1, parse("1.2.3").compare(parse("1.2.4-aaa")))
	}

	@Test
	fun orderOfTwoStrings() {
		assertEquals(Order.LESS, Version.order("1.8.0_504", "21.0.12.1", VersionScheme.JAVA))
		assertEquals(Order.GREATER, Version.order("21.0.12.1", "1.8.0_504", VersionScheme.JAVA))
		assertEquals(Order.EQUAL, Version.order("1.8.0_504", "8.0.504", VersionScheme.JAVA))
		assertEquals(Order.LESS, Version.order("2", "4"))
		assertEquals(Order.EQUAL, Version.order("unparsed", "unparsed"))
		assertEquals(Order.UNORDERED, Version.order("unparsed", "HEAD"))
		assertEquals(Order.UNORDERED, Version.order("unparsed", "1.2"))
		assertEquals(Order.UNORDERED, Version.order("1.2", "unparsed"))
	}

	@Test
	fun schemeFollowsThePath() {
		assertEquals(VersionScheme.JAVA, VersionScheme.of("tool.java.version"))
		assertEquals(VersionScheme.GENERIC, VersionScheme.of("tool.go.version"))
	}

	@Test
	fun fuzzNeverThrowsAndOrderIsAntisymmetric() {
		val alphabet = "0123456789.-+_~ ()[]vgoabcrelasetnpdw:\t,"
		var state = 7L
		fun next(bound: Int): Int {
			state = state * 6364136223846793005L + 1442695040888963407L
			return ((state ushr 33) % bound).toInt()
		}

		val parsed = mutableListOf<Version>()
		repeat(20_000) {
			val text = CharArray(next(19)) { alphabet[next(alphabet.length)] }.concatToString()
			Version.parse(text)?.let(parsed::add)
			Version.parse(text, VersionScheme.JAVA)
			Version.order(text, text.reversed())
		}
		assertTrue(parsed.size > 1_000, "parsed ${parsed.size}")
		for ((a, b) in parsed.zipWithNext()) {
			assertEquals(-a.compare(b), b.compare(a), "$a $b")
			assertEquals(-a.compareCore(b), b.compareCore(a), "$a $b")
		}
	}

	private fun fixtureVersion(tool: String, line: String): String? = when (tool) {
		"java" -> line.substringAfter('"', "").substringBefore('"')
		"go" -> line.split(' ')[2]
		"cc" -> line.substringAfter(") ").substringBefore(' ')
		"php", "python", "rust" -> line.split(' ')[1]
		"git" -> line.split(' ')[2]
		"coreutils" -> line.takeIf { ") " in it }?.substringAfter(") ")
		"node" -> line
		else -> null
	}?.takeIf { it.isNotEmpty() }

	@Test
	fun versionsInTheCapturedTranscriptsParse() {
		val tools = listOf("java", "go", "cc", "php", "python", "rust", "git", "coreutils", "node")
		val seen = mutableMapOf<String, String>()
		for (name in Fixtures.names) {
			val tool = name.substringBefore('/')
			if (tool !in tools) continue
			val line = Fixtures.text(name).lineSequence().first()
			val version = fixtureVersion(tool, line) ?: continue
			val scheme = if (tool == "java") VersionScheme.JAVA else VersionScheme.GENERIC
			assertNotNull(Version.parse(version, scheme), "$name '$version'")
			seen[name] = version
		}
		assertTrue(seen.size >= 20, "only ${seen.size} transcripts carried a version")
		assertEquals("8.0.504", describe(seen.getValue("java/temurin-8.txt"), VersionScheme.JAVA))
		val twentyOne = seen.getValue("java/temurin-21.txt")
		assertEquals("21.0.12.1", describe(twentyOne, VersionScheme.JAVA))
		assertEquals("13.2.1", describe(seen.getValue("cc/alpine-3.20.txt")))
		assertEquals("1.21.13", describe(seen.getValue("go/go-1.21.txt")))
		assertNull(seen["coreutils/alpine-3.20.txt"])
	}
}
