package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.why.DockerExecHost
import dev.gmitch215.drift.cli.why.Place
import dev.gmitch215.drift.cli.why.PlaceException
import dev.gmitch215.drift.cli.why.ReplyCommand
import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.diff.DiffKind
import dev.gmitch215.drift.fixtures.CiFixtures
import dev.gmitch215.drift.fixtures.WhyFixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.FakeSsh
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.scan.Replies
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.Asks
import dev.gmitch215.drift.why.Families
import dev.gmitch215.drift.why.Session
import dev.gmitch215.drift.why.Sides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROOT = "/work/repo"
private const val BASELINE = ".drift/why/baseline"
private const val JARGON = "capsule|bundle|tier|attribute"

private fun ok(text: String) = CommandResult(0, text)

private fun limits(nofile: String) = "Limit  Soft Limit  Hard Limit  Units\n" +
    "Max open files   $nofile   $nofile   files\n"

/** What one side of a comparison holds: files, environment and the output of tool commands. */
private class Machine(
    val env: Map<String, String> = emptyMap(),
    val files: Map<String, String> = emptyMap(),
    val tools: Map<List<String>, CommandResult> = emptyMap(),
)

private val NODE = listOf("node", "--version")
private val PYTHON = listOf("python3", "--version")
private val GIT = listOf("git", "--version")

private fun node(version: String) = mapOf(NODE to ok("v$version\n"))

private fun docker(name: String, machine: Machine, running: String = "true") = buildMap {
    put(listOf("docker", "inspect", "--format", "{{.State.Running}}", name), ok("$running\n"))
    put(
        listOf("docker", "exec", name, "env"),
        ok(machine.env.entries.joinToString("") { "${it.key}=${it.value}\n" }),
    )
    put(listOf("docker", "exec", name, "uname", "-m"), ok("x86_64\n"))
    for ((path, text) in machine.files) put(listOf("docker", "exec", name, "cat", path), ok(text))
    for ((argv, result) in machine.tools) put(listOf("docker", "exec", name) + argv, result)
}

private fun local(machine: Machine, extra: Map<List<String>, CommandResult> = emptyMap()) =
    FakeHost(
        env = machine.env + ("PWD" to ROOT),
        files = machine.files,
        commands = machine.tools + extra,
    )

private object UnwritableFiles : CaseFiles {
    override fun read(path: String): String? = null

    override fun write(path: String, text: String) = false
}

private fun flat(text: String) = text.replace(Regex("\\s+"), " ")

private fun golden(name: String) = WhyFixtures.text("golden/$name.txt")

private fun tinyfmtLock(version: String) = mapOf(
    "package.json" to "{\"dependencies\":{\"tinyfmt\":\"^1.0.0\"}}",
    "package-lock.json" to "{\"lockfileVersion\":3,\"packages\":{\"\":" +
        "{\"dependencies\":{\"tinyfmt\":\"^1.0.0\"}},\"node_modules/tinyfmt\":" +
        "{\"version\":\"$version\"}}}",
)

class WhyCommandTest {
    private val disk = MemoryCaseFiles()

    private fun why(host: FakeHost, args: String) = DriftCommand(host, disk).test("why $args")

    private fun container(here: Machine, there: Machine, name: String = "web") =
        local(here, docker(name, there))

    private fun assertPlain(name: String, args: String, here: Machine, there: Machine) {
        val result = why(container(here, there), args)
        assertEquals(0, result.statusCode, result.output)
        assertEquals(golden(name), result.stdout, result.stdout)
        assertFalse(Regex(JARGON, RegexOption.IGNORE_CASE).containsMatchIn(result.stdout))
    }

    // #region flows through a container

    @Test
    fun aToolVersionThatDiffersIsNamedWithBothVersionsAndAPin() {
        val here = Machine(files = mapOf(".nvmrc" to "22\n"), tools = node("22.4.0"))
        val there = Machine(tools = node("20.11.1"))
        assertPlain("node-version", "--there docker:web", here, there)
    }

    @Test
    fun aVersionFileOnOneSideAloneIsNotALockedPackage() {
        val here = Machine(files = mapOf(".nvmrc" to "22\n"), tools = node("22.4.0"))
        val there = Machine(tools = node("22.4.0"))
        val result = why(container(here, there), "--there docker:web")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("project.pins.node" in result.stdout, result.stdout)
        assertFalse("Locked package" in result.stdout, result.stdout)
    }

    @Test
    fun theFailureTextPointsAtTheRuntimeEvenWhenOtherThingsDiffer() {
        val here = Machine(
            env = mapOf("TZ" to "UTC"),
            tools = node("22.4.0") + mapOf(listOf("node", "test.js") to ok("ok\n")),
        )
        val there = Machine(
            env = mapOf("TZ" to "America/New_York"),
            tools = node("20.11.1") + mapOf(
                listOf("node", "test.js") to CommandResult(
                    1,
                    "TypeError: Object.groupBy is not a function\n    at test.js:3:8\n",
                ),
            ),
        )
        val result = why(container(here, there), "--there docker:web -- node test.js")
        assertEquals(0, result.statusCode, result.output)
        assertEquals(golden("node-from-error-text"), result.stdout, result.stdout)
    }

    @Test
    fun aFailureInYourCodeWithNoDifferenceIsNotAnEnvironmentDifference() {
        val failing = CommandResult(1, "Error: bad\n    at test.js:3:8\n")
        val here = Machine(tools = node("22.4.0") + mapOf(listOf("node", "test.js") to ok("ok\n")))
        val there = Machine(tools = node("22.4.0") + mapOf(listOf("node", "test.js") to failing))
        val result = why(container(here, there), "--there docker:web -- node test.js")
        assertEquals(0, result.statusCode, result.output)
        assertTrue(
            "No environment difference explains this. The error points at your own code " +
                "(test.js:3): start there." in flat(result.stdout),
            result.stdout,
        )
    }

    @Test
    fun anUnrelatedDifferenceDoesNotHideTheProgramSideSignal() {
        val failing = CommandResult(1, "Expected: 3\nActual: 4\n    at test.js:3:8\n")
        val here = Machine(
            env = mapOf("TZ" to "UTC"),
            tools = node("22.4.0") + mapOf(
                listOf("node", "test.js") to ok("ok\n"),
            ),
        )
        val there = Machine(
            env = mapOf("TZ" to "America/New_York"),
            tools = node("22.4.0") +
                mapOf(listOf("node", "test.js") to failing),
        )
        val result = why(container(here, there), "--there docker:web -- node test.js")
        assertEquals(0, result.statusCode, result.output)
        assertTrue(
            "No environment difference explains this. The error points at your own code " +
                "(test.js:3)" in flat(result.stdout),
            result.stdout,
        )
    }

    @Test
    fun aNamedDifferenceKeepsTheEnvironmentAnswer() {
        val failing = CommandResult(
            1,
            "TypeError: Object.groupBy is not a function\n" +
                "    at test.js:3:8\n",
        )
        val here = Machine(tools = node("22.4.0") + mapOf(listOf("node", "test.js") to ok("ok\n")))
        val there = Machine(tools = node("20.11.1") + mapOf(listOf("node", "test.js") to failing))
        val result = why(container(here, there), "--there docker:web -- node test.js")
        assertEquals(0, result.statusCode, result.output)
        assertFalse("No environment difference" in flat(result.stdout), result.stdout)
        assertTrue("Node is 20.11.1 there and 22.4.0 here" in flat(result.stdout), result.stdout)
    }

    @Test
    fun severalDifferencesThatFitAreListedWithTheWayToSeparateThem() {
        val here = Machine(
            env = mapOf("TZ" to "UTC", "LANG" to "C.UTF-8"),
            tools = mapOf(PYTHON to ok("Python 3.12.1\n")),
        )
        val there = Machine(
            env = mapOf("TZ" to "America/New_York", "LANG" to "POSIX"),
            tools = mapOf(PYTHON to ok("Python 3.12.1\n")),
        )
        assertPlain("tie", "--there docker:web", here, there)
        assertTrue(".drift/why/case/case.json" in disk.files, disk.files.keys.toString())
    }

    @Test
    fun nothingDifferingSaysWhatWasAndWasNotLookedAt() {
        val same = Machine(env = mapOf("TZ" to "UTC"), tools = node("22.4.0"))
        assertPlain("nothing-differs", "--there docker:web", same, same)
    }

    @Test
    fun aToolMissingThereIsNamedAsMissing() {
        val here = Machine(tools = mapOf(GIT to ok("git version 2.39.5\n")))
        val there = Machine()
        assertPlain("missing-tool", "--there docker:web", here, there)
    }

    @Test
    fun theErrorTextFromAMissingCommandPointsAtTheMissingTool() {
        val run = listOf("sh", "check.sh")
        val here = Machine(tools = mapOf(GIT to ok("git version 2.39.5\n"), run to ok("")))
        val there = Machine(
            tools = mapOf(run to CommandResult(127, "check.sh: 1: git: not found\n")),
            env = mapOf("TZ" to "Asia/Tokyo"),
        )
        val result = why(container(here, there), "--there docker:web -- sh check.sh")
        val text = flat(result.stdout)
        assertTrue("Git is not installed there (2.39.5 here)" in text, text)
        assertTrue("the error text points at it" in text, text)
        assertFalse("Time zone" in text, text)
    }

    @Test
    fun anOpenFileLimitIsNamedWithTheUlimitAndContainerFixes() {
        val here = Machine(files = mapOf("/proc/self/limits" to limits("1024")))
        val there = Machine(files = mapOf("/proc/self/limits" to limits("64")))
        val local = local(here, docker("web", there))
        val result = why(local, "--there docker:web")
        assertEquals(golden("open-file-limit"), result.stdout, result.stdout)
        assertTrue("ulimit -n 1024" in result.stdout)
        assertTrue("--ulimit nofile=1024:1024" in result.stdout)
        assertTrue(
            "If ulimit -n 1024 says 'Operation not permitted', the hard limit is the cap (64 " +
                "there): raise it in the service unit (LimitNOFILE)" in flat(result.stdout),
            result.stdout,
        )
    }

    @Test
    fun anOpenFileLimitWithoutAHardValueSaysTheCapWithoutOne() {
        val fix = Families.describe(change("limits.nofile", "1024", "64"), Sides.HERE_THERE).fix
        assertTrue(
            "If ulimit -n 1024 says 'Operation not permitted', the hard limit is the cap: raise " +
                "it in the service unit" in fix,
            fix,
        )
        assertFalse("(64 there)" in fix, fix)
    }

    @Test
    fun aLockedPackageThatDiffersIsNamedWithTheInstallFix() {
        assertPlain(
            "locked-package",
            "--there docker:web",
            Machine(files = tinyfmtLock("2.0.0")),
            Machine(files = tinyfmtLock("1.1.0")),
        )
    }

    @Test
    fun failsHereSwapsTheDirectionAndSaysSo() {
        val here = Machine(tools = node("20.11.1"))
        val there = Machine(tools = node("22.4.0"))
        val result = why(container(here, there), "--there docker:web --fails-here")
        val text = flat(result.stdout)
        assertTrue("Assuming it works there and fails here" in text, text)
        assertTrue("Node is 20.11.1 here and 22.4.0 there" in text, text)
    }

    @Test
    fun theCommandDecidesTheDirectionWhenOnlyOneSideFails() {
        val fails = CommandResult(1, "TypeError: x is not a function\n")
        val here = Machine(tools = node("20.11.1") + mapOf(listOf("sh", "t") to fails))
        val there = Machine(tools = node("22.4.0") + mapOf(listOf("sh", "t") to ok("")))
        val result = why(container(here, there), "--there docker:web -- sh t")
        val text = flat(result.stdout)
        val because = "It most likely fails here because Node is 20.11.1 here and 22.4.0 there"
        assertTrue(because in text, text)
        assertFalse("Assuming" in text)
    }

    @Test
    fun aCommandThatPassesInBothPlacesHasNothingToExplain() {
        val pass = mapOf(listOf("sh", "t") to ok(""))
        val result = why(
            container(Machine(tools = pass), Machine(tools = pass)),
            "--there docker:web -- sh t",
        )
        assertTrue("It passes in both places, so there is nothing to explain." in result.stdout)
    }

    @Test
    fun anAbsentContainerToolAnswersAsAbsentNotAsAnError() {
        val host = local(Machine(), docker("web", Machine()))
        val remote = DockerExecHost.open(host, "web", null)
        assertEquals(127, remote.run(listOf("node", "--version"))?.exitCode)
        val missing = FakeHost(
            commands = docker("web", Machine()) + mapOf(
                listOf("docker", "exec", "web", "node", "--version") to CommandResult(
                    127,
                    "OCI runtime exec failed: exec failed: unable to start container process: " +
                        "exec: \"node\": executable file not found in \$PATH: unknown",
                ),
            ),
        )
        val absent = DockerExecHost.open(missing, "web", null).run(NODE)
        assertEquals(CommandResult(127, ""), absent)
    }

    // #endregion

    // #region failure messages

    @Test
    fun aContainerThatIsNotRunningIsNeverStarted() {
        val host = FakeHost(commands = docker("web", Machine(), running = "false"))
        val result = why(host, "--there docker:web")
        assertEquals(1, result.statusCode)
        assertTrue("container web is not running; start it yourself" in result.stderr)
        assertTrue("(docker start web)" in result.stderr)
        assertTrue(host.ran.none { it.getOrNull(1) == "start" })
    }

    @Test
    fun aMissingContainerAndAMissingDockerSayWhatToTry() {
        val gone = FakeHost(
            commands = mapOf(
                listOf("docker", "inspect", "--format", "{{.State.Running}}", "web") to
                    CommandResult(1, "Error: No such object: web"),
            ),
        )
        assertTrue(
            "no container named web; docker ps lists the running ones" in
                why(gone, "--there docker:web").stderr,
        )
        val none = FakeHost()
        val missing = why(none, "--there docker:web")
        assertTrue("cannot start docker; install Docker" in missing.stderr, missing.stderr)
        val bad = why(none, "--there docker:-v")
        assertTrue("is not a container name" in bad.stderr, bad.stderr)
    }

    @Test
    fun anUnreachableMachineNamesTheNextStep() {
        val base = FakeSsh(
            sshResult = CommandResult(255, "ssh: Could not resolve hostname nowhere: not known"),
        )
        val result = DriftCommand(base, disk).test("why --there me@nowhere")
        assertEquals(1, result.statusCode, result.output)
        assertTrue("cannot resolve the name me@nowhere" in result.stderr, result.stderr)
        assertTrue("check it and your ssh config" in result.stderr)
    }

    @Test
    fun aCommandOverSshOrFromAFileIsRefusedWithTheReason() {
        val result = why(FakeHost(), "--there me@build-02 -- make test")
        assertEquals(1, result.statusCode)
        assertTrue("a command can only run in a container (docker:NAME)" in result.stderr)
        assertTrue("Drop the command" in result.stderr)
    }

    @Test
    fun aMissingCaptureFileSaysHowToMakeOne() {
        val result = why(FakeHost(), "--there missing/there.json")
        assertEquals(1, result.statusCode)
        assertTrue("cannot read missing/there.json as a saved capture" in result.stderr)
        assertTrue("drift why --save FILE" in result.stderr, result.stderr)
        assertFalse("drift capture" in result.stderr, result.stderr)
    }

    @Test
    fun noGitCheckoutSaysWhatElseToTry() {
        val result = why(FakeHost(), "")
        assertEquals(1, result.statusCode)
        assertTrue("not inside a git checkout" in result.stderr, result.stderr)
        assertTrue("Other ways in: drift why --there PLACE" in result.stderr, result.stderr)
    }

    @Test
    fun aCheckoutWithNoRemoteSaysSoAndWhatElseToTry() {
        val host = FakeHost(
            commands = mapOf(listOf("git", "rev-parse", "--show-toplevel") to ok("$ROOT\n")),
        )
        val result = why(host, "")
        assertEquals(1, result.statusCode)
        val text = flat(result.stderr)
        assertTrue("this checkout has no remote named origin" in text, text)
        assertTrue("needs a git checkout whose remote is on GitHub or GitLab" in text, text)
        assertTrue("Other ways in: drift why --there PLACE" in text, text)
    }

    @Test
    fun aMissingFileOrABareCommandNamesTheDashes() {
        val result = why(FakeHost(), "make test")
        assertEquals(1, result.statusCode)
        assertTrue("To run a command, put -- before it: drift why -- make test" in result.stderr)
        val both = why(FakeHost(), "--json --detail x")
        assertTrue("--json and --detail cannot be combined" in both.stderr)
    }

    // #endregion

    // #region a saved capture

    @Test
    fun aSavedCaptureIsComparedAndTheFamiliesItLacksAreLeftOut() {
        val sparse = Capsule(
            "there",
            listOf(Attribute("env.TZ", "America/New_York", "env", Stability.STATIC)),
        )
        val dir = "saved"
        disk.write("$dir/capsule.json", sparse.canonical())
        val host = FakeHost(
            env = mapOf("TZ" to "UTC", "PWD" to ROOT),
            files = disk.files,
            commands = node("22.4.0"),
        )
        val result = why(host, "--there $dir")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("the saved capture in $dir" in result.stdout.lines().first())
        val zone = "Time zone (TZ): UTC here, America/New_York there"
        assertTrue(zone in result.stdout, result.stdout)
        assertTrue("Node" !in result.stdout.substringBefore("What to try next"), result.stdout)
        assertTrue("has no tool facts" in result.stdout, result.stdout)
    }

    // #endregion

    // #region saving and error text

    private val scenario4Env = mapOf(
        "CI" to "false",
        "COLORTERM" to "truecolor",
        "EDITOR" to "vim",
        "GOFLAGS" to "-mod=vendor",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "MAKEFLAGS" to "-j8",
        "NODE_ENV" to "development",
        "NPM_CONFIG_REGISTRY" to "https://registry.npmjs.org/",
        "PAGER" to "less",
        "RUST_LOG" to "info",
        "TMPDIR" to "/tmp",
    )

    @Test
    fun saveWritesThisMachineAndTheFileReadsBackThroughThere() {
        val failing = Machine(env = mapOf("TZ" to "UTC"), tools = node("22.4.0"))
        val saved = why(local(failing), "--save there.json")
        assertEquals(0, saved.statusCode, saved.output)
        assertEquals(
            listOf(
                "Saved this machine to $ROOT/there.json.",
                "Copy it to the machine where the program works.",
                "Then run this there: drift why --there there.json",
            ),
            saved.stdout.lines().take(3),
        )
        val back = FakeHost(
            env = mapOf("TZ" to "Asia/Tokyo", "PWD" to ROOT),
            files = disk.files,
            commands = node("22.4.0"),
        )
        val result = why(back, "--there there.json")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("Time zone (TZ): Asia/Tokyo here, UTC there" in result.stdout, result.stdout)
    }

    @Test
    fun saveRefusesToReplaceAFileUnlessForced() {
        disk.write("there.json", "old\n")
        val host = local(Machine(tools = node("22.4.0")))
        val refused = why(host, "--save there.json")
        assertEquals(1, refused.statusCode)
        assertTrue(
            "there.json already exists. Choose another name, or add --force to replace it." in
                refused.stderr,
            refused.stderr,
        )
        assertEquals("old\n", disk.files.getValue("there.json"))
        val forced = why(host, "--save there.json --force")
        assertEquals(0, forced.statusCode, forced.output)
        assertFalse(disk.files.getValue("there.json") == "old\n")
    }

    @Test
    fun saveAndForceTakeNoOtherArguments() {
        val host = local(Machine())
        val combined = why(host, "--save there.json --there docker:web")
        assertEquals(1, combined.statusCode)
        assertTrue(
            "--save cannot be combined with --there, a command or a file" in combined.stderr,
            combined.stderr,
        )
        val alone = why(host, "--force")
        assertEquals(1, alone.statusCode)
        assertTrue("--force only applies with --save" in alone.stderr, alone.stderr)
    }

    @Test
    fun errorTextFromAFileNamesTheRuntimeInTheSavedCapture() {
        val host = FakeHost(
            env = mapOf("TZ" to "UTC", "PWD" to ROOT),
            files = mapOf(
                "there.json" to WhyFixtures.text("scenario-1-fails.json"),
                "err.txt" to "TypeError: Object.groupBy is not a function\n    at test.js:3:8\n",
            ),
            commands = node("22.4.0"),
        )
        val result = why(host, "--there there.json --error @err.txt")
        assertEquals(0, result.statusCode, result.output)
        assertEquals(golden("error-node-runtime"), result.stdout, result.stdout)
    }

    @Test
    fun errorTextFromStandardInputPointsAtTheMissingTool() {
        val host = FakeHost(
            env = scenario4Env + ("PWD" to ROOT),
            files = mapOf(
                "there.json" to WhyFixtures.text("scenario-4-fails.json"),
                "/dev/stdin" to "sh: 1: git: not found\n",
            ),
            commands = node("22.4.0") + mapOf(GIT to ok("git version 2.39.5\n")),
        )
        val result = why(host, "--there there.json --error -")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue(
            "it most likely fails there because Git is not installed there (2.39.5 here)" in text,
            text,
        )
        assertTrue("(your error text names it: git)" in text, text)
    }

    @Test
    fun errorTextFromAFileNamesAMissingToolAmongOtherDifferences() {
        val env = scenario4Env.mapValues { (_, v) -> "$v-here" }
        val host = FakeHost(
            env = env + ("PWD" to ROOT),
            files = mapOf(
                "there.json" to WhyFixtures.text("scenario-4-fails.json"),
                "err.txt" to "sh: 1: git: not found\n",
            ),
            commands = node("22.4.0") + mapOf(GIT to ok("git version 2.39.5\n")),
        )
        val result = why(host, "--there there.json --error @err.txt")
        assertEquals(0, result.statusCode, result.output)
        assertEquals(golden("error-missing-tool"), result.stdout, result.stdout)
    }

    @Test
    fun errorTextNamingAToolMakesItTheOnlyCause() {
        val env = scenario4Env.mapValues { (_, v) -> "$v-here" }
        val host = FakeHost(
            env = env + ("PWD" to ROOT),
            files = mapOf(
                "there.json" to WhyFixtures.text("scenario-4-fails.json"),
                "err.txt" to "SH: 1: GIT: NOT FOUND!\n",
            ),
            commands = node("22.4.0") + mapOf(GIT to ok("git version 2.39.5\n")),
        )
        val result = why(host, "--there there.json --error @err.txt")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue(
            "it most likely fails there because Git is not installed there (2.39.5 here)" in text,
            text,
        )
        assertTrue(
            "Git version: 2.39.5 here, not installed there (your error text names it: git)" in text,
            text,
        )
        assertTrue(
            "12 other differences exist, but the error text does not point at them: Git version " +
                "is the only one it names." in text,
            text,
        )
    }

    @Test
    fun errorTextNamingTwoToolsKeepsThemTiedAheadOfTheRest() {
        val here = Machine(
            files = mapOf("err.txt" to "git: not found and node: not found\n"),
            tools = node("22.4.0") + mapOf(GIT to ok("git version 2.39.5\n")),
        )
        val result = why(container(here, Machine()), "--there docker:web --error @err.txt")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue("2 differences" in text, text)
        assertTrue(
            "Node version: 22.4.0 here, not installed there " +
                "(your error text names it: node)" in text,
            text,
        )
        assertTrue(
            "Git version: 2.39.5 here, not installed there " +
                "(your error text names it: git)" in text,
            text,
        )
    }

    @Test
    fun anEnvironmentVariableNamedInTheErrorTextIsTheCause() {
        val here = Machine(
            env = mapOf("NODE_OPTIONS" to "--max-old-space-size=4096"),
            files = mapOf("err.txt" to "error: NODE_OPTIONS has an invalid value\n"),
        )
        val result = why(container(here, Machine()), "--there docker:web --error @err.txt")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue(
            "it most likely fails there because NODE_OPTIONS is set to " +
                "--max-old-space-size=4096 here and not set there" in text,
            text,
        )
        assertTrue("(your error text names it: node_options)" in text, text)
    }

    @Test
    fun aPackageNamedInTheErrorTextIsTheCause() {
        val here = Machine(
            files = tinyfmtLock("2.0.0") + mapOf("err.txt" to "Cannot find module 'tinyfmt'\n"),
        )
        val there = Machine(files = tinyfmtLock("1.1.0"))
        val result = why(container(here, there), "--there docker:web --error @err.txt")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue(
            "Locked package tinyfmt: 2.0.0 here, 1.1.0 there (your error text names it: tinyfmt)" in
                text,
            text,
        )
    }

    @Test
    fun aSavedCaptureAsksForTheErrorMessageInsteadOfACommand() {
        val there = Machine(
            env = mapOf("TZ" to "America/New_York", "LANG" to "POSIX"),
            tools = mapOf(PYTHON to ok("Python 3.12.1\n")),
        )
        val saved = why(local(there), "--save there.json")
        assertEquals(0, saved.statusCode, saved.output)
        val back = FakeHost(
            env = mapOf("TZ" to "UTC", "LANG" to "C.UTF-8", "PWD" to ROOT),
            files = disk.files,
            commands = mapOf(PYTHON to ok("Python 3.12.1\n")),
        )
        val result = why(back, "--there there.json")
        assertEquals(0, result.statusCode, result.output)
        assertTrue(
            "Paste the error message you saw: drift why --there there.json --error \"...\"" in
                result.stdout,
            result.stdout,
        )
        assertFalse("-- COMMAND" in result.stdout, result.stdout)
    }

    @Test
    fun errorTextWithNoKnownPhraseLeavesTheAnswerAsItWas() {
        val host = FakeHost(
            env = mapOf("TZ" to "UTC", "PWD" to ROOT),
            files = mapOf("there.json" to WhyFixtures.text("scenario-1-fails.json")),
            commands = node("22.4.0"),
        )
        val plain = why(host, "--there there.json")
        val unmatched = why(host, "--there there.json --error unrelated")
        assertEquals(0, unmatched.statusCode, unmatched.output)
        val note = "Your error text matches no known failure phrase, so the answer is the same " +
            "as without it.\n\n"
        assertTrue(unmatched.stdout.startsWith(note), unmatched.stdout)
        assertEquals(
            plain.stdout.substringBefore("What to try next"),
            unmatched.stdout.removePrefix(note).substringBefore("What to try next"),
        )
    }

    @Test
    fun errorTextLongerThan64KibIsCutAndSaysSo() {
        val big = "x".repeat(70_000) + "\nnot found\n"
        val here = Machine(files = mapOf("big.txt" to big), tools = node("22.4.0"))
        val there = Machine(tools = node("22.4.0"))
        val result = why(container(here, there), "--there docker:web --error @big.txt")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("longer than 64 KiB, so only the first 64 KiB was read" in result.stdout)
    }

    @Test
    fun errorTextAcceptedOnlyWhereItCanPointAtAnAnswer() {
        val withCommand = why(local(Machine()), "--there docker:web --error x -- sh check.sh")
        assertEquals(1, withCommand.statusCode)
        assertTrue("--error and -- COMMAND cannot be combined" in withCommand.stderr)
        val withFile = why(local(Machine()), "--error x issue.md")
        assertEquals(1, withFile.statusCode)
        assertTrue("--error cannot be combined with an issue file" in withFile.stderr)
    }

    @Test
    fun errorFileThatCannotBeReadSaysSo() {
        val result = why(local(Machine()), "--there docker:web --error @missing.txt")
        assertEquals(1, result.statusCode)
        assertTrue("cannot read missing.txt" in result.stderr, result.stderr)
    }

    @Test
    fun theHelpNamesTheSaveAndErrorFlags() {
        val help = DriftCommand(FakeHost(), disk).test("why --help").stdout
        assertTrue("--save=<file>" in help && "--error=<text>" in help, help)
        val manual = DriftCommand(FakeHost(), disk).test("help why").stdout
        assertTrue("--save FILE" in manual && "--error TEXT" in manual, manual)
    }

    // #endregion

    // #region a machine over ssh

    @Test
    fun anSshMachineIsReadWithoutToolsAndTheAnswerSaysSo() {
        val remote = FakeSsh(
            remoteEnv = mapOf("TZ" to "Asia/Tokyo"),
            remoteFiles = mapOf("/proc/self/limits" to limits("64")),
        )
        val base = FakeSsh(machines = mapOf("me@build-02" to remote))
        val result = DriftCommand(base, disk).test("why --there me@build-02")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("read over ssh" in result.stdout.lines().first(), result.stdout)
        assertTrue("Over ssh I read system files and environment variables only" in result.stdout)
        assertTrue("running read-only fact commands on me@build-02" in result.stderr)
    }

    // #endregion

    // #region run a command here

    private fun commandHost(exit: Int, output: String = "") = FakeHost(
        env = mapOf("PWD" to ROOT),
        files = disk.files,
        commands = mapOf(listOf("sh", "t.sh") to CommandResult(exit, output)) + node("22.4.0"),
    )

    @Test
    fun aPassingCommandSavesTheBaselineInOneLine() {
        val result = why(commandHost(0), "-- sh t.sh")
        assertEquals(0, result.statusCode, result.output)
        assertEquals(golden("baseline-saved"), result.stdout, result.stdout)
        assertTrue("$BASELINE/capsule.json" in disk.files)
        assertTrue("sh t.sh" in disk.files.getValue("$BASELINE/result.json"))
    }

    @Test
    fun aFailureIsComparedWithTheSavedBaseline() {
        why(commandHost(0), "-- sh t.sh")
        val failing = FakeHost(
            env = mapOf("PWD" to ROOT),
            files = disk.files,
            commands = mapOf(
                listOf("sh", "t.sh") to CommandResult(1, "TypeError: x is not a function\n"),
            ) + node("20.11.1"),
        )
        val result = why(failing, "-- sh t.sh")
        assertEquals(0, result.statusCode, result.output)
        assertEquals(golden("baseline-compared"), result.stdout, result.stdout)
    }

    @Test
    fun aFailureWithNoBaselineSaysWhatItCannotDoAndWhatToDo() {
        val result = why(commandHost(1, "boom\n"), "-- sh t.sh")
        assertEquals(0, result.statusCode, result.output)
        assertEquals(golden("no-baseline"), result.stdout, result.stdout)
        assertTrue(disk.files.keys.none { it.startsWith(BASELINE) })
    }

    @Test
    fun aBaselineFromAnotherCommandIsNamed() {
        val other = FakeHost(
            env = mapOf("PWD" to ROOT),
            files = disk.files,
            commands = mapOf(listOf("sh", "other.sh") to ok("")) + node("22.4.0"),
        )
        why(other, "-- sh other.sh")
        val result = why(commandHost(1, "boom\n"), "-- sh t.sh")
        assertTrue(
            "The baseline was saved from a different command (sh other.sh)." in flat(result.stdout),
            result.stdout,
        )
    }

    // #endregion

    // #region an issue text

    @Test
    fun anIssueTextNamesTheFactItPointsAt() {
        val host = FakeHost(files = mapOf("issue.md" to WhyFixtures.text("issue-git.md")))
        val result = why(host, "issue.md")
        assertEquals(0, result.statusCode, result.output)
        assertEquals(golden("issue-git"), result.stdout, result.stdout)
    }

    @Test
    fun anIssueWithNoFactSaysSoAndStandardInputWorks() {
        val host = FakeHost(files = mapOf("/dev/stdin" to "It is broken, please fix.\n"))
        val result = why(host, "-")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("I found no environment fact in this report" in result.stdout, result.stdout)
        assertTrue("What to try next:" in result.stdout)
    }

    @Test
    fun anIssueWithAFrameInYourCodeAndNoEnvironmentFactSaysItIsNotTheEnvironment() {
        val host = FakeHost(files = mapOf("issue.md" to "TypeError: x\n    at test.js:3:8\n"))
        val result = why(host, "issue.md")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue(
            "This does not look like an environment difference. The report points at your own " +
                "code (test.js:3): start there." in text,
            result.stdout,
        )
        assertTrue("What to try next: Open test.js:3" in text, result.stdout)
        assertFalse(Regex(JARGON, RegexOption.IGNORE_CASE).containsMatchIn(result.stdout))
    }

    @Test
    fun theIssueJsonNamesTheKindOnlyWhenTheClassApplies() {
        val host = FakeHost(files = mapOf("issue.md" to "TypeError: x\n    at test.js:3:8\n"))
        val json = CanonicalJson.parse(why(host, "--json issue.md").stdout) as JsonObject
        val plain = json.require("plain") as JsonObject
        assertEquals("not-environment", plain.require("kind").string())
        assertTrue(json.require("notEnvironment") is JsonObject)
        val plainHost = FakeHost(files = mapOf("issue.md" to "It is broken, please fix.\n"))
        val other = CanonicalJson.parse(why(plainHost, "--json issue.md").stdout) as JsonObject
        assertNull((other.require("plain") as JsonObject)["kind"])
        assertNull(other["notEnvironment"])
    }

    @Test
    fun anIssueThatNamesAnEnvironmentPhraseIsNotCalledNotEnvironment() {
        val issue = "TypeError: Object.groupBy is not a function\n    at test.js:3:8\n"
        val result = why(FakeHost(files = mapOf("issue.md" to issue)), "issue.md")
        assertEquals(0, result.statusCode, result.output)
        val said = "This does not look like an environment difference"
        assertFalse(said in flat(result.stdout), result.stdout)
    }

    // #region asks and replies

    private val session = ".drift/why/session.json"
    private val asking = "The build fails on our server with node and npm.\n"

    private fun asked(text: String = asking, args: String = "issue.md"): Pair<FakeHost, String> {
        val host = FakeHost(files = mapOf("issue.md" to text))
        return host to why(host, args).stdout
    }

    private fun replyHost(stdin: String) = FakeHost(files = mapOf("/dev/stdin" to stdin))

    private fun plainOf(json: String) =
        (CanonicalJson.parse(json) as JsonObject).require("plain") as JsonObject

    @Test
    fun aReportThatLeavesAToolOpenAsksForOneCheckAtATime() {
        val (_, out) = asked()
        val text = flat(out)
        assertTrue(
            "To tell, run node --version where it fails and paste the result: the report " +
                "names Node but not which version fails." in text,
            out,
        )
        assertTrue("In one step: node --version | drift why --reply" in text, out)
        assertFalse("npm --version" in text, out)
        assertFalse(Regex(JARGON, RegexOption.IGNORE_CASE).containsMatchIn(out))
    }

    @Test
    fun theJsonKeepsTheWholeListOfChecks() {
        val host = FakeHost(files = mapOf("issue.md" to asking))
        val plain = plainOf(why(host, "--json issue.md").stdout)
        val list = (plain.require("ask") as JsonArray).items.map { it as JsonObject }
        assertEquals(listOf("tool.node", "tool.npm"), list.map { it.require("family").string() })
        assertEquals("node --version", list[0].require("command").string())
        assertEquals("node --version | drift why --reply", list[0].require("piped").string())
        val none = FakeHost(files = mapOf("issue.md" to "It is broken, please fix.\n"))
        assertNull(plainOf(why(none, "--json issue.md").stdout)["ask"])
    }

    @Test
    fun askingWritesTheSessionFile() {
        val (_, _) = asked()
        val saved = CanonicalJson.parse(disk.read(session)!!) as JsonObject
        assertEquals(1L, (saved.require("schema") as JsonInt).value)
        assertTrue("node and npm" in saved.require("report").string())
        val asks = (saved.require("asks") as JsonArray).items.map { it as JsonObject }
        assertEquals(listOf("tool.node", "tool.npm"), asks.map { it.require("family").string() })
        assertEquals(
            listOf("node", "--version"),
            (asks[0].require("argv") as JsonArray).items.map {
                it.string()
            },
        )
        assertTrue(asks.all { it["reply"] is JsonNull })
    }

    @Test
    fun theSessionNeverKeepsASecretFromTheReport() {
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        asked("Fails with node. Token $token was in the log.\n")
        assertFalse(token in disk.read(session)!!)
    }

    @Test
    fun anUnwritableFolderStillGetsTheAnswerAndANote() {
        val host = FakeHost(files = mapOf("issue.md" to asking))
        val result = DriftCommand(host, UnwritableFiles).test("why issue.md")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("run node --version where it fails" in flat(result.stdout), result.stdout)
        assertTrue("I could not write $session;" in result.stderr, result.stderr)
        val json = DriftCommand(host, UnwritableFiles).test("why --json issue.md")
        assertEquals(0, json.statusCode, json.output)
        assertNotNull(plainOf(json.stdout)["ask"])
        assertTrue("I could not write $session;" in json.stderr, json.stderr)
    }

    @Test
    fun aNothingToAskReportWritesNoSession() {
        asked("It is broken, please fix.\n")
        assertNull(disk.read(session))
    }

    @Test
    fun sessionOverridesThePathAndTheOneStepForm() {
        val (_, out) = asked(args = "--session mine.json issue.md")
        assertTrue("node --version | drift why --reply --session mine.json" in flat(out), out)
        assertNotNull(disk.read("mine.json"))
        assertNull(disk.read(session))
    }

    @Test
    fun aReplyAnswersTheFirstOpenCheckAndAsksTheNext() {
        asked()
        val result = why(replyHost("v18.19.0\n"), "--reply")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue("Node version: not stated where it works, 18.19.0 where it fails" in text, text)
        assertTrue("Run node --version where it works. If you run it there" in text, text)
        assertFalse("npm --version" in text, text)
        val saved = Session.parse(disk.read(session)!!)!!
        assertEquals(
            listOf("tool.node"),
            saved.asks.filter {
                it.reply != null
            }.map { it.ask.family },
        )
        assertEquals(listOf("tool.npm"), saved.open.map { it.ask.family })
    }

    @Test
    fun theLastReplyEndsTheQuestionsAndSaysSo() {
        asked()
        why(replyHost("v18.19.0\n"), "--reply")
        val last = why(replyHost("10.2.3\n"), "--reply")
        assertEquals(0, last.statusCode, last.output)
        val text = flat(last.stdout)
        assertFalse("To tell, run" in text, text)
        assertTrue("npm version: " in text || "npm" in text, text)
        assertEquals(emptyList(), Session.parse(disk.read(session)!!)!!.open)
        val again = why(replyHost("10.2.3\n"), "--reply")
        assertEquals(1, again.statusCode)
        assertTrue("every question" in flat(again.stderr), again.stderr)
    }

    @Test
    fun aReplyThatIsNotTheCommandsOutputKeepsTheCheckOpen() {
        asked()
        val before = disk.read(session)
        val result = why(replyHost("command not found: node\n"), "--reply")
        assertEquals(1, result.statusCode)
        val text = flat(result.stderr)
        assertTrue("I could not read that as the output of node --version" in text, text)
        assertTrue("node --version | drift why --reply" in text, text)
        assertEquals(before, disk.read(session))
    }

    @Test
    fun aReplyNeedsASessionAndNothingElse() {
        val none = why(replyHost("v18.19.0\n"), "--reply")
        assertEquals(0, none.statusCode, none.output)
        assertTrue("There is no session here, so I cannot compare yet." in flat(none.stdout))
        val unreadable = why(replyHost("hello there\n"), "--reply")
        assertEquals(1, unreadable.statusCode)
        assertTrue("there is no session here" in flat(unreadable.stderr), unreadable.stderr)
        val extra = why(replyHost("x"), "--reply issue.md")
        assertEquals(1, extra.statusCode)
        assertTrue("--reply reads standard input" in extra.stderr, extra.stderr)
        disk.write(session, "not json")
        val broken = why(replyHost("x"), "--reply")
        assertEquals(1, broken.statusCode)
        assertTrue(
            "cannot read .drift/why/session.json as a drift why session" in flat(broken.stderr),
        )
    }

    @Test
    fun aReplyAnswersWithTheSessionOfAnotherPathAndJson() {
        asked(args = "--session mine.json issue.md")
        val result = why(replyHost("v18.19.0\n"), "--reply --session mine.json --json")
        assertEquals(0, result.statusCode, result.output)
        val plain = plainOf(result.stdout)
        val list = (plain.require("ask") as JsonArray).items.map { it as JsonObject }
        assertEquals(
            "node --version | drift why --reply --works --session mine.json",
            list[0].require("pipedWorks").string(),
        )
        assertEquals(
            "npm --version | drift why --reply --session mine.json",
            list[1].require("piped").string(),
        )
    }

    // #region the works side

    @Test
    fun afterTheFailingSideTheWorksSideComesBeforeTheNextCheck() {
        asked()
        val result = why(replyHost("v18.19.0\n"), "--reply")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue(
            "I have no recorded rule that explains this failure from Node version 18.19.0 " +
                "alone, so I cannot say whether it matters yet." in text,
            text,
        )
        assertFalse("know of no rule" in text, text)
        assertTrue(
            "Run node --version where it works. If you run it there with `| drift why --reply " +
                "--works`, Drift prints one line to copy back to where you started; or copy " +
                "the output, go back to where you started (the folder where you ran drift " +
                "why), run `drift why " +
                "--reply --works`, paste it and press Ctrl-D (Ctrl-Z then Enter on Windows)." in
                text,
            text,
        )
        assertTrue(
            "If you can reach that machine by ssh: `ssh USER@HOST node --version | drift why " +
                "--reply --works`" in text,
            text,
        )
        assertFalse("npm --version" in text, text)
    }

    @Test
    fun aToolSettingWithoutARowIsNamedInWordsNeverByItsKey() {
        val sides = Sides("where it works", "where it fails", assumed = false)
        fun label(path: String) = Families.describe(path, null, "x", sides).label
        assertEquals("PHP interface (sapi)", label("tool.php.sapi"))
        assertEquals("Git flavour", label("tool.git.flavour"))
        assertEquals("zzz build mode", label("tool.zzz.build-mode"))
        asked("The build fails on the server with php.\n")
        val result = why(replyHost("PHP 7.4.33 (cli) (built: Jan  1 2024) ( NTS )\n"), "--reply")
        val text = flat(result.stdout)
        val line = "PHP interface (sapi): not stated where it works, cli where it fails"
        assertTrue(line in text, text)
        assertFalse("tool.php." in text, text)
    }

    @Test
    fun theJsonCarriesBothPipedFormsAndTheSessionPath() {
        asked(args = "--session mine.json issue.md")
        val json = why(replyHost("v18.19.0\n"), "--reply --session mine.json --json")
        val list = (plainOf(json.stdout).require("ask") as JsonArray).items.map { it as JsonObject }
        val first = list.first()
        assertEquals("tool.node", first.require("family").string())
        assertEquals(
            "node --version | drift why --reply --session mine.json",
            first.require("piped").string(),
        )
        assertEquals(
            "node --version | drift why --reply --works --session mine.json",
            first.require("pipedWorks").string(),
        )
        assertEquals(
            "Run node --version where it works. If you run it there with `| drift why --reply " +
                "--works --session mine.json`, Drift prints one line to copy back to where you " +
                "started; or copy the output, go back to where you started (the folder where " +
                "you ran drift why), run " +
                "`drift why --reply --works --session mine.json`, paste it and press Ctrl-D " +
                "(Ctrl-Z then Enter on Windows).",
            first.require("pasteWorks").string(),
        )
    }

    @Test
    fun aWorksAnswerThatDiffersNamesTheFact() {
        asked()
        why(replyHost("v18.19.0\n"), "--reply")
        val result = why(replyHost("v20.11.1\n"), "--reply --works")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue("Node version: 20.11.1 where it works, 18.19.0 where it fails" in text, text)
        assertFalse("Assuming" in text, text)
        assertEquals(emptyList(), Session.parse(disk.read(session)!!)!!.open)
    }

    @Test
    fun aWorksAnswerThatIsEqualSaysItDoesNotDifferAndMovesOn() {
        asked()
        why(replyHost("v18.19.0\n"), "--reply")
        val result = why(replyHost("v18.19.0\n"), "--reply --works")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue("Node version is the same where it works and where it fails" in text, text)
        assertTrue("To tell, run npm --version where it fails" in text, text)
    }

    @Test
    fun aPastedWorksAnswerWithoutAFinalNewlineOrWithWindowsLineEndsIsRead() {
        asked()
        why(replyHost("v18.19.0\n"), "--reply")
        val result = why(replyHost("v20.11.1\r\n"), "--reply --works")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("Node version: 20.11.1 where it works" in flat(result.stdout), result.stdout)
        val bare = MemoryCaseFiles()
        DriftCommand(FakeHost(files = mapOf("issue.md" to asking)), bare).test("why issue.md")
        DriftCommand(replyHost("v18.19.0"), bare).test("why --reply")
        val pasted = DriftCommand(replyHost("v20.11.1"), bare).test("why --reply --works")
        assertTrue("20.11.1 where it works" in flat(pasted.stdout), pasted.stdout)
    }

    // simulates the shell: the arguments of `printf '%s\n' 'a' 'b'` become the lines of stdin
    private fun printed(command: String): String {
        val body = command.removePrefix("printf '%s\\n' ").substringBefore(" | drift ")
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var inside = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                inside && c == '\'' -> inside = false

                inside -> line.append(c)

                c == '\'' -> inside = true

                c == '\\' && body.getOrNull(i + 1) == '\'' -> {
                    line.append('\'')
                    i++
                }

                c == ' ' -> {
                    lines += line.toString()
                    line.clear()
                }

                else -> error("unquoted $c in $command")
            }
            i++
        }
        lines += line.toString()
        return lines.joinToString("") { "$it\n" }
    }

    @Test
    fun aReplyOnAMachineWithNoSessionPrintsTheOneCommandThatCarriesItBack() {
        asked()
        why(replyHost("v18.19.0\n"), "--reply")
        val laptop = DriftCommand(replyHost("v20.11.1\n"), MemoryCaseFiles())
            .test("why --reply --works")
        assertEquals(0, laptop.statusCode, laptop.output)
        val out = laptop.stdout.lines().filter { it.isNotBlank() }
        assertEquals(
            "There is no session here, so I cannot compare yet. The session is in the folder " +
                "where you ran drift why. Run this there:",
            out[0],
        )
        assertEquals("printf '%s\\n' 'v20.11.1' | drift why --reply --works", out[1])
        val server = why(replyHost(printed(out[1])), "--reply --works")
        assertEquals(0, server.statusCode, server.output)
        assertTrue(
            "Node version: 20.11.1 where it works, 18.19.0 where it fails" in flat(server.stdout),
            server.stdout,
        )
    }

    @Test
    fun theCarriedCommandKeepsAGivenSessionAndTheFailingSideForm() {
        val works = DriftCommand(replyHost("v20.11.1\n"), MemoryCaseFiles())
            .test("why --reply --works --session mine.json")
        assertEquals(
            "printf '%s\\n' 'v20.11.1' | drift why --reply --works --session 'mine.json'",
            works.stdout.lines().filter { it.isNotBlank() }[1],
        )
        val php = "PHP 8.3.12 (cli) (built: x) ( NTS )\nCopyright\n"
        val fails = DriftCommand(replyHost(php), MemoryCaseFiles()).test("why --reply")
        val line = fails.stdout.lines().filter { it.isNotBlank() }[1]
        assertEquals(
            "printf '%s\\n' 'PHP 8.3.12 (cli) (built: x) ( NTS )' 'Copyright' | " +
                "drift why --reply",
            line,
        )
        val json = DriftCommand(replyHost("v20.11.1\n"), MemoryCaseFiles())
            .test("why --reply --json")
        assertTrue("printf" in plainOf(json.stdout).require("next").toString(), json.stdout)
    }

    @Test
    fun aLongReplyOnAMachineWithNoSessionSaysToUseAFile() {
        val long = "v20.11.1\n" + "x".repeat(ReplyCommand.MAX_BYTES)
        val result = DriftCommand(replyHost(long), MemoryCaseFiles()).test("why --reply --works")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("Copy it into a file" in flat(result.stdout), result.stdout)
        assertTrue("drift why --reply --works < FILE" in flat(result.stdout), result.stdout)
        assertFalse("printf" in result.stdout)
    }

    @Test
    fun theCarriedCommandNeverLetsTheReplyBeRunByTheShell() {
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        val hostile = listOf(
            "v20.11.1",
            "it's \$(id) `id` \"x\" \\ ; rm -rf / '",
            "\u001b[31mred\u001b[0m caf\u00e9 bidi \u202e end \u0000 nul",
            "token $token",
            "",
            "tab\tseparated",
        ).joinToString("\n") + "\n\n"
        val command = assertNotNull(ReplyCommand.build(hostile, true, null))
        assertFalse(token in command)
        assertFalse(command.any { it > '~' || (it < ' ') }, command)
        val lines = printed(command).lines().dropLast(1)
        assertEquals("v20.11.1", lines[0])
        assertEquals("it's \$(id) `id` \"x\" \\ ; rm -rf / '", lines[1])
        assertEquals("[31mred[0m caf? bidi ? end  nul", lines[2])
        assertFalse(lines[2].any { it < ' ' || it > '~' }, lines[2])
        assertEquals("", lines[4])
        assertEquals("tab separated", lines[5])
        assertEquals(null, ReplyCommand.build("x".repeat(ReplyCommand.MAX_BYTES + 1), false, null))
    }

    @Test
    fun theWorksSideCanComeBeforeTheFailingSide() {
        asked()
        val first = why(replyHost("v20.11.1\n"), "--reply --works")
        assertEquals(0, first.statusCode, first.output)
        val saved = flat(first.stdout)
        assertTrue("I have the answer from the machine where it works" in saved, saved)
        assertTrue("In one step: node --version | drift why --reply" in saved, saved)
        val second = why(replyHost("v18.19.0\n"), "--reply")
        val text = flat(second.stdout)
        assertTrue("Node version: 20.11.1 where it works, 18.19.0 where it fails" in text, text)
    }

    @Test
    fun anUnreadableWorksAnswerIsRefusedAndKeepsTheSession() {
        asked()
        why(replyHost("v18.19.0\n"), "--reply")
        val before = disk.read(session)
        val result = why(replyHost("command not found: node\n"), "--reply --works")
        assertEquals(1, result.statusCode)
        val text = flat(result.stderr)
        assertTrue("on the machine where it works" in text, text)
        assertTrue("node --version | drift why --reply --works" in text, text)
        assertEquals(before, disk.read(session))
    }

    @Test
    fun theWorksAnswerNeedsASessionAndAPendingQuestion() {
        asked("It works on my laptop but fails in CI.\n")
        val capture = why(replyHost("v20.11.1\n"), "--reply --works")
        assertEquals(1, capture.statusCode)
        assertTrue("waits for an answer from the machine where it works" in flat(capture.stderr))
        val alone = why(replyHost("x"), "--works issue.md")
        assertEquals(1, alone.statusCode)
        assertTrue("--works only applies with --reply" in alone.stderr, alone.stderr)
    }

    @Test
    fun anOldSessionStillReadsAndTheWorksAnswerIsNeverStoredAsText() {
        val old = "{\"asks\":[{\"argv\":[\"node\",\"--version\"],\"command\":\"node --version\"," +
            "\"family\":\"tool.node\",\"reply\":{\"facts\":[{\"key\":\"tool.node.version\"," +
            "\"value\":\"18.19.0\"}]},\"why\":\"w\"}],\"report\":\"It fails with node.\"," +
            "\"schema\":1}"
        val parsed = Session.parse(old)!!
        assertNull(parsed.asks.single().works)
        assertFalse("works" in parsed.text())
        disk.write(session, old)
        val marker = "v20.11.1 private-marker"
        val result = why(replyHost("v20.11.1\nprivate-marker\n"), "--reply --works")
        assertEquals(0, result.statusCode, result.output)
        assertFalse(marker in disk.read(session)!! || "private-marker" in disk.read(session)!!)
        val after = Session.parse(disk.read(session)!!)!!
        assertEquals(listOf("tool.node.version" to "20.11.1"), after.asks.single().works)
    }

    @Test
    fun theSameWorksAnswerGivesTheSameBytes() {
        asked()
        why(replyHost("v18.19.0\n"), "--reply")
        why(replyHost("v20.11.1\n"), "--reply --works")
        val first = disk.read(session)
        val other = MemoryCaseFiles()
        DriftCommand(FakeHost(files = mapOf("issue.md" to asking)), other).test("why issue.md")
        DriftCommand(replyHost("v18.19.0\n"), other).test("why --reply")
        DriftCommand(replyHost("v20.11.1\n"), other).test("why --reply --works")
        assertEquals(first, other.read(session))
    }

    @Test
    fun aSavedCaptureWithNoDifferenceNeverClaimsToBeOfThisMachine() {
        val first = Machine(env = mapOf("HOSTNAME" to "box-1"), tools = node("20.11.1"))
        why(local(first), "--save there.json")
        val saved = disk.read("there.json")!!
        val second = Machine(
            env = mapOf("HOSTNAME" to "box-2"),
            files = mapOf("there.json" to saved),
            tools = node("20.11.1"),
        )
        val result = why(local(second), "--there there.json")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue("drift why: this machine against the saved capture in there.json" in text, text)
        assertTrue(
            "Nothing I can read differs between this machine and the saved capture. If you ran " +
                "this on the machine that made the capture, it cannot differ: run it on the " +
                "machine where the program works." in text,
            text,
        )
        assertFalse("itself" in text || "its own" in text, text)
        assertFalse("Assuming it works here" in text, text)
    }

    // #endregion

    // #region a CI log

    private val ciLines = listOf(
        "Set up job",
        "Run actions/setup-python@v5",
        "  python-version: 3.9",
        "Python 3.9.18",
        "  File \"/work/app/check.py\", line 2",
        "    match x:",
        "SyntaxError: invalid syntax",
        "Error: Process completed with exit code 1.",
    )
    private val ciLog = ciLines.joinToString("\n") {
        "build (ubuntu-22.04)\tRun tests\t2026-01-02T03:04:05.1234567Z $it"
    }

    @Test
    fun aCiLogNamesTheRuntimeItPrintsAndAsksForItWhereItWorks() {
        val host = FakeHost(files = mapOf("log.txt" to ciLog))
        val first = why(host, "log.txt")
        assertEquals(0, first.statusCode, first.output)
        val text = flat(first.stdout)
        assertTrue("The report names Python version 3.9.18 on the machine where it fails" in text)
        assertTrue("Python version: not stated where it works, 3.9.18 where it fails" in text, text)
        assertTrue("Run python3 --version where it works." in text, text)
        assertFalse("pip --version" in text, text)
        val saved = Session.parse(disk.read(session)!!)!!
        assertEquals(listOf("tool.python.version" to "3.9.18"), saved.asks.first().reply)
        val answer = why(replyHost("Python 3.12.15\n"), "--reply --works")
        assertEquals(0, answer.statusCode, answer.output)
        val named = flat(answer.stdout)
        assertTrue("Python version: 3.12.15 where it works, 3.9.18 where it fails" in named, named)
    }

    @Test
    fun aCiLogThatAnotherTextFlowReadsTheSameWayGivesTheSameFacts() {
        val json = why(FakeHost(files = mapOf("log.txt" to ciLog)), "--json log.txt").stdout
        val facts = (CanonicalJson.parse(json) as JsonObject).require("facts") as JsonArray
        val keys = facts.items.map { (it as JsonObject).require("key").string() }
        assertTrue("run:signature" in keys && "tool.python.version" in keys, keys.toString())
    }

    // #endregion

    // #region the clean run

    private val boom = "Error: boom happened in the handler"
    private val cleanReport =
        "The app stops at start-up.\n\n```\n\$ node app.js\n$boom\n```\n"

    private fun cleanAsked(): String {
        val (_, out) = asked(cleanReport)
        return out
    }

    private fun cleanLine() =
        Regex("In one step: (.*)").find(flat(cleanAsked()))!!.groupValues[1].trim()

    @Test
    fun aReportWithACommandAndAFailureAsksForTheCleanRunFirst() {
        val out = cleanAsked()
        val text = flat(out)
        assertTrue(
            "To tell, run the command from the report in a clean container and paste what it " +
                "prints (the command comes from the report, so read it before you run it):" in text,
            out,
        )
        val line = cleanLine()
        assertTrue(
            line.startsWith("docker run --rm --user \"\$(id -u):\$(id -g)\" --cap-drop ALL") &&
                " --network none -v \"\$PWD\":/src:ro --tmpfs /w:exec,mode=1777 -w /w node:" in
                line &&
                line.endsWith(" sh -c 'cp -R /src/. /w/ && node app.js' 2>&1 | drift why --reply"),
            line,
        )
        val saved = Session.parse(disk.read(session)!!)!!
        assertEquals(listOf("clean-run", "tool.node"), saved.open.map { it.ask.family })
        assertFalse(Regex(JARGON, RegexOption.IGNORE_CASE).containsMatchIn(out))
    }

    @Test
    fun theJsonCarriesTheCleanRunAskWithTheThreeFields() {
        val host = FakeHost(files = mapOf("issue.md" to cleanReport))
        val list = (plainOf(why(host, "--json issue.md").stdout).require("ask") as JsonArray)
            .items.map { it as JsonObject }
        val first = list.first()
        assertEquals("clean-run", first.require("family").string())
        assertEquals(setOf("family", "command", "piped"), first.fields.keys)
        assertTrue(first.require("command").string().startsWith("docker run --rm"))
    }

    @Test
    fun aSameFailureReplyEndsInNotEnvironmentAndKeepsNoOutputText() {
        cleanAsked()
        val marker = "at /w/very-private-path/marker.js:7:3"
        val output = "$boom\n    $marker\n"
        val result = why(replyHost(output), "--reply")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue(
            "It fails the same way on a clean machine, so your environment is not the cause; " +
                "look at the program or the input." in text,
            result.stdout,
        )
        assertTrue("The clean run printed the same failure as the report: $boom" in text, text)
        val saved = disk.read(session)!!
        assertFalse(marker in saved)
        assertFalse("very-private-path" in saved)
        val reply = Session.parse(saved)!!.asks.single().reply
        assertEquals(listOf("run:clean" to "same"), reply)
    }

    @Test
    fun theSameReplyInJsonNamesTheKind() {
        cleanAsked()
        val result = why(replyHost("$boom\n"), "--reply --json")
        val plain = plainOf(result.stdout)
        assertEquals("not-environment", plain.require("kind").string())
        val root = CanonicalJson.parse(result.stdout) as JsonObject
        val reading = root.require("notEnvironment") as JsonObject
        assertEquals("clean-run", reading.require("signal").string())
    }

    @Test
    fun aWorksReplyAsksForTheCaptureInTheOneStepForm() {
        cleanAsked()
        val result = why(replyHost("listening on 3000\n"), "--reply")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue("It works on a clean machine, so something on your machine differs." in text)
        assertTrue(
            "In one step: drift why --save there.json && drift why --reply < there.json" in text,
            text,
        )
        assertFalse("not the cause" in text, text)
        val reply = Session.parse(disk.read(session)!!)!!.asks[0].reply
        assertEquals(listOf("run:clean" to "works"), reply)
    }

    @Test
    fun anEmptyReplyMeansItRanAndPrintedNoError() {
        cleanAsked()
        val result = why(replyHost(""), "--reply")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("It works on a clean machine" in flat(result.stdout), result.stdout)
    }

    @Test
    fun aDifferentFailureKeepsTheCheckOpenAndSaysWhatDiffered() {
        cleanAsked()
        val before = disk.read(session)
        val result = why(replyHost("Error: another thing broke\n"), "--reply")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue(
            "The clean run failed differently from the report (it printed Error: another thing " +
                "broke, the report shows $boom), so I cannot compare them yet." in text,
            text,
        )
        assertTrue("In one step: docker run" in text, text)
        assertEquals(before, disk.read(session))
        val json = plainOf(why(replyHost("Error: another thing broke\n"), "--reply --json").stdout)
        assertEquals(
            "clean-run",
            ((json.require("ask") as JsonArray).items.single() as JsonObject)
                .require("family").string(),
        )
    }

    @Test
    fun dockersOwnErrorAndBinaryOutputAreNotReadAsTheAnswer() {
        cleanAsked()
        val before = disk.read(session)
        for (junk in listOf(
            "docker: Cannot connect to the Docker daemon at unix:///var/run/docker.sock.\n",
            "zsh: command not found: docker\n",
            "\u0000\u0001\u0002binary",
        )) {
            val result = why(replyHost(junk), "--reply")
            assertEquals(1, result.statusCode, junk)
            assertTrue("I could not read that as the output of docker run" in flat(result.stderr))
            assertEquals(before, disk.read(session))
        }
    }

    @Test
    fun theSameCleanRunReplyGivesTheSameBytes() {
        cleanAsked()
        val result = why(replyHost("$boom\n"), "--reply")
        val first = disk.read(session)
        val again = MemoryCaseFiles()
        val host = FakeHost(files = mapOf("issue.md" to cleanReport))
        DriftCommand(host, again).test("why issue.md")
        val second = DriftCommand(replyHost("$boom\n"), again).test("why --reply")
        assertEquals(result.stdout, second.stdout)
        assertEquals(first, again.read(session))
    }

    // #endregion

    @Test
    fun theSameInputsGiveTheSameSessionBytes() {
        asked()
        val first = disk.read(session)
        val other = MemoryCaseFiles()
        DriftCommand(FakeHost(files = mapOf("issue.md" to asking)), other).test("why issue.md")
        assertEquals(first, other.read(session))
    }

    @Test
    fun theCaptureIsAskedForAndAnsweredWithAFile() {
        asked("It works on my laptop but fails in CI.\n")
        val saved = Session.parse(disk.read(session)!!)!!
        assertEquals(listOf("capture"), saved.open.map { it.ask.family })
        val capsule = Capsule(
            "there",
            listOf(
                Attribute("tool.node.version", "18.19.0", "scanner"),
                Attribute("env.HOME", "/home/user", "env"),
            ),
        ).canonical()
        val result = why(replyHost(capsule), "--reply")
        assertEquals(0, result.statusCode, result.output)
        val after = Session.parse(disk.read(session)!!)!!
        val facts = after.asks.single().reply!!
        assertEquals(listOf("tool.node.version" to "18.19.0"), facts)
        assertTrue("Node version: " in flat(result.stdout), result.stdout)
        val bad = why(replyHost("{}"), "--reply")
        assertEquals(1, bad.statusCode)
    }

    private class Round(val fixture: String, val ask: Ask, val facts: Map<String, String>)

    @Test
    fun everyReplyFixtureIsReadBackIntoTheSession() {
        val rounds = listOf(
            Round(
                "node",
                Ask("tool.node", listOf("node", "--version"), "w"),
                mapOf("tool.node.version" to "24.20.0"),
            ),
            Round(
                "electron",
                Ask("tool.electron", listOf("electron", "--version"), "w"),
                mapOf("tool.electron.version" to "28.1.0"),
            ),
            Round(
                "openssl",
                Ask("tool.openssl", listOf("openssl", "version"), "w"),
                mapOf("tool.openssl.version" to "3.0.13", "tool.openssl.family" to "openssl"),
            ),
            Round(
                "php-modules",
                Ask("tool.php.extensions", listOf("php", "-m"), "w"),
                mapOf("tool.php.extensions" to "core,curl,date,json,mbstring,zip"),
            ),
            Round(
                "df",
                Ask("limits.disk-space", listOf("df", "-h", "."), "w"),
                mapOf("limits.disk-space" to "74%"),
            ),
            Round(
                "uname",
                Ask("kernel.release", listOf("uname", "-r"), "w"),
                mapOf("kernel.release" to "6.8.0-138-generic"),
            ),
            Round(
                "os-release",
                Ask("os.release", listOf("cat", "/etc/os-release"), "w"),
                mapOf(
                    "os.name" to "linux",
                    "os.release.ID" to "ubuntu",
                    "os.release.VERSION_ID" to "24.04",
                    "os.release.PRETTY_NAME" to "Ubuntu 24.04.5 LTS",
                ),
            ),
            Round(
                "paper-version",
                Ask("tool.paper", listOf("/version"), "w"),
                mapOf("runtime.platform" to "paper", "tool.paper.version" to "1.20.1-196"),
            ),
        )
        for (round in rounds) {
            val files = MemoryCaseFiles()
            files.write(session, Session.start("It fails.", listOf(round.ask)).text())
            val stdin = WhyFixtures.text("replies/${round.fixture}.txt")
            val host = FakeHost(files = mapOf("/dev/stdin" to stdin))
            val result = DriftCommand(host, files).test("why --reply")
            assertEquals(0, result.statusCode, "${round.fixture}: ${result.output}")
            val saved = Session.parse(files.read(session)!!)!!
            assertEquals(round.facts, saved.asks.single().reply!!.toMap(), round.fixture)
        }
    }

    @Test
    fun theCurlFixtureIsNotAnOpensslReply() {
        val files = MemoryCaseFiles()
        val ask = Ask("tool.openssl", listOf("openssl", "version"), "w")
        files.write(session, Session.start("It fails.", listOf(ask)).text())
        val host = FakeHost(files = mapOf("/dev/stdin" to WhyFixtures.text("replies/curl.txt")))
        val result = DriftCommand(host, files).test("why --reply")
        assertEquals(1, result.statusCode)
        assertTrue("I could not read that as the output of openssl version" in result.stderr)
    }

    @Test
    fun anErrorTextAloneIsReadAsAReportWhenThereIsNoCheckout() {
        val result = why(FakeHost(), "--error \"Error: too many open files while running node\"")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue(text.startsWith("drift why: reading the error text"), text)
        assertTrue(
            "No CI run or second machine was available, so I read only the error text" in text,
        )
        assertTrue("add --there PLACE" in text, text)
        assertTrue("To tell, run ulimit -n where it fails" in text, text)
        assertFalse("not inside a git checkout" in result.stderr)
        assertNotNull(disk.read(session))
    }

    @Test
    fun aRuntimeNamedOnlyByItsErrorIsAskedForThenTheCaptureFollows() {
        val result = why(FakeHost(), "--error \"ModuleNotFoundError: No module named 'distutils'\"")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        assertTrue("To tell, run python3 --version where it fails" in text, text)
        assertFalse("ask them" in text, text)
        val saved = Session.parse(disk.read(session)!!)!!
        assertEquals(listOf("tool.python", "capture"), saved.open.map { it.ask.family })
        val next = why(replyHost("Python 3.11.4\n"), "--reply")
        assertEquals(0, next.statusCode, next.output)
        val after = flat(next.stdout)
        assertTrue("Run python3 --version where it works" in after, after)
        val kept = Session.parse(disk.read(session)!!)!!
        assertEquals(listOf("capture"), kept.open.map { it.ask.family })
    }

    @Test
    fun theVersionOfAnInferredRuntimeCanNameTheCause() {
        why(FakeHost(), "--error \"ModuleNotFoundError: No module named 'distutils'\"")
        val next = why(replyHost("Python 3.12.1\n"), "--reply")
        assertEquals(0, next.statusCode, next.output)
        assertTrue("Python 3.12 removes distutils" in flat(next.stdout), next.stdout)
    }

    @Test
    fun aSingleMachineReporterIsToldWhatToRunNotWhatToAskSomeoneElse() {
        val result = why(FakeHost(), "--error \"It broke.\"")
        val text = flat(result.stdout)
        assertTrue("I found no environment fact in this error text" in text, text)
        assertTrue("To tell, run drift why --save there.json where it fails" in text, text)
        assertTrue("In one step: drift why --save there.json && drift why --reply" in text, text)
        assertFalse("ask them" in text || "Ask the reporter" in text, text)
    }

    @Test
    fun anErrorTextAloneAssumesItWorksElsewhereSoItNeverCallsItNotEnvironment() {
        val line = "AssertionError: expected 4 lines, got 1"
        val result = why(FakeHost(), "--error \"$line\"")
        val text = flat(result.stdout)
        assertFalse("not an environment difference" in text, text)
        assertTrue("To tell, run drift why --save there.json where it fails" in text, text)
        val saved = disk.read(session)!!
        assertTrue("\"elsewhere\":true" in saved, saved)
        val capsule = Capsule("there", listOf(Attribute("env.TZ", "America/New_York", "env")))
        val after = why(replyHost(capsule.canonical()), "--reply")
        assertEquals(0, after.statusCode, after.output)
        assertFalse("not an environment difference" in flat(after.stdout), after.stdout)
    }

    @Test
    fun anEqualMissingToolAnswerSaysItIsNotWhatDiffers() {
        why(FakeHost(), "--error \"bash: git: command not found\"")
        why(replyHost("/usr/bin/git\n"), "--reply")
        val result = why(replyHost("/usr/bin/git\n"), "--reply --works")
        assertEquals(0, result.statusCode, result.output)
        val text = flat(result.stdout)
        val same = "Git is the same where it works and where it fails, so it is not what differs."
        assertTrue(same in text, text)
        assertTrue("Git: installed where it works and where it fails" in text, text)
        assertFalse("tool.git" in text, text)
    }

    // the plain label of every fact a reply can produce, for every check the table asks
    @Test
    fun noFindingLineStartsWithARawDottedKey() {
        val samples = mapOf(
            "tool.node" to "v20.11.1",
            "tool.npm" to "10.2.3",
            "tool.pnpm" to "9.0.0",
            "tool.yarn" to "1.22.19",
            "tool.bun" to "1.0.25",
            "tool.deno" to "deno 1.40.0 (release, x86_64)",
            "tool.python" to "Python 3.12.1",
            "tool.pip" to "pip 24.0 from /x/pip (python 3.12)",
            "tool.poetry" to "Poetry (version 1.7.1)",
            "tool.php" to "PHP 8.3.12 (cli) (built: x) ( NTS )",
            "tool.composer" to "Composer version 2.6.5 2023-10-06 10:11:52",
            "tool.java" to "openjdk version \"21.0.1\" 2023-10-17",
            "tool.gradle" to "Gradle 8.5",
            "tool.mvn" to "Apache Maven 3.9.6 (abc)",
            "tool.go" to "go version go1.22.1 linux/amd64",
            "tool.rust" to "rustc 1.75.0 (82e1608df 2023-12-21)",
            "tool.cargo" to "cargo 1.75.0 (1d8b05cdd 2023-11-20)",
            "tool.ruby" to "ruby 3.3.0 (2023-12-25 revision abc) [x86_64-linux]",
            "tool.dotnet" to "8.0.100",
            "tool.git" to "git version 2.39.5",
            "tool.docker" to "Docker version 24.0.7, build afdd53b",
            "tool.podman" to "podman version 4.9.0",
            "tool.skopeo" to "skopeo version 1.14.0",
            "tool.curl" to WhyFixtures.text("replies/curl.txt"),
            "tool.openssl" to "OpenSSL 3.0.2 15 Mar 2022 (Library: OpenSSL 3.0.2 15 Mar 2022)",
            "tool.electron" to "v28.0.0",
            "tool.cmake" to "cmake version 3.28.1",
            "tool.bazel" to "bazel 7.0.0",
            "tool.pwsh" to "PowerShell 7.4.0",
            "tool.dart" to "Dart SDK version: 3.2.0 (stable)",
            "tool.xcode" to "Xcode 15.0\nBuild version 15A240d",
            "tool.cc" to "gcc (Debian 12.2.0-14) 12.2.0",
            "tool.clang" to "clang version 16.0.6",
            "tool.libc" to "ldd (Debian GLIBC 2.36-9+deb12u3) 2.36",
            "limits.nofile" to "1024",
            "limits.disk-space" to WhyFixtures.text("replies/df.txt"),
            "limits.disk-quota" to WhyFixtures.text("replies/df.txt"),
            "tool.php.extensions" to WhyFixtures.text("replies/php-modules.txt"),
            "os.release" to WhyFixtures.text("replies/os-release.txt"),
            "os.version" to "14.1",
            "kernel.release" to WhyFixtures.text("replies/uname.txt"),
            "env.TZ" to "Europe/Paris",
            "env.LANG" to WhyFixtures.text("replies/locale.txt"),
            "tool.cocoapods" to "1.14.3",
            "tool.kotlin" to "info: kotlinc-jvm 1.9.20 (JRE 21.0.1+12)",
        )
        val sides = Sides("where it works", "where it fails", assumed = false)
        val raw = Regex("^[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)+")
        var read = 0
        val asks = Asks.known + Ask("tool.git", listOf("command", "-v", "git"), "") +
            Ask("env.DATA_DIR", listOf("printenv", "DATA_DIR"), "") +
            Ask("tool.paper", listOf("/version"), "")
        for (ask in asks) {
            val text = samples[ask.family] ?: continue
            val facts = Replies.read(ask, text) ?: continue
            read++
            for (fact in facts) {
                val v = fact.value
                val pairs = listOf(null to v, v to "other", v to v)
                for ((works, fails) in pairs) {
                    val label = Families.describe(fact.path, works, fails, sides).label
                    assertFalse(raw.containsMatchIn(label), "${fact.path} -> $label")
                }
            }
        }
        assertTrue(read >= 30, "only $read checks were read")
        val unsampled = Asks.known.map { it.family }.filter { it !in samples }
        assertEquals(emptyList(), unsampled, "no sample output for these checks")
    }

    @Test
    fun aMissingCommandInTheErrorIsAskedForWithCommandV() {
        val line = "FileNotFoundError: [Errno 2] No such file or directory: 'git'"
        val result = why(FakeHost(), "--error \"$line\"")
        val text = flat(result.stdout)
        assertTrue("To tell, run command -v git where it fails" in text, text)
        assertTrue("In one step: command -v git | drift why --reply" in text, text)
        val empty = why(replyHost(""), "--reply")
        assertEquals(0, empty.statusCode, empty.output)
        val saved = Session.parse(disk.read(session)!!)!!
        assertEquals(listOf("tool.git.installed" to "no"), saved.asks.first().reply)
        val shown = flat(empty.stdout)
        val want = "Git: not stated where it works, not installed where it fails"
        assertTrue(want in shown, shown)
        assertTrue("The report says Git is not installed on the machine where it fails." in shown)
        assertFalse("installation" in shown, shown)
        val works = why(replyHost("/usr/bin/git\n"), "--reply --works")
        assertEquals(0, works.statusCode, works.output)
        val both = flat(works.stdout)
        assertTrue("Git: installed where it works, not installed where it fails" in both, both)
    }

    @Test
    fun anErrorTextAloneKeepsOtherCiProblems() {
        val without = ciCommands().filterKeys { it.firstOrNull() != "gh" }
        val host = FakeHost(env = mapOf("PWD" to ROOT), commands = without)
        val result = why(host, "--error x")
        assertEquals(1, result.statusCode)
        assertTrue("gh is not installed or not on PATH" in result.stderr, result.stderr)
    }

    @Test
    fun anErrorTextWithAnIssueFileSaysWhichOfTheTwoToDrop() {
        val result = why(local(Machine()), "--error x issue.md")
        assertEquals(1, result.statusCode)
        val text = flat(result.stderr)
        assertTrue("drop --error to read the file as a report" in text, text)
        assertTrue("drop the file to read the error text" in text, text)
    }

    // #endregion

    // #endregion

    // #region CI

    private fun gh(vararg filter: String, limit: Int) = listOf(
        "gh", "run", "list", "-R", "gmitch215/Drift", *filter,
        "--limit", "$limit", "--json",
        "databaseId,workflowName,workflowDatabaseId,headSha,headBranch,url,createdAt,event," +
            "conclusion",
    )

    private fun view(id: String) = listOf(
        "gh",
        "run",
        "view",
        id,
        "-R",
        "gmitch215/Drift",
        "--json",
        "databaseId,conclusion,jobs",
    )

    private fun log(id: String, job: String) =
        listOf("gh", "run", "view", id, "-R", "gmitch215/Drift", "--log", "--job", job)

    private fun live(name: String) = CiFixtures.text("live/$name")

    private fun ciCommands(withPassing: Boolean = true): Map<List<String>, CommandResult> {
        val head = "c775d8f53129708364803952edc93cf66ca73201"
        val passed = if (withPassing) live("github/run-list-success.json") else "[]"
        return mapOf(
            listOf("git", "rev-parse", "--show-toplevel") to ok("$ROOT\n"),
            listOf("git", "remote", "get-url", "origin") to
                ok("https://github.com/gmitch215/Drift.git\n"),
            listOf("git", "rev-parse", "--abbrev-ref", "HEAD") to ok("master\n"),
            listOf("git", "rev-parse", "HEAD") to ok("$head\n"),
            gh("--branch", "master", "--status", "failure", limit = 20) to
                ok(live("github/run-list-failure.json")),
            gh(
                "--branch",
                "master",
                "--workflow",
                "377446806",
                "--status",
                "success",
                limit = 50,
            ) to ok(passed),
            gh("--workflow", "377446806", "--status", "success", limit = 50) to ok(passed),
            view("40042665061") to ok(live("github/run-red.json")),
            view("38452229524") to ok(live("github/run-green.json")),
            log("40042665061", "120718675506") to ok(live("github/log-red-linux-x64.log")),
            log("38452229524", "115487406178") to ok(live("github/log-green-linux-x64.log")),
        )
    }

    private fun ciHost(withPassing: Boolean = true) = FakeHost(
        os = "linux",
        env = mapOf("PWD" to ROOT, "TZ" to "UTC"),
        commands = ciCommands(withPassing),
    )

    @Test
    fun noArgumentsInAGithubCheckoutReadTheLatestFailedCiRun() {
        val result = why(ciHost(), "")
        assertEquals(0, result.statusCode, result.output)
        assertEquals(golden("ci"), result.stdout, result.stdout)
    }

    @Test
    fun withoutAPassingRunCiStillAnswersAndSaysWhatItCouldNotCheck() {
        val result = why(ciHost(withPassing = false), "")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("No earlier passing CI run was found" in result.stdout, result.stdout)
    }

    @Test
    fun aMissingGhNamesTheInstallAndTheLoginCommand() {
        val without = ciCommands().filterKeys { it.firstOrNull() != "gh" }
        val result = why(FakeHost(env = mapOf("PWD" to ROOT), commands = without), "")
        assertEquals(1, result.statusCode)
        assertTrue("gh is not installed or not on PATH" in result.stderr, result.stderr)
        assertTrue("install it from https://cli.github.com" in result.stderr)
        assertTrue("gh auth login" in result.stderr, result.stderr)
    }

    // #endregion

    // #region output

    @Test
    fun jsonCarriesTheExistingStateAndAPlainObject() {
        val here = Machine(tools = node("22.4.0"))
        val there = Machine(tools = node("20.11.1"))
        val result = why(container(here, there), "--there docker:web --json")
        assertEquals(0, result.statusCode, result.output)
        val json = CanonicalJson.parse(result.stdout) as JsonObject
        assertTrue(json.require("relevant") is JsonArray)
        val plain = json.require("plain") as JsonObject
        assertEquals(
            listOf("certainty", "direction", "evidence", "findings", "flow", "next", "verdict"),
            plain.fields.keys.sorted(),
        )
        val first = (plain.require("findings") as JsonArray).items[0] as JsonObject
        assertEquals("tool.node.version", first.require("path").string())
        val verdict = flat(plain.require("verdict").string())
        assertTrue("Node is 20.11.1 there and 22.4.0 here" in verdict)
        assertTrue(plain.require("flow") is JsonString)
    }

    @Test
    fun detailAddsTheFullRankingAndTheDefaultViewKeepsItOut() {
        val here = Machine(tools = node("22.4.0"))
        val there = Machine(tools = node("20.11.1"))
        val plain = why(container(here, there), "--there docker:web").stdout
        val detail = why(container(here, there), "--there docker:web --detail").stdout
        assertTrue(detail.startsWith(plain))
        assertTrue("Candidates, best first" in detail && "tier" in detail, detail)
        assertFalse("Candidates" in plain)
    }

    @Test
    fun theSameInputsGiveTheSameBytes() {
        val here = Machine(env = mapOf("TZ" to "UTC"), tools = node("22.4.0"))
        val there = Machine(env = mapOf("TZ" to "Asia/Tokyo"), tools = node("20.11.1"))
        val first = why(container(here, there), "--there docker:web").stdout
        val second = why(container(here, there), "--there docker:web").stdout
        assertEquals(first, second)
        assertEquals(
            why(container(here, there), "--there docker:web --json").stdout,
            why(container(here, there), "--there docker:web --json").stdout,
        )
    }

    @Test
    fun theHelpShowsTheFourFlowsAndTheManualFitsFortyLines() {
        val help = DriftCommand(FakeHost(), disk).test("why --help").stdout
        for (example in listOf("drift why", "--there docker:web", "-- npm test", "why issue.md")) {
            assertTrue(example in help, help)
        }
        val manual = DriftCommand(FakeHost(), disk).test("help why").stdout
        assertTrue(manual.lines().size <= 40, manual.lines().size.toString())
        assertTrue("--fails-here" in manual && "docker:NAME" in manual)
        val top = DriftCommand(FakeHost(), disk).test("--help").stdout
        assertTrue(top.indexOf("drift why:") < top.indexOf("drift ci:"), top)
        assertTrue(top.indexOf("why  ") in 0 until top.indexOf("capture  "), top)
    }

    // #endregion

    // #region places

    private fun change(path: String, works: String?, fails: String?) = AttributeDiff(
        path,
        Dimension.of(path),
        DiffKind.CHANGED,
        works,
        fails,
        Stability.STATIC,
        null,
    )

    @Test
    fun placesAreToldApartByTheirSpelling() {
        val none: (String) -> Boolean = { false }
        assertEquals(Place.Docker("web", null), Place.parse("docker:web", none))
        assertEquals(Place.Docker("web", "/app"), Place.parse("docker:web:/app", none))
        assertEquals(Place.Ssh("me@host"), Place.parse("ssh://me@host/", none))
        assertEquals(Place.Ssh("me@host"), Place.parse("me@host", none))
        assertEquals(Place.Ssh("build-02"), Place.parse("build-02", none))
        assertEquals(Place.File("there.json"), Place.parse("there.json", none))
        assertEquals(Place.File("out/there"), Place.parse("out/there", none))
        assertEquals(Place.File("there"), Place.parse("there") { it == "there" })
    }

    @Test
    fun aContainerNameOrDirectoryThatIsNotValidIsRefused() {
        assertFailsWith<PlaceException> { DockerExecHost.open(FakeHost(), "bad name", null) }
        assertFailsWith<PlaceException> { DockerExecHost.open(FakeHost(), "web", "relative") }
    }

    // #endregion
}
