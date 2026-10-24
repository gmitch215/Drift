package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.CaptureCommand
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.FakeSsh
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val FILES = mapOf(
    "/etc/os-release" to "ID=debian\nVERSION_ID=\"12\"\n",
    "/sys/fs/cgroup/cpu.max" to "max 100000\n",
)

private fun machine(zone: String) = FakeSsh(
    remoteFiles = FILES,
    remoteEnv = mapOf("TZ" to zone, "LANG" to "C.UTF-8"),
    remoteCommands = mapOf(
        listOf("uname", "-r") to CommandResult(0, "6.1.0-18\n"),
        listOf("getconf", "_NPROCESSORS_ONLN") to CommandResult(0, "8\n"),
        listOf("readlink", "/etc/localtime") to
            CommandResult(0, "/usr/share/zoneinfo/$zone\n"),
    ),
)

private const val CASE_DIR = ".drift/ssh/ci-green-ci-red"

class SshCommandTest {
    private val files = MemoryCaseFiles()

    private fun capture(base: FakeSsh, args: String) = CaptureCommand(base, files).test(args)

    private fun pair() = FakeSsh(
        machines = mapOf("ci-green" to machine("UTC"), "ci-red" to machine("Asia/Tokyo")),
    )

    private fun diagnose(base: FakeSsh, args: String) = DriftCommand(base, files).test(
        "diagnose $args",
    )

    @Test
    fun captureOverSshPrintsTheRemoteCapsuleAfterOneConsentLine() {
        val base = machine("UTC")
        val result = capture(base, "--ssh me@build-02 --label build-02")
        assertEquals(0, result.statusCode, result.output)
        val capsule = Capsule.parse(result.stdout.trim())
        assertEquals("build-02", capsule.label)
        assertEquals("ssh", capsule.attributes.single { it.path == "capture.transport" }.value)
        assertTrue(capsule.attributes.none { it.path.startsWith("kotlin.") })
        val consent = result.stderr.lines().single { it.isNotBlank() }
        assertTrue("read-only fact commands on me@build-02 over ssh" in consent, consent)
        assertTrue("no repository code" in consent && "no sudo" in consent, consent)
        assertTrue("stores no credentials" in consent, consent)
        assertEquals(base.remoteRan.first(), listOf("uname", "-sm"))
    }

    @Test
    fun theSameRemoteGivesTheSameCapsuleBytes() {
        val first = capture(machine("UTC"), "--ssh build-02").stdout
        val second = capture(machine("UTC"), "--ssh build-02").stdout
        assertEquals(first, second)
    }

    @Test
    fun outWritesTheCapsuleFileInsteadOfPrintingIt() {
        val result = capture(machine("UTC"), "--ssh build-02 --label b --out cap/b")
        assertEquals(0, result.statusCode, result.output)
        assertEquals("", result.stdout)
        assertTrue("wrote capsule to cap/b" in result.stderr)
        assertEquals("b", Capsule.parse(files.files.getValue("cap/b/capsule.json").trim()).label)
        val local = CaptureCommand(FakeHost(os = "linux"), files).test("--out cap/local")
        assertEquals(0, local.statusCode, local.output)
        assertTrue("cap/local/capsule.json" in files.files)
    }

    @Test
    fun toolsProbesAndProjectAreRefusedOverSshBeforeAnyCommandRuns() {
        for (flag in listOf("--tools", "--probes", "--project")) {
            val base = machine("UTC")
            val result = capture(base, "--ssh build-02 $flag")
            assertNotEquals(0, result.statusCode, flag)
            assertTrue("not available with --ssh in this version" in result.output, result.output)
            assertTrue(base.ran.isEmpty(), flag)
        }
    }

    @Test
    fun aBadHostIsRefusedBeforeSshRuns() {
        for (host in listOf("-oProxyCommand=id", "a;b", "\"a b\"", "me@-x", "a\$(id)")) {
            val base = machine("UTC")
            val result = capture(base, "--ssh=$host")
            assertNotEquals(0, result.statusCode, host)
            assertTrue("is not a host name" in result.output, result.output)
            assertTrue(base.ran.isEmpty(), host)
            assertFalse("running read-only" in result.stderr)
        }
    }

    @Test
    fun eachSshFailureExitsNonZeroWithAPlainMessage() {
        val cases = listOf(
            "No ED25519 host key is known for h and you have requested strict checking.\r\n" +
                "Host key verification failed.\r\n" to "add the key yourself",
            "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!\r\nHost key verification " +
                "failed.\r\n" to "may have been replaced or intercepted",
            "me@h: Permission denied (publickey).\r\n" to "never asks for a password",
            "ssh: connect to host h port 22: Connection refused\r\n" to "refused the connection",
            "ssh: connect to host h port 22: Operation timed out\r\n" to "timed out",
        )
        for ((text, expected) in cases) {
            val result = capture(FakeSsh(sshResult = CommandResult(255, text)), "--ssh h")
            assertEquals(1, result.statusCode, text)
            assertEquals("", result.stdout, text)
            assertTrue(expected in result.output, result.output)
        }
    }

    @Test
    fun aBuildThatCannotStartProgramsOrFindSshSaysSo() {
        val web = FakeSsh(capabilities = emptySet<Capability>())
        val webResult = capture(web, "--ssh h")
        assertEquals(1, webResult.statusCode)
        assertTrue("cannot start other programs" in webResult.output, webResult.output)
        val none = capture(FakeSsh(canRun = false), "--ssh h")
        assertEquals(1, none.statusCode)
        assertTrue("OpenSSH" in none.output, none.output)
    }

    @Test
    fun diagnoseOverSshComparesTwoMachinesAndWritesTheCapsulesAndCase() {
        val result = diagnose(pair(), "--ssh ci-green --ssh ci-red")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("tz.system-zone" in result.stdout, result.stdout)
        assertTrue("wrote" in result.stderr && CASE_DIR in result.stderr, result.stderr)
        assertTrue("No repository code ran" in result.stderr, result.stderr)
        assertTrue("$CASE_DIR/case.json" in files.files)
        val green = Capsule.parse(files.files.getValue("$CASE_DIR/capsules/passing/capsule.json"))
        val red = Capsule.parse(files.files.getValue("$CASE_DIR/capsules/failing/capsule.json"))
        assertEquals("ci-green", green.label)
        assertEquals("ci-red", red.label)
        assertEquals("UTC", green.attributes.single { it.path == "tz.system-zone" }.value)
        assertEquals("Asia/Tokyo", red.attributes.single { it.path == "tz.system-zone" }.value)
    }

    @Test
    fun diagnoseOverSshPrintsTextAndRunsAgainOverTheSameDirectory() {
        val first = diagnose(pair(), "--ssh ci-green --ssh ci-red --detail summary")
        assertEquals(0, first.statusCode, first.output)
        assertTrue("tz.system-zone" in first.stdout, first.stdout)
        val again = diagnose(pair(), "--ssh ci-green --ssh ci-red --detail summary")
        assertEquals(0, again.statusCode, again.output)
        assertEquals(first.stdout, again.stdout)
    }

    @Test
    fun anExplicitCaseDirectoryIsUsedAndAnExistingCaseIsRefused() {
        val result = diagnose(pair(), "--ssh ci-green --ssh ci-red --case out/pair")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("out/pair/case.json" in files.files)
        assertFalse(files.files.keys.any { it.startsWith(".drift/") })
        val again = diagnose(pair(), "--ssh ci-green --ssh ci-red --case out/pair")
        assertEquals(1, again.statusCode)
        assertTrue("already holds a case" in again.output, again.output)
    }

    @Test
    fun diagnoseOverSshNeedsExactlyTwoHostsAndNothingElse() {
        val bad = listOf(
            "--ssh ci-green",
            "--ssh a --ssh b --ssh c",
            "--ssh a --ssh b cap.json",
            "--ssh a --ssh b --against base",
            "--ssh a --ssh b --set",
        )
        for (args in bad) {
            val base = pair()
            val result = diagnose(base, args)
            assertNotEquals(0, result.statusCode, args)
            assertTrue("--ssh" in result.output, "$args\n${result.output}")
            assertTrue(base.ran.isEmpty(), args)
        }
    }

    @Test
    fun aFailingSecondMachineStopsTheComparisonWithItsReason() {
        val base = FakeSsh(
            machines = mapOf(
                "ci-green" to machine("UTC"),
                "ci-red" to FakeSsh(
                    sshResult = CommandResult(255, "me@h: Permission denied (publickey).\n"),
                ),
            ),
        )
        val result = diagnose(base, "--ssh ci-green --ssh ci-red")
        assertEquals(1, result.statusCode)
        assertTrue("without a prompt" in result.output, result.output)
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun theFlagsAppearInHelp() {
        val capture = DriftCommand(FakeHost()).test("capture --help").output
        val diagnose = DriftCommand(FakeHost()).test("diagnose --help").output
        assertTrue("--ssh" in capture && "--out" in capture, capture)
        assertTrue("--ssh" in diagnose, diagnose)
    }
}
