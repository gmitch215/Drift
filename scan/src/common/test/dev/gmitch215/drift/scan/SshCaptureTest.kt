package dev.gmitch215.drift.scan

import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.FakeSsh
import dev.gmitch215.drift.host.SshHost
import dev.gmitch215.drift.host.SshTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LINUX_FILES = mapOf(
    "/etc/os-release" to "ID=debian\nVERSION_ID=\"12\"\nPRETTY_NAME=\"Debian 12\"\n",
    "/sys/fs/cgroup/cpu.max" to "200000 100000\n",
    "/sys/fs/cgroup/memory.max" to "4294967296\n",
    "/proc/self/cgroup" to "0::/user.slice\n",
    "/proc/cmdline" to "BOOT_IMAGE=/vmlinuz quiet\n",
)

private val LINUX_COMMANDS = mapOf(
    listOf("uname", "-r") to CommandResult(0, "6.1.0-18\n"),
    listOf("getconf", "_NPROCESSORS_ONLN") to CommandResult(0, "8\n"),
    listOf("readlink", "/etc/localtime") to CommandResult(0, "/usr/share/zoneinfo/Asia/Tokyo\n"),
)

private fun linux(env: Map<String, String> = mapOf("TZ" to "Asia/Tokyo", "LANG" to "C.UTF-8")) =
    FakeSsh(remoteFiles = LINUX_FILES, remoteEnv = env, remoteCommands = LINUX_COMMANDS)

private fun capture(base: FakeSsh, label: String = "remote", keep: Boolean = false) =
    SshHost.open(base, SshTarget.parse("me@build-02")!!).let { host ->
        try {
            Capture.run(host, label, keepIdentifiers = keep, transport = "ssh")
        } finally {
            host.close()
        }
    }

class SshCaptureTest {
    @Test
    fun aRemoteCapsuleHoldsTheRemoteFactsAndNoLocalBuildFacts() {
        val byPath = capture(linux()).attributes.associateBy { it.path }
        assertEquals("linux", byPath.getValue("os.name").value)
        assertEquals("x86_64", byPath.getValue("os.arch").value)
        assertEquals("debian", byPath.getValue("os.release.ID").value)
        assertEquals("6.1.0-18", byPath.getValue("kernel.release").value)
        assertEquals("8", byPath.getValue("cpu.count").value)
        assertEquals("200000 100000", byPath.getValue("cgroup.cpu.max").value)
        assertEquals("v2", byPath.getValue("cgroup.version").value)
        assertEquals("quiet", byPath.getValue("kernel.cmdline").value)
        assertEquals("Asia/Tokyo", byPath.getValue("tz.system-zone").value)
        assertEquals("Asia/Tokyo", byPath.getValue("env.TZ").value)
        for (local in listOf(
            "kotlin.version",
            "kotlin.target",
            "drift.platform",
            "runtime.platform",
        )) {
            assertNull(byPath[local], local)
        }
    }

    @Test
    fun aRemoteCapsuleRecordsHowItWasTaken() {
        val transport = capture(linux()).attributes.single { it.path == "capture.transport" }
        assertEquals("ssh", transport.value)
        assertEquals("ssh", transport.source)
    }

    @Test
    fun aDeclaredRuntimeOnTheRemoteIsKept() {
        val env = mapOf("DRIFT_RUNTIME_PLATFORM" to "node")
        val byPath = capture(linux(env)).attributes.associateBy { it.path }
        assertEquals("node", byPath.getValue("runtime.platform").value)
        assertEquals("env", byPath.getValue("runtime.platform").source)
    }

    @Test
    fun aLocalCaptureStillCarriesTheBuildFacts() {
        val local = Capture.run(FakeHost(platform = "linux"), "t")
        val byPath = local.attributes.associateBy { it.path }
        assertEquals("linux", byPath.getValue("kotlin.target").value)
        assertEquals("linux", byPath.getValue("drift.platform").value)
        assertEquals("linux", byPath.getValue("runtime.platform").value)
        assertNull(byPath["capture.transport"])
    }

    @Test
    fun aRemoteCaptureRunsOnlyTheFixedReadCommands() {
        val base = linux()
        capture(base)
        assertEquals(
            listOf(
                listOf("cat", "/etc/crypto-policies/config"),
                listOf("cat", "/etc/os-release"),
                listOf("cat", "/proc/cmdline"),
                listOf("cat", "/proc/self/cgroup"),
                listOf("cat", "/proc/sys/kernel/apparmor_restrict_unprivileged_userns"),
                listOf("cat", "/sys/fs/cgroup/cpu.max"),
                listOf("cat", "/sys/fs/cgroup/memory.max"),
                listOf("cat", "/sys/fs/cgroup/memory/memory.limit_in_bytes"),
                listOf("cat", "/usr/share/zoneinfo/+VERSION"),
                listOf("cat", "/usr/share/zoneinfo/tzdata.zi"),
                listOf("env"),
                listOf("getconf", "_NPROCESSORS_ONLN"),
                listOf("readlink", "/etc/localtime"),
                listOf("uname", "-r"),
                listOf("uname", "-sm"),
            ),
            base.remoteRan.distinct().sortedBy { it.joinToString(" ") },
        )
        assertEquals(17, base.remoteRan.size)
    }

    @Test
    fun aMacRemoteAddsOnlyItsVersionCommand() {
        val base = FakeSsh(
            uname = "Darwin arm64",
            remoteCommands = mapOf(
                listOf("sw_vers", "-productVersion") to CommandResult(0, "15.2\n"),
            ),
        )
        val byPath = capture(base).attributes.associateBy { it.path }
        assertEquals("macos", byPath.getValue("os.name").value)
        assertEquals("aarch64", byPath.getValue("os.arch").value)
        assertEquals("15.2", byPath.getValue("os.version").value)
        assertTrue(listOf("sw_vers", "-productVersion") in base.remoteRan)
    }

    @Test
    fun theSameRemoteGivesTheSameBytes() {
        val first = capture(linux()).canonical()
        val second = capture(linux()).canonical()
        assertEquals(first, second)
    }

    @Test
    fun theRemoteAccountAndHomeAreAnonymizedLikeALocalOne() {
        val env = mapOf(
            "USER" to "zorbulax",
            "HOME" to "/home/zorbulax",
            "NOTE" to "/home/zorbulax/x",
        )
        val capsule = capture(linux(env))
        assertFalse("zorbulax" in capsule.canonical())
        val kept = capture(linux(env), keep = true)
        assertTrue("zorbulax" in kept.canonical())
    }

    @Test
    fun theLabelIsTheOneGiven() {
        assertEquals("build-02", capture(linux(), label = "build-02").label)
    }
}
