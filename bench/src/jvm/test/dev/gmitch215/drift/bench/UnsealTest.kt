package dev.gmitch215.drift.bench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnsealTest {
    private class Scratch {
        val root: Path = Files.createTempDirectory("bench-unseal")
        val work = Work(root)

        init {
            val source = Path.of(".")
            source.resolve("data").toFile().copyRecursively(root.resolve("data").toFile())
            val runner = root.resolve("runner").createDirectories()
            for (name in listOf("harness.sh", "run-batch.sh", "native.sh")) {
                runner.resolve(name).writeText(source.resolve("runner/$name").readText())
            }
        }

        fun file(name: String): Path = root.resolve("data").resolve(name)
    }

    private fun refusal(block: () -> Unit): String =
        assertFailsWith<UnsealException>(block = block).message!!

    @Test
    fun aDryRunChecksTheFreezeAndWritesNothing() {
        val s = Scratch()
        s.work.writeFreeze("abc123")
        val unsealed = s.work.unseal("abc123", "tester", "2026-01-01T00:00:00Z", dryRun = true)
        assertEquals(s.work.split().test, unsealed.test)
        assertEquals(emptyList(), s.work.unsealLines())
    }

    @Test
    fun aChangedHashRefusesAndNamesTheField() {
        val s = Scratch()
        s.work.writeFreeze("abc123")
        val file = s.file("calibration/evaluation.json")
        file.writeText(file.readText() + " ")
        val message = refusal { s.work.unseal("abc123", "tester", "t", dryRun = true) }
        assertTrue(message.startsWith("unseal refused: "), message)
        assertTrue(message.contains("evaluation_sha256 (frozen "), message)
        assertTrue(message.contains("engine_calibration"), message)
        assertFalse(message.contains("fit_sha256"), message)
        assertEquals(emptyList(), s.work.unsealLines())
    }

    @Test
    fun aChangedScenarioListRefuses() {
        val s = Scratch()
        s.work.writeFreeze("abc123")
        val file = s.file("scenarios.json")
        file.writeText(file.readText() + "\n")
        assertTrue(refusal { s.work.unseal("c", "o", "t") }.contains("scenarios_sha256"))
    }

    @Test
    fun noFreezeRefuses() {
        val s = Scratch()
        assertTrue(refusal { s.work.unseal("c", "o", "t") }.contains("freeze.json"))
    }

    @Test
    fun theUnsealIsLoggedOnceAndOpensTheTestScenarios() {
        val s = Scratch()
        s.work.writeFreeze("abc123")
        val before = Files.createTempDirectory("bench-before")
        assertTrue(refusal { s.work.openUnsealed() }.contains("unseal first"))
        assertFailsWith<SealedException> {
            s.work.prepare(before, s.work.split().test.take(1).toSet())
        }
        val unsealed = s.work.unseal("abc123", "tester", "2026-01-01T00:00:00Z")
        assertEquals(listOf("2026-01-01T00:00:00Z\tabc123\ttester"), s.work.unsealLines())
        assertEquals(unsealed.test, s.work.openUnsealed().test)
        assertTrue(refusal { s.work.unseal("abc123", "tester", "later") }.contains("already"))
        assertTrue(refusal { s.work.writeFreeze("abc123") }.contains("already"))
        assertEquals(1, s.work.unsealLines().size)

        val out = Files.createTempDirectory("bench-test-run")
        val ids = s.work.prepare(out, emptySet(), unsealed)
        assertEquals(unsealed.test.sorted(), ids.sorted())
        assertEquals(
            ids.sorted(),
            out.listDirectoryEntries().filter { Files.isDirectory(it) }.map { it.name }.sorted(),
        )
        assertFailsWith<SealedException> {
            s.work.prepare(out, setOf(s.work.devIds().first()), unsealed)
        }
    }

    @Test
    fun aFreezeMovedAfterTheUnsealBlocksTheTestRun() {
        val s = Scratch()
        s.work.writeFreeze("abc123")
        s.work.unseal("abc123", "tester", "t")
        val file = s.file("split.json")
        file.writeText(file.readText() + "\n")
        assertTrue(refusal { s.work.openUnsealed() }.contains("split_sha256"))
    }
}
