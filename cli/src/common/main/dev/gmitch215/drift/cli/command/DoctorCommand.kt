package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.TIMED_OUT_EXIT_CODE
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj

private const val PROBE_TIMEOUT_MILLIS = 2_000L
private const val NOT_FOUND = 127 // posix "command not found" status
private const val NO_FIX = "none"

internal enum class Status(val label: String) { OK("ok"), WARN("warn"), FAIL("fail") }

internal class Check(val name: String, val status: Status, val cause: String, val fix: String)

private val RELEASE_ARCHES = mapOf(
    "macos" to setOf("arm64", "aarch64"),
    "windows" to setOf("x64", "x86_64", "amd64"),
    "linux" to setOf("x64", "x86_64", "amd64", "arm64", "aarch64"),
)

class DoctorCommand(private val host: Host) : CliktCommand(name = "doctor") {
    private val json by option("--json", help = "print canonical JSON instead of text").flag()

    override fun help(context: Context) =
        "Check that this host can run Drift and reach Docker and git, and say what to fix"

    override fun helpEpilog(context: Context) = examples("doctor")

    override fun run() {
        val checks = listOf(
            platformCheck(host),
            processCheck(host),
            workingDirectoryCheck(host),
            toolCheck(
                host,
                name = "docker",
                argv = listOf("docker", "version", "--format", "{{.Server.Version}}"),
                absentFix = "install Docker; reproduce and solve need it",
                brokenFix = "start the Docker daemon and check that this user can reach it",
            ),
            toolCheck(
                host,
                name = "git",
                argv = listOf("git", "--version"),
                absentFix = "install git; triage and upgrade-check record its version",
                brokenFix = "reinstall git or fix its entry on PATH",
            ),
        )
        val failed = checks.count { it.status == Status.FAIL }
        val warned = checks.count { it.status == Status.WARN }
        if (json) {
            echo(CanonicalJson.encode(report(checks, failed, warned)))
        } else {
            for (check in checks) {
                echo("${check.status.label.padEnd(4)}  ${check.name}")
                echo("      cause: ${check.cause}")
                echo("      fix: ${check.fix}")
            }
        }
        if (failed > 0) throw ProgramResult(1)
    }

    private fun report(checks: List<Check>, failed: Int, warned: Int) = obj(
        "checks" to JsonArray(
            checks.map {
                obj(
                    "name" to JsonString(it.name),
                    "status" to JsonString(it.status.label),
                    "cause" to JsonString(it.cause),
                    "fix" to JsonString(it.fix),
                )
            },
        ),
        "failed" to JsonInt(failed.toLong()),
        "warned" to JsonInt(warned.toLong()),
    )
}

private fun platformCheck(host: Host): Check {
    val arch = host.arch.lowercase()
    return when {
        host.os == "unknown" -> Check(
            "platform",
            Status.FAIL,
            "the operating system is not one Drift knows",
            "run drift on macOS, Windows or Linux",
        )

        arch in RELEASE_ARCHES[host.os].orEmpty() -> Check(
            "platform",
            Status.OK,
            "${host.os} ${host.arch} is a release target",
            NO_FIX,
        )

        else -> Check(
            "platform",
            Status.WARN,
            "${host.os} ${host.arch} is not a release target",
            "use a release archive, or build from source; drift may still run",
        )
    }
}

private fun processCheck(host: Host): Check {
    if (Capability.PROCESS !in host.capabilities) {
        return Check(
            "process",
            Status.FAIL,
            "this host cannot start processes",
            "run drift on a platform with process support",
        )
    }
    val argv = when (host.os) {
        "windows" -> listOf("cmd", "/c", "exit 0")
        else -> listOf("sh", "-c", "exit 0")
    }
    val result = host.run(argv, PROBE_TIMEOUT_MILLIS) {}
    return when (result?.exitCode) {
        null -> Check(
            "process",
            Status.FAIL,
            "the host could not start a process",
            "check that ${argv.first()} is on PATH",
        )

        TIMED_OUT_EXIT_CODE -> Check(
            "process",
            Status.WARN,
            "a process did not exit within 2 seconds",
            "stop programs that load the machine, then rerun drift doctor",
        )

        0 -> Check("process", Status.OK, "a child process starts and exits", NO_FIX)

        else -> Check(
            "process",
            Status.FAIL,
            "a child process exited with status ${result.exitCode}",
            "check that ${argv.first()} runs from a terminal",
        )
    }
}

private fun workingDirectoryCheck(host: Host): Check {
    val result = host.run(listOf("sh", "-c", "test -w ."), PROBE_TIMEOUT_MILLIS) {}
    return when (result?.exitCode) {
        0 -> Check("working directory", Status.OK, "the working directory is writable", NO_FIX)

        null, NOT_FOUND -> Check(
            "working directory",
            Status.WARN,
            "cannot run sh to test the working directory",
            "check the directory's permissions by hand",
        )

        else -> Check(
            "working directory",
            Status.WARN,
            "the working directory is not writable by this user",
            "run drift from a directory you can write to",
        )
    }
}

private fun toolCheck(
    host: Host,
    name: String,
    argv: List<String>,
    absentFix: String,
    brokenFix: String,
): Check {
    val result = host.run(argv, PROBE_TIMEOUT_MILLIS) {}
    return when (result?.exitCode) {
        null, NOT_FOUND -> Check(name, Status.WARN, "$name is not on PATH", absentFix)

        TIMED_OUT_EXIT_CODE -> Check(
            name,
            Status.WARN,
            "$name did not answer within 2 seconds",
            brokenFix,
        )

        0 -> Check(name, Status.OK, "$name answers", NO_FIX)

        else -> Check(name, Status.WARN, "$name exited with status ${result.exitCode}", brokenFix)
    }
}
