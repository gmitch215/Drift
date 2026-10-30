package dev.gmitch215.drift.why

import dev.gmitch215.drift.model.Capsule

/**
 * Facts that differ between any two machines and say nothing about a failure: the host name, the
 * account, the terminal, the capture's own bookkeeping. They are removed before the ranking so
 * the verdict, the evidence and the experiment plan all read the same facts.
 */
object Noise {
    private val NAMES = setOf(
        "HOSTNAME",
        "HOME",
        "PWD",
        "OLDPWD",
        "SHLVL",
        "_",
        "USER",
        "LOGNAME",
        "TERM",
        "COLORTERM",
        "SHELL",
        "MAIL",
        "TMPDIR",
        "LS_COLORS",
        "DISPLAY",
        "LESSOPEN",
        "LESSCLOSE",
        "PATH",
        "GPG_TTY",
        "GPG_KEY",
        "PYTHON_SHA256",
        "YARN_VERSION",
        "HOSTTYPE",
        "MACHTYPE",
        "OSTYPE",
        "INVOCATION_ID",
        "JOURNAL_STREAM",
    )
    private val PREFIXES = listOf(
        "env.XDG_",
        "env.SSH_",
        "env.TERM_",
        "env.ITERM_",
        "env.VSCODE_",
        "env.LC_TERMINAL",
        "env.__",
        "env.DBUS_",
        "env.GITHUB_",
        "env.RUNNER_",
        "env.CI_",
        "env.npm_",
    )
    private val PATHS = setOf("runtime.platform", "capture.transport", "kotlin.target")
    private val PATH_PREFIXES = listOf("drift.", "kotlin.")

    fun isNoise(path: String): Boolean = path in PATHS ||
        PATH_PREFIXES.any(path::startsWith) ||
        (path.startsWith("env.") && path.removePrefix("env.") in NAMES) ||
        PREFIXES.any(path::startsWith)

    /** For the CI flow: the runner's own labels always differ from this machine's. */
    fun isCiLabel(path: String): Boolean = path.startsWith("ci.")

    fun strip(capsule: Capsule, skip: (String) -> Boolean = { false }): Capsule {
        val restated = restatedVersions(capsule)
        return capsule.copy(
            attributes = capsule.attributes.filterNot {
                isNoise(it.path) || skip(it.path) || it.path in restated
            },
        )
    }

    /** `env.NODE_VERSION` repeats `tool.node.version`: drop it where the tool fact exists. */
    private fun restatedVersions(capsule: Capsule): Set<String> {
        val paths = capsule.attributes.map { it.path }.toSet()
        return paths.filter { it.startsWith("env.") && it.endsWith("_VERSION") }
            .filter { env ->
                val tool = env.removePrefix("env.").removeSuffix("_VERSION").lowercase()
                "tool.$tool.version" in paths
            }.toSet()
    }
}
