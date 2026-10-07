package dev.gmitch215.drift.bench

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunnerTest {
	private val windows = System.getProperty("os.name").startsWith("Windows")

	private val template = """
		schema: 1
		templates:
		  - id: fake-mode-a
		    title: A mode variable decides the outcome
		    dimension: env
		    instances: 2
		    variants:
		      - mode: bad
		      - mode: worse
		    gate: "500"
		    trials: "20"
		    runtime: sh
		    program: |
		      if [ "${'$'}BENCH_FLAKE" != 1 ]; then echo ok; exit 0; fi
		      if [ "${'$'}MODE" != good ]; then echo "FAILURE-{{mode}}"; exit 1; fi
		      echo ok
		    signature: "FAILURE-{{mode}}"
		    base:
		      image: "debian:12-slim"
		    green:
		      env:
		        MODE: good
		    red:
		      env:
		        MODE: "{{mode}}"
		    cause:
		      dimension: env
		      path: env.MODE
		      direction: changed
	""".trimIndent()

	private val fakeDocker = """
		#!/bin/sh
		for a in "${'$'}@"; do last=${'$'}a; done
		prev=
		for a in "${'$'}@"; do
		  case "${'$'}prev" in
		    --volume) case "${'$'}a" in *:/bench:ro) dir=${'$'}{a%:/bench:ro} ;; esac ;;
		    --env) export "${'$'}a" ;;
		  esac
		  prev=${'$'}a
		done
		BENCH_DIR=${'$'}dir exec sh "${'$'}dir/harness.sh" "${'$'}last"
	""".trimIndent() + "\n"

	private fun root(): Path {
		val root = Files.createTempDirectory("bench-root")
		root.resolve("scenarios").createDirectories().resolve("fake.yml").writeText(template)
		val runner = root.resolve("runner").createDirectories()
		for (name in listOf("harness.sh", "run-batch.sh")) {
			runner.resolve(name).writeText(Path.of("runner", name).readText())
		}
		return root
	}

	private fun batch(prepared: Path): String {
		val bin = Files.createTempDirectory("bench-bin")
		val docker = bin.resolve("docker")
		docker.writeText(fakeDocker)
		Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"))
		val timeout = bin.resolve("timeout")
		timeout.writeText("#!/bin/sh\nshift\nexec \"\$@\"\n")
		Files.setPosixFilePermissions(timeout, PosixFilePermissions.fromString("rwxr-xr-x"))
		val pb = ProcessBuilder(
			"sh",
			prepared.resolve("run-batch.sh").toString(),
			prepared.toString(),
		)
		pb.environment()["PATH"] = bin.toString() + ":" + System.getenv("PATH")
		pb.environment().remove("DRIFT")
		pb.redirectErrorStream(true)
		val p = pb.start()
		val out = p.inputStream.bufferedReader().readText()
		assertEquals(0, p.waitFor(), out)
		return out
	}

	@Test
	fun theBatchRunsBothArmsThroughTheFakeDockerAndIngestValidates() {
		if (windows) return
		val work = Work(root())
		work.writeGenerated()
		val ids = work.devIds().toList()
		assertTrue(ids.isNotEmpty())
		val prepared = Files.createTempDirectory("bench-prepared")
		assertEquals(ids.sorted(), work.prepare(prepared, emptySet()).sorted())
		val log = batch(prepared)
		assertTrue(log.contains("BATCH DONE"))
		assertEquals(ids.size * 2, log.lines().count { it.contains(" exit=0 ") })
		val verdicts = work.ingest(prepared, null, requireCapsule = false)
		assertEquals(ids.toSet(), verdicts.keys)
		assertTrue(
			verdicts.values.all { it.valid },
			verdicts.values.flatMap { it.reasons }.toString(),
		)
	}

	@Test
	fun aBatchSkipsFinishedArms() {
		if (windows) return
		val work = Work(root())
		work.writeGenerated()
		val prepared = Files.createTempDirectory("bench-prepared")
		work.prepare(prepared, emptySet())
		batch(prepared)
		val log = batch(prepared)
		assertEquals(0, log.lines().count { it.contains(" exit=") })
	}

	@Test
	fun aTamperedOutputIsDroppedAndListed() {
		if (windows) return
		val work = Work(root())
		work.writeGenerated()
		val prepared = Files.createTempDirectory("bench-prepared")
		val id = work.prepare(prepared, emptySet()).first()
		batch(prepared)
		val green = prepared.resolve(id).resolve("green.out")
		green.writeText(green.readText().replace("TRIAL\t0\t0\t", "TRIAL\t0\t1\t"))
		val verdicts = work.ingest(prepared, null, requireCapsule = false)
		assertFalse(verdicts.getValue(id).valid)
		assertTrue(work.droppedIds().contains(id))
		assertFalse(work.generate().first.contains("\"id\":\"$id\""))
	}

	@Test
	fun aTestScenarioIsNeverPrepared() {
		val work = Work(root())
		work.writeGenerated()
		val test = SplitRule.split(work.loadScenarios().map { it.id }).test
		assertTrue(test.isNotEmpty())
		val e = assertFailsWith<SealedException> {
			work.prepare(Files.createTempDirectory("bench-prepared"), setOf(test.first()))
		}
		assertTrue(e.message!!.contains(test.first()))
	}

	@Test
	fun fileMountsAreWrittenUnderTheArmDirectory() {
		val root = root()
		root.resolve("scenarios/fake.yml").writeText(
			template.replace("MODE: good", "MODE: good\n      files:\n        /etc/x: \"hello\\n\"")
				.replace(
					"    red:\n      env:",
					"    red:\n      files:\n        /etc/x: \"bye\\n\"\n      env:",
				),
		)
		val work = Work(root)
		work.writeGenerated()
		val prepared = Files.createTempDirectory("bench-prepared")
		val id = work.prepare(prepared, emptySet()).first()
		assertEquals("hello\n", prepared.resolve(id).resolve("files/green/0").readText())
		assertEquals("bye\n", prepared.resolve(id).resolve("files/red/0").readText())
	}
}
