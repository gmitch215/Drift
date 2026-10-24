package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunProcessTest {
    private val java = File(System.getProperty("java.home"), "bin/java").path.replace('\\', '/')

    @Test
    fun runsARealProcessAndKeepsItsExitCode() {
        val disk = MemoryCaseFiles()
        val ok = DriftCommand(systemHost(), disk).test("run --out ok -- \"$java\" -version")
        assertEquals(0, ok.statusCode, ok.stderr)
        assertTrue("version" in ok.stdout, ok.stdout)
        assertTrue(Capsule.parse(disk.files.getValue("ok/capsule.json")).attributes.isNotEmpty())

        val bad = DriftCommand(systemHost(), disk)
            .test("run --out bad -- \"$java\" -cp . NoSuchClassForDrift")
        assertEquals(1, bad.statusCode, bad.stderr)
        val result = CanonicalJson.parse(disk.files.getValue("bad/result.json")).obj()
        assertEquals(1L, result.require("exitCode").long())
        assertTrue(disk.files.getValue("bad/tail.log").contains("NoSuchClassForDrift"))
    }

    @Test
    fun repeatedRealRunsRecordWallTimeAndNoCpuOrMemoryOnTheJvm() {
        val disk = MemoryCaseFiles()
        val result = DriftCommand(systemHost(), disk)
            .test("run --out rep --repeat 2 -- \"$java\" -version")
        assertEquals(0, result.statusCode, result.stderr)
        val run = Run.parse(disk.files.getValue("rep/run.json"))
        assertEquals(2, run.samples.size)
        assertTrue(run.samples.all { it.exitCode == 0 && it.wallMs > 0 })
        assertTrue(run.samples.all { it.userMs == null && it.peakKb == null })
        assertTrue(run.samples.all { it.systemMs == null })
        assertTrue("2 runs, 2 passed (100%)" in result.stderr, result.stderr)
    }
}
