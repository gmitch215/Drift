package dev.gmitch215.drift.cli.why

/** The text of `drift help why`. */
internal object WhyManual {
    val text = """
        drift why: say why a program works in one place and fails in another.

        Pick the flow by what you give it. The first output line names the flow it chose.
          drift why                in a git checkout on GitHub or GitLab: this machine
                                   against the latest failed CI run (gh or glab, logged in)
          drift why --there PLACE  this machine against PLACE, where it fails. PLACE is
                                     docker:NAME  a running container (read only)
                                     user@host    another machine over ssh (read only)
                                     FILE         a capture saved by drift capture
          drift why -- COMMAND     run COMMAND here; a pass saves a baseline in .drift/why,
                                   a later failure is compared with it
          drift why FILE           read an issue or an error report (- is standard input)

        After --there, add -- COMMAND to run it in both places (docker: places only). Its
        error text then points at the difference.

        The answer has three parts: the verdict in one sentence, the evidence in up to
        three lines (what it is, the value on each side) and what to try next. It assumes
        it works here and fails there; --fails-here swaps the sides. When the data cannot
        name one cause it lists the differences that fit and the fastest way to tell them
        apart. When nothing differs it says what Drift looked at and what it did not.

        Options
          --fails-here   the program fails here and works there
          --save FILE    capture this machine into FILE, to copy to the machine where it works
          --error TEXT   the error the program printed: TEXT, @FILE, or - for standard input
          --force        with --save, replace FILE if it already exists
          --reply        pipe in the answer it asked for: CMD | drift why --reply [--session FILE]
          --detail       add the full ranking and plan behind the answer
          --json         the machine form of the same answer, with a plain object

        Examples
          drift why --save there.json        on the failing machine: save its capture
          drift why --there there.json --error @error.txt
                                             on the working machine: compare, using the error

        Drift reads tool versions, environment variables, limits, time zone and the project
        lockfiles without being asked. Exit status: 0 when it gave an answer, 1 when it
        could not reach a place or a tool; the message says what to do next.
    """.trimIndent() + "\n"
}
