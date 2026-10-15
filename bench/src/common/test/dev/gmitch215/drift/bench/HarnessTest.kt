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

    private companion object {
        const val GREEN_X = "\"green\":{\"image\":\"x\"}"
        const val RED_Y = "\"red\":{\"image\":\"y\"}"
    }
}
