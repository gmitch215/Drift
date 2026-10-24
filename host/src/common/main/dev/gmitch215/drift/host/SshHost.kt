package dev.gmitch215.drift.host

private const val COMMAND_TIMEOUT_MILLIS = 30_000L
private const val CLOSE_TIMEOUT_MILLIS = 10_000L
private const val SSH_FAILED = 255
private const val MAX_OUTPUT = 1 shl 20
private val CONTROL_DIR = Regex("^/tmp/drift-ssh\\.[A-Za-z0-9]{6}$")

/**
 * A host that runs the allowlisted read-only fact commands on a remote machine through the
 * system `ssh`, started by [base]. It stores no credentials and never changes `known_hosts`.
 * After a command fails because ssh did, [problem] holds why and every later command returns
 * null at once.
 */
class SshHost private constructor(
    private val base: Host,
    private val target: SshTarget,
    private val control: String?,
) : Host {
    override val platform = "ssh"
    override val capabilities = setOf(Capability.PROCESS, Capability.FILES, Capability.ENV)
    override var os = "unknown"
        private set
    override var arch = "unknown"
        private set
    var problem: SshException? = null
        private set
    private var closed = false

    override fun env(): Map<String, String> =
        run(SshAllowlist.ENV)?.takeIf { it.exitCode == 0 }?.let { parseEnvOutput(it.output) }
            ?: emptyMap()

    override fun readText(path: String): String? =
        run(listOf("cat", path))?.takeIf { it.exitCode == 0 }?.output

    /** Throws [SshRefused] for any command outside [SshAllowlist]. */
    override fun run(argv: List<String>): CommandResult? {
        if (!SshAllowlist.allows(argv)) {
            throw SshRefused("refused to run on a remote host: ${argv.joinToString(" ")}")
        }
        return remote(argv)
    }

    private fun remote(argv: List<String>): CommandResult? {
        if (problem != null) return null
        val out = StringBuilder()
        var seen = 0
        val result = base.run(sshArgv(target, control, argv), COMMAND_TIMEOUT_MILLIS) { chunk ->
            if (out.length < MAX_OUTPUT) out.append(chunk.take(MAX_OUTPUT - out.length))
            seen += chunk.length
        }
        if (result == null) {
            return fail(
                SshProblem.NO_SSH,
                "cannot start ssh; install an OpenSSH client and make sure it is on the path",
            )
        }
        if (seen > MAX_OUTPUT) {
            return fail(
                SshProblem.TOO_MUCH_OUTPUT,
                "${target.text} sent more than 1 MiB for one command, so Drift stopped",
            )
        }
        if (result.exitCode == TIMED_OUT_EXIT_CODE) {
            return fail(SshProblem.TIMEOUT, "ssh to ${target.text} timed out")
        }
        if (result.exitCode == SSH_FAILED) {
            problem = sshFailure("$out", target.text)
            return null
        }
        return CommandResult(result.exitCode, "$out")
    }

    private fun fail(kind: SshProblem, message: String): CommandResult? {
        problem = SshException(kind, message)
        return null
    }

    private fun probe() {
        val result = remote(SshAllowlist.UNAME)
        problem?.let { throw it }
        val parts = result?.output?.lines()?.lastOrNull { it.isNotBlank() }?.trim()
            ?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
        if (result?.exitCode != 0 || parts.size < 2) {
            throw SshException(
                SshProblem.NOT_POSIX,
                "${target.text} did not answer uname; Drift reads Linux, macOS and BSD hosts " +
                    "over ssh",
            )
        }
        os = normalizeOs(parts[0])
        arch = parts.drop(1).joinToString(" ")
        if (os == "windows") {
            throw SshException(
                SshProblem.WINDOWS,
                "${target.text} is a Windows host; Drift does not read Windows over ssh yet",
            )
        }
    }

    /** Closes the shared connection and removes its private directory; safe to call twice. */
    fun close() {
        if (closed) return
        closed = true
        val dir = control ?: return
        base.run(sshCloseArgv(target, dir), CLOSE_TIMEOUT_MILLIS) { }
        base.run(listOf("rm", "-f", "$dir/m"))
        base.run(listOf("rmdir", dir))
    }

    companion object {
        /** Connects and learns the remote system; throws [SshException] when it cannot. */
        fun open(base: Host, target: SshTarget, multiplex: Boolean = true): SshHost {
            if (Capability.PROCESS !in base.capabilities) {
                throw SshException(
                    SshProblem.NO_SSH,
                    "this build cannot start other programs, so it cannot run ssh; use the " +
                        "desktop command line",
                )
            }
            val control = if (multiplex && base.os != "windows") privateDirectory(base) else null
            val host = SshHost(base, target, control)
            try {
                host.probe()
            } catch (e: SshException) {
                host.close()
                throw e
            }
            return host
        }

        private fun privateDirectory(base: Host): String? {
            val made = base.run(listOf("mktemp", "-d", "/tmp/drift-ssh.XXXXXX"))
            val path = made?.takeIf { it.exitCode == 0 }?.output?.trim()
            return path?.takeIf { CONTROL_DIR.matches(it) }
        }
    }
}
