package dev.gmitch215.drift.bench

/** Text of the files the runner hands to a container: the per-arm plan and the docker command. */
object Harness {
    const val MEMORY = "512m"
    const val CPUS = "2"
    const val NETWORK = "none"
    private const val GCOMPAT = "RUN apk add --no-cache gcompat libgcc"

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

    /** The arm's variables as `export` lines that `native.sh` and `harness.ps1` read. */
    fun env(arm: Arm): String = arm.env.entries.sortedBy { it.key }
        .joinToString("") { (k, v) -> "export $k=${quote(v)}\n" }

    /** Why a scenario cannot run on the host without a container, or null when it can. */
    fun nativeRefusal(scenario: Scenario): String? {
        val settings = listOf(scenario.green, scenario.red).flatMap { arm ->
            listOfNotNull(
                "files".takeIf { arm.files.isNotEmpty() },
                "ulimits".takeIf { arm.ulimits.isNotEmpty() },
                "cpuset".takeIf { arm.cpuset != null },
                "memory".takeIf { arm.memory != MEMORY },
                "cpus".takeIf { arm.cpus != CPUS },
                "network".takeIf { arm.network != NETWORK },
            )
        }.distinct()
        return if (settings.isEmpty()) null else "container settings: " + settings.joinToString()
    }

    /** Host path under the scenario directory for the nth file mount of an arm. */
    fun filePath(armName: String, index: Int): String = "files/$armName/$index"

    /** The tag of an Alpine image with gcompat and libgcc added, which the glibc drift needs. */
    fun gcompatTag(image: String): String =
        "drift-bench-gcompat:" + image.replace(Regex("[^A-Za-z0-9_.-]"), "-")

    /** A shell script that runs one arm; `DRIFT` names the Linux drift binary to mount, if any. */
    fun script(scenario: Scenario, armName: String, arm: Arm): String {
        val alpine = "alpine" in arm.image
        val image = if (alpine) gcompatTag(arm.image) else arm.image
        val lines = mutableListOf<String>()
        lines += "#!/bin/sh"
        lines += "dir=\$(cd \"\$(dirname \"\$0\")\" && pwd)"
        if (alpine) {
            lines += "printf 'FROM %s\\n$GCOMPAT\\n' ${quote(arm.image)} | " +
                "docker build --quiet --tag ${quote(image)} - > /dev/null || exit 1"
        }
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
        lines += "exec \"\$@\" ${quote(image)} sh /bench/harness.sh $armName"
        return lines.joinToString("\n", postfix = "\n")
    }
}
