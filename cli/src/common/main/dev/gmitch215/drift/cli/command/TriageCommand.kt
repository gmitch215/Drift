package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.know.Learned
import dev.gmitch215.drift.triage.Calibrate
import dev.gmitch215.drift.triage.Confidence
import dev.gmitch215.drift.triage.ConfidenceTable
import dev.gmitch215.drift.triage.Disposition
import dev.gmitch215.drift.triage.DraftRule
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.triage.TriageOptions
import dev.gmitch215.drift.triage.TriageText

class TriageCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "triage") {
    private val issue by argument(
        "issue",
        help = "issue text (Markdown), - for standard input, or calibrate to recalibrate",
    )
    private val json by option("--json", help = "print canonical JSON instead of text").flag()
    private val threshold by option(
        "--publish-threshold",
        help = "confidence from 0 to 1 at which a draft rule is published (default: drafts only)",
    )
    private val learned by option(
        "--learned",
        metavar = "DIR",
        help = "learned-rule store: its rules are matched, and a published draft is added to it",
    )
    private val confidence by option(
        "--confidence",
        metavar = "FILE",
        help = "table written by triage calibrate, replacing the starting confidence of a path",
    )
    private val author by option("--author", help = "login of the reporter, stored as a hash")
    private val date by option("--date", help = "day the report was read, yyyy-mm-dd, for the rule")

    private val corpus by option(
        "--corpus",
        metavar = "FILE",
        help = "calibrate: corpus written by buildCorpus, .json or .json.zst (zst needs zstd)",
    )
    private val out by option("--out", metavar = "FILE", help = "calibrate: the table to write")
    private val seed by option("--seed", help = "calibrate: seed of the dev and held-out split")
        .default(Calibrate.SEED)
    private val minFacts by option(
        "--min-facts",
        help = "calibrate: dev facts a path needs to move",
    )
        .long().default(Calibrate.MIN_FACTS)

    override fun help(context: Context) =
        "Read an issue body offline: environment facts with confidence, matching rules, " +
            "contradicted claims and a draft rule. `triage calibrate --corpus FILE --out FILE` " +
            "recalibrates the confidence of each path on your own issues"

    override fun helpEpilog(context: Context) = examples("triage")

    override fun run() {
        if (issue == CALIBRATE) return calibrate()
        if (corpus != null || out != null) {
            throw UsageError("--corpus and --out belong to triage calibrate")
        }
        val limit = threshold?.let {
            Confidence.parse(it) ?: throw UsageError("--publish-threshold must be 0 to 1: $it")
        }
        date?.let { if (!isDate(it)) throw UsageError("--date must be yyyy-mm-dd: $it") }
        val bases = confidence?.let { path ->
            val text = files.read(path) ?: throw CliktError("cannot read confidence table: $path")
            try {
                ConfidenceTable.parse(text)
            } catch (e: IllegalArgumentException) {
                throw CliktError("bad confidence table $path: ${e.message}")
            }
        }.orEmpty()
        val path = if (issue == "-") STDIN else issue
        val text = host.readText(path) ?: throw CliktError(
            if (issue == "-") "cannot read standard input" else "cannot read issue text: $issue",
        )
        val rules = rulesWithLearned(files, learned)
        val result = Triage.run(text, TriageOptions(limit, author, date, bases), rules)
        learned?.let { store(it, result.draft) }
        if (json) {
            rawEcho(CanonicalJson.encode(result.toJson()))
        } else {
            rawEcho(TriageText.render(result), trailingNewline = false)
        }
    }

    private fun store(dir: String, draft: DraftRule?) {
        val rule = draft?.takeIf { it.disposition == Disposition.PUBLISH }?.rule ?: return
        val file = Learned.path(dir)
        val old = files.read(file)
        val next = Learned.add(old, rule)
        if (next == old) {
            echo("learned store $file already holds ${rule.id}", err = true)
        } else if (files.write(file, next)) {
            echo("added ${rule.id} to the learned store $file", err = true)
        } else {
            throw CliktError("cannot write the learned store: $file")
        }
    }

    private fun isDate(text: String): Boolean {
        if (text.length != 10 || text[4] != '-' || text[7] != '-') return false
        val digits = text.filterIndexed { i, _ -> i != 4 && i != 7 }
        if (!digits.all { it in '0'..'9' }) return false
        return text.substring(5, 7).toInt() in 1..12 && text.substring(8).toInt() in 1..31
    }

    private fun calibrate() {
        val from = corpus ?: throw UsageError("triage calibrate needs --corpus")
        val to = out ?: throw UsageError("triage calibrate needs --out")
        val text = if (from.endsWith(".zst")) decompress(from) else files.read(from)
        val lines = (text ?: throw CliktError("cannot read corpus: $from")).lineSequence()
            .asIterable()
        val result = try {
            Calibrate.run(lines, seed, minFacts)
        } catch (e: JsonException) {
            throw CliktError("bad corpus $from: ${e.message}")
        }
        if (!files.write(to, result.toJson())) throw CliktError("cannot write table: $to")
        echo(result.text(), trailingNewline = false)
        echo("wrote $to", err = true)
    }

    private fun decompress(path: String): String {
        val ran = host.run(listOf("zstd", "-dc", "--long=27", path))
            ?: throw CliktError("reading $path needs the zstd program; decompress it first")
        if (ran.exitCode != 0) throw CliktError("zstd could not read $path (exit ${ran.exitCode})")
        return ran.output
    }

    private companion object {
        const val STDIN = "/dev/stdin"
        const val CALIBRATE = "calibrate"
    }
}
