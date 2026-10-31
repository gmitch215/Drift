package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.DemoFixtures
import dev.gmitch215.drift.fixtures.DemoGolden
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.scan.why.WhyFlow
import dev.gmitch215.drift.why.Explain
import dev.gmitch215.drift.why.Inputs
import dev.gmitch215.drift.why.PlainView
import dev.gmitch215.drift.why.Sides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ERROR = "TypeError: Object.groupBy is not a function"

class DemoCommandTest {
    private val host = FakeHost(canRun = false)
    private val disk = MemoryCaseFiles()

    private fun demo() = DriftCommand(host, disk).test("demo")

    private fun block(out: String, from: String, to: String) =
        out.substringAfter(from).substringBefore(to).trim() + "\n"

    @Test
    fun printsTheGolden() {
        val result = demo()
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("", result.stderr)
        assertEquals(DemoGolden.text("demo.txt"), result.stdout)
    }

    @Test
    fun neverTouchesTheHostOrTheDisk() {
        demo()
        assertTrue(host.ran.isEmpty())
        assertTrue(disk.files.isEmpty(), disk.files.keys.toString())
    }

    @Test
    fun stepOneIsWhatWhyErrorPrints() {
        val real = DriftCommand(FakeHost(), MemoryCaseFiles()).test("why --error \"$ERROR\"")
        val shown = block(demo().stdout, "\n\$ drift why --error \"$ERROR\"\n", "\nStep 2")
        assertEquals(real.stdout, shown)
    }

    @Test
    fun stepTwoIsWhatWhyReplyPrints() {
        val files = MemoryCaseFiles()
        DriftCommand(FakeHost(), files).test("why --error \"$ERROR\"")
        val stdin = FakeHost(files = mapOf("/dev/stdin" to DemoFixtures.text("fails.json")))
        val real = DriftCommand(stdin, files).test("why --reply")
        val out = demo().stdout
        val command = "\n\$ drift why --save there.json && drift why --reply < there.json\n"
        val shown = block(out, command, "\nStep 3")
        assertEquals(real.stdout, shown)
    }

    @Test
    fun stepThreeHoldsTheVerdictOfTheEngine() {
        val works = Capsule.parse(DemoFixtures.text("works.json")).copy(label = "here")
        val there = Capsule.parse(DemoFixtures.text("fails.json")).copy(label = "there.json")
        val answer = Explain.run(
            Inputs(
                works,
                there,
                Sides.HERE_THERE,
                failure = WhyFlow.symptoms(ERROR),
                errorText = true,
            ),
        )
        val verdict = PlainView.of("x", answer).verdict
        val flat = demo().stdout.replace(Regex("\\s+"), " ")
        assertTrue(verdict.replace(Regex("\\s+"), " ") in flat, verdict)
    }

    @Test
    fun theCommandIsListedAndTakesNoArgument() {
        assertTrue("demo" in DriftCommand(host, disk).test("--help").stdout)
        assertEquals(1, DriftCommand(host, disk).test("demo extra").statusCode)
    }
}
