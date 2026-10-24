package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.command.RunCommand
import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private class CgroupHost(
    private val clock: TestTimeSource,
    private val walls: List<Long>,
    private val throttledPerRun: List<Long>?,
) : Host {
    private var calls = 0
    private var throttled = 0L

    override val platform = "fake"
    override val capabilities = Capability.entries.toSet()
    override val os = "linux"
    override val arch = "x86_64"

    override fun env() = mapOf("TZ" to "UTC")

    override fun readText(path: String): String? = when {
        throttledPerRun == null -> null
        path == "/proc/self/cgroup" -> "0::/\n"
        path == "/sys/fs/cgroup/cpu.stat" -> "nr_periods 9\nnr_throttled $throttled\n"
        else -> null
    }

    override fun run(argv: List<String>): CommandResult? = run(argv, null) {}

    override fun run(
        argv: List<String>,
        timeoutMillis: Long?,
        onOutput: (String) -> Unit,
    ): CommandResult? {
        if (argv != listOf("t")) return CommandResult(127, "")
        clock += walls[calls].milliseconds
        throttled += throttledPerRun?.get(calls) ?: 0
        calls++
        return CommandResult(0, "")
    }
}

class CompareCommandTest {
    private val disk = MemoryCaseFiles()

    private fun record(name: String, walls: List<Long>, throttled: List<Long>? = null): String {
        val clock = TestTimeSource()
        val host = CgroupHost(clock, walls, throttled)
        val result = RunCommand(host, disk, clock)
            .test("--out $name --label $name --repeat ${walls.size} -- t")
        assertEquals(0, result.statusCode, result.stderr)
        return name
    }

    private fun compare(args: String) =
        DriftCommand(FakeHost(canRun = false, files = disk.files.toMap()), disk)
            .test("compare $args")

    private val fast = listOf(100L, 100, 101, 102, 103)
    private val slow = listOf(249L, 250, 250, 251, 252)

    @Test
    fun printsRangesTheExactTestTheEffectSizeAndTheInterval() {
        record("fast", fast)
        record("slow", slow)
        val result = compare("fast slow")
        assertEquals(0, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals("A: fast (fast)", lines[0])
        assertEquals("B: slow (slow)", lines[1])
        assertEquals("Wall time (ms): A 5 samples, B 5 samples", lines[2])
        assertEquals("  A: min 100, median 101, max 103", lines[3])
        assertEquals("  B: min 249, median 250, max 252", lines[4])
        assertEquals("  ratio of medians, B over A: 2.5", lines[5])
        assertEquals("  Mann-Whitney U 0; exact p 0.0079", lines[6])
        assertEquals(
            "  Cliff's delta +1000 permille (large; positive means B is larger)",
            lines[7],
        )
        assertEquals(
            "  bootstrap interval of the ratio, 2000 resamples, 95 percent: 2.4 to 2.5",
            lines[8],
        )
        assertEquals(
            "  B is 2.5 times slower than A; exact p 0.0079; " +
                "the interval of the ratio of B to A is 2.4 to 2.5.",
            lines[9],
        )
        assertEquals(10, lines.size - 1, result.stdout)
        assertFalse(result.stdout.contains("CPU time"))
        assertFalse(result.stdout.contains("throttl"))
    }

    @Test
    fun theSameStoredInputsGiveTheSameBytesAndSwappingThemFlipsTheDirection() {
        record("fast", fast)
        record("slow", slow)
        assertEquals(compare("fast slow").stdout, compare("fast slow").stdout)
        assertEquals(compare("fast slow --json").stdout, compare("fast slow --json").stdout)
        val back = compare("slow fast").stdout
        assertTrue("A is 2.5 times slower than B; exact p 0.0079" in back, back)
    }

    @Test
    fun theSameCommandTwiceSaysThereIsNoDifference() {
        record("one", listOf(110, 110, 111, 110, 110))
        record("two", listOf(110, 100, 110, 111, 110))
        val text = compare("one two").stdout
        assertTrue("No difference larger than the run to run spread" in text, text)
        assertFalse("times slower" in text)
    }

    @Test
    fun jsonCarriesTheSameNumbers() {
        record("fast", fast)
        record("slow", slow)
        val json = CanonicalJson.parse(compare("fast slow --json").stdout).obj()
        assertEquals("fast", json.require("runA").string())
        val wall = json.require("metrics").array().single().obj()
        assertEquals("Wall time", wall.require("name").string())
        assertEquals(2475L, wall.require("ratioPermille").long())
        assertEquals(2L, wall.require("exactExtreme").long())
        assertEquals(252L, wall.require("exactTotal").long())
        assertEquals(1000L, wall.require("deltaPermille").long())
        assertEquals("large", wall.require("deltaLabel").string())
        assertEquals("second larger", wall.require("direction").string())
        assertEquals(2427L, wall.require("intervalLowPermille").long())
    }

    @Test
    fun saysWhenOnlyOneSideWasThrottled() {
        record("calm", fast, throttled = List(5) { 0L })
        record("busy", slow, throttled = listOf(4L, 3, 5, 0, 0))
        val text = compare("calm busy").stdout
        assertTrue("CPU throttled 12 times in B, none in A." in text, text)
        assertTrue(
            "Throttling by the container is a cause to check before blaming the code." in text,
        )
        val same = record("calm2", fast, throttled = List(5) { 0L })
        assertFalse("throttl" in compare("calm $same").stdout)
    }

    @Test
    fun runRecordsTheCgroupDeltaOfEachRunOnlyWhenItWasReadable() {
        record("busy", slow, throttled = listOf(4L, 0, 1, 0, 2))
        val read = Run.parse(disk.files.getValue("busy/run.json")).samples
        assertEquals(listOf(4L, 0L, 1L, 0L, 2L), read.map { it.cgroup?.throttled })
        record("plain", slow)
        val absent = Run.parse(disk.files.getValue("plain/run.json")).samples
        assertTrue(absent.all { it.cgroup == null })
        assertFalse("cgroup" in disk.files.getValue("plain/run.json"))
        assertNull(absent.first().cgroup)
    }

    @Test
    fun refusesWhatItCannotCompare() {
        record("fast", fast)
        val missing = compare("fast nowhere")
        assertEquals(1, missing.statusCode)
        assertTrue("nowhere is not a capsule directory" in missing.stderr, missing.stderr)
        val clock = TestTimeSource()
        RunCommand(CgroupHost(clock, listOf(5), null), disk, clock).test("--out single -- t")
        val single = compare("fast single")
        assertEquals(1, single.statusCode)
        assertTrue("single has 0 samples; compare needs at least 2" in single.stderr, single.stderr)
        assertTrue(compare("fast").statusCode != 0)
    }
}
