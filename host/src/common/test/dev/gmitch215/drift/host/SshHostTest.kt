package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CONTROL = "/tmp/drift-ssh.AbC123"

private val OPTIONS = listOf(
    "ssh", "-T", "-n",
    "-o", "BatchMode=yes",
    "-o", "StrictHostKeyChecking=yes",
    "-o", "ForwardAgent=no",
    "-o", "ForwardX11=no",
    "-o", "ClearAllForwardings=yes",
    "-o", "ConnectTimeout=10",
    "-o", "LogLevel=ERROR",
)

private val MULTIPLEX = listOf(
    "-o",
    "ControlMaster=auto",
    "-o",
    "ControlPersist=30",
    "-o",
    "ControlPath=$CONTROL/m",
)

private val HOSTILE = listOf(
    "a;b",
    "\$(id)",
    "`id`",
    "it's",
    "\"quoted\"",
    "line\nbreak",
    "-rf",
    "",
    "a b  c",
    "back\\slash",
    "'",
    "''",
    "x".repeat(100_000),
)

private val UNKNOWN_KEY_TEXT = "No ED25519 host key is known for build-02 and you have requested " +
    "strict checking.\r\nHost key verification failed.\r\n"

private val CHANGED_KEY = "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\r\n" +
    "@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @\r\n" +
    "Host key for build-02 has changed and you have requested strict checking.\r\n" +
    "Host key verification failed.\r\n"

private fun target(text: String = "me@build-02") = SshTarget.parse(text)!!

private fun mktemp(argv: List<String>, result: CommandResult) =
    if (argv[0] == "mktemp") result else null

/** Answers every local command from [answer]; `null` from it means the program is missing. */
private class ScriptedHost(
    private val inner: FakeSsh = FakeSsh(),
    private val answer: (List<String>) -> CommandResult? = { null },
    private val flood: Int = 0,
) : Host by inner {
    val timeouts = mutableListOf<Long?>()
    val calls = mutableListOf<List<String>>()

    override fun run(argv: List<String>): CommandResult? = run(argv, null) { }

    override fun run(
        argv: List<String>,
        timeoutMillis: Long?,
        onOutput: (String) -> Unit,
    ): CommandResult? {
        calls += argv
        if (argv[0] == "ssh") timeouts += timeoutMillis
        if (flood > 0 && argv[0] == "ssh") {
            repeat(flood) { onOutput("y".repeat(1 shl 16)) }
            return CommandResult(0, "")
        }
        val result = answer(argv) ?: inner.run(argv) ?: return null
        if (result.output.isNotEmpty()) onOutput(result.output)
        return result.copy(output = "")
    }
}

class SshHostTest {
    @Test
    fun theSshCommandLineIsExact() {
        val base = FakeSsh(remoteFiles = mapOf("/etc/os-release" to "ID=debian\n"))
        val host = SshHost.open(base, target())
        assertEquals("linux", host.os)
        assertEquals("x86_64", host.arch)
        assertEquals("ssh", host.platform)
        assertEquals("ID=debian\n", host.readText("/etc/os-release"))
        assertEquals(listOf("mktemp", "-d", "/tmp/drift-ssh.XXXXXX"), base.ran[0])
        assertEquals(
            OPTIONS + MULTIPLEX + listOf("--", "me@build-02", "'uname' '-sm'"),
            base.ran[1],
        )
        assertEquals(
            OPTIONS + MULTIPLEX + listOf("--", "me@build-02", "'cat' '/etc/os-release'"),
            base.ran[2],
        )
    }

    @Test
    fun closingEndsTheSharedConnectionAndRemovesItsDirectoryOnce() {
        val base = FakeSsh()
        val host = SshHost.open(base, target())
        base.ran.clear()
        host.close()
        host.close()
        assertEquals(
            listOf(
                OPTIONS + MULTIPLEX + listOf("-O", "exit", "--", "me@build-02"),
                listOf("rm", "-f", "$CONTROL/m"),
                listOf("rmdir", CONTROL),
            ),
            base.ran,
        )
    }

    @Test
    fun withoutMultiplexingTheCommandLineHasNoControlOptions() {
        val base = FakeSsh()
        val host = SshHost.open(base, target("build-02"), multiplex = false)
        assertEquals(
            OPTIONS + listOf("--", "build-02", "'uname' '-sm'"),
            base.ran.single(),
        )
        host.close()
        assertEquals(1, base.ran.size)
    }

    @Test
    fun aDirectoryThatCannotBeMadeOrIsNotPrivateMeansNoMultiplexing() {
        val missing = ScriptedHost(answer = { mktemp(it, CommandResult(1, "")) })
        SshHost.open(missing, target()).close()
        assertFalse(missing.calls.any { "ControlMaster=auto" in it })
        val odd = ScriptedHost(answer = { mktemp(it, CommandResult(0, "/etc\n")) })
        SshHost.open(odd, target()).close()
        assertFalse(odd.calls.any { "ControlMaster=auto" in it })
        assertFalse(odd.calls.any { it[0] == "rmdir" })
    }

    @Test
    fun aWindowsLocalHostNeverMultiplexes() {
        val base = FakeSsh(os = "windows")
        SshHost.open(base, target())
        assertTrue(base.ran.none { it[0] == "mktemp" })
        assertFalse(base.ran.any { "ControlMaster=auto" in it })
    }

    @Test
    fun noOptionThatWeakensTheConnectionEverAppears() {
        val base = FakeSsh(remoteFiles = mapOf("/etc/os-release" to "x"))
        val host = SshHost.open(base, target())
        host.readText("/etc/os-release")
        host.env()
        host.close()
        for (argv in base.ran.filter { it[0] == "ssh" }) {
            val text = argv.joinToString(" ")
            assertFalse("StrictHostKeyChecking=no" in text || "accept-new" in text, text)
            assertFalse("UserKnownHostsFile" in text || "sshpass" in text, text)
            assertFalse("-A" in argv || "sudo" in text, text)
            assertTrue("BatchMode=yes" in argv && "ForwardAgent=no" in argv, text)
        }
    }

    @Test
    fun theDestinationAlwaysFollowsTheOptionTerminator() {
        val base = FakeSsh()
        val host = SshHost.open(base, target("me@build-02"))
        host.env()
        for (argv in base.ran.filter { it[0] == "ssh" && "-O" !in it }) {
            assertEquals("--", argv[argv.size - 3])
            assertEquals("me@build-02", argv[argv.size - 2])
        }
    }

    @Test
    fun hostileStringsRoundTripThroughOneLevelOfShellQuoting() {
        for (text in HOSTILE) {
            assertEquals(listOf(text), shellWords(shellQuote(text)), text.take(40))
        }
        assertEquals("'it'\\''s'", shellQuote("it's"))
        val words = HOSTILE
        val argv = sshArgv(target(), null, words)
        assertEquals(words, shellWords(argv.last()))
        assertEquals(1, argv.count { it == "--" })
    }

    @Test
    fun hostileHostArgumentsAreRejectedBeforeSshRuns() {
        val bad = listOf(
            "-oProxyCommand=id",
            "-h",
            "-",
            "a;b",
            "a b",
            "a\$(id)",
            "a`id`",
            "a\nb",
            "a'b",
            "a/b",
            "a:22",
            "",
            "@h",
            "me@",
            "me@-x",
            "-me@host",
            "me@host\n",
            "[::1",
            "[zz]",
            "x".repeat(300),
        )
        for (text in bad) assertNull(SshTarget.parse(text), text)
    }

    @Test
    fun validHostArgumentsKeepTheirNameAndDropIpv6Brackets() {
        val cases = mapOf(
            "build-02" to "build-02",
            "me@build-02" to "me@build-02",
            "10.0.0.5" to "10.0.0.5",
            "a_b.c-d" to "a_b.c-d",
            "me@[::1]" to "me@::1",
            "[fe80::1]" to "fe80::1",
        )
        for ((text, destination) in cases) {
            val parsed = assertNotNull(SshTarget.parse(text), text)
            assertEquals(text, parsed.text)
            assertEquals(destination, parsed.destination)
        }
    }

    @Test
    fun onlyTheFixedReadOnlyCommandsAreAllowed() {
        val base = FakeSsh()
        val host = SshHost.open(base, target())
        val before = base.ran.size
        val refused = listOf(
            listOf("cat", "/etc/shadow"),
            listOf("cat", "/etc/os-release; id"),
            listOf("cat", "-n", "/etc/os-release"),
            listOf("cat", "/etc/os-release", "/etc/shadow"),
            listOf("cat"),
            listOf("sh", "-c", "id"),
            listOf("df"),
            listOf("mount"),
            listOf("find", "/"),
            listOf("ls", "/etc"),
            listOf("sudo", "uname", "-r"),
            listOf("env", "FOO=1"),
            listOf("uname"),
            listOf("uname", "-a"),
            listOf("rm", "-rf", "/"),
        )
        for (argv in refused) assertFailsWith<SshRefused>(argv.toString()) { host.run(argv) }
        assertFailsWith<SshRefused> { host.readText("/home/me/.ssh/id_ed25519") }
        assertEquals(before, base.ran.size)
    }

    @Test
    fun theAllowedReadsReachTheRemoteAsQuotedWords() {
        val base = FakeSsh(
            remoteFiles = mapOf("/proc/cmdline" to "quiet\n"),
            remoteCommands = mapOf(listOf("uname", "-r") to CommandResult(0, "6.1.0\n")),
            remoteEnv = mapOf("A" to "1", "B" to "two"),
        )
        val host = SshHost.open(base, target())
        assertEquals("6.1.0\n", host.run(listOf("uname", "-r"))?.output)
        assertEquals("quiet\n", host.readText("/proc/cmdline"))
        assertNull(host.readText("/etc/os-release"))
        assertEquals(mapOf("A" to "1", "B" to "two"), host.env())
        assertEquals(
            listOf(
                listOf("uname", "-sm"),
                listOf("uname", "-r"),
                listOf("cat", "/proc/cmdline"),
                listOf("cat", "/etc/os-release"),
                listOf("env"),
            ),
            base.remoteRan,
        )
    }

    @Test
    fun theLocaleAndNetworkReadsAreAllowedAndTheWriteProbeIsNot() {
        val base = FakeSsh(
            remoteFiles = mapOf("/etc/hosts" to "127.0.0.1 localhost\n"),
            remoteCommands = mapOf(
                listOf("ls", "-1", "/sys/class/net") to CommandResult(0, "lo\n"),
            ),
        )
        val host = SshHost.open(base, target())
        assertEquals("127.0.0.1 localhost\n", host.readText("/etc/hosts"))
        assertEquals("lo\n", host.run(listOf("ls", "-1", "/sys/class/net"))?.output)
        assertFailsWith<SshRefused> { host.run(listOf("ls", "-1", "/root")) }
        assertFalse(Capability.WRITE_FILES in host.capabilities)
        assertNull(host.syncNanos("/tmp", 25))
    }

    @Test
    fun theRemoteSystemComesFromUname() {
        val mac = SshHost.open(FakeSsh(uname = "Darwin arm64"), target())
        assertEquals("macos", mac.os)
        assertEquals("arm64", mac.arch)
        val bsd = SshHost.open(FakeSsh(uname = "FreeBSD amd64"), target())
        assertEquals("freebsd", bsd.os)
        val odd = SshHost.open(FakeSsh(uname = "Darwin Power Macintosh"), target())
        assertEquals("Power Macintosh", odd.arch)
    }

    @Test
    fun eachSshFailureMapsToAPlainMessage() {
        val cases = mapOf(
            SshProblem.UNKNOWN_KEY to (UNKNOWN_KEY_TEXT to "not in your known_hosts"),
            SshProblem.CHANGED_KEY to (CHANGED_KEY to "may have been replaced or intercepted"),
            SshProblem.AUTH to
                ("me@build-02: Permission denied (publickey)." to "without a prompt"),
            SshProblem.REFUSED to
                ("ssh: connect to host build-02 port 22: Connection refused" to "refused"),
            SshProblem.UNRESOLVED to (
                "ssh: Could not resolve hostname build-02: Name or service not known" to
                    "cannot resolve"
                ),
            SshProblem.TIMEOUT to
                ("ssh: connect to host build-02 port 22: Operation timed out" to "timed out"),
            SshProblem.FAILED to
                ("ssh: connect to host build-02 port 22: No route to host" to "No route"),
        )
        for ((problem, pair) in cases) {
            val base = FakeSsh(sshResult = CommandResult(255, pair.first))
            val error = assertFailsWith<SshException>(problem.name) {
                SshHost.open(base, target("build-02"))
            }
            assertEquals(problem, error.problem, problem.name)
            assertTrue(pair.second in error.message.orEmpty(), "${problem.name}: ${error.message}")
        }
    }

    @Test
    fun aChangedKeyIsNeverReportedAsAnUnknownOne() {
        val error = assertFailsWith<SshException> {
            SshHost.open(FakeSsh(sshResult = CommandResult(255, CHANGED_KEY)), target())
        }
        assertEquals(SshProblem.CHANGED_KEY, error.problem)
        assertFalse("known_hosts, so Drift will not connect" in error.message.orEmpty())
    }

    @Test
    fun anUnexplainedFailureQuotesOnlyAShortFirstLine() {
        val text = "weird failure " + "z".repeat(500) + "\nsecond line"
        val error = assertFailsWith<SshException> {
            SshHost.open(FakeSsh(sshResult = CommandResult(255, text)), target("build-02"))
        }
        assertEquals(SshProblem.FAILED, error.problem)
        assertFalse("second line" in error.message.orEmpty())
        assertTrue(error.message.orEmpty().length < 300)
    }

    @Test
    fun aTimeoutKillsTheCommandAndStopsLaterOnes() {
        val base = ScriptedHost(answer = {
            if (it[0] == "ssh") CommandResult(TIMED_OUT_EXIT_CODE, "") else null
        })
        val error = assertFailsWith<SshException> { SshHost.open(base, target()) }
        assertEquals(SshProblem.TIMEOUT, error.problem)
        assertEquals(listOf<Long?>(30_000L), base.timeouts)
    }

    @Test
    fun everyRemoteCommandRunsUnderATimeout() {
        val base = ScriptedHost()
        val host = SshHost.open(base, target())
        host.env()
        host.readText("/etc/os-release")
        assertEquals(3, base.timeouts.size)
        assertTrue(base.timeouts.all { it == 30_000L })
    }

    @Test
    fun aFailureAfterConnectingIsRecordedAndLaterCommandsDoNotRun() {
        val inner = FakeSsh(remoteFiles = mapOf("/etc/os-release" to "ID=x\n"))
        var drop = false
        val base = ScriptedHost(inner, answer = {
            if (drop && it[0] == "ssh" && "-O" !in it) {
                CommandResult(255, "Connection to build-02 closed by remote host.")
            } else {
                null
            }
        })
        val host = SshHost.open(base, target())
        assertEquals("ID=x\n", host.readText("/etc/os-release"))
        assertNull(host.problem)
        drop = true
        assertNull(host.readText("/etc/os-release"))
        assertEquals(SshProblem.FAILED, host.problem?.problem)
        val calls = base.calls.size
        assertNull(host.readText("/etc/os-release"))
        assertTrue(host.env().isEmpty())
        assertEquals(calls, base.calls.size)
    }

    @Test
    fun outputPastTheBoundIsRefusedAndTheMemoryStaysBounded() {
        val flood = ScriptedHost(flood = 40)
        val error = assertFailsWith<SshException> { SshHost.open(flood, target()) }
        assertEquals(SshProblem.TOO_MUCH_OUTPUT, error.problem)
        assertTrue("1 MiB" in error.message.orEmpty())
        val fits = ScriptedHost(
            FakeSsh(remoteFiles = mapOf("/etc/os-release" to "a".repeat(900_000))),
        )
        val host = SshHost.open(fits, target())
        assertEquals(900_000, host.readText("/etc/os-release")?.length)
        assertNull(host.problem)
    }

    @Test
    fun aHostThatIsNotPosixOrIsWindowsIsRefusedWithAReason() {
        val silent = FakeSsh(remoteCommands = emptyMap(), uname = "")
        assertEquals(
            SshProblem.NOT_POSIX,
            assertFailsWith<SshException> { SshHost.open(silent, target()) }.problem,
        )
        val missing = ScriptedHost(answer = {
            if (it[0] == "ssh") CommandResult(127, "sh: uname: not found") else null
        })
        assertEquals(
            SshProblem.NOT_POSIX,
            assertFailsWith<SshException> { SshHost.open(missing, target()) }.problem,
        )
        val windows = FakeSsh(uname = "MINGW64_NT-10.0 x86_64")
        assertEquals(
            SshProblem.WINDOWS,
            assertFailsWith<SshException> { SshHost.open(windows, target()) }.problem,
        )
        assertTrue(windows.ran.any { it[0] == "rmdir" }, "the directory is removed on failure")
    }

    @Test
    fun aHostThatCannotStartProgramsSaysSo() {
        val web = FakeSsh(capabilities = emptySet())
        val error = assertFailsWith<SshException> { SshHost.open(web, target()) }
        assertEquals(SshProblem.NO_SSH, error.problem)
        assertTrue("cannot start other programs" in error.message.orEmpty())
        assertTrue(web.ran.isEmpty())
        val none = FakeSsh(canRun = false)
        val missing = assertFailsWith<SshException> { SshHost.open(none, target()) }
        assertEquals(SshProblem.NO_SSH, missing.problem)
        assertTrue("OpenSSH" in missing.message.orEmpty())
    }

    @Test
    fun theShellWordSplitterHandlesEscapesAndGaps() {
        assertContentEquals(listOf("a", "b c", "d'e", ""), shellWords("a 'b c' d\\'e ''"))
        assertEquals(emptyList(), shellWords("   "))
        assertEquals(listOf("ab"), shellWords("a\\b"))
    }
}
