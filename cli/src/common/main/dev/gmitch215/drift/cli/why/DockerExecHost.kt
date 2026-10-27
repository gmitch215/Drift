package dev.gmitch215.drift.cli.why

import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.Host

/** A place Drift cannot reach; [message] says what happened and what to try next. */
internal class PlaceException(message: String) : Exception(message)

private const val NOT_FOUND = 127
private const val CANNOT_RUN = 126
private const val NO_DOCKER =
    "cannot start docker; install Docker, or put docker on your PATH, and try again"
private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")

/**
 * A host that runs commands inside a running container with `docker exec`, started by [base].
 * It copies nothing into the container, starts no container and changes none: each fact is one
 * `docker exec` of a command that only reads. A command the image does not have answers as
 * absent (exit 127), the way a missing tool answers on any host.
 */
internal class DockerExecHost private constructor(
    private val base: Host,
    val container: String,
    private val dir: String?,
) : Host {
    override val platform = "docker"
    override val capabilities = setOf(Capability.PROCESS, Capability.FILES, Capability.ENV)
    override var os = "linux"
        private set
    override var arch = "unknown"
        private set

    /** Set when docker stopped answering after the connection; later commands return null. */
    var problem: PlaceException? = null
        private set

    private fun argv(command: List<String>): List<String> =
        listOf("docker", "exec") + (dir?.let { listOf("-w", it) } ?: emptyList()) +
            container + command

    override fun env(): Map<String, String> =
        run(listOf("env"))?.takeIf { it.exitCode == 0 }?.output?.lines()
            ?.filter { '=' in it }
            ?.associate { it.substringBefore('=') to it.substringAfter('=').trimEnd('\r') }
            ?: emptyMap()

    override fun readText(path: String): String? =
        run(listOf("cat", path))?.takeIf { it.exitCode == 0 }?.output

    override fun run(argv: List<String>): CommandResult? {
        if (problem != null) return null
        val result = base.run(argv(argv)) ?: return stopped("cannot start docker")
        return answer(result)
    }

    override fun run(
        argv: List<String>,
        timeoutMillis: Long?,
        onOutput: (String) -> Unit,
    ): CommandResult? {
        if (problem != null) return null
        return base.run(argv(argv), timeoutMillis, onOutput)
    }

    private fun answer(result: CommandResult): CommandResult? {
        val text = result.output.lowercase()
        if (result.exitCode in setOf(CANNOT_RUN, NOT_FOUND) && notFound(text)) {
            return CommandResult(NOT_FOUND, "")
        }
        if ("no such container" in text || "is not running" in text) {
            return stopped("container $container stopped answering; check docker ps")
        }
        return result
    }

    private fun notFound(text: String) = "executable file not found" in text ||
        ("no such file or directory" in text && "oci runtime" in text)

    private fun stopped(message: String): CommandResult? {
        problem = PlaceException(message)
        return null
    }

    companion object {
        /** Connects to the running container [name]; throws [PlaceException] when it cannot. */
        fun open(base: Host, name: String, workDir: String?): DockerExecHost {
            if (!NAME.matches(name)) {
                throw PlaceException(
                    "$name is not a container name: use docker:NAME with the name that " +
                        "docker ps shows, for example docker:web",
                )
            }
            if (workDir != null && !workDir.startsWith("/")) {
                throw PlaceException("the directory in docker:$name:DIR must be an absolute path")
            }
            if (Capability.PROCESS !in base.capabilities) {
                throw PlaceException(
                    "this build cannot start other programs, so it cannot run docker; use the " +
                        "desktop command line",
                )
            }
            val state = base.run(
                listOf("docker", "inspect", "--format", "{{.State.Running}}", name),
            ) ?: throw PlaceException(NO_DOCKER)
            val text = state.output.trim()
            if (state.exitCode == NOT_FOUND || state.exitCode == CANNOT_RUN) {
                throw PlaceException(NO_DOCKER)
            }
            if (state.exitCode != 0) throw inspectFailure(name, text)
            if (text != "true") {
                throw PlaceException(
                    "container $name is not running; start it yourself (docker start $name) " +
                        "and try again. Drift never starts a container",
                )
            }
            val directory = workDir ?: sameDirectory(base, name)
            val host = DockerExecHost(base, name, directory)
            val uname = host.run(listOf("uname", "-m"))
            host.problem?.let { throw it }
            host.arch = uname?.takeIf { it.exitCode == 0 }?.output?.trim().orEmpty()
                .ifEmpty { "unknown" }
            return host
        }

        private fun inspectFailure(name: String, text: String): PlaceException {
            val lower = text.lowercase()
            return when {
                "no such object" in lower || "no such container" in lower -> PlaceException(
                    "no container named $name; docker ps lists the running ones",
                )

                "permission denied" in lower -> PlaceException(
                    "docker refused access for this user; run the same docker ps command first " +
                        "and fix the permission it shows",
                )

                "cannot connect to the docker daemon" in lower ||
                    "is the docker daemon running" in lower -> PlaceException(
                    "Docker is not running on this machine; start Docker and try again",
                )

                else -> PlaceException(
                    "docker could not inspect $name: ${text.lines().firstOrNull().orEmpty()}",
                )
            }
        }

        private fun sameDirectory(base: Host, name: String): String? {
            val here = base.env()["PWD"]?.takeIf { it.startsWith("/") } ?: return null
            val probe = base.run(listOf("docker", "exec", "-w", here, name, "true"))
            return here.takeIf { probe?.exitCode == 0 }
        }
    }
}
