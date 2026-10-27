package dev.gmitch215.drift.scan

import dev.gmitch215.drift.scan.scanner.Scanners
import dev.gmitch215.drift.scan.scanner.ToolRegistry
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.Asks
import dev.gmitch215.drift.triage.CleanMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepliesTest {
    private fun ask(family: String, vararg command: String) = Ask(family, command.toList(), "test")

    private fun read(ask: Ask, text: String) =
        Replies.read(ask, text)?.associate { it.path to it.value }

    @Test
    fun everyToolCommandOfTheAskTableIsAScannerCommand() {
        val known = (Scanners.all + ToolRegistry.all).map { it.command }.toSet()
        for (command in Asks.toolCommands) {
            assertTrue(command in known, "no scanner runs $command")
        }
    }

    @Test
    fun aToolReplyIsReadByTheScannerOfThatCommand() {
        assertEquals(
            mapOf("tool.node.version" to "20.11.1"),
            read(ask("tool.node", "node", "--version"), "v20.11.1\n"),
        )
        assertEquals(
            mapOf("tool.docker.version" to "24.0.7"),
            read(ask("tool.docker", "docker", "--version"), "Docker version 24.0.7, build afdd53b"),
        )
        assertEquals(
            mapOf("tool.php.version" to "7.4.33", "tool.php.sapi" to "cli"),
            read(ask("tool.php", "php", "-v"), "PHP 7.4.33 (cli) (built: Nov 1 2023)\n"),
        )
    }

    @Test
    fun aToolReplyThatIsNotThatToolsOutputIsRefused() {
        assertNull(read(ask("tool.node", "node", "--version"), "command not found: node"))
        assertNull(read(ask("tool.node", "node", "--version"), ""))
        assertNull(read(ask("tool.docker", "docker", "--version"), "v20.11.1"))
        assertNull(read(ask("tool.unknown", "nothing", "--version"), "1.0"))
    }

    @Test
    fun aLimitIsANumberOrUnlimited() {
        val nofile = ask("limits.nofile", "ulimit", "-n")
        assertEquals(mapOf("limits.nofile" to "1024"), read(nofile, "1024\n"))
        assertEquals(mapOf("limits.nofile" to "unlimited"), read(nofile, "unlimited"))
        assertNull(read(nofile, "ulimit: not found"))
        assertNull(read(nofile, ""))
    }

    @Test
    fun theLinuxReleaseComesFromOsRelease() {
        val release = ask("os.release", "cat", "/etc/os-release")
        val text = "NAME=\"Ubuntu\"\nVERSION_ID=\"22.04\"\nID=ubuntu\n" +
            "PRETTY_NAME=\"Ubuntu 22.04.3 LTS\"\n"
        assertEquals(
            mapOf(
                "os.name" to "linux",
                "os.release.ID" to "ubuntu",
                "os.release.VERSION_ID" to "22.04",
                "os.release.PRETTY_NAME" to "Ubuntu 22.04.3 LTS",
            ),
            read(release, text),
        )
        assertNull(read(release, "cat: /etc/os-release: No such file or directory"))
    }

    @Test
    fun macAndWindowsVersionsAreRead() {
        val mac = ask("os.version", "sw_vers", "-productVersion")
        assertEquals(mapOf("os.name" to "macos", "os.version" to "14.2.1"), read(mac, "14.2.1\n"))
        assertNull(read(mac, "Microsoft Windows [Version 10.0.19045.3693]"))
        val windows = ask("os.version", "ver")
        assertEquals(
            mapOf("os.name" to "windows", "os.version" to "10.0.19045.3693"),
            read(windows, "\r\nMicrosoft Windows [Version 10.0.19045.3693]\r\n"),
        )
        assertNull(read(windows, "14.2.1"))
    }

    @Test
    fun theKernelReleaseIsReadFromThreeForms() {
        val kernel = ask("kernel.release", "uname", "-r")
        assertEquals(
            mapOf("kernel.release" to "5.15.0-91-generic"),
            read(kernel, "5.15.0-91-generic"),
        )
        val both = mapOf("kernel.release" to "5.15.0-91-generic", "os.name" to "linux")
        assertEquals(both, read(kernel, "Linux 5.15.0-91-generic"))
        assertEquals(both, read(kernel, "Linux host-name 5.15.0-91-generic #101-Ubuntu SMP x86_64"))
        assertNull(read(kernel, "uname: illegal option"))
    }

    @Test
    fun anUnsetTimeZoneIsAnEmptyAnswer() {
        val zone = ask("env.TZ", "printenv", "TZ")
        assertEquals(mapOf("env.TZ" to "Europe/Paris"), read(zone, "Europe/Paris\n"))
        assertEquals(mapOf("env.TZ" to "UTC"), read(zone, "UTC\n"))
        assertEquals(mapOf("env.TZ" to "EST5EDT"), read(zone, "EST5EDT\n"))
        assertNull(read(zone, "command not found: node\n"))
        assertNull(read(zone, "hello\n"))
        assertEquals(emptyMap(), read(zone, ""))
    }

    @Test
    fun theLocaleReplyKeepsTheNamesThatAreSet() {
        val locale = ask("env.LANG", "locale")
        val text = "LANG=en_US.UTF-8\nLC_CTYPE=\"en_US.UTF-8\"\nLC_ALL=\n"
        assertEquals(mapOf("env.LANG" to "en_US.UTF-8"), read(locale, text))
        assertNull(read(locale, "locale: command not found"))
    }

    @Test
    fun electronIsReadLikeAnyRegistryTool() {
        val electron = ask("tool.electron", "electron", "--version")
        assertEquals(mapOf("tool.electron.version" to "28.1.0"), read(electron, "v28.1.0\n"))
        assertNull(read(electron, "electron: command not found"))
    }

    @Test
    fun theDiskUseComesFromTheLastRowOfDf() {
        val disk = ask("limits.disk-space", "df", "-h", ".")
        val text = "Filesystem      Size  Used Avail Use% Mounted on\n" +
            "/dev/sda1       100G   98G  2.0G  98% /\n"
        assertEquals(mapOf("limits.disk-space" to "98%"), read(disk, text))
        assertEquals(
            mapOf("limits.disk-quota" to "98%"),
            read(ask("limits.disk-quota", "df", "-h", "."), text),
        )
        assertNull(read(disk, "df: .: No such file or directory"))
        assertNull(read(disk, "Filesystem Size\nnothing here"))
    }

    @Test
    fun aVariableReplyIsTheValueOrNothingWhenUnset() {
        val variable = ask("env.DATA_DIR", "printenv", "DATA_DIR")
        assertEquals(mapOf("env.DATA_DIR" to "/srv/data"), read(variable, "/srv/data\n"))
        assertEquals(emptyMap(), read(variable, ""))
    }

    @Test
    fun aServerConsoleReplyGivesThePlatformAndItsVersion() {
        val paper = ask("tool.paper", "/version")
        val old = "This server is running Paper version git-Paper-196 (MC: 1.20.1) " +
            "(Implementing API version 1.20.1-R0.1-SNAPSHOT)"
        assertEquals(
            mapOf("runtime.platform" to "paper", "tool.paper.version" to "1.20.1-196"),
            read(paper, old),
        )
        val dated = "This server is running Paper version 1.21.4-232-main@abc1234 " +
            "(2025-01-01T00:00:00Z) (Implementing API version 1.21.4-R0.1-SNAPSHOT)"
        assertEquals("1.21.4-232", read(paper, dated)?.get("tool.paper.version"))
        val spigot = ask("tool.spigot", "/version")
        val craft = "This server is running CraftBukkit version 3955-Spigot-abc-def (MC: 1.20.4)"
        assertEquals("1.20.4", read(spigot, craft)?.get("tool.spigot.version"))
        assertNull(read(paper, "Unknown command. Type \"/help\" for help."))
    }

    @Test
    fun phpExtensionsAndOpensslAreReadByTheirScanners() {
        val modules = ask("tool.php.extensions", "php", "-m")
        val text = "[PHP Modules]\ncore\nCurl\njson\n\n[Zend Modules]\n"
        assertEquals(mapOf("tool.php.extensions" to "core,curl,json"), read(modules, text))
        assertNull(read(modules, "php: command not found"))
        assertEquals(
            mapOf("tool.openssl.version" to "3.0.2", "tool.openssl.family" to "openssl"),
            read(ask("tool.openssl", "openssl", "version"), "OpenSSL 3.0.2 15 Mar 2022 (Library)"),
        )
    }

    @Test
    fun aCommandVReplyGivesWhetherTheCommandIsInstalled() {
        val ask = ask("tool.git", "command", "-v", "git")
        assertEquals(mapOf("tool.git.installed" to "yes"), read(ask, "/usr/bin/git\n"))
        assertEquals(mapOf("tool.git.installed" to "yes"), read(ask, "git\n"))
        assertEquals(mapOf("tool.git.installed" to "yes"), read(ask, "alias git='hub'\n"))
        assertEquals(mapOf("tool.git.installed" to "no"), read(ask, ""))
        assertEquals(mapOf("tool.git.installed" to "no"), read(ask, "\n  \n"))
        assertNull(read(ask, "bash: command: not found and more words\n"))
    }

    @Test
    fun aFamilyWithoutAParserIsRefused() {
        assertNull(read(ask(Ask.CAPTURE, "drift", "why", "--save", "there.json"), "{}"))
    }

    private val report = "```\n\$ node app.js\nError: boom happened in the handler\n```\n"

    @Test
    fun aCleanRunReplyIsSameWorksOrDifferent() {
        val same = Replies.cleanRun(report, "Error: boom happened in the handler\n    at x\n")
        assertEquals(CleanMatch.SAME, same?.match)
        assertEquals(CleanMatch.WORKS, Replies.cleanRun(report, "ready\n")?.match)
        assertEquals(CleanMatch.WORKS, Replies.cleanRun(report, "")?.match)
        val other = Replies.cleanRun(report, "Error: another thing broke\n")
        assertEquals(CleanMatch.DIFFERENT, other?.match)
        assertEquals("Error: another thing broke", other?.seen)
        assertEquals("Error: boom happened in the handler", other?.wanted)
    }

    @Test
    fun aCleanRunReplyThatSaysNothingAboutTheCommandIsRefused() {
        assertNull(Replies.cleanRun(report, "a\u0000b"))
        assertNull(Replies.cleanRun(report, "\uFFFD\uFFFD\uFFFD ok"))
        for (junk in listOf(
            "docker: Cannot connect to the Docker daemon at unix:///x. Is it running?",
            "docker: Error response from daemon: pull access denied for nope",
            "bash: docker: command not found",
            "zsh: command not found: docker",
            "Got permission denied while trying to connect to the Docker daemon socket",
        )) {
            assertNull(Replies.cleanRun(report, junk), junk)
        }
    }

    @Test
    fun aCleanRunReplyGivesTheSameReadingEveryTime() {
        val text = "Error: boom happened in the handler\n".repeat(3)
        val a = Replies.cleanRun(report, text)
        val b = Replies.cleanRun(report, text)
        assertEquals(a?.match, b?.match)
        assertEquals(a?.seen, b?.seen)
    }
}
