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
	fun fileMountPathsLiveUnderTheArm() {
		assertEquals("files/green/3", Harness.filePath("green", 3))
	}

	private companion object {
		const val GREEN_X = "\"green\":{\"image\":\"x\"}"
		const val RED_Y = "\"red\":{\"image\":\"y\"}"
	}
}
