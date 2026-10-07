package dev.gmitch215.drift.scan

import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.rank.ProbeLinks
import dev.gmitch215.drift.scan.probe.Catalog
import dev.gmitch215.drift.scan.probe.Family
import dev.gmitch215.drift.scan.probe.ProbeRunner
import dev.gmitch215.drift.scan.scanner.Scanners
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbeTest {
	private val noProcess = FakeHost(platform = "wasm", capabilities = emptySet())

	private fun transcript(id: String) = ProbeRunner.run(
	    noProcess,
	    Catalog.all.single {
	    it.id == id
	},
	).transcript

	@Test
	fun catalogIdsAreUniqueAndCoverEveryFamily() {
		val ids = Catalog.all.map { it.id }
		assertEquals(ids.size, ids.toSet().size)
		assertEquals(Family.entries.toSet(), Catalog.all.map { it.family }.toSet())
		assertTrue(
			ids.all {
				it.substringBefore('.') in
					setOf(
						"numeric",
						"text",
						"collections",
						"time",
						"process",
						"resources",
						"kotlin",
					)
			},
		)
		assertTrue(ids.size >= 30, "only ${ids.size} probes")
	}

	@Test
	fun probesWithoutCapabilitiesReportUnavailableNeverPass() {
		val results = ProbeRunner.run(noProcess, Catalog.all)
		for (probe in Catalog.all) {
			val result = results.single { it.id == probe.id }
			if (probe.needs.isEmpty()) {
				assertEquals(ProbeStatus.OK, result.status, probe.id)
			} else {
				assertEquals(ProbeStatus.UNAVAILABLE, result.status, probe.id)
				assertTrue(result.transcript.startsWith("requires "), result.transcript)
			}
		}
	}

	@Test
	fun everyProbeIsDeterministicOnTheRealHost() {
		val host = systemHost()
		val a = ProbeRunner.run(host, Catalog.all)
		val b = ProbeRunner.run(host, Catalog.all)
		assertEquals(a, b)
		assertTrue(a.all { it.transcript.endsWith("\n") })
	}

	@Test
	fun portableProbesMatchIndependentlyComputedTranscripts() {
		assertEquals(
			"string = 99162322\nempty = 0\nlist = 30817\nmap = 96\ndouble = 1073217536\n" +
				"neg-zero = -2147483648\nlong = -2147483648\nbool = 1231\npair = 151\n",
			transcript("text.hashcode"),
		)
		assertEquals(
			"int-max-plus-one = -2147483648\nlong-min-div-minus-one = -9223372036854775808\n" +
				"int-min-negate = -2147483648\nshl-33 = 2\nlong-shl-65 = 2\nbyte-wrap = -56\n" +
				"neg-rem = -1\nfloor-mod = 2\n",
			transcript("numeric.int-overflow"),
		)
		assertEquals(
			"nan-int = 0\nbig-int = 2147483647\nneg-big-long = -9223372036854775808\n" +
				"trunc-pos = 3\ntrunc-neg = -3\ninf-long = 9223372036854775807\n" +
				"float-big-int = 2147483647\n",
			transcript("numeric.float-to-int"),
		)
		assertTrue(
			transcript(
				"collections.random-seeded",
			).startsWith("ints = [972016666, 1740578880, -408207414, -112774692]\n"),
		)
	}

	@Test
	fun durationFormattingMatchesDocumentedForms() {
		val lines = transcript("time.duration-format").lines().associate {
			it.substringBefore(" = ") to
				it.substringAfter(" = ")
		}
		assertEquals("1h 30m", lines["minutes"])
		assertEquals("1.5s", lines["fractional"])
		assertEquals("20m 34.567s", lines["millis"])
		assertEquals("Infinity", lines["infinite"])
		assertEquals("0s", lines["zero"])
		assertEquals("-5s", lines["negative"])
		assertEquals("100ns", lines["nanos"])
		assertEquals("3d 4h", lines["days"])
		assertEquals("PT1H30M", lines["iso"])
	}

	@Test
	fun processProbesCanFailAgainstAMisbehavingHost() {
		val good =
			FakeHost(commands = mapOf(listOf("echo", "drift") to CommandResult(0, "drift\n")))
		val bad =
			FakeHost(commands = mapOf(listOf("echo", "drift") to CommandResult(0, "drift \n")))
		val probe = Catalog.all.single { it.id == "process.echo" }
		assertEquals("echo = exit=0 out=drift\\n\n", ProbeRunner.run(good, probe).transcript)
		assertTrue(
			ProbeRunner.run(good, probe).transcript != ProbeRunner.run(bad, probe).transcript,
		)
	}

	@Test
	fun missingCommandProbeAcceptsNullOrNonzero() {
		val probe = Catalog.all.single { it.id == "process.missing-command" }
		assertEquals("nonzero-or-null = true\n", ProbeRunner.run(FakeHost(), probe).transcript)
		assertEquals(
			"nonzero-or-null = true\n",
			ProbeRunner.run(FakeHost(canRun = false), probe).transcript,
		)
		val zero =
			FakeHost(commands = mapOf(listOf("drift-no-such-binary-4f1c") to CommandResult(0, "")))
		assertEquals("nonzero-or-null = false\n", ProbeRunner.run(zero, probe).transcript)
	}

	@Test
	fun cgroupProbesParseLimits() {
		val host = FakeHost(
			files = mapOf(
				"/sys/fs/cgroup/cpu.max" to "250000 100000\n",
				"/sys/fs/cgroup/memory.max" to "max\n",
			),
		)
		assertEquals(
			"cpu-max = limited cpus=2.50\n",
			ProbeRunner.run(
				host,
				Catalog.all.single {
					it.id ==
						"resources.cgroup-cpu"
				},
			).transcript,
		)
		assertEquals(
			"memory-max = unlimited\n",
			ProbeRunner.run(
				host,
				Catalog.all.single {
					it.id ==
						"resources.cgroup-memory"
				},
			).transcript,
		)
		assertEquals(
			"cpu-max = n/a\n",
			ProbeRunner.run(
				FakeHost(),
				Catalog.all.single {
					it.id ==
						"resources.cgroup-cpu"
				},
			).transcript,
		)
	}

	@Test
	fun capsuleCarriesProbeResults() {
		val capsule = Capture.run(FakeHost(), "p", Catalog.all.filter { it.needs.isEmpty() })
		assertTrue(capsule.probes.isNotEmpty())
		assertEquals(capsule, Capsule.parse(capsule.canonical()))
		assertTrue(Capability.PROCESS !in FakeHost(capabilities = emptySet()).capabilities)
	}

	@Test
	fun everyCatalogProbeHasALinkEntryNamingRealAttributes() {
		val ids = Catalog.all.map { it.id }.toSet()
		val linked = ids.filter { it.substringBefore('.') != "kotlin" }.toSet()
		assertEquals(linked, ProbeLinks.attributes.keys)
		val host = FakeHost(
			platform = "linux",
			env = mapOf("HOME" to "/h", "TZ" to "UTC", "LANG" to "C", "LC_ALL" to "C"),
			files = mapOf(
				"/etc/os-release" to "ID=alpine\n",
				"/sys/fs/cgroup/cpu.max" to "max 100000\n",
				"/sys/fs/cgroup/memory.max" to "max\n",
			),
		)
		val paths = Capture.run(host, "t").attributes.map { it.path } +
			Scanners.all.map { "tool.${it.group}.version" }
		for ((id, prefixes) in ProbeLinks.attributes) {
			for (prefix in prefixes) {
				assertTrue(paths.any { it.startsWith(prefix) }, "$id names no attribute: $prefix")
			}
		}
	}
}
