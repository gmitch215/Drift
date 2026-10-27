package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessTest {
    private fun scenario(arms: String): Scenario {
        val text = """{"id":"h","dimension":"network","runtime":"python3","program":"print(1)",
            |"signature":"it's","exit":"137","trials":"20",$arms,
            |"cause":{"dimension":"network","path":"network.hosts","direction":"changed"}}
        """.trimMargin()
        return Template.fromJson(CanonicalJson.parse(text)).build(1, emptyMap(), 99)
    }

    private val s = scenario(
        """
        "green":{"image":"python:3.12-slim","memory":"512m"},
        "red":{"image":"python:3.12-slim","memory":"96m","cpus":"0.5","cpuset":"0-1",
        "env":{"B":"2","A":"it's"},"ulimits":{"nofile":"64:64"},
        "files":{"/etc/hosts":"x\n","/etc/app/lock":"y\n"},"args":["--flag","a b"]}
        """,
    )

    @Test
    fun quotingSurvivesASingleQuote() {
        assertEquals("'plain'", Harness.quote("plain"))
        assertEquals("'it'\\''s'", Harness.quote("it's"))
    }

    @Test
    fun everyRuntimeNamesItsFileAndCommand() {
        val expected = mapOf(
            "go" to ("prog.go" to "go run prog.go"),
            "ruby" to ("prog.rb" to "ruby prog.rb"),
            "php" to ("prog.php" to "php prog.php"),
            "dotnet" to (
                "prog.fsx" to
                    "sh -c 'dotnet /usr/share/dotnet/sdk/*/FSharp/fsi.dll \"\$@\"' sh prog.fsx"
                ),
            "rust" to (
                "prog.rs" to
                    "sh -c 'rustc -o /tmp/prog \"\$@\" && /tmp/prog' sh prog.rs"
                ),
            "r" to ("prog.R" to "Rscript prog.R"),
            "dart" to ("prog.dart" to "dart prog.dart"),
            "julia" to ("prog.jl" to "julia prog.jl"),
            "elixir" to ("prog.exs" to "elixir prog.exs"),
        )
        for ((runtime, want) in expected) {
            val text = """{"id":"r","dimension":"runtime","runtime":"$runtime","program":"x",
                |"signature":"s","green":{"image":"a"},"red":{"image":"b"},
                |"cause":{"dimension":"runtime","path":"tool.x.version","direction":"lower"}}
            """.trimMargin()
            val scenario = Template.fromJson(CanonicalJson.parse(text)).build(1, emptyMap(), 1)
            assertEquals(want.first, Dimensions.runtimes.getValue(runtime).first)
            assertEquals(want.second, Harness.command(scenario, scenario.green))
        }
    }

    @Test
    fun theCommandUsesTheRuntimeOrTheOverride() {
        assertEquals("python3 prog.py", Harness.command(s, s.green))
        assertEquals("python3 prog.py '--flag' 'a b'", Harness.command(s, s.red))
        val shell =
            scenario("\"green\":{\"image\":\"x\",\"interpreter\":\"bash\"},$RED_Y")
        assertEquals("bash prog.py", Harness.command(shell, shell.green))
    }

    @Test
    fun anArmWithAShellBuildsAnImageWhoseBinShIsLinkedToIt() {
        val bash = scenario("\"green\":{\"image\":\"debian:12-slim\",\"shell\":\"bash\"},$RED_Y")
        val script = Harness.script(bash, "green", bash.green)
        assertTrue(
            "printf 'FROM %s\\nRUN ln -sf %s /bin/sh\\n' 'debian:12-slim' 'bash' | " +
                "docker build --quiet --tag 'drift-bench-sh-bash:debian-12-slim' -" in script,
            script,
        )
        assertTrue("'drift-bench-sh-bash:debian-12-slim' sh /bench/harness.sh green" in script)
        assertTrue("ln -sf" !in Harness.script(bash, "red", bash.red))
        assertTrue("\"shell\":\"bash\"" in bash.green.toJson().let { CanonicalJson.encode(it) })
        assertTrue("shell" !in CanonicalJson.encode(bash.red.toJson()))
    }

    @Test
    fun aWorkspaceArmNamesItsFilesAndAsksForTheProjectCapture() {
        val pinned = scenario(
            "\"green\":{\"image\":\"x\",\"workspace\":{\".nvmrc\":\"20\\n\"}},$RED_Y",
        )
        assertTrue("PROJECT=1\n" in Harness.plan(pinned, pinned.green))
        assertTrue("PROJECT" !in Harness.plan(pinned, pinned.red))
        assertEquals("green.ws/.nvmrc", Harness.workspacePath("green", ".nvmrc"))
        assertEquals("container settings: workspace", Harness.nativeRefusal(pinned))
        val json = CanonicalJson.encode(pinned.green.toJson())
        assertTrue("\"workspace\":{\".nvmrc\":\"20\\n\"}" in json)
        assertTrue("workspace" !in CanonicalJson.encode(pinned.red.toJson()))
    }

    @Test
    fun thePlanCarriesTrialsSaltGateAndTheSignature() {
        val plan = Harness.plan(s, s.red)
        assertEquals(
            "TRIALS=20\nSALT=99\nGATE=1000\n" +
                "CMD='python3 prog.py '\\''--flag'\\'' '\\''a b'\\'''\n" +
                "SIG='it'\\''s'\nSIGCODE='137'\n",
            plan,
        )
        assertTrue(
            Harness.plan(
                scenario("\"green\":{\"image\":\"x\"},\"red\":{\"image\":\"y\"}"),
                s.green,
            ).contains("GATE=1000"),
        )
    }

    @Test
    fun theScriptCarriesEveryFlagOfTheArm() {
        val script = Harness.script(s, "red", s.red)
        for (
        expected in listOf(
            "--name 'drift-bench-h-1-red'",
            "--user \"\$(id -u):\$(id -g)\"",
            "--network 'none'",
            "--memory '96m' --memory-swap '96m' --cpus '0.5'",
            "--cpuset-cpus '0-1'",
            "--ulimit 'nofile=64:64'",
            "--env 'A=it'\\''s'",
            "--env 'B=2'",
            "--env 'HOME=/tmp'",
            "\"\$dir/files/red/0:/etc/app/lock:ro\"",
            "\"\$dir/files/red/1:/etc/hosts:ro\"",
            "\"\$DRIFT:/opt/drift:ro\"",
            "'python:3.12-slim' sh /bench/harness.sh red",
        )
        ) {
            assertTrue(script.contains(expected), "missing $expected in\n$script")
        }
        assertTrue(script.startsWith("#!/bin/sh\n"))
    }

    @Test
    fun anAlpineArmRunsOnAnImageThatHasGcompat() {
        val t = scenario(
            "\"green\":{\"image\":\"node:22-alpine3.20\"},\"red\":{\"image\":\"y\"}",
        )
        val script = Harness.script(t, "green", t.green)
        val tag = "drift-bench-gcompat:node-22-alpine3.20"
        assertEquals(tag, Harness.gcompatTag("node:22-alpine3.20"))
        assertTrue(
            script.contains(
                "printf 'FROM %s\\nRUN apk add --no-cache gcompat libgcc\\n' " +
                    "'node:22-alpine3.20' | " +
                    "docker build --quiet --tag '$tag' - > /dev/null || exit 1",
            ),
            script,
        )
        assertTrue(script.contains("'$tag' sh /bench/harness.sh green"), script)
        val plain = Harness.script(t, "red", t.red)
        assertFalse(plain.contains("docker build") || plain.contains("gcompat"), plain)
    }

    @Test
    fun aPlainArmHasNoOptionalFlags() {
        val script = Harness.script(s, "green", s.green)
        assertFalse(script.contains("--cpuset-cpus"))
        assertFalse(script.contains("--ulimit"))
        assertFalse(script.contains("/etc/hosts"))
    }

    @Test
    fun anArmEnvHomeWinsOverTheDefault() {
        val t =
            scenario("$GREEN_X,\"red\":{\"image\":\"y\",\"env\":{\"HOME\":\"/root\"}}")
        val script = Harness.script(t, "red", t.red)
        assertTrue(script.contains("--env 'HOME=/root'"))
        assertFalse(script.contains("HOME=/tmp"))
    }

    @Test
    fun theEnvFileHoldsSortedQuotedExports() {
        assertEquals("export A='it'\\''s'\nexport B='2'\n", Harness.env(s.red))
        assertEquals("", Harness.env(scenario(GREEN_X + "," + RED_Y).green))
    }

    @Test
    fun aScenarioWithContainerSettingsIsRefusedNatively() {
        assertEquals(
            "container settings: files, ulimits, cpuset, memory, cpus",
            Harness.nativeRefusal(s),
        )
        val plain = scenario(GREEN_X + "," + RED_Y)
        assertEquals(null, Harness.nativeRefusal(plain))
        val net = scenario("\"green\":{\"image\":\"x\",\"network\":\"host\"},$RED_Y")
        assertEquals("container settings: network", Harness.nativeRefusal(net))
    }

    @Test
    fun fileMountPathsLiveUnderTheArm() {
        assertEquals("files/green/3", Harness.filePath("green", 3))
    }

    private fun solveScenario(runtime: String, extra: String, arms: String): Scenario {
        val text = """{"id":"h","dimension":"network","runtime":"$runtime","program":"x",
            |$extra,$arms,
            |"cause":{"dimension":"network","path":"network.hosts","direction":"changed"}}
        """.trimMargin()
        return Template.fromJson(CanonicalJson.parse(text)).build(1, emptyMap(), 99)
    }

    private fun solveGolden(run: String, failWhen: String, gate: String?): String {
        val head = """
            #!/bin/sh
            dir=@(cd "@(dirname "@0")" && pwd)
            [ -n "@DRIFT" ] || { echo 'set DRIFT to the drift binary' >&2; exit 2; }
            solved=@{SOLVED:-@dir/../solved}
            limit=@{SOLVE_TIMEOUT:-2700}
            DOCKER_CONFIG=@{TMPDIR:-/tmp}/drift-solve-docker-config
            mkdir -p "@DOCKER_CONFIG"
            export DOCKER_CONFIG
            : > "@dir/solve.verdict"
            if grep -q aarch64 "@dir/capsule.red.json"; then
            ~arms=@{DRIFT_ARMS_AARCH64:-@{DRIFT_ARMS:-@DRIFT}}
            else
            ~arms=@{DRIFT_ARMS_X86_64:-@{DRIFT_ARMS:-@DRIFT}}
            fi
            set -m
            "@DRIFT" solve "@dir/capsule.green.json" "@dir/capsule.red.json" \
            ~--run $run \
            ~--fail-when $failWhen \
            ~--workspace "@dir/workspace" \
            ~--case "@solved/h-1" \

        """.trimIndent()
        val gateLine = gate?.let { "~--gate $it \\\n~--pilot 30 \\\n" } ?: ""
        val watchdog = "( sleep \"@limit\"; echo timeout > \"@dir/solve.verdict\"; " +
            "kill -s TERM -- \"-@pid\"; sleep 10; kill -s KILL -- \"-@pid\" ) > /dev/null 2>&1 &\n"
        val tail = """
            ~--drift-binary "@arms" \
            ~> "@dir/solve.out" 2> "@dir/solve.err" &
            pid=@!
            WATCHDOG
            watch=@!
            wait "@pid"
            kill -s KILL "@watch" 2> /dev/null
            if [ ! -s "@dir/solve.verdict" ]; then
            ~case @(head -n 1 "@dir/solve.out") in
            ~~'CONFIRMED EFFECT (bundle)'*) v=confirmed-bundle ;;
            ~~'CONFIRMED EFFECT'*) v=confirmed-effect ;;
            ~~CONFIRMED*) v=confirmed ;;
            ~~NARROWED*) v=narrowed ;;
            ~~NEEDS*) v=needs ;;
            ~~STUCK*) v=stuck ;;
            ~~*) v=error ;;
            ~esac
            ~echo "@v" > "@dir/solve.verdict"
            fi
            cat "@dir/solve.verdict"
            case @(cat "@dir/solve.verdict") in timeout | error) exit 1 ;; esac

        """.trimIndent().replace("WATCHDOG\n", watchdog)
        return (head + gateLine + tail).replace('@', '$').replace('~', '\t')
    }

    @Test
    fun anIntermittentPythonSolveArmsTheGateAndLeavesFlakeOutOfTheRun() {
        val t = solveScenario(
            "python3",
            "\"signature\":\"it's\",\"gate\":\"500\"",
            "\"green\":{\"image\":\"python:3.12-slim\"}," +
                "\"red\":{\"image\":\"python:3.12-slim\",\"files\":{\"/etc/hosts\":\"x\"}}",
        )
        assertEquals("intermittent", t.klass)
        assertEquals(
            solveGolden(
                "'python3 prog.py'",
                "'it'\\''s'",
                "'permille=500,salt=99,env=BENCH_FLAKE'",
            ),
            Harness.solveScript(t),
        )
    }

    @Test
    fun aSignatureWithRegexCharactersIsPassedAsALiteral() {
        assertEquals("\\[\\[: not found", Harness.regexLiteral("[[: not found"))
        assertEquals("a\\.b\\(c\\)\\$", Harness.regexLiteral("a.b(c)$"))
        assertEquals("plain text", Harness.regexLiteral("plain text"))
        val t = solveScenario(
            "sh",
            "\"signature\":\"[[: not found\"",
            "\"green\":{\"image\":\"debian:12-slim\"}," +
                "\"red\":{\"image\":\"debian:12-slim\",\"files\":{\"/etc/hosts\":\"x\"}}",
        )
        assertTrue("--fail-when '\\[\\[: not found'" in Harness.solveScript(t))
    }

    @Test
    fun aDeterministicNodeSolveSetsFlakeInTheRun() {
        val t = solveScenario(
            "node",
            "\"signature\":\"boom\"",
            "\"green\":{\"image\":\"node:22\"},\"red\":{\"image\":\"node:20\",\"args\":[\"a b\"]}",
        )
        assertEquals(
            solveGolden("'BENCH_FLAKE=1 node prog.js '\\''a b'\\'''", "'boom'", null),
            Harness.solveScript(t),
        )
    }

    @Test
    fun anAlpineShellSolveWithAnExitCodeFailsOnAnyNonzeroExit() {
        val t = solveScenario(
            "sh",
            "\"exit\":\"137\"",
            "\"green\":{\"image\":\"alpine:3.20\"},\"red\":{\"image\":\"alpine:3.19\"}",
        )
        assertEquals(
            solveGolden("'BENCH_FLAKE=1 sh prog.sh'", "'exit'", null),
            Harness.solveScript(t),
        )
        assertEquals("alpine:3.19\nalpine:3.20\n", Harness.images(t))
    }

    private companion object {
        const val GREEN_X = "\"green\":{\"image\":\"x\"}"
        const val RED_Y = "\"red\":{\"image\":\"y\"}"
    }
}
