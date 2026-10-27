package dev.gmitch215.drift.bench

import dev.gmitch215.drift.lab.SolveOptions

/** Text of the files the runner hands to a container: the per-arm plan and the docker command. */
object Harness {
    const val MEMORY = "512m"
    const val CPUS = "2"
    const val NETWORK = "none"
    private const val GCOMPAT = "RUN apk add --no-cache gcompat libgcc"

    fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private val regexMeta = "\\.[](){}*+?^$|"

    fun regexLiteral(text: String): String = buildString {
        for (c in text) {
            if (c in regexMeta) append('\\')
            append(c)
        }
    }

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
        if (arm.workspace.isNotEmpty()) append("PROJECT=1\n")
    }

    /** The arm's variables as `export` lines that `native.sh` and `harness.ps1` read. */
    fun env(arm: Arm): String = arm.env.entries.sortedBy { it.key }
        .joinToString("") { (k, v) -> "export $k=${quote(v)}\n" }

    /** Why a scenario cannot run on the host without a container, or null when it can. */
    fun nativeRefusal(scenario: Scenario): String? {
        val settings = listOf(scenario.green, scenario.red).flatMap { arm ->
            listOfNotNull(
                "files".takeIf { arm.files.isNotEmpty() },
                "workspace".takeIf { arm.workspace.isNotEmpty() },
                "ulimits".takeIf { arm.ulimits.isNotEmpty() },
                "cpuset".takeIf { arm.cpuset != null },
                "memory".takeIf { arm.memory != MEMORY },
                "cpus".takeIf { arm.cpus != CPUS },
                "network".takeIf { arm.network != NETWORK },
            )
        }.distinct()
        return if (settings.isEmpty()) null else "container settings: " + settings.joinToString()
    }

    /** Path under the scenario directory of a workspace file of an arm. */
    fun workspacePath(armName: String, path: String): String = "$armName.ws/$path"

    /** Host path under the scenario directory for the nth file mount of an arm. */
    fun filePath(armName: String, index: Int): String = "files/$armName/$index"

    /** The tag of a Debian image whose `/bin/sh` is linked to [shell]. */
    fun shellTag(image: String, shell: String): String =
        "drift-bench-sh-$shell:" + image.replace(Regex("[^A-Za-z0-9_.-]"), "-")

    /** The tag of an Alpine image with gcompat and libgcc added, which the glibc drift needs. */
    fun gcompatTag(image: String): String =
        "drift-bench-gcompat:" + image.replace(Regex("[^A-Za-z0-9_.-]"), "-")

    /** A shell script that runs one arm; `DRIFT` names the Linux drift binary to mount, if any. */
    fun script(scenario: Scenario, armName: String, arm: Arm): String {
        val alpine = "alpine" in arm.image
        val shell = arm.shell
        val image = when {
            alpine -> gcompatTag(arm.image)
            shell != null -> shellTag(arm.image, shell)
            else -> arm.image
        }
        val lines = mutableListOf<String>()
        lines += "#!/bin/sh"
        lines += "dir=\$(cd \"\$(dirname \"\$0\")\" && pwd)"
        if (alpine) {
            lines += "printf 'FROM %s\\n$GCOMPAT\\n' ${quote(arm.image)} | " +
                "docker build --quiet --tag ${quote(image)} - > /dev/null || exit 1"
        }
        if (shell != null && !alpine) {
            val args = "${quote(arm.image)} ${quote(shell)}"
            lines += "printf 'FROM %s\\nRUN ln -sf %s /bin/sh\\n' $args | " +
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

    /** The distinct arm images of a scenario, one per line, sorted. */
    fun images(scenario: Scenario): String = listOf(scenario.green.image, scenario.red.image)
        .distinct().sorted().joinToString("") { it + "\n" }

    /**
     * A shell script that runs `drift solve` for one prepared scenario. The capsules sit next to
     * it as `capsule.<arm>.json` and the program under `workspace/`; `DRIFT` is the absolute path
     * of a Linux drift binary, `SOLVED` the directory that receives the case. The binary placed in
     * the arms follows the capsule's architecture: `DRIFT_ARMS_AARCH64` or `DRIFT_ARMS_X86_64`,
     * then `DRIFT_ARMS`, then `DRIFT`.
     */
    fun solveScript(scenario: Scenario): String {
        val intermittent = scenario.klass == "intermittent"
        val command = command(scenario, scenario.red)
        val run = if (intermittent) command else "BENCH_FLAKE=1 $command"
        val failWhen = regexLiteral(scenario.signature).ifEmpty { "exit" }
        val lines = mutableListOf<String>()
        lines += "#!/bin/sh"
        lines += "dir=\$(cd \"\$(dirname \"\$0\")\" && pwd)"
        lines += "[ -n \"\$DRIFT\" ] || { echo 'set DRIFT to the drift binary' >&2; exit 2; }"
        lines += "solved=\${SOLVED:-\$dir/../solved}"
        lines += "limit=\${SOLVE_TIMEOUT:-2700}"
        lines += "DOCKER_CONFIG=\${TMPDIR:-/tmp}/drift-solve-docker-config"
        lines += "mkdir -p \"\$DOCKER_CONFIG\""
        lines += "export DOCKER_CONFIG"
        lines += ": > \"\$dir/solve.verdict\""
        lines += "if grep -q aarch64 \"\$dir/capsule.red.json\"; then"
        lines += "\tarms=\${DRIFT_ARMS_AARCH64:-\${DRIFT_ARMS:-\$DRIFT}}"
        lines += "else"
        lines += "\tarms=\${DRIFT_ARMS_X86_64:-\${DRIFT_ARMS:-\$DRIFT}}"
        lines += "fi"
        lines += "set -m"
        lines += "\"\$DRIFT\" solve \"\$dir/capsule.green.json\" \"\$dir/capsule.red.json\" \\"
        lines += "\t--run ${quote(run)} \\"
        lines += "\t--fail-when ${quote(failWhen)} \\"
        lines += "\t--workspace \"\$dir/workspace\" \\"
        lines += "\t--case \"\$solved/${scenario.id}\" \\"
        if (intermittent) {
            val gate = "permille=${scenario.red.gate},salt=${scenario.salt},env=BENCH_FLAKE"
            lines += "\t--gate ${quote(gate)} \\"
            lines += "\t--pilot ${SolveOptions.MAX_PILOT} \\"
        }
        lines += "\t--drift-binary \"\$arms\" \\"
        lines += "\t> \"\$dir/solve.out\" 2> \"\$dir/solve.err\" &"
        lines += "pid=\$!"
        lines += "( sleep \"\$limit\"; echo timeout > \"\$dir/solve.verdict\"; " +
            "kill -s TERM -- \"-\$pid\"; sleep 10; kill -s KILL -- \"-\$pid\" ) " +
            "> /dev/null 2>&1 &"
        lines += "watch=\$!"
        lines += "wait \"\$pid\""
        lines += "kill -s KILL \"\$watch\" 2> /dev/null"
        lines += "if [ ! -s \"\$dir/solve.verdict\" ]; then"
        lines += "\tcase \$(head -n 1 \"\$dir/solve.out\") in"
        lines += "\t\t'CONFIRMED EFFECT (bundle)'*) v=confirmed-bundle ;;"
        lines += "\t\t'CONFIRMED EFFECT'*) v=confirmed-effect ;;"
        lines += "\t\tCONFIRMED*) v=confirmed ;;"
        lines += "\t\tNARROWED*) v=narrowed ;;"
        lines += "\t\tNEEDS*) v=needs ;;"
        lines += "\t\tSTUCK*) v=stuck ;;"
        lines += "\t\t*) v=error ;;"
        lines += "\tesac"
        lines += "\techo \"\$v\" > \"\$dir/solve.verdict\""
        lines += "fi"
        lines += "cat \"\$dir/solve.verdict\""
        lines += "case \$(cat \"\$dir/solve.verdict\") in timeout | error) exit 1 ;; esac"
        return lines.joinToString("\n", postfix = "\n")
    }
}
