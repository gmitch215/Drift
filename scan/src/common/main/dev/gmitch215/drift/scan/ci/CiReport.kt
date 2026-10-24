package dev.gmitch215.drift.scan.ci

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.case.Render
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.rank.Ranking
import dev.gmitch215.drift.rank.Tier

/** What `drift ci` compared and found; [dir] is where the case and the capsules were written. */
class CiReport(
    val repo: CiRepo,
    val provider: CiProviderKind,
    val failing: CiRunInfo,
    val passing: CiRunInfo,
    val job: String,
    val failingStep: String?,
    val otherFailedJobs: List<String>,
    val errors: List<String>,
    val passingCapsule: Capsule,
    val failingCapsule: Capsule,
    val ranking: Ranking,
    val plan: Plan,
    val dir: String,
    val shownDir: String,
    val ignoreLine: String?,
) {
    private val passingLabel get() = passingCapsule.label
    private val failingLabel get() = failingCapsule.label
    private val top get() = ranking.candidates.firstOrNull()

    /** True when the best candidate stands alone and a rule or probe supports it. */
    val namesCause: Boolean
        get() = top?.let { it.bundle == null && it.tier >= Tier.PLAUSIBLE } ?: false

    fun text(detail: Detail?): String {
        val out = mutableListOf<String>()
        out += "drift ci: ${repo.path}, ${repo.branch?.let { "branch $it, " }.orEmpty()}" +
            "HEAD ${repo.head.take(SHORT)}"
        if (failing.sha != repo.head) {
            out += "Note: the failing run is for ${failing.sha.take(SHORT)}, not HEAD."
        }
        out += ""
        out += labelled("Failing", "${failing.title} (${describe(failing)})")
        out += labelled("", failing.url)
        out += labelled("Passing", "${passing.title} (${describe(passing)})")
        out += labelled("", passing.url)
        val step = failingStep?.let { ", failed in step $it" }.orEmpty()
        out += labelled("Job", "$job$step")
        if (otherFailedJobs.isNotEmpty()) {
            val others = "Other failed jobs: ${otherFailedJobs.joinToString(", ")}. " +
                "Pass --job NAME to compare one of them."
            out += paragraph(others, LABEL.length)
        }
        if (errors.isNotEmpty()) {
            out += paragraph("First errors in the log: ${errors.joinToString("; ")}", 0)
        }
        out += ""
        out += environmentLines()
        out += ""
        out += paragraph("Result: ${Render.summary(ranking.toJson())}", 0)
        out += paragraph("Confidence: ${confidence()}", 0)
        if (!namesCause) {
            out += ""
            out += paragraph(limits(), 0)
        }
        out += ""
        out += "Saved to $shownDir"
        out += "Next: drift case show $shownDir"
        out += "Then: drift next $shownDir"
        if (!namesCause) {
            out += ""
            out += rerun()
        }
        if (ignoreLine != null) {
            out += ""
            out += "git does not ignore this directory; add this line to .gitignore:"
            out += "  $ignoreLine"
        }
        val tail = if (detail == null) "" else "\n" + Render.render(ranking, plan, detail)
        return out.joinToString("\n") + "\n" + tail
    }

    fun toJson(): JsonObject = obj(
        "schema" to JsonInt(SCHEMA),
        "provider" to JsonString(provider.id),
        "repository" to obj(
            "host" to JsonString(repo.host),
            "path" to JsonString(repo.path),
            "branch" to jsonOrNull(repo.branch),
            "head" to JsonString(repo.head),
        ),
        "failing" to run(failing),
        "passing" to run(passing),
        "job" to JsonString(job),
        "otherFailedJobs" to JsonArray(otherFailedJobs.map(::JsonString)),
        "namesCause" to JsonBool(namesCause),
        "directory" to JsonString(shownDir),
        "ranking" to ranking.toJson(),
    )

    private fun run(run: CiRunInfo) = obj(
        "id" to JsonString(run.id),
        "workflow" to JsonString(run.workflow),
        "sha" to JsonString(run.sha),
        "branch" to JsonString(run.branch),
        "url" to JsonString(run.url),
        "createdAt" to JsonString(run.createdAt),
    )

    private fun describe(run: CiRunInfo): String {
        val branch = if (run.branch.isEmpty()) "" else ", branch ${run.branch}"
        return "${run.sha.take(SHORT)}, ${run.createdAt.take(DATE)}$branch"
    }

    private fun environmentLines(): List<String> {
        val ranked = ranking.candidates
        if (ranked.isEmpty()) {
            return listOf(
                "The two jobs show the same environment (${failingCapsule.attributes.size} " +
                    "attributes read from each log).",
            )
        }
        val before = passingCapsule.attributes.associate { it.path to it.value }
        val after = failingCapsule.attributes.associate { it.path to it.value }
        val shown = ranked.take(SUSPECTS)
        val head = "Environment differences, best first (${shown.size} of ${ranked.size}):"
        return listOf(head) + shown.mapIndexed { i, c ->
            "  ${i + 1}. ${c.path}: ${before[c.path] ?: "(absent)"} -> " +
                "${after[c.path] ?: "(absent)"} (tier ${c.tier.name.lowercase()})"
        }
    }

    private fun confidence(): String {
        val best = top ?: return "none, the logs show no environment difference."
        val tied = best.bundle?.let { id -> ranking.bundles.firstOrNull { it.id == id } }
        return when {
            tied != null ->
                "low, ${tied.members.size} changes tie and the data cannot separate them."

            best.tier == Tier.KNOWN -> "high, a rule with verified symptoms matches this change."

            best.tier == Tier.PLAUSIBLE ->
                "moderate, a rule or probe links this change to the failure; nothing was run."

            best.tier == Tier.CORRELATED -> "low, the change correlates with the failure only."

            else -> "low, the value changed and nothing else points at it."
        }
    }

    private fun limits(): String {
        val code = if (passing.sha == failing.sha) {
            ""
        } else {
            " Read the code change next: git diff --stat ${passing.sha.take(SHORT)} " +
                failing.sha.take(SHORT)
        }
        return "This compares the runner environment of the two jobs, not your code. It cannot " +
            "find an ordinary code regression, so a failure that comes from a code change " +
            "shows up as no cause or a weak difference.$code"
    }

    private fun rerun(): List<String> {
        val base = "$shownDir/"
        val back = repo.branch?.let { listOf("  git switch $it") }.orEmpty()
        return listOf(
            "To compare instrumented captures of both commits, run your test command on each",
            "(commit or stash local changes first):",
            "  git switch --detach ${passing.sha.take(SHORT)}",
            "  drift run --tools --project --label $passingLabel --out ${base}passing-local " +
                "-- TEST_COMMAND",
            "  git switch --detach ${failing.sha.take(SHORT)}",
            "  drift run --tools --project --label $failingLabel --out ${base}failing-local " +
                "-- TEST_COMMAND",
        ) + back + listOf(
            "  drift diagnose ${base}passing-local ${base}failing-local --detail summary",
            "TEST_COMMAND is what the failing step ran" +
                (failingStep?.let { " (step $it)" }.orEmpty()) + "; drift does not guess it.",
        )
    }

    private fun labelled(label: String, text: String) = label.padEnd(LABEL.length) + text

    private fun paragraph(text: String, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        val lines = mutableListOf<String>()
        var line = pad
        for (word in text.split(' ')) {
            if (line.length > indent && line.length + 1 + word.length > WIDTH) {
                lines += line
                line = pad
            }
            line += if (line.length > indent) " $word" else word
        }
        return lines + line
    }

    private companion object {
        const val LABEL = "Passing  "
        const val SHORT = 7
        const val DATE = 10
        const val SUSPECTS = 3
        const val WIDTH = 100
        const val SCHEMA = 1L
    }
}
