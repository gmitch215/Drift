package dev.gmitch215.drift.diff

/**
 * Environment variables that name one CI run, job or commit, so they differ on every run of the
 * same job. They stay in the capsule but never rank as a cause.
 *
 * GitHub: https://docs.github.com/en/actions/reference/workflows-and-actions/variables
 * GitLab: https://docs.gitlab.com/ci/variables/predefined_variables/
 *
 * `CI_JOB_*` is listed key by key: CI_JOB_NAME, CI_JOB_IMAGE, CI_JOB_STAGE and CI_JOB_TAGS are
 * stable and can be real causes.
 */
object CiVolatile {
    private val keys = setOf(
        "RUNNER_TEMP",
        "RUNNER_TRACKING_ID",
        "RUNNER_NAME",
        "CI_JOB_ID",
        "CI_JOB_URL",
        "CI_JOB_TOKEN",
        "CI_JOB_STARTED_AT",
        "CI_JOB_STARTED_AT_SLUG",
        "CI_JOB_RETRY_COUNT",
    )

    private val prefixes = listOf(
        "GITHUB_",
        "ACTIONS_",
        "INPUT_",
        "STATE_",
        "CI_PIPELINE_",
        "CI_COMMIT_",
        "CI_MERGE_REQUEST_",
        "CI_BUILD_",
    )

    fun covers(path: String): Boolean {
        if (!path.startsWith("env.")) return false
        val name = path.removePrefix("env.")
        return name in keys || prefixes.any { name.startsWith(it) }
    }
}
