package dev.gmitch215.drift

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.Tar
import dev.gmitch215.drift.case.TarBadHeader
import dev.gmitch215.drift.case.TarBadText
import dev.gmitch215.drift.case.TarDuplicate
import dev.gmitch215.drift.case.TarNotCanonical
import dev.gmitch215.drift.case.TarOversized
import dev.gmitch215.drift.case.TarRead
import dev.gmitch215.drift.case.TarTruncated
import dev.gmitch215.drift.case.TarUnsafePath
import dev.gmitch215.drift.case.TarUnsupported
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.lab.Archive
import dev.gmitch215.drift.lab.ArchiveOpen
import dev.gmitch215.drift.lab.ArchivePack
import dev.gmitch215.drift.lab.ReportHtml
import dev.gmitch215.drift.lab.SolveCertificate
import dev.gmitch215.drift.lab.Verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveTest {
	private fun packed(case: CaseFile) = assertIs<ArchivePack.Packed>(Archive.pack(case))

	private fun read(bytes: ByteArray) = Tar.read(bytes)

	private fun refusal(bytes: ByteArray) = assertIs<TarRead.Failed>(read(bytes)).problem

	private fun fixChecksum(bytes: ByteArray, at: Int) {
		for (i in 148 until 156) bytes[at + i] = ' '.code.toByte()
		var sum = 0
		for (i in 0 until 512) sum += bytes[at + i].toInt() and 0xff
		val text = sum.toString(8).padStart(6, '0')
		text.encodeToByteArray().copyInto(bytes, at + 148)
		bytes[at + 154] = 0
		bytes[at + 155] = ' '.code.toByte()
	}

	private fun patched(bytes: ByteArray, at: Int, text: String, header: Int = 0): ByteArray {
		val copy = bytes.copyOf()
		text.encodeToByteArray().copyInto(copy, header + at)
		fixChecksum(copy, header)
		return copy
	}

	private val external = listOf(
		"<script",
		"<link",
		"<img",
		"<iframe",
		"src=",
		"href=",
		"http://",
		"https://",
		"@import",
		"url(",
	)

	private val small = Tar.write(mapOf("xx/yyyy" to "hello", "zz.txt" to "a\nb"))

	// #region tar

	@Test
	fun theTarHasFixedHeadersAndSortedNames() {
		val names = listOf("b.txt", "a/c.txt", "a.txt")
		val bytes = Tar.write(names.associateWith { "x" })
		val entries = assertIs<TarRead.Entries>(read(bytes)).files
		assertEquals(names.sorted(), entries.keys.toList())
		assertEquals("0000644", bytes.decodeToString(100, 107))
		assertEquals("0000000", bytes.decodeToString(108, 115))
		assertEquals("0000000", bytes.decodeToString(116, 123))
		assertEquals("00000000000", bytes.decodeToString(136, 147))
		assertEquals("ustar", bytes.decodeToString(257, 262))
		assertEquals(0, bytes.size % 512)
		assertEquals(1024, bytes.takeLast(1024).count { it.toInt() == 0 })
	}

	@Test
	fun writingReadingAndWritingAgainGivesTheSameBytes() {
		val files = mapOf(
			"one" to "",
			"two/three" to "\u00e9\u20ac text\r\n",
			"z" to "x".repeat(1500),
		)
		val bytes = Tar.write(files)
		val back = assertIs<TarRead.Entries>(read(bytes)).files
		assertEquals(files, back)
		assertTrue(Tar.write(back).contentEquals(bytes))
	}

	@Test
	fun aLongPathIsStoredWithItsPrefixAndPathsThatCannotBeStoredAreRefused() {
		val long = "d".repeat(60) + "/" + "e".repeat(60) + "/" + "f".repeat(40)
		val back = assertIs<TarRead.Entries>(read(Tar.write(mapOf(long to "x")))).files
		assertEquals(setOf(long), back.keys)
		assertNull(Tar.problem("a/b.txt"))
		val unsafe = listOf("", "/abs", "a/../b", "..", "a//b", "a/", "a\\b", "a\nb", "\u00e9")
		for (bad in unsafe + "x".repeat(300)) {
			assertIs<TarUnsafePath>(Tar.problem(bad), bad)
		}
	}

	@Test
	fun everyTruncationIsATypedProblem() {
		for (n in small.indices) {
			val problem = refusal(small.copyOf(n))
			assertTrue(
				problem is TarTruncated || problem is TarBadHeader,
				"$n bytes: ${problem.message()}",
			)
		}
	}

	@Test
	fun everySingleBitFlipOfASmallArchiveIsRefusedOrChangesAnEntry() {
		val original = assertIs<TarRead.Entries>(read(small)).files
		for (i in small.indices) {
			for (bit in 0 until 8) {
				val copy = small.copyOf()
				copy[i] = (copy[i].toInt() xor (1 shl bit)).toByte()
				val result = read(copy)
				if (result is TarRead.Entries) {
					assertNotEquals(
					original,
					result.files,
					"byte $i bit $bit",
				)
				}
			}
		}
	}

	@Test
	fun hostileEntriesAreTypedProblems() {
		assertIs<TarUnsafePath>(refusal(patched(small, 0, "../yyyy")))
		assertIs<TarUnsafePath>(refusal(patched(small, 0, "/x/yyyy")))
		assertIs<TarOversized>(refusal(patched(small, 124, "77777777777")))
		val link = patched(small, 156, "2")
		assertEquals('2', assertIs<TarUnsupported>(refusal(link)).type)
		assertIs<TarBadHeader>(refusal(small.copyOf().also { it[0] = (it[0] + 1).toByte() }))
		assertIs<TarBadHeader>(refusal(patched(small, 257, "ustaX")))
		assertIs<TarBadHeader>(refusal(patched(small, 124, "00000000x00")))
		assertIs<TarNotCanonical>(refusal(patched(small, 136, "00000000001")))
		assertIs<TarNotCanonical>(refusal(patched(small, 100, "0000755")))
		assertIs<TarNotCanonical>(refusal(small + ByteArray(512)))
		val text = small.copyOf().also { it[512] = 0xff.toByte() }
		assertIs<TarBadText>(refusal(text))
	}

	@Test
	fun aDuplicateNameIsRefused() {
		val one = Tar.write(mapOf("a.txt" to "hello"))
		val body = one.copyOf(one.size - 1024)
		val twice = body + one
		assertEquals("a.txt", assertIs<TarDuplicate>(refusal(twice)).path)
	}

	@Test
	fun anArchiveWithTooManyEntriesIsRefused() {
		val files = (0..Tar.MAX_ENTRIES).associate { "f$it" to "" }
		assertIs<TarOversized>(refusal(Tar.write(files)))
	}

	@Test
	fun seededMutationsNeverThrow() {
		var seed = 12345L
		fun next(): Int {
			seed = (seed * 6364136223846793005L + 1442695040888963407L)
			return ((seed ushr 33) and 0x7fffffff).toInt()
		}
		val bytes = packed(LabCases.confirmed).bytes
		repeat(20) {
			val copy = bytes.copyOf()
			repeat(1 + next() % 3) {
				val i = next() % copy.size
				copy[i] = (copy[i].toInt() xor (1 shl (next() % 8))).toByte()
			}
			assertFalse(Verify.open(copy).ok, "a mutated archive cannot verify")
			val cut = next() % bytes.size
			assertFalse(Verify.open(bytes.copyOf(cut)).ok)
		}
	}

	// #endregion

	// #region archive

	@Test
	fun packReadAndRepackGiveIdenticalBytesForEveryVerdictClass() {
		for ((name, case) in LabCases.all) {
			val first = packed(case)
			val opened = assertIs<ArchiveOpen.Opened>(Archive.open(first.bytes), name)
			assertEquals(first.case.files, opened.case.files, name)
			assertTrue(packed(opened.case).bytes.contentEquals(first.bytes), name)
			assertEquals(emptyList(), opened.case.check(), name)
		}
	}

	@Test
	fun theArchiveHoldsTheDocumentedLayout() {
		val files = packed(LabCases.confirmed).case.files
		assertEquals(files.keys.sorted(), files.keys.toList())
		val dirs = listOf(
			"observations/",
			"environments/",
			"hypotheses/",
			"experiments/",
			"results/",
			"eliminations/",
			"confirmed/",
			"reproduce/",
		)
		for (dir in dirs) {
			assertTrue(files.keys.any { it.startsWith(dir) }, dir)
		}
		for (file in listOf("certificate.json", "report.html", "manifest.json", "case.json")) {
			assertTrue(file in files, file)
		}
		assertTrue(files.keys.none { it.startsWith("arms/") })
		assertTrue(files.keys.any { it.startsWith("reproduce/x1-control/") })
		assertTrue("reproduce/x1-control/Dockerfile" in files)
		assertTrue("reproduce/x1-control/run.sh" in files)
		assertTrue("reproduce/x1-control/manifest.json" in files)
	}

	@Test
	fun theConfirmedRecordExistsOnlyForAConfirmedVerdict() {
		for ((name, case) in LabCases.all) {
			val files = packed(case).case.files
			val confirmed = name == "confirmed" || name == "bundle"
			assertEquals(confirmed, Archive.CONFIRMED in files, name)
		}
		val record = CanonicalJson.parse(
			packed(LabCases.confirmed).case.text(Archive.CONFIRMED)!!,
		) as JsonObject
		assertEquals(JsonString("confirmed"), record["verdict"])
		assertTrue("env.TZ" in CanonicalJson.encode(record.fields.getValue("minimalSet")))
	}

	@Test
	fun packRefusesWhatIsNotASolvedOrIntactCase() {
		val files = LabCases.confirmed.files.toMutableMap()
		files.remove(SolveCertificate.CERTIFICATE)
		files[CaseFile.MANIFEST] = CaseFile.manifest(files)
		val missing = assertIs<ArchivePack.Failed>(Archive.pack(CaseFile(files)))
		assertTrue("not a solved case" in missing.message)
		val edited = LabCases.confirmed.files.toMutableMap()
		val path = edited.keys.sorted().first {
			it.startsWith("results/0") && "\"fail\"" in edited.getValue(it)
		}
		edited[path] = edited.getValue(path).replace("\"fail\"", "\"pass\"")
		val tampered = assertIs<ArchivePack.Failed>(Archive.pack(CaseFile(edited)))
		assertTrue("failed its checks" in tampered.message)
	}

	@Test
	fun theFixedSolveGivesAPinnedArchiveAndReportOnEveryTarget() {
		val p = packed(LabCases.confirmed)
		assertEquals(GOLDEN_ARCHIVE, Sha256.hex(p.bytes), "archive bytes changed")
		assertEquals(
			GOLDEN_REPORT,
			Sha256.hex(p.case.text(Archive.REPORT)!!),
			"report bytes changed",
		)
	}

	// #endregion

	// #region report

	@Test
	fun theReportIsStaticSelfContainedAndDeterministic() {
		for ((name, case) in LabCases.all) {
			val certificate = SolveCertificate.certificate(case)!!
			val html = ReportHtml.of(certificate)
			assertEquals(html, ReportHtml.of(certificate), name)
			for (banned in external) {
				assertFalse(banned in html, "$name holds $banned")
			}
			assertTrue(html.startsWith("<!DOCTYPE html>\n"))
			assertTrue("<details open>" in html && "<details>" in html)
			assertTrue(Verify.NOT_RERUN in html)
			val verdict = certificate.fields.getValue("verdict") as JsonObject
			assertTrue((verdict["label"] as JsonString).value in html, name)
			assertTrue(html.all { it == '\n' || it.code in 0x20..0x7e }, "$name is not plain ASCII")
		}
	}

	@Test
	fun theReportShowsTheCountsPValuesPosteriorsAndWhatWasNotMirrored() {
		val html = ReportHtml.of(SolveCertificate.certificate(LabCases.bundle)!!)
		val shown = listOf(
			"Posterior sequence",
			"Preregistered rules",
			"What was not mirrored",
			"Arms and their capsule differences",
			"Experiments, raw counts",
			"Exact one-sided p",
			"tool.libc.version",
			"Not isolated",
		)
		for (needle in shown) {
			assertTrue(needle in html, needle)
		}
		assertTrue(Regex("<td>\\d+ of \\d+ \\(").containsMatchIn(html))
	}

	@Test
	fun textInTheCertificateIsEscaped() {
		val certificate = SolveCertificate.certificate(LabCases.confirmed)!!
		val hostile = "<script>alert(1)</script> & \"q\" 'a'"
		val edited = JsonObject(certificate.fields + ("name" to JsonString(hostile)))
		val html = ReportHtml.of(edited)
		assertFalse("<script>" in html)
		assertTrue("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;q&quot; &#39;a&#39;" in html)
	}

	@Test
	fun aCertificateMissingAFieldIsATypedErrorNotACrash() {
		val certificate = SolveCertificate.certificate(LabCases.confirmed)!!
		val broken = JsonObject(certificate.fields - "experiments")
		assertFailsWith<JsonException> { ReportHtml.of(broken) }
	}

	// #endregion

	private companion object {
		const val GOLDEN_ARCHIVE =
			"da4b7fbab923dff3e0ef155a4e8db51d" + "b0d703d74e5094fca409f4ffb9475ed6"
		const val GOLDEN_REPORT =
			"5c440e873d266f7389a4f5533a110865" + "924c3e66c6ee454948c68c3674bc896d"
	}
}
