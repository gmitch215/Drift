package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BisectCommandTest {
    private fun capsule(label: String, red: Boolean) = Capsule(
        label,
        listOf(
            Attribute("tool.node.version", if (red) "22" else "20", "t"),
            Attribute("env.TZ", if (red) "UTC" else "Europe/Paris", "t"),
            Attribute("kernel.release", if (red) "6.8" else "6.5", "t"),
            Attribute("os.name", "linux", "t"),
        ),
    )

    private val host = FakeHost(
        canRun = false,
        files = mapOf(
            "green.json" to capsule("green", false).canonical(),
            "red.json" to capsule("red", true).canonical(),
            "same.json" to capsule("same", false).canonical(),
            "bad.json" to "{",
        ),
    )
    private val disk = MemoryCaseFiles()

    private fun drift(args: String) = DriftCommand(host, disk).test(args)

    @Test
    fun planWritesTheFileAndPrintsTheFirstStep() {
        val result = drift("bisect plan green.json red.json --out plan.json")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("wrote a plan over 3 attributes to plan.json\n", result.stderr)
        assertTrue(result.stdout.startsWith("step 1: in the green environment"), result.stdout)
        assertTrue("then: drift bisect apply plan.json --step 1 --green" in result.stdout)
        assertTrue(disk.files.getValue("plan.json").endsWith("\n"))
    }

    @Test
    fun applyWalksToTheMinimalSet() {
        var last = drift("bisect plan green.json red.json --out plan.json")
        var step = 1
        while (last.stdout.startsWith("step ")) {
            val culprit = "tool.node.version" in last.stdout
            val outcome = if (culprit) "--red" else "--green"
            last = drift("bisect apply plan.json --step $step $outcome")
            step++
            assertTrue(step < 12)
        }
        assertEquals(0, last.statusCode, last.stderr)
        assertTrue(last.stdout.startsWith("minimal set:"), last.stdout)
        assertTrue("tool.node.version: 20 -> 22" in last.stdout, last.stdout)
    }

    @Test
    fun planBytesAreStableAcrossRuns() {
        drift("bisect plan green.json red.json --out a.json")
        drift("bisect plan green.json red.json --out b.json")
        assertEquals(disk.files["a.json"], disk.files["b.json"])
    }

    @Test
    fun planRefusals() {
        assertEquals(0, drift("bisect plan green.json red.json --out plan.json").statusCode)
        val again = drift("bisect plan green.json red.json --out plan.json")
        assertTrue("plan file already exists" in again.stderr, again.stderr)
        assertEquals(0, drift("bisect plan green.json red.json --out plan.json --force").statusCode)
        val none = drift("bisect plan green.json same.json --out none.json")
        assertTrue("no static attribute differences" in none.stderr, none.stderr)
        assertFalse(disk.files.containsKey("none.json"))
        assertTrue(
            "invalid capsule file bad.json" in
                drift("bisect plan bad.json red.json --out x.json").stderr,
        )
    }

    @Test
    fun applyRefusals() {
        drift("bisect plan green.json red.json --out plan.json")
        assertEquals(1, drift("bisect apply plan.json --step 1").statusCode)
        assertEquals(1, drift("bisect apply plan.json --step 1 --red --green").statusCode)
        val wrong = drift("bisect apply plan.json --step 3 --red")
        assertTrue("the pending step is 1, not 3" in wrong.stderr, wrong.stderr)
        val missing = drift("bisect apply nope.json --step 1 --red")
        assertTrue("cannot read plan file: nope.json" in missing.stderr, missing.stderr)
        disk.files["bad.json"] = "{"
        val broken = drift("bisect apply bad.json --step 1 --red")
        assertTrue("invalid plan file bad.json" in broken.stderr)
    }

    @Test
    fun applyingTheSameOutcomeTwiceChangesNothing() {
        drift("bisect plan green.json red.json --out plan.json")
        val first = drift("bisect apply plan.json --step 1 --red")
        val saved = disk.files.getValue("plan.json")
        val second = drift("bisect apply plan.json --step 1 --red")
        assertEquals(first.stdout, second.stdout)
        assertEquals(saved, disk.files.getValue("plan.json"))
        assertTrue("already holds red" in drift("bisect apply plan.json --step 1 --green").stderr)
    }
}
