package dev.gmitch215.drift.bench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TriageDataTest {
    private val root = Path.of(".")

    @Test
    fun theCommittedJsonEqualsARegeneration() {
        assertEquals(TriageData(root).generate(), root.resolve("triage/triage.json").readText())
    }

    @Test
    fun everyFixtureBodyHasAnItem() {
        val dir = root.toAbsolutePath().normalize().resolveSibling("fixtures").resolve("triage")
        val bodies = Files.list(dir).use { s ->
            s.map { it.fileName.toString() }.filter { it.endsWith(".md") }.count()
        }
        assertEquals(bodies, TriageData(root).items().size.toLong())
    }

    @Test
    fun onlyFixtureItemsAreAccepted() {
        val tmp = Files.createTempDirectory("triage-bench")
        Files.createDirectories(tmp.resolve("triage"))
        tmp.resolve("triage/items.yml").writeText(
            "schema: 1\nitems:\n  - id: x\n    source: corpus\n    body: y\n",
        )
        val e = assertFailsWith<IllegalArgumentException> { TriageData(tmp).items() }
        assertTrue(e.message.orEmpty().contains("only fixture items"))
    }

    @Test
    fun duplicateIdsAreRefused() {
        val tmp = Files.createTempDirectory("triage-bench")
        val bench = Files.createDirectories(tmp.resolve("bench/triage"))
        val fixtures = Files.createDirectories(tmp.resolve("fixtures/triage"))
        fixtures.resolve("y.md").writeText("body")
        fixtures.resolve("y.yml").writeText("facts: []\nclaims: []\n")
        val item = "  - id: x\n    source: fixture\n    body: y\n"
        bench.resolve("items.yml").writeText("schema: 1\nitems:\n$item$item")
        val e =
            assertFailsWith<IllegalArgumentException> { TriageData(tmp.resolve("bench")).items() }
        assertTrue(e.message.orEmpty().contains("duplicate"))
    }
}
