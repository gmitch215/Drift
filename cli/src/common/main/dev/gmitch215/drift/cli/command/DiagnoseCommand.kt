package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.gmitch215.drift.case.CaseBuilder
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.CaseWrite
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.case.Render
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.CapsuleDirectory
import dev.gmitch215.drift.ci.DirectoryRead
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.history.Repeat
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.scan.Capture
import dev.gmitch215.drift.set.SetCapsule
import dev.gmitch215.drift.set.SetDiagnosis
import dev.gmitch215.drift.studio.StudioState
import dev.gmitch215.drift.symptom.Performance

private const val SET_SUMMARY_ROWS = 5

class DiagnoseCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "diagnose") {
    private val capsules by argument(
        "capsule",
        help = "capsule file or directory of the passing run, then of the failing run; " +
            "with --against, only the failing run (default: capture now); with --set, every " +
            "capsule of the set",
    ).multiple()
    private val set by option(
        "--set",
        help = "rank what splits passing from failing over any number of capsules, each ending " +
            "in #pass or #fail unless its directory records the outcome",
    ).flag()
    private val against by option(
        "--against",
        help = "baseline capsule directory or file of a passing run",
    )
    private val case by option("--case", help = "write a case directory here")
    private val detail by option("--detail", help = "print text instead of JSON")
        .choice("summary", "detail", "full")
    private val json by option("--json", help = "print canonical JSON (the default)").flag()
    private val frame by option("--frame", help = "two commits, or a local and a CI capture")
        .choice("sha", "environment").default("sha")
    private val name by option("--name", help = "case name (default: both capsule labels)")
    private val trialMinutes by option("--trial-minutes", help = "minutes one trial takes")
        .long().default(5)
    private val failures by option(
        "--failures",
        help = "failing runs seen on the red side (default: 1, or its failed samples)",
    ).int()
    private val runs by option(
        "--runs",
        help = "runs seen on the red side (default: 1, or its samples)",
    ).int()
    private val learned by option(
        "--learned",
        metavar = "DIR",
        help = "learned-rule store whose rules are ranked with the shipped ones",
    )
    private val ssh by option(
        "--ssh",
        metavar = "HOST",
        help = "capture a machine over ssh with read-only commands; give it twice, the passing " +
            "machine first, and the capsules and case go to .drift/ssh/ unless --case names a " +
            "directory",
    ).multiple()

    override fun help(context: Context) =
        "Diagnose a green and a red capsule, or a baseline and the current run: canonical JSON " +
            "by default, or text and a case; with --set, rank the attributes that split a set " +
            "of passing and failing capsules"

    private fun labelled(path: String): SetCapsule {
        val hash = path.lastIndexOf('#')
        val tag = if (hash > 0) path.substring(hash + 1) else null
        val source = if (tag == "pass" || tag == "fail") path.substring(0, hash) else path
        var failing = when (tag) {
            "pass", "passing" -> false
            "fail", "failing" -> true
            else -> null
        }
        val directory = CapsuleDirectories.read(host::readText, source)
        val capsule = when (directory) {
            is DirectoryRead.Loaded -> {
                failing = failing ?: directory.directory.outcome()
                directory.directory.capsule
            }

            is DirectoryRead.Failed -> throw CliktError(directory.reason)

            null -> loadCapsule(host, source)
        }
        val outcome = failing
            ?: throw UsageError("$source has no recorded outcome; end it with #pass or #fail")
        return SetCapsule(source, outcome, capsule)
    }

    private fun CapsuleDirectory.outcome(): Boolean? = when (run?.outcome) {
        RunOutcome.PASS -> false
        RunOutcome.FAIL -> true
        else -> execution?.let { it.exitCode != 0 }
    }

    private fun runSet() {
        if (against != null || case != null) {
            throw UsageError("--set cannot be combined with --against or --case")
        }
        if (capsules.size < 2) throw UsageError("--set needs at least two capsules")
        val result = SetDiagnosis.of(capsules.map(::labelled))
        if (result.failing == 0 || result.passing == 0) {
            throw UsageError("--set needs at least one passing and one failing capsule")
        }
        when (detail) {
            null -> rawEcho(CanonicalJson.encode(result.toJson()))
            "summary" -> echo(result.text(SET_SUMMARY_ROWS), trailingNewline = false)
            else -> echo(result.text(), trailingNewline = false)
        }
    }

    private fun sshPair(): Pair<Capsule, Capsule> {
        if (ssh.size != 2) {
            throw UsageError("--ssh takes two hosts: the passing machine, then the failing one")
        }
        val (passing, failing) = ssh.map { captureOverSsh(host, it, it, false) }
        return passing to failing
    }

    private fun sshCaseDirectory(): String {
        fun name(alias: String) = alias.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
            .joinToString("")
        return ".drift/ssh/${name(ssh[0])}-${name(ssh[1])}"
    }

    private fun writeSshCapsules(dir: String, passing: Capsule, failing: Capsule) {
        val base = dir.trimEnd('/')
        for ((side, capsule) in listOf("passing" to passing, "failing" to failing)) {
            CapsuleDirectories.write(files, "$base/capsules/$side", CapsuleDirectory(capsule))
                ?.let { throw CliktError("cannot write $it; check the directory") }
        }
    }

    private fun pair(): Pair<Capsule, Capsule> {
        if (ssh.isNotEmpty()) return sshPair()
        val baseline = against
        if (baseline == null) {
            if (capsules.size != 2) {
                // a bare archive fails in loadCapsule with the verify, report and open hint
                capsules.singleOrNull()?.let { loadCapsuleSource(host, it) }
                throw UsageError("expected a passing and a failing capsule")
            }
            return loadCapsuleSource(host, capsules[0]) to loadCapsuleSource(host, capsules[1])
        }
        if (capsules.size > 1) throw UsageError("--against takes at most one capsule")
        val current = capsules.firstOrNull()?.let { loadCapsuleSource(host, it) }
            ?: Capture.run(host, "current")
        return loadCapsuleSource(host, baseline) to current
    }

    private fun runOf(path: String?): Run? =
        path?.let { (CapsuleDirectories.read(host::readText, it) as? DirectoryRead.Loaded) }
            ?.directory?.run

    /** What the samples of the two runs say; null when neither run has any. */
    private fun measured(
        passing: Capsule,
        failing: Capsule,
        first: Run?,
        second: Run?,
    ): Performance? {
        val notes = listOfNotNull(
            Repeat.intermittent("The first run", passing.label, first?.samples.orEmpty()),
            Repeat.intermittent("The second run", failing.label, second?.samples.orEmpty()),
        )
        val compared = Performance.compare(first, second, passing, failing)
        if (notes.isEmpty()) return compared
        return Performance(
            notes + compared?.lines.orEmpty(),
            compared?.symptoms.orEmpty(),
            compared?.ranges.orEmpty(),
        )
    }

    override fun helpEpilog(context: Context) = examples("diagnose")

    override fun run() {
        if (json && detail != null) throw UsageError("--json and --detail cannot be combined")
        if (ssh.isNotEmpty() && (capsules.isNotEmpty() || against != null || set)) {
            throw UsageError("--ssh cannot be combined with capsules, --against or --set")
        }
        if (set) return runSet()
        val (passing, failing) = pair()
        val rules = rulesWithLearned(files, learned)
        val first = runOf(against ?: capsules.firstOrNull())
        val second = runOf(if (against == null) capsules.getOrNull(1) else capsules.firstOrNull())
        val performance = measured(passing, failing, first, second)
        val seen = second?.samples.orEmpty()
        val symptoms = second?.symptoms.orEmpty()
        val caseDir = case ?: if (ssh.isEmpty()) null else sshCaseDirectory()
        if (detail == null && caseDir == null) {
            val state = StudioState.of(passing, failing, rules, performance, symptoms)
            rawEcho(CanonicalJson.encode(state.toJson()))
            return
        }
        if (trialMinutes < 1) throw CliktError("--trial-minutes must be at least 1")
        val mixed = seen.size > 1 && seen.any { !it.passed }
        val runCount = runs ?: if (mixed) seen.size else 1
        val failureCount = failures ?: if (mixed) seen.count { !it.passed } else 1
        if (runCount < 0 || failureCount !in 0..runCount) {
            throw CliktError("--failures must be within 0..--runs")
        }
        val conditioned = performance?.symptoms.orEmpty().takeIf { it.isNotEmpty() }
            ?.let { symptoms + it }
        val ranking = Ranker.rank(passing, failing, rules, conditioned)
        val shape = if (frame == "sha" && ssh.isEmpty()) {
            Frame.shas(passing.label, failing.label)
        } else {
            Frame.environments(passing.label, failing.label)
        }
        val plan = Planner.from(ranking, shape, Options(trialMinutes, failureCount, runCount))
        caseDir?.let { dir ->
            if (case != null && files.read("${dir.trimEnd('/')}/case.json") != null) {
                throw CliktError("case directory already holds a case: $dir")
            }
            if (ssh.isNotEmpty()) writeSshCapsules(dir, passing, failing)
            val label = name ?: "${passing.label} to ${failing.label}"
            val built = CaseBuilder.build(label, passing, failing, ranking, plan)
            when (val result = CaseStore.write(files, dir, built)) {
                is CaseWrite.Written -> echo("wrote ${result.files} files to $dir", err = true)
                is CaseWrite.Failed -> throw CliktError("cannot write case file: ${result.path}")
            }
        }
        val level = detail?.let { Detail.parse(it) }
        if (level == null) {
            val state = StudioState.of(passing, failing, rules, performance, symptoms)
            rawEcho(CanonicalJson.encode(state.toJson()))
        } else {
            echo(Render.render(ranking, plan, level), trailingNewline = false)
            performance?.let { echo("\n" + it.lines.joinToString("\n")) }
        }
        if (ssh.isNotEmpty()) echo(SSH_LIMITS, err = true)
    }
}
