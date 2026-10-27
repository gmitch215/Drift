package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.Asks
import dev.gmitch215.drift.triage.Confidence
import dev.gmitch215.drift.triage.Fact
import dev.gmitch215.drift.triage.Side
import dev.gmitch215.drift.triage.Span
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.triage.TriageOptions
import dev.gmitch215.drift.triage.TriageResult
import dev.gmitch215.drift.triage.TriageText
import dev.gmitch215.drift.triage.Via
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskTest {
    private fun run(text: String, known: List<Fact> = emptyList()): TriageResult =
        Triage.run(text, rules = emptyList(), known = known)

    private fun families(text: String) = run(text).ask.map { it.family }

    @Test
    fun aToolTheReportNamesWithoutAVersionIsAskedFor() {
        val ask = run("The build breaks on our machine. We run it with node and npm.").ask
        assertEquals(listOf("tool.node", "tool.npm"), ask.map { it.family })
        assertEquals(listOf("node", "--version"), ask[0].command)
        assertEquals("node --version", ask[0].line)
        assertEquals("node --version | drift why --reply", ask[0].piped())
        assertEquals(
            "To tell, run node --version where it fails and paste the result: " +
                "the report names Node but not which version fails.",
            ask[0].sentence(),
        )
    }

    @Test
    fun aVersionTheReportStatesIsNotAskedFor() {
        val text = "Node v20.11.1 on Ubuntu 22.04 fails here, and npm also fails."
        assertEquals(listOf("tool.npm"), families(text))
    }

    @Test
    fun nothingIsAskedForAGenericReport() {
        assertEquals(emptyList(), run("It crashes when I click the button.").ask)
        assertEquals(emptyList(), run("").ask)
    }

    @Test
    fun anAskNeverOverridesAnotherAnswer() {
        val framed = "TypeError: x is not a function\n" +
            "    at Object.<anonymous> (/work/app/src/index.js:3:8)\nrun with node"
        val result = run(framed)
        assertEquals(emptyList(), result.ask)
        assertTrue(result.notEnvironment != null)
    }

    @Test
    fun namesInsideUrlsPathsAndPackagesAreNotTools() {
        val text = """
            See https://github.com/nodejs/node/issues/1 and /usr/local/bin/php for the path.
            It uses node-fetch and docker.io/library/app and python-dateutil.
        """.trimIndent()
        assertEquals(emptyList(), run(text).ask)
    }

    @Test
    fun goNeedsACommand() {
        assertEquals(emptyList(), run("Let's go and fix it, I will go home.").ask)
        assertEquals(
            listOf("error-text", "tool.go"),
            families("```\n\$ go build ./...\nfailed\n```"),
        )
        assertEquals(listOf("tool.go"), families("It breaks with golang here."))
    }

    @Test
    fun aHitLimitIsAskedForFirst() {
        val ask = run("Error: too many open files while running node").ask
        assertEquals(listOf("limits.nofile", "tool.node"), ask.map { it.family })
        assertEquals("ulimit -n", ask[0].line)
        assertEquals("ulimit -n | drift why --reply", ask[0].piped())
    }

    @Test
    fun aSystemWithoutAVersionIsAskedFor() {
        assertEquals(listOf("os.release"), families("It fails on Ubuntu."))
        assertEquals(listOf("os.version"), families("It fails on macOS."))
        assertEquals(listOf("os.version"), families("It fails on Windows."))
        assertEquals(emptyList(), run("It fails on Ubuntu 22.04.").ask)
        assertEquals("sw_vers -productVersion", run("It fails on macOS.").ask.single().line)
        assertEquals("ver", run("It fails on Windows.").ask.single().line)
        assertEquals("cat /etc/os-release", run("It fails on Ubuntu.").ask.single().line)
    }

    @Test
    fun aCommandInTheTextImpliesTheSystem() {
        assertEquals(listOf("os.release"), families("```\n\$ apt install foo\nfailed\n```"))
        assertEquals(listOf("os.version"), families("```\n\$ brew install foo\nfailed\n```"))
        assertEquals(listOf("os.version"), families("The file C:\\work\\app.exe fails"))
    }

    @Test
    fun timeZoneAndLocaleAreAskedForWhenTheTextIsAboutThem() {
        assertEquals(listOf("env.TZ"), families("The timezone is wrong after midnight."))
        assertEquals(listOf("env.LANG"), families("Sorting depends on the locale."))
        assertEquals(
            listOf("printenv", "TZ"),
            run("The timezone is wrong after midnight.").ask.single().command,
        )
    }

    @Test
    fun theKernelIsAskedForWhenTheTextIsAboutIt() {
        assertEquals(listOf("kernel.release"), families("It panics in the kernel module."))
    }

    @Test
    fun aWorkingSideAddsTheCapture() {
        val ask = run("It works on my laptop but fails in CI with node.").ask
        assertEquals(listOf("tool.node", Ask.CAPTURE), ask.map { it.family })
        assertEquals(
            "drift why --save there.json && drift why --reply < there.json",
            ask[1].piped(),
        )
    }

    @Test
    fun atMostThreeAsksInAFixedOrder() {
        val text = "Too many open files. It works on my laptop. node npm docker php fail. " +
            "Ubuntu timezone locale."
        val ask = run(text).ask
        assertEquals(Ask.MAX, ask.size)
        assertEquals("limits.nofile", ask[0].family)
        assertEquals(ask, run(text).ask)
    }

    @Test
    fun aCheckedFactReplacesTheTextAndSilencesTheAsk() {
        val reply = Fact(
            "tool.node.version",
            "18.19.0",
            Side.FAILING,
            Confidence(900),
            Via.TOOL,
            Span(0, 0, 0),
        )
        val text = "It fails with node 20.1.0 locally and node here."
        val after = run(text, listOf(reply))
        assertEquals(
            "18.19.0",
            after.facts.single { it.key == "tool.node.version" && it.side == Side.FAILING }.value,
        )
        assertEquals(
            1,
            after.facts.count { it.key == "tool.node.version" && it.side == Side.FAILING },
        )
        assertFalse(after.ask.any { it.family == "tool.node" })
    }

    @Test
    fun theJsonCarriesTheAskOnlyWhenThereIsOne() {
        val asked = run("fails with node").toJson()
        val list = asked.fields.getValue("ask") as JsonArray
        val first = list.items.single() as JsonObject
        assertEquals(setOf("family", "command", "piped"), first.fields.keys)
        assertEquals(JsonString("tool.node"), first.fields.getValue("family"))
        assertEquals(JsonString("node --version"), first.fields.getValue("command"))
        assertNull(run("It crashes.").toJson().fields["ask"])
        assertEquals(asked, run("fails with node").toJson())
        assertContains(CanonicalJson.encode(asked), "\"ask\"")
    }

    @Test
    fun theTextPrintsTheAsk() {
        val text = TriageText.render(run("fails with node"))
        assertContains(text, "Ask (1)")
        assertContains(text, "tool.node: node --version | drift why --reply")
        assertContains(text, "To tell, run node --version where it fails and paste the result")
        assertFalse(TriageText.render(run("It crashes.")).contains("Ask ("))
    }

    @Test
    fun moreToolsAndFamiliesAreAskedForWhenTheTextNamesThem() {
        assertEquals(
            listOf("tool.electron"),
            families("The app breaks in electron after the update."),
        )
        assertEquals(listOf("tool.openssl"), families("The ssl handshake fails after a while."))
        assertEquals(listOf("tool.openssl"), families("TLS negotiation is refused."))
        assertEquals(
            listOf("tool.openssl", "tool.curl"),
            families("curl hangs on the certificate check."),
        )
        assertEquals(
            listOf("limits.disk-space"),
            families("Error: no space left on device while writing the cache"),
        )
        assertEquals(
            listOf("limits.disk-quota"),
            families("Error: disk quota exceeded while writing the cache"),
        )
        assertEquals(listOf("df", "-h", "."), run("disk quota exceeded").ask.single().command)
    }

    @Test
    fun phpExtensionsAreAskedForWhenPhpReportsSomethingMissing() {
        val ask = run("PHP says the extension is missing.").ask
        assertEquals(listOf("tool.php", "tool.php.extensions"), ask.map { it.family })
        assertEquals(listOf("php", "-m"), ask[1].command)
        assertEquals(listOf("tool.php"), families("PHP fails to start."))
    }

    @Test
    fun anEnvironmentVariableTheTextNamesIsAskedForByName() {
        val ask = run("The app reads \$DATA_DIR and fails when it is wrong.").ask
        assertEquals(listOf("env.DATA_DIR"), ask.map { it.family })
        assertEquals(listOf("printenv", "DATA_DIR"), ask.single().command)
        assertEquals("printenv DATA_DIR | drift why --reply", ask.single().piped())
        assertEquals(
            listOf("env.CACHE_DIR"),
            families("Set the environment variable CACHE_DIR first."),
        )
        assertEquals(listOf("env.OUT_DIR"), families("It uses \${OUT_DIR} for output."))
    }

    @Test
    fun aVariableThatIsASecretOrANoiseNameIsNeverAskedFor() {
        assertEquals(emptyList(), run("It reads \$API_TOKEN and \$DB_PASSWORD.").ask)
        assertEquals(emptyList(), run("It reads \$PATH and \$HOME.").ask)
        assertEquals(emptyList(), run("Run it as \$ ls and it prints.").ask)
    }

    @Test
    fun aVariableTheReportGivesAValueForIsNotAskedForAgain() {
        val reply = Fact(
            "env.DATA_DIR",
            "/srv/data",
            Side.FAILING,
            Confidence(900),
            Via.TOOL,
            Span(0, 0, 0),
        )
        assertEquals(emptyList(), run("It reads \$DATA_DIR and fails.", listOf(reply)).ask)
    }

    @Test
    fun aServerPlatformNeedsAServerContextAndIsAskedThroughItsConsole() {
        assertEquals(emptyList(), run("A white paper on parsing.").ask)
        val ask = run("The plugin fails on our paper server.").ask
        assertEquals(listOf("tool.paper"), ask.map { it.family })
        assertEquals(listOf("/version"), ask.single().command)
        assertEquals("drift why --reply < console-output.txt", ask.single().piped())
        assertContains(ask.single().sentence(), "in the server console")
    }

    @Test
    fun aRuntimeIsInferredFromItsOwnErrorVocabulary() {
        val cases = listOf(
            "ModuleNotFoundError: No module named 'distutils'" to "tool.python",
            "ImportError: cannot import name 'x' from 'y'" to "tool.python",
            "IndentationError: unexpected indent" to "tool.python",
            "AttributeError: module 'x' has no attribute 'y'" to "tool.python",
            "Error: Cannot find module 'left-pad'" to "tool.node",
            "Error [ERR_REQUIRE_ESM]: require() of ES Module x not supported" to "tool.node",
            "TypeError: x is not a function\n    at run (node:internal/main:1:1)" to "tool.node",
            "Fatal error: Uncaught Error: Call to undefined function str_contains()" to "tool.php",
            "PHP Parse error: syntax error, unexpected '}' in x.php" to "tool.php",
            "java.lang.ClassNotFoundException: com.example.Main" to "tool.java",
            "Error: java.lang.UnsupportedClassVersionError: x" to "tool.java",
            "cannot find package \"example.com/x\" in any of" to "tool.go",
            "panic: boom\n\ngoroutine 1 [running]:" to "tool.go",
            "error[E0308]: mismatched types" to "tool.rust",
            "thread 'main' panicked at src/main.rs:2:5" to "tool.rust",
            "LoadError: cannot load such file -- x" to "tool.ruby",
            "Unhandled exception. System.IO.FileNotFoundException: x" to "tool.dotnet",
        )
        for ((text, family) in cases) {
            val asks = run(text).ask
            assertTrue(asks.any { it.family == family }, "$text -> ${asks.map { it.family }}")
        }
        val ask = run("ModuleNotFoundError: No module named 'distutils'").ask.single()
        assertEquals(listOf("python3", "--version"), ask.command)
        assertEquals("python3 --version | drift why --reply", ask.piped())
    }

    @Test
    fun aRuntimeIsNeverInferredFromAPathOrAGenericWord() {
        val none = listOf(
            "See /usr/lib/python3/site.py and /usr/local/lib/node_modules/x for the files.",
            "TypeError: x is not a function",
            "panic: something broke",
            "Fatal error: out of memory",
            "error: could not compile the crate",
            "Error: ENOENT something",
            "An ERR_ code is not shown here and it is ERR_X",
            "Exception in the kitchen",
            "Traceback of the incident",
            "AssertionError: expected 4 lines, got 1",
        )
        for (text in none) {
            assertEquals(emptyList(), run(text).ask.map { it.family }, text)
        }
    }

    @Test
    fun anInferredRuntimeIsNotAskedForWhenTheTextStatesItsVersion() {
        val text = "Python 3.12.1 here.\nModuleNotFoundError: No module named 'distutils'"
        assertEquals(emptyList(), families(text).filter { it == "tool.python" })
        val asked = families("Node v20.11.1 here.\nModuleNotFoundError: No module named 'x'")
        assertEquals(listOf("tool.python"), asked)
    }

    @Test
    fun aMissingCommandIsAskedForByItsNameWithCommandV() {
        val shell = listOf(
            "bash: git: command not found",
            "zsh: command not found: git",
            "FileNotFoundError: [Errno 2] No such file or directory: 'git'",
            "sh: 1: git: command not found",
        )
        for (text in shell) {
            val ask = run(text).ask
            assertEquals(listOf("tool.git"), ask.map { it.family }, text)
            assertEquals(listOf("command", "-v", "git"), ask.single().command, text)
            assertEquals("command -v git | drift why --reply", ask.single().piped(), text)
        }
    }

    @Test
    fun aReporterWhoWorksElsewhereGetsNoProgramReadingAndTheCapture() {
        val text = "AssertionError: expected 4 lines, got 1"
        assertTrue(Triage.run(text, rules = emptyList()).notEnvironment != null)
        val options = TriageOptions(worksElsewhere = true)
        val result = Triage.run(text, options, rules = emptyList())
        assertNull(result.notEnvironment)
        assertEquals(listOf(Ask.CAPTURE), result.ask.map { it.family })
        val named = Triage.run("ModuleNotFoundError: x", options, rules = emptyList())
        assertEquals(listOf("tool.python", Ask.CAPTURE), named.ask.map { it.family })
    }

    @Test
    fun aMissingFileOrPathIsNotACommand() {
        val files = listOf(
            "FileNotFoundError: [Errno 2] No such file or directory: 'config.json'",
            "FileNotFoundError: [Errno 2] No such file or directory: '/etc/app/x'",
            "No such file or directory: 'data/in'",
            "bash: ./run.sh: command not found",
        )
        for (text in files) {
            val checks = run(text).ask.filter { it.command.take(2) == listOf("command", "-v") }
            assertEquals(emptyList(), checks, text)
        }
    }

    @Test
    fun aMissingCommandIsNotAskedForWhenTheTextStatesIt() {
        val stated = Fact(
            "tool.git.installed",
            "no",
            Side.FAILING,
            Confidence(900),
            Via.TOOL,
            Span(0, 0, 0),
        )
        assertEquals(emptyList(), run("bash: git: command not found", listOf(stated)).ask)
    }

    @Test
    fun everyRowHasAFixedCommand() {
        val commands = Asks.toolCommands
        assertEquals(commands.distinct().size, commands.size)
        assertTrue(commands.all { it.size in 2..3 && it.none { word -> word.isBlank() } })
    }
}
