package dev.gmitch215.drift.cli

import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.lab.Executor
import dev.gmitch215.drift.lab.RawTrial
import dev.gmitch215.drift.lab.Reproduction
import dev.gmitch215.drift.lab.RunConfig
import dev.gmitch215.drift.model.Capsule
import kotlin.time.TimeSource

/**
 * Runs arms with `docker` through the host's process runner. Each trial is one `docker run --rm`
 * with the flags `reproduce` derived from the capsule; output is cut to its last [OUTPUT_BYTES]
 * bytes and one trial is cut off after `timeoutSeconds`. `maxMemory`
 * and `maxCpus` apply only to an arm whose capsule sets no limit of its own.
 */
class DockerExecutor(
	private val host: Host,
	private val workspace: String,
	private val driftBinary: String? = null,
	private val timeoutSeconds: Long = 120,
	private val maxMemory: String? = null,
	private val maxCpus: String? = null,
) : Executor {
	private val built = mutableSetOf<String>()

	override fun prepare(arm: RunConfig, dir: String): String? {
		val repro = arm.reproduction ?: return "the arm has no container"
		if (!built.add(repro.tag)) return null
		val result = host.run(listOf("sh", "-c", "${repro.buildLine(dir)} 2>&1"))
			?: return "cannot run sh to start docker"
		if (result.exitCode == 0) return null
		built.remove(repro.tag)
		return "docker build failed (exit ${result.exitCode}): ${tail(result.output, BUILD_TAIL)}"
	}

	override fun trial(arm: RunConfig, index: Int): RawTrial {
		val repro = requireNotNull(arm.reproduction) { "the arm has no container" }
		val mark = TimeSource.Monotonic.markNow()
		val result = host.run(listOf("sh", "-c", trialScript(repro, index)))
			?: return RawTrial(-1, "", 0, "cannot run sh to start docker")
		val seconds = (mark.elapsedNow().inWholeMilliseconds + 999) / 1000
		val marker = result.output.lastIndexOf(EXIT_MARKER)
		if (marker < 0) {
			return RawTrial(-1, result.output, seconds, "no exit status came back from docker")
		}
		val code = result.output.substring(marker + EXIT_MARKER.length).trim().toIntOrNull()
			?: return RawTrial(-1, result.output, seconds, "unreadable exit status from docker")
		val output = result.output.substring(0, marker)
		val infra = when (code) {
			TIMED_OUT -> "timed out after $timeoutSeconds seconds"
			DOCKER_ERROR -> "docker could not run the container: ${tail(output, BUILD_TAIL)}"
			else -> null
		}
		return RawTrial(code, output, seconds, infra)
	}

	override fun capture(arm: RunConfig): Capsule? {
		val binary = driftBinary ?: return null
		val repro = arm.reproduction ?: return null
		val extra = limits(repro) + listOf(
			"--hostname drift",
			"--volume ${quote(binary)}:/opt/drift:ro",
		)
		val line = repro.runLine(
			extra,
			"/opt/drift capture --label ${quote(arm.label)} --tools --probes",
		)
		val result = host.run(listOf("sh", "-c", "cd ${quote(workspace)} && $line 2>/dev/null"))
		if (result == null || result.exitCode != 0) return null
		return try {
			Capsule.parse(result.output.trim())
		} catch (e: Exception) {
			null
		}
	}

	internal fun trialScript(repro: Reproduction, index: Int): String {
		val extra = limits(repro) + listOf(
			"--hostname drift",
			"--env DRIFT_TRIAL=$index",
			"--name drift-solve-${repro.tag.substringAfter(':')}-$index-\$\$",
		)
		val run = repro.runLine(extra)
		return "cd ${quote(workspace)} && t=; " +
			"command -v timeout >/dev/null 2>&1 && t=\"timeout -k 5 $timeoutSeconds\"; " +
			"{ \$t $run; code=\$?; " +
			"[ \"\$code\" -eq $TIMED_OUT ] && " +
			"docker rm -f \"drift-solve-${repro.tag.substringAfter(':')}-$index-\$\$\" " +
			">/dev/null 2>&1; echo \"$EXIT_MARKER\$code\"; } 2>&1 | tail -c $OUTPUT_BYTES"
	}

	private fun limits(repro: Reproduction): List<String> = buildList {
		if (maxMemory != null && repro.flags.none { it.startsWith("--memory") }) {
			add("--memory ${quote(maxMemory)}")
		}
		if (maxCpus != null && repro.flags.none { it.startsWith("--cpus") }) {
			add("--cpus ${quote(maxCpus)}")
		}
	}

	private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

	private fun tail(text: String, n: Int) = text.trim().takeLast(n)

	companion object {
		const val OUTPUT_BYTES = 4096
		const val EXIT_MARKER = "DRIFT_EXIT="
		const val TIMED_OUT = 124
		const val DOCKER_ERROR = 125
		private const val BUILD_TAIL = 400
	}
}
