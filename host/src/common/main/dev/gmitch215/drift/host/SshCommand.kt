package dev.gmitch215.drift.host

enum class SshProblem {
    NO_SSH,
    UNKNOWN_KEY,
    CHANGED_KEY,
    AUTH,
    REFUSED,
    UNRESOLVED,
    TIMEOUT,
    TOO_MUCH_OUTPUT,
    NOT_POSIX,
    WINDOWS,
    FAILED,
}

/** An ssh capture that stopped; [message] says what happened and what to do next. */
class SshException(val problem: SshProblem, message: String) : Exception(message)

/** A command outside the fixed allowlist; it never reaches ssh. */
class SshRefused(message: String) : IllegalArgumentException(message)

private const val MAX_TARGET = 255
private const val NAME = "[A-Za-z0-9._-]+"
private val TARGET = Regex("^(?:($NAME)@)?($NAME|\\[[0-9A-Fa-f:.]+\\])$")

/**
 * A validated `[user@]name` (or `[user@][ipv6]`) that is safe to pass to ssh after `--`. Only
 * letters, digits, dot, underscore and hyphen are allowed in a name, and none may start with a
 * hyphen, so a host argument can never be read as an ssh option.
 */
class SshTarget private constructor(val text: String, val destination: String) {
    companion object {
        fun parse(text: String): SshTarget? {
            if (text.length > MAX_TARGET) return null
            val match = TARGET.matchEntire(text) ?: return null
            val user = match.groupValues[1]
            val name = match.groupValues[2]
            if (user.startsWith('-') || name.startsWith('-')) return null
            val bare = name.removeSurrounding("[", "]")
            return SshTarget(text, if (user.isEmpty()) bare else "$user@$bare")
        }
    }
}

internal fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

/** The read-only commands and files that capture needs, and nothing else. */
internal object SshAllowlist {
    val UNAME = listOf("uname", "-sm")
    val ENV = listOf("env")

    private val commands = setOf(
        UNAME,
        listOf("uname", "-r"),
        listOf("getconf", "_NPROCESSORS_ONLN"),
        listOf("sw_vers", "-productVersion"),
        listOf("readlink", "/etc/localtime"),
        ENV,
    )

    val files = setOf(
        "/etc/os-release",
        "/etc/timezone",
        "/etc/crypto-policies/config",
        "/proc/self/cgroup",
        "/proc/cmdline",
        "/proc/sys/kernel/apparmor_restrict_unprivileged_userns",
        "/sys/fs/cgroup/cpu.max",
        "/sys/fs/cgroup/memory.max",
        "/sys/fs/cgroup/memory/memory.limit_in_bytes",
        "/usr/share/zoneinfo/tzdata.zi",
        "/usr/share/zoneinfo/+VERSION",
    )

    fun allows(argv: List<String>): Boolean =
        argv in commands || (argv.size == 2 && argv[0] == "cat" && argv[1] in files)
}

private fun options(vararg settings: String) = settings.flatMap { listOf("-o", it) }

private val SSH_OPTIONS = listOf("ssh", "-T", "-n") + options(
    "BatchMode=yes",
    "StrictHostKeyChecking=yes",
    "ForwardAgent=no",
    "ForwardX11=no",
    "ClearAllForwardings=yes",
    "ConnectTimeout=10",
    "LogLevel=ERROR",
)

private fun multiplexOptions(control: String) =
    options("ControlMaster=auto", "ControlPersist=30", "ControlPath=$control/m")

/** The ssh command line for one remote command; every remote argument is single-quoted. */
internal fun sshArgv(target: SshTarget, control: String?, command: List<String>): List<String> =
    SSH_OPTIONS + (control?.let(::multiplexOptions) ?: emptyList()) +
        listOf("--", target.destination, command.joinToString(" ", transform = ::shellQuote))

internal fun sshCloseArgv(target: SshTarget, control: String): List<String> =
    SSH_OPTIONS + multiplexOptions(control) + listOf("-O", "exit", "--", target.destination)

private const val MAX_DETAIL = 200

/** Maps what ssh printed when it exited with 255 to a plain message. */
internal fun sshFailure(output: String, host: String): SshException {
    val text = output.lowercase()
    return when {
        "host identification has changed" in text ||
            "has changed and you have requested strict checking" in text ->
            SshException(
                SshProblem.CHANGED_KEY,
                "the host key of $host does not match your known_hosts, so Drift refuses to " +
                    "connect: the host may have been replaced or intercepted. Ask its owner " +
                    "before you change known_hosts",
            )

        "host key verification failed" in text || "host key is known" in text ->
            SshException(
                SshProblem.UNKNOWN_KEY,
                "$host is not in your known_hosts, so Drift will not connect. Check its " +
                    "fingerprint with its owner and add the key yourself, for example by " +
                    "connecting once with ssh. Drift never adds host keys",
            )

        "permission denied" in text -> SshException(
            SshProblem.AUTH,
            "ssh could not log in to $host without a prompt. Drift never asks for a " +
                "password; use a key or agent that your own ssh config selects for this host",
        )

        "connection refused" in text -> SshException(
            SshProblem.REFUSED,
            "$host refused the connection; check the name, the port in your ssh config and " +
                "that sshd is running",
        )

        "could not resolve hostname" in text -> SshException(
            SshProblem.UNRESOLVED,
            "cannot resolve the name $host; check it and your ssh config",
        )

        "timed out" in text -> SshException(SshProblem.TIMEOUT, "ssh to $host timed out")

        else -> {
            val line = output.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            SshException(
                SshProblem.FAILED,
                "ssh to $host failed" + if (line.isEmpty()) "" else ": " + line.take(MAX_DETAIL),
            )
        }
    }
}
