package dev.gmitch215.drift.cli

import dev.gmitch215.drift.DRIFT_VERSION
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriftCommandTest {
	private val host = FakeHost(platform = "fake", env = mapOf("TZ" to "UTC"), canRun = false)

	@Test
	fun versionNamesThePlatform() {
		val result = DriftCommand(host).test("--version")
		assertEquals(0, result.statusCode)
		assertEquals("drift version $DRIFT_VERSION (fake)", result.stdout.trim())
	}

	@Test
	fun captureEmitsParsableCapsule() {
		val result = DriftCommand(host).test("capture --label demo")
		assertEquals(0, result.statusCode)
		val capsule = Capsule.parse(result.stdout.trim())
		assertEquals("demo", capsule.label)
		assertTrue(capsule.attributes.any { it.path == "env.TZ" && it.value == "UTC" })
	}

	@Test
	fun captureBytesSurviveEveryLineSeparatorAndALongLine() {
		val tricky = "a\u0085b\u2028c\u2029d\r\n\te\u0000f\uFFFDg${"\u0085\u2028".repeat(10)}"
		val long = "x".repeat(1 shl 20) + "\u2028" + "y".repeat(10)
		val tough = FakeHost(env = mapOf("TRICKY" to tricky, "LONG" to long), canRun = false)
		val result = DriftCommand(tough).test("capture")
		assertEquals(0, result.statusCode)
		assertEquals("\n", result.stdout.takeLast(1))
		val byPath = Capsule.parse(result.stdout.dropLast(1)).attributes.associateBy { it.path }
		assertEquals(tricky.trim(), byPath.getValue("env.TRICKY").value)
		assertEquals(long, byPath.getValue("env.LONG").value)
		assertEquals(0, result.stderr.length)
	}

	@Test
	fun keepIdentifiersWarnsOnStderrAndKeepsTheName() {
		val me = FakeHost(env = mapOf("USER" to "zorbulax", "HOME" to "/home/zorbulax"))
		val kept = DriftCommand(me).test("capture --keep-identifiers")
		assertTrue("\"value\":\"zorbulax\"" in kept.stdout, kept.stdout)
		assertTrue("warning: the capsule keeps your account" in kept.stderr, kept.stderr)
		val plain = DriftCommand(me).test("capture")
		assertTrue("zorbulax" !in plain.stdout, plain.stdout)
		assertEquals("", plain.stderr)
	}

	@Test
	fun unknownCommandFails() {
		val result = DriftCommand(host).test("nope")
		assertEquals(1, result.statusCode)
		assertTrue("nope" in result.stderr, result.stderr)
	}

	private fun diagnoseHost(red: String = "37114464625") = FakeHost(
		canRun = false,
		files = mapOf(
			"green.json" to Fixtures.text("36702683742.json"),
			"red.json" to Fixtures.text("$red.json"),
			"bad.json" to "{not json",
		),
	)

	@Test
	fun diagnoseOutputIsIdenticalOnEveryTarget() {
		val result = DriftCommand(diagnoseHost()).test("diagnose green.json red.json")
		assertEquals(0, result.statusCode)
		assertEquals(DIAGNOSE_SHA, Sha256.hex(result.stdout))
		assertTrue("\"ci.runner.image.version\"" in result.stdout, result.stdout)
	}

	@Test
	fun diagnoseSelfDiffHasNoChanges() {
		val host = FakeHost(
			canRun = false,
			files = mapOf(
				"a.json" to Fixtures.text("36702683742.json"),
			),
		)
		val result = DriftCommand(host).test("diagnose a.json a.json")
		assertEquals(0, result.statusCode)
		assertTrue("\"relevant\":[]" in result.stdout, result.stdout)
	}

	@Test
	fun diagnoseNamesAMissingFile() {
		val result = DriftCommand(diagnoseHost()).test("diagnose green.json nope.json")
		assertEquals(1, result.statusCode)
		assertTrue("cannot read capsule file: nope.json" in result.stderr, result.stderr)
	}

	@Test
	fun diagnoseNamesAMalformedFile() {
		val result = DriftCommand(diagnoseHost()).test("diagnose bad.json red.json")
		assertEquals(1, result.statusCode)
		assertTrue("invalid capsule file bad.json" in result.stderr, result.stderr)
	}

	@Test
	fun diagnoseNeedsTwoFiles() {
		val result = DriftCommand(diagnoseHost()).test("diagnose green.json")
		assertEquals(1, result.statusCode)
	}

	private companion object {
		const val DIAGNOSE_SHA = "0bd532216ca7825b2d5a7e0e762c37ec0fd2c396ca6a325a2c13bd7de804b05b"
	}
}
