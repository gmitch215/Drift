package dev.gmitch215.drift.bench

/** Text of the files the runner hands to a container: the per-arm plan and the docker command. */
object Harness {
	fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

	fun command(scenario: Scenario, arm: Arm): String {
		val (file, interpreter) = Dimensions.runtimes.getValue(scenario.runtime)
		return (
			(arm.interpreter ?: interpreter) + " " + file +
			arm.args.joinToString("") { " " + quote(it) }
		)
	}

	fun plan(scenario: Scenario, arm: Arm): String = buildString {
		append("TRIALS=").append(scenario.trials).append('\n')
		append("SALT=").append(scenario.salt).append('\n')
		append("GATE=").append(arm.gate).append('\n')
		append("CMD=").append(quote(command(scenario, arm))).append('\n')
		append("SIG=").append(quote(scenario.signature)).append('\n')
		append("SIGCODE=").append(quote(scenario.signatureExit?.toString() ?: "")).append('\n')
	}

	/** Host path under the scenario directory for the nth file mount of an arm. */
	fun filePath(armName: String, index: Int): String = "files/$armName/$index"

	/** A shell script that runs one arm; `DRIFT` names the Linux drift binary to mount, if any. */
	fun script(scenario: Scenario, armName: String, arm: Arm): String {
		val lines = mutableListOf<String>()
		lines += "#!/bin/sh"
		lines += "dir=\$(cd \"\$(dirname \"\$0\")\" && pwd)"
		lines += "set -- docker run --rm --name ${quote("drift-bench-${scenario.id}-$armName")} \\"
		lines += "\t--user \"\$(id -u):\$(id -g)\" --hostname bench " +
			"--network ${quote(arm.network)} \\"
		lines += "\t--memory ${quote(arm.memory)} --memory-swap ${quote(arm.memory)} " +
			"--cpus ${quote(arm.cpus)} \\"
		if (arm.cpuset != null) lines += "\t--cpuset-cpus ${quote(arm.cpuset)} \\"
		arm.ulimits.entries.sortedBy { it.key }.forEach { (k, v) ->
			lines += "\t--ulimit ${quote("$k=$v")} \\"
		}
		(mapOf("HOME" to "/tmp") + arm.env).entries.sortedBy { it.key }.forEach { (k, v) ->
			lines += "\t--env ${quote("$k=$v")} \\"
		}
		lines += "\t--volume \"\$dir:/bench:ro\" --workdir /bench"
		arm.files.keys.sorted().forEachIndexed { i, path ->
			lines += "set -- \"\$@\" --volume \"\$dir/${filePath(armName, i)}:$path:ro\""
		}
		lines += "[ -n \"\$DRIFT\" ] && set -- \"\$@\" --volume \"\$DRIFT:/opt/drift:ro\""
		lines += "exec \"\$@\" ${quote(arm.image)} sh /bench/harness.sh $armName"
		return lines.joinToString("\n", postfix = "\n")
	}
}
