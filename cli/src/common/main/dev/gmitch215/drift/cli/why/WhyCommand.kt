package dev.gmitch215.drift.cli.why

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.case.CaseBuilder
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.CaseWrite
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.CapsuleDirectory
import dev.gmitch215.drift.ci.DirectoryRead
import dev.gmitch215.drift.ci.Execution
import dev.gmitch215.drift.cli.command.captureOverSsh
import dev.gmitch215.drift.cli.command.examples
import dev.gmitch215.drift.cli.command.loadCapsuleSource
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.SshException
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.scan.Capture
import dev.gmitch215.drift.scan.ci.CiCheck
import dev.gmitch215.drift.scan.ci.CiProblemKind
import dev.gmitch215.drift.scan.ci.CiRequest
import dev.gmitch215.drift.scan.ci.CiRuns
import dev.gmitch215.drift.scan.ci.CiStop
import dev.gmitch215.drift.scan.why.WhyFlow
import dev.gmitch215.drift.studio.StudioState
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.triage.TriageResult
import dev.gmitch215.drift.triage.TriageText
import dev.gmitch215.drift.why.Answer
import dev.gmitch215.drift.why.Certainty
import dev.gmitch215.drift.why.Explain
import dev.gmitch215.drift.why.FailurePhrases
import dev.gmitch215.drift.why.Families
import dev.gmitch215.drift.why.Inputs
import dev.gmitch215.drift.why.Noise
import dev.gmitch215.drift.why.PlainView
import dev.gmitch215.drift.why.Session
import dev.gmitch215.drift.why.Sides
import kotlin.time.TimeSource

private const val BASELINE = ".drift/why/baseline"
private const val CASE = ".drift/why/case"
private const val TAIL_LINES = 200
private const val ERROR_TEXT_BYTES = WhyFlow.ERROR_TEXT_BYTES
private const val CUT_NOTE = "Your error text is longer than 64 KiB, so only the first 64 KiB " +
    "was read."
private const val UNMATCHED_NOTE = "Your error text matches no known failure phrase, so the " +
    "answer is the same as without it."
private val FAMILIES = listOf("tool.", "project.", "limits.", "cgroup.", "tz.")
private const val STDIN = "/dev/stdin"
private const val SESSION = Session.PATH
private const val ERROR_ONLY = "No CI run or second machine was available, so I read only the " +
    "error text. To compare with another machine, add --there PLACE."
private const val OTHER_WAYS = "Other ways in: drift why --there PLACE, drift why -- COMMAND, " +
    "or drift why FILE with an issue or an error text."
private const val SAVED_NO_DIFFERENCE = "Nothing I can read differs between this machine and " +
    "the saved capture. If you ran this on the machine that made the capture, it cannot differ: " +
    "run it on the machine where the program works."
private val SSH_NOTE = "Over ssh I read system files and environment variables only, not tool " +
    "versions or project packages. For those, run drift why --save there.json on that machine, " +
    "copy the file here and pass it to --there."
private const val SSH_LOOKED = "environment variables, resource limits, memory and CPU " +
    "limits, time zone and the operating system release (read only, over ssh)"

class WhyCommand(
    private val host: Host,
    private val files: CaseFiles,
    private val clock: TimeSource = TimeSource.Monotonic,
) : CliktCommand(name = "why") {
    private var separated = false
    private var errorRaw: String? = null
    private var unmatched = false

    private val there by option(
        "--there",
        metavar = "PLACE",
        help = "where it fails: docker:NAME (a running container), user@host (read only over " +
            "ssh) or a file saved by drift capture",
    )
    private val failsHere by option(
        "--fails-here",
        help = "it fails here and works there (the default is the other way round)",
    ).flag()
    private val save by option(
        "--save",
        metavar = "FILE",
        help = "capture this machine into FILE, to copy to the machine where the program works",
    )
    private val force by option("--force", help = "replace the file --save would overwrite").flag()
    private val errorText by option(
        "--error",
        metavar = "TEXT",
        help = "the error the program printed: TEXT, @FILE, or - for standard input",
    )
    private val reply by option(
        "--reply",
        help = "read standard input as the answer to the first open question of the session",
    ).flag()
    private val worksSide by option(
        "--works",
        help = "with --reply: standard input is the answer from the machine where it works",
    ).flag()
    private val sessionFile by option(
        "--session",
        metavar = "FILE",
        help = "where drift why keeps its questions and your answers (default $SESSION)",
    )
    private val detail by option("--detail", help = "also print the full ranking and plan").flag()
    private val json by option("--json", help = "print canonical JSON with a plain object").flag()
    private val words by argument(
        "command-or-file",
        help = "after --, the command to run; otherwise an issue or error text file, or - for " +
            "standard input",
    ).multiple()

    init {
        context {
            allowInterspersedArgs = false
            transformToken = { token ->
                if (token == "--") separated = true
                token
            }
        }
    }

    override fun help(context: Context) =
        "Say why a program works in one place and fails in another. With no arguments in a " +
            "GitHub or GitLab checkout it reads the latest failed CI run; --there compares " +
            "this machine with a container, a machine or a saved capture; -- COMMAND runs " +
            "the command here and compares it with the last time it passed; a file argument " +
            "reads an issue or an error report"

    override fun helpEpilog(context: Context) = examples("why")

    override fun run() {
        val dashed = separated
        separated = false
        if (json && detail) throw UsageError("--json and --detail cannot be combined")
        if (force && save == null) throw UsageError("--force only applies with --save")
        if (worksSide && !reply) throw UsageError("--works only applies with --reply")
        if (reply) {
            val other = there != null || save != null || errorText != null
            if (other || dashed || words.isNotEmpty()) {
                throw UsageError("--reply reads standard input; it takes no other argument")
            }
            if (failsHere || force || detail) {
                throw UsageError("--reply takes only --works and --session")
            }
            replyFlow()
            return
        }
        val target = there
        val saving = save
        if (saving != null) {
            if (target != null || dashed || words.isNotEmpty() || errorText != null) {
                throw UsageError("--save cannot be combined with --there, a command or a file")
            }
            saveFlow(saving)
            return
        }
        val ciOnly = target == null && words.isEmpty() && !dashed
        val failure = errorText?.let { arg -> errorInput(arg, dashed, words, ciOnly) }
        when {
            target != null -> thereFlow(target, words, failure)

            words.isEmpty() -> ciFlow(failure)

            dashed -> commandFlow(words)

            words.size == 1 && (words[0] == "-" || host.readText(words[0]) != null) ->
                issueFlow(words[0])

            else -> throw CliktError(
                "cannot read ${words[0]} as an issue or error text. To run a command, put -- " +
                    "before it: drift why -- ${words.joinToString(" ")}",
            )
        }
    }

    // #region flows

    private fun ciFlow(failure: List<Symptom>?) {
        val runs = try {
            CiCheck.fetch(host, CiRequest(), needPassing = false)
        } catch (e: CiStop) {
            val text = errorRaw
            if (e.kind == CiProblemKind.NOT_A_CHECKOUT && text != null) {
                reportFlow("drift why: reading the error text", text, ERROR_ONLY, self = true)
                return
            }
            throw CliktError(ciMessage(e))
        }
        if (unmatched) echo(UNMATCHED_NOTE + "\n")
        val fails = runs.red.capsule!!
        val here = capture("here")
        val shared = fails.attributes.map { it.path }.toSet()
            .intersect(here.attributes.map { it.path }.toSet())
        val works = here.copy(attributes = here.attributes.filter { it.path in shared })
        val failing = fails.copy(attributes = fails.attributes.filter { it.path in shared })
        val branch = runs.repo.branch?.let { ", branch $it" }.orEmpty()
        val flow = "drift why: this machine against the latest failed CI run of " +
            "${runs.repo.path}$branch"
        val between = betweenRuns(runs)
        val extra = between?.let { betweenLine(it, runs) } ?: runs.green?.let {
            val passed = runs.passingLabel.orEmpty()
            "CI passed on $passed and failed on ${runs.failingLabel} with the same runner " +
                "environment, so the cause is probably in the code change: git diff $passed " +
                runs.failingLabel
        }
        val none = if (runs.passing == null) {
            listOf(
                "No earlier passing CI run was found, so I could not check what else changed " +
                    "between runs.",
            )
        } else {
            emptyList()
        }
        val answer = Explain.run(
            Inputs(
                works,
                failing,
                Sides.CI,
                runs.red.symptoms + failure.orEmpty(),
                pin = pin(),
                errorText = failure != null,
                skip = Noise::isCiLabel,
                extraEvidence = extra,
                caseDir = CASE,
                closing = none,
                looked = "the tools, packages and settings the CI log prints",
            ),
        )
        if (answer.certainty == Certainty.NONE && between != null) {
            val note = "Nothing on this machine differs from the failing CI run in what its log " +
                "prints, so I compared the two CI runs."
            emit(flow, betweenRuns(runs, note)!!)
            return
        }
        emit(flow, answer)
    }

    private val betweenSides = Sides("when CI passed", "in CI", assumed = false)

    /** The passing and the failing CI run against each other; null when nothing differs. */
    private fun betweenRuns(runs: CiRuns, lead: String? = null): Answer? {
        val green = runs.green?.capsule ?: return null
        val red = runs.red.capsule ?: return null
        val answer = Explain.run(
            Inputs(
                green,
                red,
                betweenSides,
                runs.red.symptoms,
                pin = pin(),
                caseDir = CASE,
                lead = lead,
                looked = "the runner image and the tools the two CI logs print",
            ),
        )
        return answer.takeIf { it.certainty != Certainty.NONE }
    }

    private fun betweenLine(answer: Answer, runs: CiRuns): String {
        val d = answer.findings.first().described
        return "Between the CI runs, ${d.label} changed: ${d.works} on ${runs.passingLabel} " +
            "(passed), ${d.fails} on ${runs.failingLabel} (failed)"
    }

    private fun ciMessage(e: CiStop): String {
        val text = e.message.orEmpty()
        if (e.kind != CiProblemKind.NOT_A_CHECKOUT) return text
        val cause = text.substringBefore(';')
        return "$cause. drift why with no arguments reads the latest failed CI run, which " +
            "needs a git checkout whose remote is on GitHub or GitLab. $OTHER_WAYS"
    }

    private fun thereFlow(target: String, command: List<String>, failure: List<Symptom>?) {
        val place = Place.parse(target) {
            host.readText(it) != null || host.readText("$it/${CapsuleDirectories.CAPSULE}") != null
        }
        if (command.isNotEmpty() && place !is Place.Docker) {
            throw CliktError(
                "a command can only run in a container (docker:NAME); over ssh and from a " +
                    "saved capture Drift reads facts and runs no repository code. Drop the " +
                    "command to compare what is there.",
            )
        }
        var looked = Explain.LOOKED_AT
        var closing = emptyList<String>()
        var docker: DockerExecHost? = null
        val flow: String
        val here: Capsule
        val there: Capsule
        try {
            when (place) {
                is Place.Docker -> {
                    val remote = DockerExecHost.open(host, place.name, place.dir)
                    docker = remote
                    flow = "drift why: this machine against the container ${place.name}"
                    here = capture("here")
                    there = capture(target, remote)
                    remote.problem?.let { throw it }
                }

                is Place.Ssh -> {
                    flow = "drift why: this machine against ${place.alias}, read over ssh"
                    here = Capture.run(host, "here")
                    there = captureOverSsh(host, place.alias, target, false)
                    looked = SSH_LOOKED
                    closing = listOf(SSH_NOTE)
                }

                is Place.File -> {
                    flow = "drift why: this machine against the saved capture in ${place.path}"
                    val loaded = readCapture(place.path)
                    val full = capture("here")
                    val absent = FAMILIES.filter { f ->
                        loaded.attributes.none { it.path.startsWith(f) } &&
                            full.attributes.any { it.path.startsWith(f) }
                    }
                    here = full.copy(
                        attributes = full.attributes.filter { a ->
                            absent.none { a.path.startsWith(it) }
                        },
                    )
                    there = loaded
                    if (absent.isNotEmpty()) {
                        val names = absent.joinToString(", ") { it.trimEnd('.') }
                        closing = listOf(
                            "That saved capture has no $names facts, so I did not compare " +
                                "them. Make a full one on that machine: drift why --save " +
                                "there.json.",
                        )
                    }
                }
            }
        } catch (e: PlaceException) {
            throw CliktError(e.message)
        } catch (e: SshException) {
            throw CliktError(e.message)
        }
        val hint = if (place is Place.Docker) {
            "Add the failing command so its error text can narrow this: " +
                "drift why --there $target -- COMMAND"
        } else {
            "Paste the error message you saw: drift why --there $target --error \"...\""
        }
        if (command.isEmpty() || docker == null) {
            val sides = if (failsHere) Sides.HERE_THERE.swapped() else Sides.HERE_THERE
            val (works, fails) = if (failsHere) there to here else here to there
            val inputs = Inputs(
                works,
                fails,
                sides,
                failure = failure,
                pin = pin(),
                errorText = failure != null,
                failureHint = hint,
                caseDir = CASE,
                closing = closing,
                looked = looked,
            )
            val answer = Explain.run(inputs)
            val none = place is Place.File && answer.certainty == Certainty.NONE
            emit(flow, answer, verdict = if (none) SAVED_NO_DIFFERENCE else null)
            return
        }
        val onHere = execute(host, command)
        val onThere = execute(docker, command)
        docker.problem?.let { throw CliktError(it.message) }
        val shown = display(command)
        val ranFlow = "$flow, running $shown in both"
        if (onHere.exit == 0 && onThere.exit == 0) {
            emitPlain(
                PlainView(
                    ranFlow,
                    null,
                    "passes",
                    "It passes in both places, so there is nothing to explain.",
                    emptyList(),
                    listOf(
                        "If it fails only sometimes, repeat it: drift run --repeat 10 -- $shown",
                        "If it failed somewhere else, compare that place: drift why --there PLACE",
                    ),
                ),
            )
            return
        }
        val bothFail = onHere.exit != 0 && onThere.exit != 0
        val swap = if (bothFail) failsHere else onHere.exit != 0
        val sides = Sides(
            if (swap) "there" else "here",
            if (swap) "here" else "there",
            assumed = bothFail,
        )
        val (works, fails) = if (swap) there to here else here to there
        val seen = if (swap) onHere else onThere
        val inputs = Inputs(
            works,
            fails,
            sides,
            failure = WhyFlow.symptoms(seen.output),
            pin = pin(),
            caseDir = CASE,
            closing = closing + if (bothFail) {
                listOf(
                    "It failed in both places, so a difference between them may not be what " +
                        "breaks it.",
                )
            } else {
                emptyList()
            },
            looked = looked,
        )
        emit(ranFlow, Explain.run(inputs), tail(seen.output))
    }

    private fun commandFlow(command: List<String>) {
        val shown = display(command)
        val flow = "drift why: running $shown here"
        val mark = clock.markNow()
        val ran = execute(host, command, live = true)
        val millis = mark.elapsedNow().inWholeMilliseconds
        val here = capture(if (ran.exit == 0) "baseline" else "now")
        if (ran.exit == 0) {
            val saved = CapsuleDirectories.write(
                files,
                BASELINE,
                CapsuleDirectory(
                    here,
                    null,
                    Execution(Redactor.redactText(shown), 0, millis, false),
                ),
            )
            if (saved != null) throw CliktError("cannot write $saved; check the directory")
            emitPlain(
                PlainView(
                    flow,
                    null,
                    "baseline",
                    "It passed. I saved this machine as the baseline in $BASELINE.",
                    emptyList(),
                    listOf("When it fails, run the same command again: drift why -- $shown"),
                ),
            )
            return
        }
        val read = CapsuleDirectories.read(host::readText, BASELINE)
        if (read is DirectoryRead.Failed) throw CliktError(read.reason)
        val baseline = (read as? DirectoryRead.Loaded)?.directory
        if (baseline == null) {
            emitPlain(
                PlainView(
                    flow,
                    null,
                    "no-baseline",
                    "It failed (exit ${ran.exit}), and I have no saved run of it passing to " +
                        "compare with, so I cannot say what changed.",
                    emptyList(),
                    listOf(
                        "Run the same command where it passes: drift why -- $shown. A pass " +
                            "saves a baseline in $BASELINE; copy that folder here.",
                        "Or compare with a machine or container where it works: drift why " +
                            "--there user@host or drift why --there docker:NAME -- $shown",
                    ),
                ),
                tail(ran.output),
            )
            return
        }
        val earlier = baseline.execution?.command
        val note = if (earlier != null && earlier != Redactor.redactText(shown)) {
            listOf("The baseline was saved from a different command ($earlier).")
        } else {
            emptyList()
        }
        val answer = Explain.run(
            Inputs(
                baseline.capsule,
                here,
                Sides.BASELINE,
                failure = WhyFlow.symptoms(ran.output),
                pin = pin(),
                caseDir = CASE,
                closing = note,
            ),
        )
        emit(flow, answer, tail(ran.output))
    }

    private fun saveFlow(path: String) {
        if (!force && files.read(path) != null) {
            throw CliktError(
                "$path already exists. Choose another name, or add --force to replace it.",
            )
        }
        if (!files.write(path, capture("here").canonical())) {
            throw CliktError("cannot write $path; check the directory")
        }
        echo("Saved this machine to ${absolutePath(path)}.")
        echo("Copy it to the machine where the program works.")
        echo("Then run this there: drift why --there ${display(listOf(path))}")
    }

    private fun issueFlow(source: String) {
        val path = if (source == "-") STDIN else source
        val text = host.readText(path) ?: throw CliktError(
            if (source == "-") "cannot read standard input" else "cannot read $source",
        )
        val name = if (source == "-") "standard input" else source
        reportFlow("drift why: reading the report in $name", text)
    }

    /** Reads [text] as a report; when it leaves checks open, saves them in the session file. */
    private fun reportFlow(
        flow: String,
        text: String,
        note: String? = null,
        self: Boolean = false,
    ) {
        val turn = WhyFlow.report(flow, text, self, note, shownSession())
        turn.session?.let(::saveSession)
        val view = turn.view
        val result = turn.result!!
        if (json) {
            val fields = result.toJson().fields + ("plain" to view.plain())
            rawEcho(CanonicalJson.encode(JsonObject(fields)))
            return
        }
        echo(view.text(), trailingNewline = false)
        if (detail) echo("\n" + TriageText.render(result), trailingNewline = false)
    }

    /** The session path as `--reply` should be told it, or null for the default. */
    private fun shownSession(): String? = sessionFile?.takeIf { it != SESSION }

    private fun saveSession(session: Session) {
        val path = sessionFile ?: SESSION
        if (!files.write(path, session.text())) noteUnwritable(path)
    }

    private fun noteUnwritable(path: String) {
        val note = "note: I could not write $path; run drift why in a folder you can write to."
        echo(note, err = true)
    }

    private fun replyFlow() {
        val path = sessionFile ?: SESSION
        val saved = files.read(path)
        if (saved == null) {
            noSession()
            return
        }
        val session = Session.parse(saved) ?: throw CliktError(
            "cannot read $path as a drift why session. Remove it and start again with " +
                "drift why FILE or drift why --error TEXT.",
        )
        if (worksSide) {
            worksFlow(session, path)
            return
        }
        if (session.open.isEmpty()) {
            throw CliktError(
                "every question in $path has an answer. Start again with drift why FILE or " +
                    "drift why --error TEXT.",
            )
        }
        val input = host.readText(STDIN) ?: throw CliktError("cannot read standard input")
        printTurn(WhyFlow.reply(session, input, shownSession()))
    }

    /** Saves the session of a step and prints it; an unreadable answer leaves the check open. */
    private fun printTurn(turn: WhyFlow.Turn) {
        when (turn) {
            is WhyFlow.Turn.Unreadable -> {
                val ask = turn.ask
                throw CliktError(
                    if (turn.works) {
                        "I could not read that as the output of ${ask.line} on the machine " +
                            "where it works, so the question is still open. Run it where it " +
                            "works: ${ask.pipedWorks(shownSession())}"
                    } else {
                        "I could not read that as the output of ${ask.line}, so the question " +
                            "is still open. Run it where the program fails: " +
                            ask.piped(shownSession())
                    },
                )
            }

            is WhyFlow.Turn.Shown -> {
                turn.session?.let(::saveSession)
                printView(turn.view, turn.result)
            }

            WhyFlow.Turn.Closed -> error("a step with a waiting check cannot be closed")
        }
    }

    /**
     * A reply on a machine with no session: it is the answer to some check, but which one is in
     * the session on another machine. Drift prints the one command that carries it there.
     */
    private fun noSession() {
        val input = host.readText(STDIN) ?: throw CliktError("cannot read standard input")
        if (!WhyFlow.readable(input)) {
            throw CliktError(
                "I could not read that as the output of a check Drift asks for, and there is no " +
                    "session here. The session is in the folder where you ran drift why " +
                    "--error; run the check where it asks and pipe it there.",
            )
        }
        val given = sessionFile
        val command = ReplyCommand.build(input, worksSide, given)
        val lead = "There is no session here, so I cannot compare yet. The session is in the " +
            "folder where you ran drift why."
        val next = command?.let { listOf(it) } ?: listOf(
            "The output is longer than ${ReplyCommand.MAX_BYTES} bytes. Copy it into a file and " +
                "run this there: drift why --reply${if (worksSide) " --works" else ""}" +
                "${given?.let { " --session $it" } ?: ""} < FILE",
        )
        if (json) {
            val flow = "drift why: no session here"
            val view = PlainView(flow, null, "none", lead, emptyList(), next)
            rawEcho(CanonicalJson.encode(JsonObject(mapOf("plain" to view.plain()))))
            return
        }
        echo("$lead Run this there:")
        echo(next.first())
    }

    /**
     * `--reply --works`: the answer to a check from the machine where the program works. It goes
     * to the first check that has the failing side and no works side, else to the first one that
     * has neither; with both sides the two are compared.
     */
    private fun worksFlow(session: Session, path: String) {
        if (WhyFlow.worksTarget(session) == null) {
            throw CliktError(
                "nothing in $path waits for an answer from the machine where it works. Start " +
                    "with drift why FILE or drift why --error TEXT, answer its first question " +
                    "where it fails with drift why --reply, then run the works-side step it " +
                    "prints.",
            )
        }
        val input = host.readText(STDIN) ?: throw CliktError("cannot read standard input")
        printTurn(WhyFlow.works(session, input, shownSession()))
    }

    private fun printView(view: PlainView, result: TriageResult?) {
        if (json) {
            val fields = result?.toJson()?.fields.orEmpty() + ("plain" to view.plain())
            rawEcho(CanonicalJson.encode(JsonObject(fields)))
            return
        }
        echo(view.text(), trailingNewline = false)
    }

    // #endregion

    // #region helpers

    private class Ran(val exit: Int, val output: String)

    private fun execute(target: Host, command: List<String>, live: Boolean = false): Ran {
        val out = StringBuilder()
        val result = target.run(command, null) {
            out.append(it)
            if (live) echo(it, err = true, trailingNewline = false)
        } ?: throw CliktError("cannot start command: ${command.first()}")
        return Ran(result.exitCode, "$out")
    }

    private fun capture(label: String, on: Host = host): Capsule =
        Capture.run(on, label, tools = true, project = true)

    private fun pin(): (String) -> String? = { Families.pin(host::readText, it) }

    private fun tail(output: String) = Execution.tail(output, TAIL_LINES).first

    private fun errorInput(
        arg: String,
        dashed: Boolean,
        words: List<String>,
        ciOnly: Boolean,
    ): List<Symptom> {
        if (dashed) {
            throw UsageError(
                "--error and -- COMMAND cannot be combined: the command's own output is the " +
                    "error text",
            )
        }
        if (words.isNotEmpty()) {
            throw UsageError(
                "--error cannot be combined with an issue file: drop --error to read the file " +
                    "as a report, or drop the file to read the error text",
            )
        }
        val raw = when {
            arg == "-" -> host.readText(STDIN) ?: throw CliktError("cannot read standard input")

            arg.startsWith("@") -> {
                val path = arg.drop(1)
                host.readText(path) ?: throw CliktError("cannot read $path")
            }

            else -> arg
        }
        val bytes = raw.encodeToByteArray()
        val text = if (bytes.size > ERROR_TEXT_BYTES) {
            if (!json) echo(CUT_NOTE + "\n")
            bytes.decodeToString(0, ERROR_TEXT_BYTES)
        } else {
            raw
        }
        errorRaw = text
        val found = WhyFlow.symptoms(text)
        unmatched = !json && FailurePhrases.pointers(found).isEmpty()
        if (unmatched && !ciOnly) echo(UNMATCHED_NOTE + "\n")
        return found
    }

    // the shell's PWD is the only working directory the host exposes; windows has none
    private fun absolutePath(path: String): String {
        if (path.startsWith("/")) return path
        val dir = host.env()["PWD"]?.takeIf { it.startsWith("/") } ?: return path
        return "${dir.trimEnd('/')}/${path.removePrefix("./")}"
    }

    private fun display(command: List<String>) =
        command.joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }

    private fun readCapture(path: String): Capsule = try {
        loadCapsuleSource(host, path)
    } catch (e: CliktError) {
        throw CliktError(
            "cannot read $path as a saved capture (${e.message}). Make one on the machine " +
                "where it fails: drift why --save FILE, then copy FILE here and pass it to " +
                "--there.",
        )
    }

    private fun writeCase(answer: Answer): String? {
        val name = "${answer.works.label} to ${answer.fails.label}"
        val built = CaseBuilder.build(name, answer.works, answer.fails, answer.ranking, answer.plan)
        return (CaseStore.write(files, CASE, built) as? CaseWrite.Failed)?.path
    }

    private fun emit(
        flow: String,
        answer: Answer,
        failingOutput: String? = null,
        verdict: String? = null,
    ) {
        val open = answer.certainty == Certainty.ONE_OF || answer.certainty == Certainty.TIE
        if (open && answer.notEnvironment == null) writeCase(answer)?.let(::noteUnwritable)
        val checks = openChecks(answer)
        val view = PlainView.of(flow, answer).withVerdict(verdict).withAsk(checks, shownSession())
        if (checks.isNotEmpty()) {
            val report = Triage.clean(failureLines(answer).joinToString("\n"))
            saveSession(Session.start(report, checks))
        }
        if (json) {
            val state = StudioState.of(
                answer.works,
                answer.fails,
                Rules.all,
                null,
                answer.failure.orEmpty(),
            ).toJson()
            rawEcho(CanonicalJson.encode(JsonObject(state.fields + ("plain" to view.plain()))))
            return
        }
        echo(view.text(), trailingNewline = false)
        if (detail) {
            echo(PlainView.detail(answer), trailingNewline = false)
            failingOutput?.let { echo("\nOutput of the failing run (last lines):\n$it") }
        }
    }

    private fun failureLines(answer: Answer) =
        answer.failure.orEmpty().filter { it.kind == SymptomKind.SIGNATURE }.map { it.value }

    /**
     * The checks an answer of "cannot tell" can still ask for: those the error text calls for
     * and the comparison did not already cover.
     */
    private fun openChecks(answer: Answer): List<Ask> {
        if (answer.certainty != Certainty.NONE || answer.notEnvironment != null) return emptyList()
        val lines = failureLines(answer)
        if (lines.isEmpty()) return emptyList()
        val compared = (answer.works.attributes + answer.fails.attributes).map { it.path }
        return Triage.run(lines.joinToString("\n")).ask.filter { ask ->
            ask.family != Ask.CAPTURE &&
                compared.none { it == ask.family || it.startsWith(ask.family + ".") }
        }
    }

    private fun emitPlain(view: PlainView, failingOutput: String? = null) {
        if (json) {
            rawEcho(CanonicalJson.encode(JsonObject(mapOf("plain" to view.plain()))))
            return
        }
        echo(view.text(), trailingNewline = false)
        if (detail) failingOutput?.let { echo("\nOutput of the failing run (last lines):\n$it") }
    }

    // #endregion
}
