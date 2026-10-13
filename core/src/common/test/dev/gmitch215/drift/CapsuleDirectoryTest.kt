package dev.gmitch215.drift

import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.CapsuleDirectory
import dev.gmitch215.drift.ci.DirectoryRead
import dev.gmitch215.drift.ci.Execution
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapsuleDirectoryTest {
    private val capsule = Capsule("c", listOf(Attribute("os.name", "linux", "test")))
    private val run = Run("c", RunOutcome.FAIL)
    private val execution = Execution("make test", 2, 1500, true)

    @Test
    fun writesAndReadsBackEveryFile() {
        val disk = MemoryCaseFiles()
        val all = CapsuleDirectory(capsule, run, execution, "last line")
        assertNull(CapsuleDirectories.write(disk, "base/", all))
        assertEquals(
            setOf("capsule.json", "run.json", "result.json", "tail.log"),
            disk.files.keys.map { it.removePrefix("base/") }.toSet(),
        )
        assertEquals("last line\n", disk.files["base/tail.log"])
        val read = CapsuleDirectories.read(disk::read, "base")
        assertEquals(all.copy(tail = null), assertIs<DirectoryRead.Loaded>(read).directory)
    }

    @Test
    fun capsuleOnlyDirectoryAndFilesThatAreNotOne() {
        val disk = MemoryCaseFiles()
        assertNull(CapsuleDirectories.write(disk, "d", CapsuleDirectory(capsule)))
        assertEquals(setOf("d/capsule.json"), disk.files.keys)
        val read = assertIs<DirectoryRead.Loaded>(CapsuleDirectories.read(disk::read, "d"))
        assertNull(read.directory.run)
        assertNull(read.directory.execution)
        assertNull(CapsuleDirectories.read(disk::read, "missing"))
    }

    @Test
    fun malformedFilesAreReportedNotThrown() {
        val disk = MemoryCaseFiles(mutableMapOf("d/capsule.json" to "{"))
        assertIs<DirectoryRead.Failed>(CapsuleDirectories.read(disk::read, "d"))
        disk.files["d/capsule.json"] = capsule.canonical()
        disk.files["d/result.json"] = "{\"schema\":2}"
        val failed = assertIs<DirectoryRead.Failed>(CapsuleDirectories.read(disk::read, "d"))
        assertTrue("unsupported result schema 2" in failed.reason, failed.reason)
    }

    @Test
    fun writeNamesTheFirstFileItCannotWrite() {
        val refusing = object : CaseFiles {
            override fun read(path: String): String? = null

            override fun write(path: String, text: String) = !path.endsWith("run.json")
        }
        val failed = CapsuleDirectories.write(refusing, "d", CapsuleDirectory(capsule, run))
        assertEquals("d/run.json", failed)
    }

    @Test
    fun executionRoundTripsAndRejectsBadJson() {
        val json = execution.toJson()
        assertEquals(execution, Execution.fromJson(json))
        assertFailsWith<JsonException> {
            Execution.fromJson(CanonicalJson.parse("{\"schema\":1}"))
        }
    }

    @Test
    fun tailKeepsTheLastLinesAndMasksSecrets() {
        val output = (1..10).joinToString("\n") { "line $it" } + "\nghp_" + "a".repeat(30) + "\n"
        val (tail, cut) = Execution.tail(output, 3)
        assertEquals("line 9\nline 10\n<redacted>", tail)
        assertTrue(cut)
        assertFalse("ghp_" in tail)
        val (whole, none) = Execution.tail("a\r\nb\r\n", 5)
        assertEquals("a\nb", whole)
        assertFalse(none)
    }

    @Test
    fun tailIsBoundedInCharacters() {
        val line = "x".repeat(1000)
        val (tail, cut) = Execution.tail(List(200) { line }.joinToString("\n"), 200)
        assertTrue(tail.length <= Execution.MAX_TAIL_CHARS)
        assertTrue(cut)
        assertTrue(tail.lines().all { it == line })
    }
}
