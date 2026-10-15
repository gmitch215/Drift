package dev.gmitch215.drift.bench

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunnerTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    private val pwsh: String? = if (
        runCatching {
            ProcessBuilder("pwsh", "-NoProfile", "-Command", "exit 0").start().waitFor() == 0
        }.getOrDefault(false)
    ) {
        "pwsh"
    } else {
        null
    }

    private val template = """
        schema: 1
        templates:
          - id: fake-mode-n
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
        for (name in listOf("harness.sh", "run-batch.sh", "native.sh")) {
            runner.resolve(name).writeText(Path.of("runner", name).readText())
        }
        return root
    }

    private fun gateInputs(): List<Pair<Int, Long>> {
        val rng = SplitMix64(7)
        val edges = listOf(0 to 0L, 0 to 4294967295L, 1 to 0L, 99999 to 4294967295L)
        return edges + (0 until 996).map { rng.below(100_000) to (rng.next() ushr 32) }
    }

    private fun hashes(inputs: List<Pair<Int, Long>>, vararg command: String): List<Long> {
        val file = Files.createTempFile("gate-inputs", ".txt")
        file.writeText(inputs.joinToString("\n", postfix = "\n") { "${it.first} ${it.second}" })
        val pb = ProcessBuilder(*command)
        pb.redirectInput(file.toFile())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), out)
        return out.lines().filter { it.isNotBlank() }.map { it.trim().toLong() }
    }

    private fun script(name: String): String = Path.of("runner", name).toAbsolutePath().toString()

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
    fun theShellGateHashMatchesTheEngineOnAThousandInputs() {
        if (windows) return
        val inputs = gateInputs()
        assertEquals(1000, inputs.size)
        assertEquals(
            inputs.map { (trial, salt) -> Gate.hash(trial, salt) },
            hashes(inputs, "sh", script("harness.sh"), "--hash"),
        )
    }

    @Test
    fun thePowerShellGateHashMatchesTheEngineOnAThousandInputs() {
        val pwsh = pwsh ?: return
        val inputs = gateInputs()
        assertEquals(
            inputs.map { (trial, salt) -> Gate.hash(trial, salt) },
            hashes(inputs, pwsh, "-NoProfile", "-File", script("harness.ps1"), "-Hash"),
        )
    }

    private fun native(prepared: Path, script: List<String>, drift: String? = null): String {
        val pb = ProcessBuilder(script + prepared.toString())
        pb.environment().remove("DRIFT_BIN")
        drift?.let { pb.environment()["DRIFT_BIN"] = it }
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), out)
        return out
    }

    private fun prepared(): Pair<Work, Path> {
        val work = Work(root())
        work.writeGenerated()
        val prepared = Files.createTempDirectory("bench-prepared")
        work.prepare(prepared, emptySet())
        return work to prepared
    }

    @Test
    fun nativeArmsRunWithoutDockerAndIngestValidates() {
        if (windows) return
        val (work, prepared) = prepared()
        val log = native(prepared, listOf("sh", prepared.resolve("native.sh").toString()))
        assertTrue(log.contains("BATCH DONE"))
        assertEquals(work.devIds().size * 2, log.lines().count { it.contains(" exit=0 ") })
        val verdicts = work.ingest(prepared, null, requireCapsule = false)
        assertEquals(work.devIds(), verdicts.keys)
        assertTrue(
            verdicts.values.all { it.valid },
            verdicts.values.flatMap { it.reasons }.toString(),
        )
    }

    @Test
    fun nativeArmsCaptureTheCapsuleWithTheArmEnvironment() {
        if (windows) return
        val (_, prepared) = prepared()
        val bin = Files.createTempDirectory("bench-bin").resolve("drift")
        bin.writeText("#!/bin/sh\necho \"{capsule \$3 MODE=\$MODE}\"\n")
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"))
        native(prepared, listOf("sh", prepared.resolve("native.sh").toString()), bin.toString())
        val id = prepared.toFile().listFiles { f -> f.isDirectory }!!.minOf { it.name }
        val red = prepared.resolve(id).resolve("red.out").readText()
        val green = prepared.resolve(id).resolve("green.out").readText()
        assertTrue(green.startsWith("CAPSULE-BEGIN\n{capsule green MODE=good}\nCAPSULE-END\n"))
        assertTrue(red.startsWith("CAPSULE-BEGIN\n{capsule red MODE="))
        assertFalse(red.contains("MODE=good"))
    }

    @Test
    fun nativeArmsSkipScenariosThatNeedContainerSettings() {
        if (windows) return
        val root = root()
        root.resolve("scenarios/fake.yml").writeText(
            template.replace("debian:12-slim\"", "debian:12-slim\"\n      memory: 96m"),
        )
        val work = Work(root)
        work.writeGenerated()
        val prepared = Files.createTempDirectory("bench-prepared")
        val id = work.prepare(prepared, emptySet()).first()
        assertEquals(
            "container settings: memory\n",
            prepared.resolve(id).resolve("native.skip").readText(),
        )
        val log = native(prepared, listOf("sh", prepared.resolve("native.sh").toString()))
        assertTrue(log.contains("$id skipped: container settings: memory"))
        assertFalse(prepared.resolve(id).resolve("green.out").exists())
    }

    @Test
    fun powerShellNativeArmsWriteTheSameOutputAsTheShellOnes() {
        val pwsh = pwsh ?: return
        if (windows) return
        val (_, viaShell) = prepared()
        native(viaShell, listOf("sh", viaShell.resolve("native.sh").toString()))
        val (_, viaPowerShell) = prepared()
        native(viaPowerShell, listOf(pwsh, "-NoProfile", "-File", script("native.ps1"), "-Root"))
        val ids = viaShell.toFile().listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()
        assertTrue(ids.isNotEmpty())
        for (id in ids) {
            for (arm in listOf("green", "red")) {
                assertEquals(
                    viaShell.resolve(id).resolve("$arm.out").readText(),
                    viaPowerShell.resolve(id).resolve("$arm.out").readText(),
                    "$id $arm",
                )
            }
        }
    }

    @Test
    fun templateIdsExpandToEveryInstanceAndRefuseAnUnknownTemplate() {
        val work = Work(root())
        work.writeGenerated()
        val expanded = work.idsOfTemplates(setOf("fake-mode-n"))
        assertTrue(expanded.isNotEmpty())
        assertEquals(setOf("fake-mode-n-1", "fake-mode-n-2").intersect(work.devIds()), expanded)
        assertEquals(emptySet(), work.idsOfTemplates(emptySet()))
        assertFailsWith<IllegalStateException> { work.idsOfTemplates(setOf("nope")) }
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
        val test = work.split().test
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
