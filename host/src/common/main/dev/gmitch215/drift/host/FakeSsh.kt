package dev.gmitch215.drift.host

/**
 * A local host that answers `ssh` from a scripted remote, for tests that need a base host for
 * [SshHost]. [sshResult], when set, answers every ssh call (a connection failure, for example);
 * [machines] answers the calls to a destination with another scripted remote.
 */
class FakeSsh(
    private val remoteFiles: Map<String, String> = emptyMap(),
    private val remoteEnv: Map<String, String> = emptyMap(),
    remoteCommands: Map<List<String>, CommandResult> = emptyMap(),
    uname: String = "Linux x86_64",
    private val sshResult: CommandResult? = null,
    private val canRun: Boolean = true,
    override val os: String = "macos",
    override val capabilities: Set<Capability> = Capability.entries.toSet(),
    private val machines: Map<String, FakeSsh> = emptyMap(),
) : Host {
    override val platform = "fake"
    override val arch = "aarch64"

    private val remote = remoteCommands + (SshAllowlist.UNAME to CommandResult(0, "$uname\n"))

    /** Every local command, ssh included. */
    val ran = mutableListOf<List<String>>()

    /** The words of every remote command, as the remote shell would split them. */
    val remoteRan = mutableListOf<List<String>>()

    override fun env(): Map<String, String> = emptyMap()

    override fun readText(path: String): String? = null

    override fun run(argv: List<String>): CommandResult? {
        if (!canRun) return null
        ran += argv
        return when (argv.firstOrNull()) {
            "ssh" -> ssh(argv)
            "mktemp" -> CommandResult(0, "/tmp/drift-ssh.AbC123\n")
            "rm", "rmdir" -> CommandResult(0, "")
            else -> CommandResult(127, "")
        }
    }

    private fun ssh(argv: List<String>): CommandResult {
        sshResult?.let { return it }
        machines[argv[argv.indexOf("--") + 1]]?.let { return it.ssh(argv) }
        if ("-O" in argv) return CommandResult(0, "")
        val words = shellWords(argv.last())
        remoteRan += listOf(words)
        return when {
            words == SshAllowlist.ENV ->
                CommandResult(0, remoteEnv.entries.joinToString("") { "${it.key}=${it.value}\n" })

            words.size == 2 && words[0] == "cat" ->
                remoteFiles[words[1]]?.let { CommandResult(0, it) } ?: CommandResult(1, "")

            else -> remote[words] ?: CommandResult(127, "")
        }
    }
}

/** Splits [text] the way a POSIX shell does for single quotes and backslash escapes. */
internal fun shellWords(text: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var started = false
    var quoted = false
    var escaped = false
    for (c in text) {
        when {
            escaped -> {
                word.append(c)
                escaped = false
            }

            quoted -> if (c == '\'') quoted = false else word.append(c)

            c == '\'' -> {
                quoted = true
                started = true
            }

            c == '\\' -> {
                escaped = true
                started = true
            }

            c.isWhitespace() -> if (started) {
                words += "$word"
                word.clear()
                started = false
            }

            else -> {
                word.append(c)
                started = true
            }
        }
    }
    if (started) words += "$word"
    return words
}
