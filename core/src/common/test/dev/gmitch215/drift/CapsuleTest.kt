package dev.gmitch215.drift

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.redact.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapsuleTest {
	private val capsule = Capsule(
		label = "fixture",
		attributes = listOf(
			Attribute("os.arch", "x86_64", "uname"),
			Attribute("env.TZ", "UTC", "env"),
			Attribute("proc.pid", "42", "host", Stability.VOLATILE),
		),
		probes = listOf(ProbeResult("text.sort", ProbeStatus.OK, "a\nb\n")),
	)

	@Test
	fun roundTrip() {
		assertEquals(
			capsule.copy(
				attributes = capsule.attributes.sortedBy {
					it.path
				},
			),
			Capsule.parse(capsule.canonical()),
		)
	}

	@Test
	fun canonicalFormIgnoresOrder() {
		val shuffled = capsule.copy(attributes = capsule.attributes.reversed())
		assertEquals(capsule.canonical(), shuffled.canonical())
		assertEquals(capsule.hash(), shuffled.hash())
	}

	@Test
	fun selfDiffIsEmpty() {
		assertEquals(emptyList(), CapsuleDiff.attributes(capsule, capsule))
	}

	@Test
	fun diffIgnoresVolatileAndReportsChanges() {
		val other = Capsule(
			"other",
			listOf(
				Attribute("os.arch", "aarch64", "uname"),
				Attribute("proc.pid", "7", "host", Stability.VOLATILE),
			),
		)
		val changes = CapsuleDiff.attributes(capsule, other)
		assertEquals(listOf("env.TZ", "os.arch"), changes.map { it.path })
		assertEquals(null, changes.first().after)
	}

	@Test
	fun rejectsUnknownSchema() {
		val text = capsule.canonical().replace("\"schema\":1", "\"schema\":9")
		assertFailsWith<JsonException> { Capsule.parse(text) }
	}

	@Test
	fun redactsPlantedSecrets() {
		val planted = listOf(
			Attribute("env.GITHUB_TOKEN", "anything", "env"),
			Attribute("env.NOTE", "ghp_abcdefghijklmnopqrstuvwxyz0123456789", "env"),
			Attribute("cmd.remote", "https://user:hunter2@example.com/repo", "git"),
			Attribute("env.AWS", "AKIAABCDEFGHIJKLMNOP", "env"),
		).map(Redactor::redact)
		assertTrue(planted.all { Redactor.MASK in it.value }, planted.toString())
		assertEquals("host example.com", Redactor.redactText("host example.com"))
		assertEquals("UTC", Redactor.redact(Attribute("env.TZ", "UTC", "env")).value)
	}
}
