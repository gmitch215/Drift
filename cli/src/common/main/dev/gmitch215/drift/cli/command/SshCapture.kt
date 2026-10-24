package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.SshException
import dev.gmitch215.drift.host.SshHost
import dev.gmitch215.drift.host.SshTarget
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.scan.Capture

internal const val SSH_TRANSPORT = "ssh"

/** What a capsule over ssh cannot show; printed after every comparison of two machines. */
internal const val SSH_LIMITS = "note: this compares what a read-only capture shows on each " +
    "machine: system files and the environment of a non-login ssh session. No repository code " +
    "ran, so the ranking names differences and cannot confirm which one causes a failure."

/**
 * Captures the machine [alias] over ssh, read-only, and prints one consent line to standard
 * error first. Throws a usage error for a bad name and a plain error for any ssh failure.
 */
internal fun CliktCommand.captureOverSsh(
    host: Host,
    alias: String,
    label: String,
    keepIdentifiers: Boolean,
): Capsule {
    val target = SshTarget.parse(alias) ?: throw UsageError(
        "$alias is not a host name: use [user@]name with letters, digits, '.', '_' and '-', " +
            "and no leading '-'",
    )
    echo(
        "drift: running read-only fact commands on ${target.text} over ssh; it reads system " +
            "files and the environment, runs no repository code, uses no sudo and stores no " +
            "credentials",
        err = true,
    )
    try {
        val remote = SshHost.open(host, target)
        try {
            val capsule = Capture.run(
                remote,
                label,
                keepIdentifiers = keepIdentifiers,
                transport = SSH_TRANSPORT,
            )
            remote.problem?.let { throw it }
            return capsule
        } finally {
            remote.close()
        }
    } catch (e: SshException) {
        throw CliktError(e.message)
    }
}
