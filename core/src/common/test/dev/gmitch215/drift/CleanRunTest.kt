package dev.gmitch215.drift

import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.lab.Distro
import dev.gmitch215.drift.lab.ImageTable
import dev.gmitch215.drift.lab.ImageTags
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.CleanMatch
import dev.gmitch215.drift.triage.CleanRun
import dev.gmitch215.drift.triage.Confidence
import dev.gmitch215.drift.triage.Fact
import dev.gmitch215.drift.triage.NotEnvSignal
import dev.gmitch215.drift.triage.Side
import dev.gmitch215.drift.triage.Span
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.triage.TriageResult
import dev.gmitch215.drift.triage.Via
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleanRunTest {
    private fun run(text: String, known: List<Fact> = emptyList()): TriageResult =
        Triage.run(text, rules = emptyList(), known = known)

    private fun clean(text: String): Ask? =
        run(text).ask.firstOrNull { it.family == CleanRun.FAMILY }

    private val failure = "Error: Cannot find module './config'"

    private fun report(command: String = "\$ node app.js", extra: String = "") = """
        The app stops at start-up.$extra

        ```
        $command
        $failure
        ```
    """.trimIndent()

    private fun reply(match: CleanMatch) = Fact(
        CleanRun.KEY,
        match.id,
        Side.FAILING,
        Confidence(900),
        Via.TOOL,
        Span(0, 0, 0),
    )

    // #region derivation

    @Test
    fun aPromptedCommandInACodeBlockGivesTheCleanRunAsk() {
        val ask = assertNotNull(clean(report()))
        val tag = assertNotNull(CleanRun.image("node", null))
        assertEquals(
            "docker run --rm --user \"\$(id -u):\$(id -g)\" --cap-drop ALL " +
                "--security-opt no-new-privileges --pids-limit 256 --memory 1g --network none " +
                "-v \"\$PWD\":/src:ro --tmpfs /w:exec,mode=1777 -w /w $tag sh -c " +
                "'cp -R /src/. /w/ && node app.js'",
            ask.line,
        )
        assertEquals(
            "${ask.line} 2>&1 | drift why --reply",
            ask.piped(),
        )
        assertEquals("${ask.line} 2>&1 | drift why --reply --session s.json", ask.piped("s.json"))
        assertTrue(
            ask.sentence().startsWith("To tell, run the command from the report in a clean"),
            ask.sentence(),
        )
    }

    @Test
    fun aGreaterThanPromptAndAQuotedBannerAreTold() {
        assertNotNull(clean(report("> node app.js")))
        assertNull(clean(report("> app@1.0.0 start")))
        assertNull(clean(report("> Task :app:compileJava FAILED")))
    }

    @Test
    fun theOneCommandOfABlockBeforeTheErrorBlockCounts() {
        val text = "It fails on start.\n\n```\nnode app.js\n```\n\n```\n$failure\n```\n"
        assertNotNull(clean(text))
        val after = "It fails on start.\n\n```\nnode app.js\n```\n\nand prints a line.\n"
        assertNull(clean(after))
        val unknown = "It fails.\n\n```\nfrobnicate now\n```\n\n```\n$failure\n```\n"
        assertNull(clean(unknown))
    }

    @Test
    fun theCommandNearestBeforeTheFailureIsTheOneUsed() {
        val text = "```\n\$ npm install\n\$ node app.js\n$failure\n```\n"
        assertTrue(assertNotNull(clean(text)).line.endsWith("&& node app.js'"))
    }

    @Test
    fun nothingIsAskedWithoutACommandOrWithoutAFailure() {
        assertNull(clean("It fails.\n\n```\n$failure\n```\n"))
        assertNull(clean("```\n\$ node app.js\nStarted on port 80\n```\nIt fails.\n"))
        assertNull(clean("Run node app.js and it fails with $failure"))
    }

    @Test
    fun aCommandThatIsNotSafeToPrintGivesNoAsk() {
        assertNull(clean(report("\$ node -e `id`")))
        assertNull(clean(report("\$ node -e \$(id)")))
        assertNull(clean(report("\$ node app.js \\")))
        assertNull(clean(report("\$ node app.js --name caf\u00E9")))
        assertNull(clean(report("\$ node app.js " + "x".repeat(400))))
        assertNull(clean(report("\$ node app.js --token <redacted>")))
    }

    @Test
    fun aToolchainWithoutAnOfficialImageGivesNoAsk() {
        assertNull(clean(report("\$ git push origin main")))
        assertNull(clean(report("\$ docker build .")))
        assertNull(clean(report("\$ drift why issue.md")))
        assertNull(clean(report("\$ dotnet run")))
    }

    @Test
    fun aScriptTakesTheToolchainTheTextNames() {
        val text = report("\$ ./run.sh", " It is a Python project.")
        val ask = assertNotNull(clean(text))
        assertTrue("python:" in ask.line && ask.line.endsWith("&& ./run.sh'"), ask.line)
        assertNull(clean(report("\$ ./run.sh")))
    }

    @Test
    fun theToolchainFollowsTheProgramNotAWrapper() {
        val ask = assertNotNull(clean(report("\$ sudo FOO=1 python3 -m pytest")))
        assertTrue(ask.line.contains("python:"), ask.line)
        assertTrue(assertNotNull(clean(report("\$ ./gradlew build"))).line.contains("temurin"))
        assertTrue(assertNotNull(clean(report("\$ python3.11 x.py"))).line.contains("python:"))
    }

    @Test
    fun theAskIsFirstWhenNoEnvironmentFactIsStatedAndLastOtherwise() {
        val bare = run(report("\$ node app.js", " It also uses npm.")).ask
        assertEquals(CleanRun.FAMILY, bare.first().family)
        val more = " Node v18.12.1 on Ubuntu 22.04. It also uses npm."
        val stated = run(report("\$ node app.js", more)).ask
        assertEquals(CleanRun.FAMILY, stated.last().family)
        assertTrue(stated.size >= 2, "$stated")
        assertEquals(1, stated.count { it.family == CleanRun.FAMILY })
    }

    @Test
    fun aStatedVersionPicksTheNearestPublishedImageOfItsMajor() {
        val ask = assertNotNull(clean(report("\$ node app.js", " It fails on Node v18.12.1.")))
        assertTrue(ask.line.contains(" node:18."), ask.line)
    }

    @Test
    fun theAskNeverReplacesAnotherAnswer() {
        val framed = "TypeError: x is not a function\n" +
            "    at Object.<anonymous> (/app/src/index.js:3:8)\n" +
            "```\n\$ node app.js\nTypeError: x\n```\n"
        assertEquals(emptyList(), run(framed).ask)
    }

    @Test
    fun theJsonOfTheAskHasTheThreeFieldsAndTheSameBytesEveryTime() {
        val ask = assertNotNull(clean(report()))
        val json = ask.toJson()
        assertEquals(setOf("family", "command", "piped"), json.fields.keys)
        assertEquals(ask, assertNotNull(clean(report())))
        assertEquals(run(report()).toJson().toString(), run(report()).toJson().toString())
    }

    private fun prose(text: String) = "$text\n\n```\n$failure\n```\n"

    @Test
    fun anInlineSpanAfterARunVerbIsACommand() {
        val verbs =
            listOf("Run", "I ran", "when I run", "Running", "executing", "typing", "command:")
        for (verb in verbs) {
            val ask = clean(prose("$verb `node app.js` and it stops."))
            assertNotNull(ask, verb)
            assertTrue(ask.line.endsWith("&& node app.js'"), ask.line)
        }
        val python = assertNotNull(clean(prose("1. Run `python3 -m pytest -x` here.")))
        assertTrue(python.line.contains("python:"), python.line)
    }

    @Test
    fun anInlineSpanThatIsNotACommandRunIsIgnored() {
        assertNull(clean(prose("The file `node app.js` is old.")))
        assertNull(clean(prose("Run `frobnicate now` and it stops.")))
        assertNull(clean(prose("Run the thing that I wrote about some time ago in `node app.js`.")))
        assertNull(clean(prose("Run `git push origin main` and it stops.")))
        assertNull(clean(prose("Brunch `node app.js` is nice.")))
        assertNull(clean(prose("Run ``node app.js`` and it stops.")))
    }

    @Test
    fun aPercentPromptIsACommandWhenItsProgramIsKnown() {
        assertNotNull(clean(report("% node app.js")))
        assertNull(clean(report("% total 5")))
        assertNull(clean(report("% app@1.0.0 start")))
    }

    @Test
    fun aLoneCommandBlockCountsWhenAnyLaterBlockHoldsTheError() {
        val between = "```\nnode app.js\n```\n\n```\nsome other output\n```\n\n```\n$failure\n```\n"
        assertNotNull(clean("It fails.\n\n$between"))
        val before = "```\n$failure\n```\n\n```\nnode app.js\n```\n"
        assertNull(clean("It fails.\n\n$before"))
        val two = "```\nnode app.js\nnode other.js\n```\n\n```\n$failure\n```\n"
        assertNull(clean("It fails.\n\n$two"))
    }

    @Test
    fun aCommandWithoutAFailureAsksForTheErrorText() {
        fun errorText(text: String) = run(text).ask.firstOrNull { it.family == CleanRun.ERROR_TEXT }
        val ask = assertNotNull(errorText("It stops at start. Run `node app.js` to see it."))
        assertEquals(CleanRun.ERROR_TEXT, ask.family)
        assertEquals("COMMAND 2>&1 | drift why --reply", ask.piped())
        assertTrue(ask.sentence().startsWith("To tell, run the command from the report where"))
        assertNull(errorText("It stops at start."))
        assertNull(errorText("It stops. Run `node -e \"\$(id)\"` to see it."))
        assertNull(errorText("It stops. Run `git push` to see it."))
    }

    @Test
    fun theCommandRunsInAContainedCopyAndOnlyTheCommandComesFromTheReport() {
        val hostile = "node -e \"x';touch /tmp/y;'\""
        val ask = assertNotNull(clean(report("\$ $hostile")))
        val tag = assertNotNull(CleanRun.image("node", null))
        val fixed = listOf(
            "docker", "run", "--rm", "--user", "\"\$(id -u):\$(id -g)\"", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges", "--pids-limit", "256", "--memory", "1g",
            "--network", "none", "-v", "\"\$PWD\":/src:ro", "--tmpfs", "/w:exec,mode=1777",
            "-w", "/w", tag, "sh", "-c",
        )
        assertEquals(fixed, ask.command.dropLast(1))
        val last = ask.command.last()
        assertEquals("cp -R /src/. /w/ && $hostile", unquote(last))
        assertTrue(last.startsWith("'cp -R /src/. /w/ && ") && last.endsWith("'"), last)
        assertFalse(ask.command.dropLast(1).any { "touch" in it || "node -e" in it })
    }

    @Test
    fun theNetworkStaysOnOnlyWhenTheReportShowsNetworkUse() {
        assertTrue("--network none" in assertNotNull(clean(report())).line)
        val url = report("\$ node app.js", " It downloads a file from https://example.com/x.")
        assertFalse("--network" in assertNotNull(clean(url)).line)
        assertFalse("--network" in assertNotNull(clean(report("\$ npm test"))).line)
        val installs = report("\$ node app.js", " It runs npm install first.")
        assertFalse("--network" in assertNotNull(clean(installs)).line)
    }

    // #endregion

    // #region quoting

    private fun unquote(quoted: String): String {
        val out = StringBuilder()
        var inside = false
        var i = 0
        while (i < quoted.length) {
            val c = quoted[i]
            when {
                inside && c == '\'' -> inside = false

                inside -> out.append(c)

                c == '\'' -> inside = true

                c == '\\' && quoted.getOrNull(i + 1) == '\'' -> {
                    out.append('\'')
                    i++
                }

                else -> error("unquoted character $c in $quoted")
            }
            i++
        }
        assertFalse(inside, quoted)
        return out.toString()
    }

    @Test
    fun quotingKeepsEveryCharacterAndEscapesTheQuote() {
        val commands = listOf(
            "node app.js",
            "echo 'hi'",
            "'",
            "''",
            "a'b'c",
            "echo \$HOME \"x\" \\n *",
            "x; y && z | w > f 2>&1",
            "tab\tseparated",
            "!history #hash",
        )
        for (command in commands) {
            assertEquals(command, unquote(assertNotNull(CleanRun.quote(command), command)), command)
        }
        assertEquals("'echo '\\''hi'\\'''", CleanRun.quote("echo 'hi'"))
        assertEquals("'echo \$HOME'", CleanRun.quote("echo \$HOME"))
    }

    @Test
    fun quotingRefusesWhatIsNotSafeToPrint() {
        val refused = listOf(
            "",
            "   ",
            "a\nb",
            "a\rb",
            "a\u0000b",
            "a\u001bb",
            "echo `id`",
            "echo \$(id)",
            "caf\u00E9",
            "bidi \u202E",
            "node x.js \\",
            "echo <redacted>",
            "x".repeat(401),
        )
        for (command in refused) assertNull(CleanRun.quote(command), command)
        assertNotNull(CleanRun.quote("x".repeat(400)))
    }

    // #endregion

    // #region the image

    @Test
    fun everyImageTheTableHandsOutIsPublished() {
        for ((key, _) in CleanRun.programs) {
            val tag = assertNotNull(CleanRun.image(key, null), key)
            val repository = tag.substringBefore(':')
            val body = tag.substringAfter(':')
            val published = ImageTags.repositories.getValue(repository)
            assertTrue(
                published.any { (rest, versions) ->
                    body.endsWith(rest) && body.removeSuffix(rest) in versions
                },
                tag,
            )
        }
    }

    @Test
    fun theDefaultFlavorIsTheOneTheImageTableChooses() {
        for ((key, _) in CleanRun.programs) {
            val tag = assertNotNull(CleanRun.image(key, null), key)
            val version = tag.substringAfter(':').takeWhile { it.isDigit() || it == '.' }
            val row = ImageTable.rows.first { it.tool == key }
            val attrs = buildMap {
                put(row.versionPaths.first(), version)
                if (key == "cc") put("tool.cc.family", "gcc")
            }
            assertEquals(row.pick(attrs, Distro.of(emptyMap()))?.tag, tag, key)
        }
    }

    @Test
    fun everyProgramWordIsOneThatTheImageTableNamesForTheSameToolchain() {
        for ((key, words) in CleanRun.programs) {
            val commands = ImageTable.rows.first { it.tool == key }.commands
            assertTrue(commands.containsAll(words), "$key: ${words - commands}")
        }
    }

    @Test
    fun aVersionIsResolvedToTheNewestPublishedOneWithThatPrefix() {
        val exact = assertNotNull(CleanRun.image("node", "20.11.1"))
        assertEquals("node:20.11.1-bookworm-slim", exact)
        val major = assertNotNull(CleanRun.image("node", "v18"))
        assertTrue(major.startsWith("node:18."), major)
        val newest = assertNotNull(CleanRun.image("node", null))
        val known = ImageTags.repositories.getValue("node").getValue("-bookworm-slim")
        assertTrue(newest.substringAfter(':').removeSuffix("-bookworm-slim") in known, newest)
        assertEquals("eclipse-temurin:8-jdk", CleanRun.image("java", "1.8.0_292"))
        assertEquals("eclipse-temurin:17-jdk", CleanRun.image("java", "17.0.2"))
        assertTrue(assertNotNull(CleanRun.image("cc", "13.2.1")).startsWith("gcc:13.2"))
    }

    @Test
    fun aVersionTheRegistryNoLongerCarriesGivesNoAsk() {
        val text = report("\$ node app.js", " It fails on node 12.22.0.")
        assertNull(clean(text))
    }

    @Test
    fun aVersionNoImageCarriesGivesNoImage() {
        assertNull(CleanRun.image("node", "1.2"))
        assertNull(CleanRun.image("node", "latest"))
        assertNull(CleanRun.image("nothing", null))
    }

    // #endregion

    // #region reading the output

    private val sig = CleanRun.failure("```\n$failure\n```\n")

    private fun read(output: String) = CleanRun.read(sig, output)

    @Test
    fun theSameFailureOnTheCleanMachineIsRecognisedThroughPathsAndNumbers() {
        assertEquals(CleanMatch.SAME, read("$failure\n").match)
        assertEquals(CleanMatch.SAME, read("\u001b[31m$failure\u001b[0m\nat x\n").match)
        assertEquals(CleanMatch.SAME, read("2024-01-02T03:04:05Z $failure\n").match)
        val paths =
            CleanRun.failure("Error: ENOENT: no such file or directory, open '/home/u/app/x.json'")
        val there = "Error: ENOENT: no such file or directory, open '/w/x.json'"
        assertEquals(CleanMatch.SAME, CleanRun.read(paths, there).match)
        val numbers = CleanRun.failure("Error: connect ECONNREFUSED 10.0.0.1:5432")
        assertEquals(
            CleanMatch.SAME,
            CleanRun.read(numbers, "Error: connect ECONNREFUSED 127.0.0.1:6543").match,
        )
    }

    @Test
    fun noErrorInTheOutputMeansItWorks() {
        assertEquals(CleanMatch.WORKS, read("Started on port 80\n").match)
        assertEquals(CleanMatch.WORKS, read("Tests: 0 failed, 5 passed\n").match)
        val empty = read("")
        assertEquals(CleanMatch.WORKS, empty.match)
        assertNull(empty.seen)
        assertEquals(CleanMatch.WORKS, read("  \n\n").match)
    }

    @Test
    fun anotherErrorKeepsTheCheckOpenAndSaysWhatDiffered() {
        val other = read("Error: Cannot find module 'left-pad'\n")
        assertEquals(CleanMatch.DIFFERENT, other.match)
        assertEquals("Error: Cannot find module 'left-pad'", other.seen)
        assertEquals("Error: Cannot find module './config'", other.wanted)
        assertEquals(CleanMatch.DIFFERENT, read("npm ERR! code ELIFECYCLE\n").match)
        assertEquals(CleanMatch.DIFFERENT, read("Segmentation fault (core dumped)\n").match)
    }

    @Test
    fun aHugeOutputIsReadInLinearTimeAndKeepsItsMeaning() {
        val noise = "line of ordinary output\n".repeat(20_000)
        assertEquals(CleanMatch.WORKS, read(noise).match)
        assertEquals(CleanMatch.SAME, read(noise + failure + "\n").match)
        val one = "x".repeat(400_000)
        assertEquals(CleanMatch.WORKS, read(one).match)
    }

    @Test
    fun aBinaryOutputIsNotReadable() {
        assertTrue(CleanRun.isBinary("a\u0000b"))
        assertTrue(CleanRun.isBinary("\uFFFD\uFFFD\uFFFDabc"))
        assertFalse(CleanRun.isBinary("plain\ttext\r\n\u001b[0m"))
        assertFalse(CleanRun.isBinary(""))
    }

    @Test
    fun aSignalOrTransportFailureMatchesByItsCanonicalForm() {
        val report = CleanRun.failure("fetch failed: connection reset by peer\n")
        assertEquals(
            CleanMatch.SAME,
            CleanRun.read(report, "request: ECONNRESET while reading\n").match,
        )
    }

    // #endregion

    // #region the session facts

    @Test
    fun aSameFailureReplyEndsInTheNotEnvironmentReading() {
        val text = report()
        val result = run(text, listOf(reply(CleanMatch.SAME)))
        val reading = assertNotNull(result.notEnvironment)
        assertEquals(NotEnvSignal.CLEAN_RUN, reading.signal)
        assertEquals(failure, reading.text)
        assertEquals(emptyList(), result.ask)
        assertEquals(JsonString("clean-run"), reading.toJson().fields["signal"])
    }

    @Test
    fun aWorksReplyLeadsToTheCaptureAndNeverToNotEnvironment() {
        val result = run(report(), listOf(reply(CleanMatch.WORKS)))
        assertNull(result.notEnvironment)
        assertEquals(Ask.CAPTURE, result.ask.first().family)
        assertTrue(result.ask.none { it.family == CleanRun.FAMILY })
        assertEquals(
            "drift why --save there.json && drift why --reply < there.json",
            result.ask.first().piped(),
        )
    }

    @Test
    fun anEnvironmentReportEndsInNotEnvironmentOnlyWhenTheCleanRunReproducesIt() {
        val text = "It works on node 20.11.1 and fails on node 18.19.0.\n\n" +
            "```\n\$ npm test\nSyntaxError: Unexpected token '?'\n```\n"
        val asked = run(text)
        assertEquals(1, asked.ask.count { it.family == CleanRun.FAMILY })
        assertNull(asked.notEnvironment)
        val same = run(text, listOf(reply(CleanMatch.SAME)))
        assertEquals(NotEnvSignal.CLEAN_RUN, same.notEnvironment?.signal)
        val works = run(text, listOf(reply(CleanMatch.WORKS)))
        assertNull(works.notEnvironment)
        assertEquals(Ask.CAPTURE, works.ask.first().family)
    }

    // #endregion
}
