package dev.gmitch215.drift.help

/** A word Drift uses in its own sense, with the plain definition of the README Terms table. */
data class Term(val name: String, val meaning: String)

/** The seven terms a newcomer meets first; the CLI prints them for `drift help <term>`. */
object Glossary {
    val terms: List<Term> = listOf(
        Term(
            "Capsule",
            "A file that records one environment: the operating system, tool versions, " +
                "environment variables (secret values hidden) and the results of small checks.",
        ),
        Term(
            "Diagnose",
            "Compare the capsule of a run that passed with the capsule of a run that failed, " +
                "and rank what differs.",
        ),
        Term(
            "Solve",
            "Run the failing command in containers that differ in one setting, under a rule " +
                "written down before the first run, to see which difference causes the failure. " +
                "This part of Drift is the Lab.",
        ),
        Term(
            "Certificate",
            "The record of every run in a solve and its verdict, kept in a .driftcase file.",
        ),
        Term(
            "Verify",
            "Recompute every count and verdict in a .driftcase file from the file itself. " +
                "It does not run anything again.",
        ),
        Term(
            "Atlas",
            "A table of what small Kotlin programs print on each target, a platform Kotlin " +
                "builds for, such as the JVM, native code or WebAssembly.",
        ),
        Term("Probe", "One of those small programs."),
    )

    fun find(name: String): Term? = terms.firstOrNull { it.name.equals(name.trim(), true) }
}
