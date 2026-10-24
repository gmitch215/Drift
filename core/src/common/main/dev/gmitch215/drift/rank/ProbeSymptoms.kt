package dev.gmitch215.drift.rank

import dev.gmitch215.drift.symptom.FailureSignature

/**
 * The symptoms a failure caused by what a probe measures can show, keyed by probe id: lowercase
 * ASCII phrases that may appear in a failure line, and exit codes. A probe that differs explains
 * an observed failure only when the failure mentions one of its phrases or carries one of its
 * exit codes (see [FailureSignature]); otherwise the difference is observational.
 *
 * The phrases are a reading of what each probe prints, not a measurement. The value probes share
 * the generic words of an assertion that compared two values. The `kotlin.` probes have no entry
 * and never explain a failure.
 */
object ProbeSymptoms {
    private val value = listOf(
        "expected",
        "assert",
        "mismatch",
        "not equal",
        "differs",
        "does not match",
    )

    private val shell = listOf(
        "syntax error",
        "unexpected",
        "unterminated",
        "bad substitution",
        "unrecognized option",
        "invalid option",
        "illegal option",
        "not found",
    )

    private val zone = listOf("timezone", "time zone", "zoneinfo", "utc", "daylight")

    private val float = value + listOf("double", "float", "decimal", "precision", "nan", "infinity")

    val phrases: Map<String, List<String>> = mapOf(
        "collections.hash-order" to value + listOf("order", "iteration"),
        "collections.random-seeded" to value + listOf("random", "seed"),
        "collections.sort-edge-cases" to value + listOf("sort", "order", "compar"),
        "numeric.double-tostring" to float + "scientific",
        "numeric.float-tostring" to float + "scientific",
        "numeric.float-to-int" to float + listOf("out of range", "overflow", "truncat"),
        "numeric.int-overflow" to value + listOf("overflow", "out of range", "too large"),
        "numeric.math-bits" to value + listOf("bit", "overflow", "shift"),
        "numeric.parse" to value + listOf(
            "numberformat",
            "invalid literal",
            "could not convert",
            "cannot parse",
            "unable to parse",
            "invalid number",
        ),
        "numeric.rounding" to value + listOf("round", "precision", "decimal"),
        "process.echo" to shell,
        "process.env-visible" to listOf(
            "keyerror",
            "environment variable",
            "not set",
            "unbound variable",
            "undefined variable",
            "environ",
        ),
        "process.exit-code" to listOf("exit status", "exited with", "return code", "non-zero"),
        "process.large-output" to listOf("broken pipe", "epipe", "truncated", "buffer"),
        "process.missing-command" to listOf(
            "command not found",
            "not found",
            "no such file or directory",
            "enoent",
            "cannot find",
        ),
        "process.quoting" to shell,
        "process.resolve-localhost" to listOf(
            "name or service not known",
            "getaddrinfo",
            "gaierror",
            "unknown host",
            "could not resolve",
            "name resolution",
            "enotfound",
            "localhost",
        ),
        "process.stderr-merged" to value + "stderr",
        "process.timezone" to zone,
        "resources.cgroup-cpu" to listOf(
            "cpu",
            "gomaxprocs",
            "availableprocessors",
            "zerodivisionerror",
            "throttl",
            "thread pool",
            "slower",
        ),
        "resources.cgroup-memory" to listOf(
            "out of memory",
            "oom",
            "cannot allocate memory",
            "memoryerror",
            "killed",
            "sigkill",
            "memory",
            "heap space",
            "enomem",
        ),
        "resources.locale-env" to zone + listOf(
            "locale",
            "lang",
            "lc_",
            "charmap",
            "codec",
            "unicodeencodeerror",
            "unicodedecodeerror",
            "encoding",
        ),
        "resources.missing-file" to listOf(
            "no such file or directory",
            "enoent",
            "filenotfounderror",
            "file not found",
            "cannot open",
        ),
        "text.case-mapping" to value + listOf("case", "upper", "lower", "locale"),
        "text.char-classes" to value + listOf("character class", "isalpha", "isletter", "unicode"),
        "text.compare" to value + listOf("compar", "collat", "sort", "order"),
        "text.default-tostring" to value + listOf("tostring", "repr"),
        "text.exception-messages" to value + listOf("exception", "message"),
        "text.hashcode" to value + "hash",
        "text.regex" to listOf(
            "regex",
            "pattern",
            "syntaxerror",
            "invalid escape",
            "unmatched",
            "lookbehind",
            "unicode",
        ),
        "text.supplementary" to value + listOf(
            "surrogate",
            "supplementary",
            "unicode",
            "codepoint",
            "utf-16",
            "invalid utf",
        ),
        "time.duration-format" to value + listOf("duration", "iso-8601", "format"),
        "time.monotonic" to listOf("clock", "monotonic", "went backwards", "negative"),
    )

    val exits: Map<String, Set<String>> = mapOf(
        "resources.cgroup-memory" to setOf("137"),
    )

    fun explains(probe: String, failure: FailureSignature): Boolean =
        phrases[probe].orEmpty().any(failure::mentions) ||
            exits[probe].orEmpty().any(failure::hasExit)
}
