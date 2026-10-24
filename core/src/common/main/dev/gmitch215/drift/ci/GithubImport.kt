package dev.gmitch215.drift.ci

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.symptom.SymptomExtractor

/**
 * Reads GitHub Actions output that was saved to a file: a job log (raw, or as printed by
 * `gh run view --log`) and the summary from `gh run view --json`. The runner image block,
 * the operating system group, the runner version and the job `env:` block become capsule
 * attributes with source `log`; symptoms come from [SymptomExtractor].
 */
object GithubImport {
    private const val SOURCE = "log"
    private const val GROUP_LIMIT = 64
    private const val LINE_LIMIT = 4096
    private const val ERROR_TAG = "##[error]"
    private const val BOM = "\uFEFF"
    private val runnerVersion = Regex("Current runner version: '([^']+)'")

    /** The outcome is `FAIL` when the log holds an error annotation and `UNKNOWN` otherwise. */
    fun log(text: String, id: String, job: String? = null): Import {
        val lines = bodies(text, job)
        val attributes = environment(lines)
        val symptoms = SymptomExtractor.extract(text)
        if (attributes.isEmpty()) {
            return Import.Refused(
                "no runner environment found (expected an Operating System or Runner Image group)",
            )
        }
        val outcome = if (ERROR_TAG in text) RunOutcome.FAIL else RunOutcome.UNKNOWN
        val capsule = Capsule(id, attributes.sortedBy { it.path })
        return Import.Done(Run(id, outcome, emptyList(), symptoms, capsule))
    }

    /** The outcome and the steps of a `gh run view --json` file; there is no capsule. */
    fun summary(text: String): Import {
        val root = try {
            LooseJson.parse(text) as? JsonObject
        } catch (e: JsonException) {
            return Import.Refused("not valid JSON: ${e.message}")
        } ?: return Import.Refused("expected a JSON object from gh run view --json")
        val jobs = (root.fields["jobs"] as? JsonArray)?.items.orEmpty().mapNotNull {
            it as? JsonObject
        }
        if ("conclusion" !in root.fields && jobs.isEmpty()) {
            return Import.Refused("no conclusion or jobs in the gh run view --json output")
        }
        val id = when (val raw = root.fields["databaseId"]) {
            is JsonInt -> raw.value.toString()
            is JsonString -> raw.value
            else -> "run"
        }
        val steps = jobs.flatMap { job ->
            val name = text(job, "name")
            (job.fields["steps"] as? JsonArray)?.items.orEmpty().mapNotNull { s ->
                val step = s as? JsonObject ?: return@mapNotNull null
                Step(
                    name,
                    text(step, "name") ?: return@mapNotNull null,
                    StepConclusion.ofConclusion(text(step, "conclusion")),
                    duration(step),
                )
            }
        }
        val outcome = RunOutcome.ofConclusion(text(root, "conclusion"))
        return Import.Done(Run(id, outcome, steps))
    }

    /** The log, plus the outcome and steps of [summary] when it is given. */
    fun run(log: String, summary: String?, id: String, job: String? = null): Import {
        val read = log(log, id, job)
        if (summary == null || read !is Import.Done) return read
        return when (val parsed = summary(summary)) {
            is Import.Refused -> parsed

            is Import.Done ->
                Import.Done(read.run.copy(outcome = parsed.run.outcome, steps = parsed.run.steps))
        }
    }

    private fun text(o: JsonObject, key: String): String? = (o.fields[key] as? JsonString)?.value

    private fun duration(step: JsonObject): Long? {
        val start = text(step, "startedAt")?.let { SymptomExtractor.epochAt(it, 0) }
        val end = text(step, "completedAt")?.let { SymptomExtractor.epochAt(it, 0) }
        return if (start != null && end != null && end >= start) end - start else null
    }

    private class Row(val job: String?, val text: String)

    private fun row(raw: String): Row {
        val line = raw.trimEnd('\r').replace(BOM, "").take(LINE_LIMIT)
        val tab = line.indexOf('\t')
        val second = if (tab > 0) line.indexOf('\t', tab + 1) else -1
        var job: String? = null
        var rest = line
        if (second > tab && SymptomExtractor.epochAt(line, second + 1) != null) {
            job = line.substring(0, tab)
            rest = line.substring(second + 1)
        }
        if (SymptomExtractor.epochAt(rest, 0) != null) {
            val space = rest.indexOf(' ')
            rest = if (space < 0) "" else rest.substring(space + 1)
        }
        return Row(job, SymptomExtractor.stripAnsi(rest.removePrefix("")))
    }

    private fun bodies(text: String, job: String?): List<String> {
        val rows = text.lineSequence().map(::row).toList()
        val jobs = rows.mapNotNull { it.job }.distinct()
        val chosen = job ?: jobs.firstOrNull { name ->
            rows.any { it.job == name && it.text == "##[group]Runner Image" }
        } ?: jobs.firstOrNull()
        return rows.filter { chosen == null || it.job == chosen }.map { it.text }
    }

    private fun group(lines: List<String>, title: String): List<String> {
        val start = lines.indexOf("##[group]$title")
        if (start < 0) return emptyList()
        return lines.drop(start + 1).take(GROUP_LIMIT).takeWhile { it != "##[endgroup]" }
    }

    private fun field(lines: List<String>, name: String): String? =
        lines.firstOrNull { it.startsWith("$name: ") }?.substringAfter(": ")?.trim()

    private fun environment(lines: List<String>): List<Attribute> {
        val found = linkedMapOf<String, Attribute>()

        fun add(path: String, value: String?, stability: Stability = Stability.STATIC) {
            if (!value.isNullOrEmpty()) {
                found[path] = Redactor.redact(Attribute(path, value, SOURCE, stability))
            }
        }

        val os = group(lines, "Operating System")
        add("os.release.name", os.getOrNull(0)?.trim())
        add("os.version", os.getOrNull(1)?.trim())
        add("os.release.channel", os.getOrNull(2)?.trim())
        val image = group(lines, "Runner Image")
        add("ci.runner.image", field(image, "Image"))
        add("ci.runner.image.version", field(image, "Version"))
        val provisioner = group(lines, "Runner Image Provisioner")
        add("ci.provisioner.name", provisioner.getOrNull(0)?.trim())
        add("ci.provisioner.version", field(provisioner, "Version"))
        add("ci.provisioner.build-date", field(provisioner, "Build Date"))
        add("ci.runner.region", field(provisioner, "Azure Region"), Stability.VOLATILE)
        val runner = lines.firstNotNullOfOrNull { runnerVersion.find(it)?.groupValues?.get(1) }
        add("ci.runner.version", runner)
        for ((name, value) in jobEnvironment(lines)) add("env.$name", value)
        return found.values.toList()
    }

    private fun jobEnvironment(lines: List<String>): List<Pair<String, String>> {
        val run = lines.indexOfFirst { it.startsWith("##[group]Run ") }
        if (run < 0) return emptyList()
        val after = lines.drop(run + 1).take(GROUP_LIMIT * 4)
        val env = after.takeWhile { it != "##[endgroup]" }.indexOf("env:")
        if (env < 0) return emptyList()
        return after.drop(env + 1).takeWhile { it.startsWith("  ") }.mapNotNull {
            val name = it.trim().substringBefore(':')
            val value = it.trim().substringAfter(':', "").trim()
            if (name.isEmpty() || value.isEmpty() || value == "***") null else name to value
        }
    }
}
