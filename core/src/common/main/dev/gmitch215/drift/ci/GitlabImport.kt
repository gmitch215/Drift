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
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.symptom.SymptomExtractor

/**
 * Reads GitLab CI output that was saved to a file: a job trace and the pipeline JSON (the
 * pipeline object, an object with a `jobs` array, or the jobs array alone). The runner version,
 * executor and image in the trace become capsule attributes with source `trace`.
 */
object GitlabImport {
    private const val SOURCE = "trace"
    private const val LINE_LIMIT = 4096
    private val runner = Regex("Running with gitlab-runner (\\S+)")
    private val executor = Regex("Preparing the \"([^\"]+)\" executor")
    private val image = Regex("executor with image (\\S+)")
    private val digest = Regex("for (\\S+) with digest (\\S+)")
    private val stamp = Regex("^\\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z \\d\\d[OE][+ ]")

    /** The outcome comes from the final `Job succeeded` or `ERROR: Job failed` line, if any. */
    fun trace(text: String, id: String): Import {
        val lines = lines(text)
        val found = linkedMapOf<String, Attribute>()

        fun add(path: String, pattern: Regex) {
            val value = lines.firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1) }
            if (!value.isNullOrEmpty()) {
                found[path] = Redactor.redact(Attribute(path, value, SOURCE))
            }
        }

        add("ci.runner.version", runner)
        add("ci.runner.executor", executor)
        add("ci.runner.image", image)
        val jobImage = found["ci.runner.image"]?.value
        val jobDigest = lines.firstNotNullOfOrNull {
            digest.find(it)?.groupValues?.takeIf { g -> g[1] == jobImage }?.get(2)
        }
        if (!jobDigest.isNullOrEmpty()) {
            found["ci.runner.image.digest"] =
                Redactor.redact(Attribute("ci.runner.image.digest", jobDigest, SOURCE))
        }
        if (found.isEmpty()) {
            return Import.Refused(
                "no runner environment found (expected a gitlab-runner header and executor lines)",
            )
        }
        val outcome = when {
            lines.any { it.startsWith("ERROR: Job failed") } -> RunOutcome.FAIL
            lines.any { it.startsWith("Job succeeded") } -> RunOutcome.PASS
            else -> RunOutcome.UNKNOWN
        }
        val capsule = Capsule(id, found.values.sortedBy { it.path })
        val symptoms = SymptomExtractor.extract(lines.joinToString("\n"))
        return Import.Done(Run(id, outcome, emptyList(), symptoms, capsule))
    }

    /** The outcome and the jobs (as steps, with the stage as the job) of the pipeline JSON. */
    fun summary(text: String): Import {
        val root = try {
            LooseJson.parse(text)
        } catch (e: JsonException) {
            return Import.Refused("not valid JSON: ${e.message}")
        }
        val pipeline = root as? JsonObject
        val list = (root as? JsonArray) ?: pipeline?.fields?.get("jobs") as? JsonArray
        val jobs = list?.items.orEmpty().mapNotNull { it as? JsonObject }
        if (pipeline?.fields?.containsKey("status") != true && jobs.isEmpty()) {
            return Import.Refused("no pipeline status or jobs in the GitLab JSON")
        }
        val steps = jobs.mapNotNull { job ->
            val name = text(job, "name") ?: return@mapNotNull null
            Step(
                text(job, "stage"),
                name,
                stepConclusion(text(job, "status")),
                LooseJson.millis(job.fields["duration"]),
            )
        }
        val outcome = when {
            pipeline?.fields?.containsKey("status") == true -> outcome(text(pipeline, "status"))

            steps.any { it.conclusion == StepConclusion.FAILURE } -> RunOutcome.FAIL

            steps.isNotEmpty() && steps.all { it.conclusion == StepConclusion.SUCCESS } ->
                RunOutcome.PASS

            else -> RunOutcome.UNKNOWN
        }
        val id = when (val raw = pipeline?.fields?.get("id")) {
            is JsonInt -> raw.value.toString()
            is JsonString -> raw.value
            else -> "pipeline"
        }
        return Import.Done(Run(id, outcome, steps))
    }

    /** The trace, plus the outcome and jobs of [summary] when it is given. */
    fun run(trace: String, summary: String?, id: String): Import {
        val read = trace(trace, id)
        if (summary == null || read !is Import.Done) return read
        return when (val parsed = summary(summary)) {
            is Import.Refused -> parsed

            is Import.Done ->
                Import.Done(read.run.copy(outcome = parsed.run.outcome, steps = parsed.run.steps))
        }
    }

    private fun text(o: JsonObject, key: String): String? = (o.fields[key] as? JsonString)?.value

    private fun outcome(status: String?): RunOutcome = when (status) {
        "success" -> RunOutcome.PASS
        "failed" -> RunOutcome.FAIL
        "canceled", "canceling" -> RunOutcome.CANCELLED
        else -> RunOutcome.UNKNOWN
    }

    private fun stepConclusion(status: String?): StepConclusion = when (status) {
        "success" -> StepConclusion.SUCCESS
        "failed" -> StepConclusion.FAILURE
        "skipped" -> StepConclusion.SKIPPED
        "canceled", "canceling" -> StepConclusion.CANCELLED
        else -> StepConclusion.UNKNOWN
    }

    private fun lines(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n')
        .lineSequence()
        .map { SymptomExtractor.stripAnsi(it.take(LINE_LIMIT).replace(stamp, "")).trim() }
        .filterNot { it.startsWith("section_start:") || it.startsWith("section_end:") }
        .toList()
}
