package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.case.Render
import dev.gmitch215.drift.ci.GithubImport
import dev.gmitch215.drift.ci.GitlabImport
import dev.gmitch215.drift.ci.Import
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.studio.StudioState

enum class CiFormat(val label: String) {
    GITHUB("GitHub Actions"),
    GITLAB("GitLab CI"),
}

sealed interface CiResult {
    class Done(val state: StudioState, val plan: String) : CiResult {
        val separated: Boolean get() = state.relevant.isNotEmpty() && state.competing.isEmpty()
    }

    class Bad(val message: String, val askFormat: Boolean = false) : CiResult
}

const val CI_LIMIT =
    "No difference between the environments stands out as the cause. Drift compares the " +
        "environments of two runs, so it cannot find an ordinary code regression. If the code " +
        "changed between the two runs, check that change first."

/** Reads saved job logs the way `drift import` does, then ranks the two environments. */
object CiDiagnosis {
    const val MAX_LOG = 16 * 1024 * 1024
    private const val PASSING = "passing"
    private const val FAILING = "failing"
    private const val TRIAL_MINUTES = 5L

    fun detect(text: String): CiFormat? {
        val github = "##[group]" in text || "Current runner version: '" in text
        val gitlab = "Running with gitlab-runner" in text
        return when {
            github && !gitlab -> CiFormat.GITHUB
            gitlab && !github -> CiFormat.GITLAB
            else -> null
        }
    }

    fun read(file: PickedFile): Load<String> {
        val bytes = file.bytes
            ?: return Load.Bad("cannot read ${file.name}: ${file.problem ?: "no data"}.")
        if (bytes.size > MAX_LOG) {
            return Load.Bad("cannot read ${file.name}: it is larger than 16 MB.")
        }
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) {
            return Load.Bad("cannot read ${file.name}: it is not a text file.")
        }
        if (text.isBlank()) return Load.Bad("cannot read ${file.name}: the file is empty.")
        return Load.Ok(file.name, text)
    }

    fun run(passing: String, failing: String, format: CiFormat?, job: String?): CiResult {
        for ((side, text) in listOf(PASSING to passing, FAILING to failing)) {
            if (text.isBlank()) {
                return CiResult.Bad("The $side log is empty. Paste it or choose a file.")
            }
            if (text.length > MAX_LOG) return CiResult.Bad("The $side log is larger than 16 MB.")
        }
        val kinds = listOf(passing, failing).map { format ?: detect(it) }
        val unknown = listOf(PASSING, FAILING).filterIndexed { i, _ -> kinds[i] == null }
        if (unknown.isNotEmpty()) {
            val which = if (unknown.size == 2) "either log" else "the ${unknown.single()} log"
            return CiResult.Bad("Drift cannot tell which CI system made $which.", askFormat = true)
        }
        val (first, second) = kinds.map { it!! }
        if (first != second) {
            return CiResult.Bad(
                "The two logs come from different CI systems (${first.label} and " +
                    "${second.label}). Use two logs from the same system.",
            )
        }
        val name = job?.trim()?.takeIf { it.isNotEmpty() }
        val capsules = mutableListOf<Capsule>()
        for ((side, text) in listOf(PASSING to passing, FAILING to failing)) {
            val read = if (first == CiFormat.GITHUB) {
                GithubImport.log(text, side, name)
            } else {
                GitlabImport.trace(text, side)
            }
            val capsule = (read as? Import.Done)?.run?.capsule
                ?: return CiResult.Bad(noEnvironment(side, first, name))
            capsules += capsule
        }
        val state = StudioState.of(capsules[0], capsules[1])
        val ranking = Ranker.rank(capsules[0], capsules[1])
        val plan = Planner.from(
            ranking,
            Frame.shas(PASSING, FAILING),
            Options(TRIAL_MINUTES, failures = 1, runs = 1),
        )
        return CiResult.Done(state, Render.render(ranking, plan, Detail.DETAIL))
    }

    private fun noEnvironment(side: String, format: CiFormat, job: String?): String {
        val part = when {
            format == CiFormat.GITLAB -> "has no GitLab runner section"
            job != null -> "has no environment section for the job \"$job\""
            else -> "has no runner environment section"
        }
        val advice = when {
            format == CiFormat.GITLAB ->
                "Use the full job trace, from the line that starts with " +
                    "Running with gitlab-runner."

            job != null -> "Check the job name, or leave it empty."

            else -> "Use the full job log, starting at the Set up job step."
        }
        return "The $side log $part, so Drift has nothing to compare. $advice"
    }
}
