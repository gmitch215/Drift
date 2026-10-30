package dev.gmitch215.drift.why

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.symptom.FailureSignature
import dev.gmitch215.drift.symptom.Symptom

/** The kind of difference a failure message points at; each kind says which changes it fits. */
enum class Pointer(val phrases: List<String>) {
    RUNTIME(
        listOf(
            "is not a function",
            "is not defined",
            "is not a constructor",
            "undefined symbol",
            "has no attribute",
            "unexpected token",
            "syntaxerror",
            "nosuchmethoderror",
            "nosuchfielderror",
            "unsupported class version",
            "unsupportedclassversionerror",
            "unknown option",
            "unrecognized option",
            "invalid option",
            "unknown flag",
            "no such method",
        ),
    ) {
        override fun fits(change: AttributeDiff) =
            change.dimension == Dimension.RUNTIME || change.dimension == Dimension.COMPILER
    },
    MODULE(
        listOf(
            "cannot find module",
            "no module named",
            "modulenotfounderror",
            "importerror",
            "cannot find package",
            "unresolved dependency",
            "could not find a version",
            "could not resolve dependenc",
        ),
    ) {
        override fun fits(change: AttributeDiff) = change.path.startsWith("project.") ||
            change.path.startsWith("deps.") || change.dimension == Dimension.RUNTIME
    },
    MISSING_TOOL(
        listOf(
            "command not found",
            "not found",
            "not found in path",
            "executable file not found",
            "is not recognized as an internal or external command",
            "filenotfounderror",
            "no such file or directory",
        ),
    ) {
        override fun fits(change: AttributeDiff) =
            change.path.startsWith("tool.") && change.after == null
    },
    LIMIT(
        listOf(
            "too many open files",
            "emfile",
            "enfile",
            "file size limit",
            "cannot fork",
            "resource temporarily unavailable",
            "ulimit",
            "argument list too long",
        ),
    ) {
        override fun fits(change: AttributeDiff) = change.path.startsWith("limits.")
    },
    MEMORY(
        listOf(
            "out of memory",
            "oom",
            "cannot allocate memory",
            "memoryerror",
            "heap space",
            "enomem",
            "killed",
        ),
    ) {
        override fun fits(change: AttributeDiff) = change.dimension == Dimension.MEMORY ||
            change.path == "limits.as" || change.path == "limits.data"
    },
    NETWORK(
        listOf(
            "connection refused",
            "econnrefused",
            "timed out",
            "name or service not known",
            "could not resolve",
            "getaddrinfo",
            "certificate",
            "ssl",
            "tls",
        ),
    ) {
        override fun fits(change: AttributeDiff) = change.dimension == Dimension.NETWORK
    },
    PERMISSION(
        listOf("permission denied", "eacces", "read-only file system", "operation not permitted"),
    ) {
        override fun fits(change: AttributeDiff) = change.path.startsWith("limits.") ||
            change.path.startsWith("file.") || change.path.startsWith("fs.") ||
            change.path.startsWith("sysctl.")
    },
    LOCALE(
        listOf(
            "timezone",
            "time zone",
            "invalid date",
            "locale",
            "unicodeencodeerror",
            "unicodedecodeerror",
            "charmap",
            "codec can't",
            "encoding",
        ),
    ) {
        override fun fits(change: AttributeDiff) = change.dimension == Dimension.LOCALE
    },
    ;

    abstract fun fits(change: AttributeDiff): Boolean
}

/**
 * Reads failure text for the kinds of difference it points at. The phrases are plain lowercase
 * ASCII matched at a word start, the way [FailureSignature] matches the phrases of a probe.
 */
object FailurePhrases {
    fun pointers(failure: List<Symptom>?): List<Pointer> {
        val signature = failure?.takeIf { it.isNotEmpty() }?.let(::FailureSignature)
            ?: return emptyList()
        return Pointer.entries.filter { p -> p.phrases.any(signature::mentions) }
    }

    /** The first phrase, of a kind that fits [change], that [failure] mentions. */
    fun pointedAt(failure: List<Symptom>?, change: AttributeDiff): String? {
        val signature = failure?.takeIf { it.isNotEmpty() }?.let(::FailureSignature)
            ?: return null
        return Pointer.entries.filter { it.fits(change) }
            .firstNotNullOfOrNull { p -> p.phrases.firstOrNull(signature::mentions) }
    }
}
