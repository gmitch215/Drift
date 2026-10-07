package dev.gmitch215.drift

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.diff.DiffKind
import dev.gmitch215.drift.diff.Normalize
import dev.gmitch215.drift.diff.Order
import dev.gmitch215.drift.diff.ProbeChange
import dev.gmitch215.drift.diff.ProbeChangeStatus
import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Stability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffTest {
	private fun capsule(vararg attributes: Attribute, probes: List<ProbeResult> = emptyList()) =
		Capsule("c", attributes.toList(), probes)

	private fun attr(path: String, value: String, stability: Stability = Stability.STATIC) =
		Attribute(path, value, "test", stability)

	private fun probe(id: String, transcript: String, status: ProbeStatus = ProbeStatus.OK) =
		ProbeResult(id, status, transcript)

	private fun swapped(d: AttributeDiff) = d.copy(
		kind = when (d.kind) {
			DiffKind.ADDED -> DiffKind.REMOVED
			DiffKind.REMOVED -> DiffKind.ADDED
			DiffKind.CHANGED -> DiffKind.CHANGED
		},
		before = d.after,
		after = d.before,
		order = when (d.order) {
			Order.LESS -> Order.GREATER
			Order.GREATER -> Order.LESS
			else -> d.order
		},
	)

	private fun swapped(p: ProbeChange) = p.copy(before = p.after, after = p.before)

	private fun assertReversal(green: Capsule, red: Capsule) {
		val forward = CapsuleDiff.diff(green, red)
		val backward = CapsuleDiff.diff(red, green)
		assertEquals(forward.changes.map(::swapped), backward.changes)
		assertEquals(forward.ignored.map(::swapped), backward.ignored)
		assertEquals(forward.probes.map(::swapped), backward.probes)
	}

	@Test
	fun selfDiffIsEmpty() {
		val c = capsule(
			attr("os.arch", "x86_64"),
			attr("tool.java.version", "21.0.12.1"),
			attr("proc.pid", "42", Stability.VOLATILE),
			probes = listOf(
				probe("text.sort", "a\n"),
				probe("process.echo", "requires process\n", ProbeStatus.UNAVAILABLE),
			),
		)
		val d = CapsuleDiff.diff(c, c)
		assertEquals(emptyList(), d.changes)
		assertEquals(emptyList(), d.ignored)
		assertTrue(d.isEmpty)
		assertEquals(
			listOf(ProbeChangeStatus.UNAVAILABLE, ProbeChangeStatus.SAME),
			d.probes.map { it.status },
		)
	}

	@Test
	fun addedRemovedAndChangedAreTyped() {
		val green = capsule(attr("os.arch", "x86_64"), attr("tool.go.version", "1.21.13"))
		val red = capsule(attr("tool.go.version", "1.24.13"), attr("tool.node.version", "24.1.0"))
		val d = CapsuleDiff.diff(green, red)
		assertEquals(
			listOf(
				AttributeDiff(
					"os.arch",
					Dimension.CPU,
					DiffKind.REMOVED,
					"x86_64",
					null,
					Stability.STATIC,
					null,
				),
				AttributeDiff(
					"tool.go.version",
					Dimension.RUNTIME,
					DiffKind.CHANGED,
					"1.21.13",
					"1.24.13",
					Stability.STATIC,
					Order.LESS,
				),
				AttributeDiff(
					"tool.node.version",
					Dimension.RUNTIME,
					DiffKind.ADDED,
					null,
					"24.1.0",
					Stability.STATIC,
					null,
				),
			),
			d.changes,
		)
		assertEquals(emptyList(), d.ignored)
	}

	@Test
	fun changesSortByDimensionThenPathWithUnclassifiedLast() {
		val green = capsule()
		val red = capsule(
			attr("zzz.other", "1"),
			attr("drift.platform", "jvm"),
			attr("env.HOME", "/root"),
			attr("tool.go.version", "1.24"),
			attr("os.release.ID", "alpine"),
			attr("env.AAA", "1"),
		)
		assertEquals(
			listOf(
				"os.release.ID",
				"tool.go.version",
				"env.AAA",
				"env.HOME",
				"drift.platform",
				"zzz.other",
			),
			CapsuleDiff.diff(green, red).changes.map { it.path },
		)
	}

	@Test
	fun volatileAndNoisyAreIgnoredButCounted() {
		val green = capsule(
			attr("env.PWD", "/a", Stability.VOLATILE),
			attr("env.SEED", "1", Stability.NOISY),
			attr("env.MIXED", "x"),
			attr("env.SAME", "v", Stability.NOISY),
		)
		val red = capsule(
			attr("env.PWD", "/b", Stability.VOLATILE),
			attr("env.MIXED", "y", Stability.VOLATILE),
			attr("env.SAME", "v", Stability.NOISY),
			attr("env.NEW", "n", Stability.NOISY),
		)
		val d = CapsuleDiff.diff(green, red)
		assertEquals(emptyList(), d.changes)
		assertEquals(
			listOf(
				"env.MIXED" to Stability.VOLATILE,
				"env.NEW" to Stability.NOISY,
				"env.PWD" to Stability.VOLATILE,
				"env.SEED" to Stability.NOISY,
			),
			d.ignored.map { it.path to it.stability },
		)
		assertEquals(
			listOf(DiffKind.CHANGED, DiffKind.ADDED, DiffKind.CHANGED, DiffKind.REMOVED),
			d.ignored.map { it.kind },
		)
	}

	@Test
	fun probeStatusesNeverTreatMissingEvidenceAsAPass() {
		val green = capsule(
			probes = listOf(
				probe("a.same", "x\n"),
				probe("b.differs", "x\n"),
				probe("c.down-red", "x\n"),
				probe("d.down-green", "requires process\n", ProbeStatus.UNAVAILABLE),
				probe("e.only-green", "x\n"),
				probe("f.both-down", "requires file\n", ProbeStatus.UNAVAILABLE),
			),
		)
		val red = capsule(
			probes = listOf(
				probe("a.same", "x\n"),
				probe("b.differs", "y\n"),
				probe("c.down-red", "requires file\n", ProbeStatus.UNAVAILABLE),
				probe("d.down-green", "x\n"),
				probe("f.both-down", "requires file\n", ProbeStatus.UNAVAILABLE),
				probe("g.only-red", "x\n"),
			),
		)
		val d = CapsuleDiff.diff(green, red)
		assertEquals(
			listOf(
				ProbeChange("a.same", "x\n", "x\n", ProbeChangeStatus.SAME),
				ProbeChange("b.differs", "x\n", "y\n", ProbeChangeStatus.DIFFERENT),
				ProbeChange("c.down-red", "x\n", null, ProbeChangeStatus.UNAVAILABLE),
				ProbeChange("d.down-green", null, "x\n", ProbeChangeStatus.UNAVAILABLE),
				ProbeChange("e.only-green", "x\n", null, ProbeChangeStatus.UNAVAILABLE),
				ProbeChange("f.both-down", null, null, ProbeChangeStatus.UNAVAILABLE),
				ProbeChange("g.only-red", null, "x\n", ProbeChangeStatus.UNAVAILABLE),
			),
			d.probes,
		)
		assertTrue(!d.isEmpty)
	}

	@Test
	fun unavailableProbesAloneDoNotMakeADiffNonEmpty() {
		val down = capsule(probes = listOf(probe("p", "requires file\n", ProbeStatus.UNAVAILABLE)))
		assertTrue(CapsuleDiff.diff(down, down).isEmpty)
	}

	@Test
	fun directionIsGreenToRed() {
		val green = capsule(attr("tool.go.version", "1.21.13"))
		val red = capsule(attr("tool.go.version", "1.24.13"))
		assertEquals("1.21.13", CapsuleDiff.diff(green, red).changes.single().before)
		assertEquals(Order.GREATER, CapsuleDiff.diff(red, green).changes.single().order)
	}

	@Test
	fun reversalSwapsAddedAndRemovedAndFlipsOrder() {
		val green = capsule(
			attr("os.arch", "x86_64"),
			attr("tool.go.version", "1.21.13"),
			attr("tool.java.version", "1.8.0_504"),
			attr("env.TZ", "UTC"),
			attr("env.PWD", "/a", Stability.VOLATILE),
			probes = listOf(probe("p1", "a\n"), probe("p2", "x\n"), probe("p4", "q\n")),
		)
		val red = capsule(
			attr("tool.go.version", "1.24.13"),
			attr("tool.java.version", "21.0.12.1"),
			attr("tool.node.version", "24.1.0"),
			attr("env.TZ", "Europe/Paris"),
			attr("env.PWD", "/b", Stability.VOLATILE),
			probes = listOf(probe("p1", "b\n"), probe("p3", "z\n"), probe("p4", "q\n")),
		)
		assertReversal(green, red)
		val kinds = CapsuleDiff.diff(green, red).changes.map { it.kind }.toSet()
		assertEquals(setOf(DiffKind.ADDED, DiffKind.REMOVED, DiffKind.CHANGED), kinds)
		assertEquals(
			listOf(Order.LESS, Order.LESS, Order.UNORDERED),
			CapsuleDiff.diff(green, red).changes.filter { it.order != null }.map { it.order },
		)
	}

	@Test
	fun versionOrderUsesTheJavaSchemeOnlyUnderToolJava() {
		fun order(path: String, a: String, b: String) = CapsuleDiff
			.diff(capsule(attr(path, a)), capsule(attr(path, b)))
			.changes.single().order

		assertEquals(Order.UNORDERED, order("tool.java.version", "1.8.0_504", "8.0.504+1"))
		assertEquals(Order.LESS, order("tool.java.version", "1.8.0_504", "21.0.12.1"))
		assertEquals(Order.GREATER, order("tool.go.version", "1.8.0_504", "0.8.0"))
		assertEquals(Order.LESS, order("cpu.count", "2", "4"))
		assertEquals(Order.UNORDERED, order("os.arch", "x86_64", "aarch64"))
		assertEquals(Order.UNORDERED, order("tool.cc.version", "unparsed", "14.2.0"))
	}

	@Test
	fun aChangedValueNeverReportsEqualOrder() {
		fun order(a: String, b: String) = CapsuleDiff
			.diff(
				capsule(attr("ci.provisioner.build-date", a)),
				capsule(attr("ci.provisioner.build-date", b)),
			)
			.changes.single().order

		assertEquals(Order.UNORDERED, order("2026-08-28T16:44:25Z", "2026-09-01T19:56:44Z"))
		assertEquals(Order.UNORDERED, order("1.0.0-a", "1.0.0-b"))
		assertEquals(Order.LESS, order("1.0.0", "1.0.1"))
		assertEquals(Order.GREATER, order("2.0", "1.9"))
		val diff = CapsuleDiff.diff(
			capsule(attr("tool.java.version", "1.8.0_504"), attr("cpu.count", "2")),
			capsule(attr("tool.java.version", "8.0.504+1"), attr("cpu.count", "02")),
		)
		assertTrue(diff.changes.none { it.order == Order.EQUAL })
	}

	@Test
	fun osNameIsNormalizedAcrossTargets() {
		val spellings = listOf("mac", "macosx", "macos", "Mac OS X", "darwin", " Darwin ")
		for (a in spellings) {
			for (b in spellings) {
				val d = CapsuleDiff.diff(capsule(attr("os.name", a)), capsule(attr("os.name", b)))
				assertEquals(emptyList(), d.changes, "$a $b")
			}
		}
		for (name in listOf("windows", "windows 11", "mingw")) {
			assertEquals("windows", Normalize.osName(name))
		}
		assertEquals("linux", Normalize.osName("linux"))
		assertEquals("unknown", Normalize.osName("unknown"))
		assertEquals("freebsd", Normalize.osName("FreeBSD"))

		val mac = capsule(attr("os.name", "mac"))
		val real = CapsuleDiff.diff(mac, capsule(attr("os.name", "linux")))
		assertEquals(Dimension.OS, real.changes.single().dimension)
		assertEquals("macos", real.changes.single().before)
		val wasm = CapsuleDiff.diff(
			capsule(attr("os.name", "macosx")),
			capsule(attr("os.name", "unknown")),
		)
		assertEquals("unknown", wasm.changes.single().after)
	}

	@Test
	fun onlyOsNameIsNormalized() {
		assertEquals("mac", Normalize.value("env.OS", "mac"))
		assertEquals("macos", Normalize.value("os.name", "mac"))
	}

	@Test
	fun pathsEmittedTodayMapToTheirDimension() {
		val table = mapOf(
			"os.name" to Dimension.OS,
			"os.version" to Dimension.OS,
			"os.release.ID" to Dimension.OS,
			"os.release.VERSION_ID" to Dimension.OS,
			"os.release.PRETTY_NAME" to Dimension.OS,
			"tool.libc.family" to Dimension.OS,
			"kernel.release" to Dimension.KERNEL,
			"os.arch" to Dimension.CPU,
			"cpu.count" to Dimension.CPU,
			"cgroup.cpu.max" to Dimension.CPU_LIMIT,
			"cgroup.memory.max" to Dimension.MEMORY,
			"tool.java.implementation" to Dimension.RUNTIME,
			"tool.java.version" to Dimension.RUNTIME,
			"tool.go.version" to Dimension.RUNTIME,
			"tool.go.os" to Dimension.RUNTIME,
			"tool.go.arch" to Dimension.RUNTIME,
			"tool.node.version" to Dimension.RUNTIME,
			"tool.python.version" to Dimension.RUNTIME,
			"tool.python.externally-managed" to Dimension.RUNTIME,
			"tool.php.version" to Dimension.RUNTIME,
			"tool.php.sapi" to Dimension.RUNTIME,
			"tool.php.thread-safety" to Dimension.RUNTIME,
			"tool.rust.version" to Dimension.RUNTIME,
			"runtime.platform" to Dimension.RUNTIME,
			"tool.cc.family" to Dimension.COMPILER,
			"tool.cc.version" to Dimension.COMPILER,
			"tool.visualstudio.version" to Dimension.COMPILER,
			"ci.runner.label" to Dimension.BUILD,
			"tool.coreutils.flavor" to Dimension.USERLAND,
			"tool.coreutils.version" to Dimension.USERLAND,
			"tool.git.version" to Dimension.USERLAND,
			"env.PATH" to Dimension.ENV,
			"env.JAVA_HOME" to Dimension.ENV,
			"env.LANG" to Dimension.LOCALE,
			"env.LANGUAGE" to Dimension.LOCALE,
			"env.LC_ALL" to Dimension.LOCALE,
			"env.TZ" to Dimension.LOCALE,
			"env.HTTP_PROXY" to Dimension.NETWORK,
			"env.https_proxy" to Dimension.NETWORK,
			"env.NO_PROXY" to Dimension.NETWORK,
			"limits.nofile" to Dimension.LIMITS,
			"network.loopback" to Dimension.NETWORK,
			"browser.engine" to Dimension.BROWSER,
		)
		for ((path, dimension) in table) assertEquals(dimension, Dimension.of(path), path)
		assertEquals(Dimension.entries.toSet(), table.values.toSet())
		assertEquals(14, Dimension.entries.size)
		assertEquals(Dimension.entries.size, Dimension.entries.map { it.id }.toSet().size)
	}

	@Test
	fun captureAndUnknownPathsHaveNoDimension() {
		assertNull(Dimension.of("drift.platform"))
		assertNull(Dimension.of("zzz.unknown"))
		assertNull(Dimension.of("tool.mystery.version"))
		assertNull(Dimension.of(""))
	}

	@Test
	fun probesMapToTheDimensionTheyProbe() {
		val table = mapOf(
			"numeric.double-tostring" to Dimension.RUNTIME,
			"numeric.parse" to Dimension.RUNTIME,
			"text.regex" to Dimension.RUNTIME,
			"text.hashcode" to Dimension.RUNTIME,
			"collections.hash-order" to Dimension.RUNTIME,
			"time.duration-format" to Dimension.RUNTIME,
			"time.monotonic" to Dimension.RUNTIME,
			"process.echo" to Dimension.USERLAND,
			"process.exit-code" to Dimension.USERLAND,
			"process.stderr-merged" to Dimension.USERLAND,
			"process.missing-command" to Dimension.USERLAND,
			"process.quoting" to Dimension.USERLAND,
			"process.large-output" to Dimension.USERLAND,
			"process.env-visible" to Dimension.ENV,
			"process.timezone" to Dimension.LOCALE,
			"process.resolve-localhost" to Dimension.NETWORK,
			"resources.cgroup-cpu" to Dimension.CPU_LIMIT,
			"resources.cgroup-memory" to Dimension.MEMORY,
			"resources.locale-env" to Dimension.LOCALE,
			"resources.missing-file" to Dimension.OS,
		)
		for ((id, dimension) in table) assertEquals(dimension, Dimension.ofProbe(id), id)
		assertNull(Dimension.ofProbe("mystery.probe"))
	}

	private class Example(
		val dimension: Dimension,
		val path: String,
		val before: String,
		val after: String,
		val order: String,
	)

	@Test
	fun everyDimensionHasAGoldenDiff() {
		val examples = listOf(
			Example(Dimension.OS, "os.release.VERSION_ID", "3.20.8", "3.22.2", "less"),
			Example(Dimension.KERNEL, "kernel.release", "6.17.0-1-azure", "7.0.0-1-azure", "less"),
			Example(Dimension.CPU, "cpu.count", "2", "4", "less"),
			Example(Dimension.CPU_LIMIT, "cgroup.cpu.max", "max 1000", "200000 1000", "unordered"),
			Example(Dimension.MEMORY, "cgroup.memory.max", "max", "536870912", "unordered"),
			Example(Dimension.RUNTIME, "tool.node.version", "18.20.8", "24.21.0", "less"),
			Example(Dimension.COMPILER, "tool.cc.version", "13.2.1", "14.2.0", "less"),
			Example(Dimension.BUILD, "ci.runner.label", "ubuntu-22", "ubuntu-24", "unordered"),
			Example(Dimension.USERLAND, "tool.git.version", "2.34.1", "2.45.4", "less"),
			Example(Dimension.ENV, "env.JAVA_HOME", "/opt/a", "/opt/b", "unordered"),
			Example(Dimension.LOCALE, "env.TZ", "UTC", "Europe/Paris", "unordered"),
			Example(Dimension.LIMITS, "limits.nofile", "1024", "65536", "less"),
			Example(Dimension.NETWORK, "env.NO_PROXY", "localhost", "localhost,.corp", "unordered"),
			Example(Dimension.BROWSER, "browser.engine", "chromium 126", "webkit 18", "unordered"),
		)
		assertEquals(Dimension.entries.toSet(), examples.map { it.dimension }.toSet())
		for (e in examples) {
			val green = capsule(attr(e.path, e.before))
			val red = capsule(attr(e.path, e.after))
			assertEquals(
				"{\"after\":\"${e.after}\",\"before\":\"${e.before}\"," +
					"\"dimension\":\"${e.dimension.id}\",\"kind\":\"changed\"," +
					"\"order\":\"${e.order}\",\"path\":\"${e.path}\",\"stability\":\"static\"}",
				CanonicalJson.encode(CapsuleDiff.diff(green, red).changes.single().toJson()),
			)
			assertReversal(green, red)
		}
	}

	@Test
	fun canonicalBytesOfADiffAreFixed() {
		val green = capsule(
			attr("tool.java.version", "1.8.0_504"),
			attr("os.name", "mac"),
			attr("env.PWD", "/a", Stability.VOLATILE),
			probes = listOf(probe("text.sort", "a\n"), probe("process.echo", "x\n")),
		)
		val red = capsule(
			attr("tool.java.version", "21.0.12.1"),
			attr("os.name", "macosx"),
			attr("env.PWD", "/b", Stability.VOLATILE),
			attr("tool.cc.version", "14.2.0"),
			probes = listOf(
				probe("text.sort", "b\n"),
				probe("process.echo", "requires process\n", ProbeStatus.UNAVAILABLE),
			),
		)
		val expected = "{\"changes\":[" +
			"{\"after\":\"21.0.12.1\",\"before\":\"1.8.0_504\",\"dimension\":\"runtime\"," +
			"\"kind\":\"changed\",\"order\":\"less\",\"path\":\"tool.java.version\"," +
			"\"stability\":\"static\"}," +
			"{\"after\":\"14.2.0\",\"before\":null,\"dimension\":\"compiler\"," +
			"\"kind\":\"added\",\"order\":null,\"path\":\"tool.cc.version\"," +
			"\"stability\":\"static\"}]," +
			"\"ignored\":[{\"after\":\"/b\",\"before\":\"/a\",\"dimension\":\"env\"," +
			"\"kind\":\"changed\",\"order\":\"unordered\",\"path\":\"env.PWD\"," +
			"\"stability\":\"volatile\"}]," +
			"\"probes\":[" +
			"{\"after\":null,\"before\":\"x\\n\",\"id\":\"process.echo\"," +
			"\"status\":\"unavailable\"}," +
			"{\"after\":\"b\\n\",\"before\":\"a\\n\",\"id\":\"text.sort\"," +
			"\"status\":\"different\"}]}"
		assertEquals(expected, CanonicalJson.encode(CapsuleDiff.diff(green, red).toJson()))
	}

	@Test
	fun fixtureVersionsDiffInTheRuntimeDimension() {
		fun version(name: String, line: (String) -> String) =
			line(Fixtures.text(name).lineSequence().first())

		val eight = version("java/temurin-8.txt") { it.substringAfter('"').substringBefore('"') }
		val twentyOne = version("java/temurin-21.txt") {
			it.substringAfter('"').substringBefore('"')
		}
		val gcc13 = version("cc/alpine-3.20.txt") { it.substringAfter(") ").substringBefore(' ') }
		val gcc14 = version("cc/alpine-3.22.txt") { it.substringAfter(") ").substringBefore(' ') }
		val green = capsule(attr("tool.java.version", eight), attr("tool.cc.version", gcc13))
		val red = capsule(attr("tool.java.version", twentyOne), attr("tool.cc.version", gcc14))
		val d = CapsuleDiff.diff(green, red)
		assertEquals(
			listOf(
				Triple("tool.java.version", Dimension.RUNTIME, Order.LESS),
				Triple("tool.cc.version", Dimension.COMPILER, Order.LESS),
			),
			d.changes.map { Triple(it.path, it.dimension, it.order) },
		)
		assertReversal(green, red)
		assertTrue(CapsuleDiff.diff(green, green).isEmpty)
	}
}
