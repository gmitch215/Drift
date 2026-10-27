package dev.gmitch215.drift.scan.ci

import dev.gmitch215.drift.case.CaseBuilder
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.CaseWrite
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.CapsuleDirectory
import dev.gmitch215.drift.ci.Import
import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Ranking
import dev.gmitch215.drift.symptom.SymptomKind

/** Every field is optional: with none set, the failing run of the current branch is used. */
data class CiRequest(
    val remote: String = "origin",
    val provider: CiProviderKind? = null,
    val commit: String? = null,
    val good: String? = null,
    val run: String? = null,
    val job: String? = null,
    val out: String? = null,
)

/** What [CiCheck.fetch] read: the failing run, a passing run if any, and their job logs. */
class CiRuns(
    val repo: CiRepo,
    val provider: CiProviderKind,
    val failing: CiRunInfo,
    val passing: CiRunInfo?,
    val job: CiJobInfo,
    val jobs: List<CiJobInfo>,
    val red: Run,
    val green: Run?,
    val failingLabel: String,
    val passingLabel: String?,
)

sealed interface CiOutcome {
    data class Compared(val report: CiReport) : CiOutcome

    data class Stopped(val kind: CiProblemKind, val message: String) : CiOutcome
}

/**
 * Finds the failing run of the checkout and a passing run of the same workflow, reads the runner
 * environment of the same job in both through the provider's tool, and diagnoses the pair with
 * the same ranking, plan and case that `drift diagnose --case` writes.
 */
object CiCheck {
    fun run(host: Host, files: CaseFiles, request: CiRequest): CiOutcome = try {
        CiOutcome.Compared(compare(host, files, request))
    } catch (e: CiStop) {
        CiOutcome.Stopped(e.kind, e.message.orEmpty())
    }

    /**
     * Finds the failing run and reads its job log into a [Run]. With [needPassing] false a
     * checkout with no earlier passing run still answers, with [CiRuns.passing] null.
     */
    fun fetch(host: Host, request: CiRequest, needPassing: Boolean = true): CiRuns {
        validate(request)
        if (Capability.PROCESS !in host.capabilities) {
            throw CiStop(
                CiProblemKind.MISSING_TOOL,
                "this build cannot start other programs, and drift ci needs git and the " +
                    "provider's command line tool",
            )
        }
        val repo = CiRepos.detect(host, request.remote)
        val kind = request.provider ?: CiRepos.providerFor(repo.host, host.env())
            ?: throw CiStop(
                CiProblemKind.UNSUPPORTED_HOST,
                "drift ci supports GitHub (github.com, or the host in GH_HOST) and GitLab " +
                    "(gitlab.com, or the host in GITLAB_HOST), and ${repo.host} is neither. " +
                    "Save the failing and a passing job log and use drift import, then " +
                    "drift diagnose.",
            )
        val provider = when (kind) {
            CiProviderKind.GITHUB -> GithubCi(host, repo.host)
            CiProviderKind.GITLAB -> GitlabCi(host, repo.host)
        }
        val failing = provider.failedRun(repo, repo.branch, request.commit, request.run)
        val passing = if (needPassing) {
            provider.passingRun(repo, failing, request.good)
        } else {
            try {
                provider.passingRun(repo, failing, request.good)
            } catch (e: CiStop) {
                if (e.kind != CiProblemKind.NO_PASSING_RUN) throw e
                null
            }
        }
        val redSummary = provider.summary(repo, failing)
        val redJobs = provider.jobs(redSummary)
        val redJob = failingJob(redJobs, request.job, failing)
        val sameCommit = failing.sha == passing?.sha
        val redLabel = failing.sha.take(SHORT) + if (sameCommit) "@${failing.id}" else ""
        val redLog = provider.log(repo, failing, redJob)
        val red = imported(provider, redLog, redSummary, redLabel, redJob, failing)
        var green: Run? = null
        var greenLabel: String? = null
        if (passing != null) {
            val greenSummary = provider.summary(repo, passing)
            val greenJobs = provider.jobs(greenSummary)
            val greenJob = greenJobs.filter { it.name == redJob.name }
                .let { same -> same.lastOrNull { !it.failed } ?: same.lastOrNull() }
                ?: throw CiStop(
                    CiProblemKind.NO_JOB,
                    "the passing run ${passing.id} has no job named ${redJob.name}; its jobs " +
                        "are ${greenJobs.joinToString(", ") { it.name }}. Pass --good to " +
                        "choose another passing run or --job to compare another job.",
                )
            val greenLog = provider.log(repo, passing, greenJob)
            greenLabel = passing.sha.take(SHORT) + if (sameCommit) "@${passing.id}" else ""
            green = imported(provider, greenLog, greenSummary, greenLabel, greenJob, passing)
        }
        return CiRuns(
            repo,
            kind,
            failing,
            passing,
            redJob,
            redJobs,
            red,
            green,
            redLabel,
            greenLabel,
        )
    }

    private fun compare(host: Host, files: CaseFiles, request: CiRequest): CiReport {
        val runs = fetch(host, request)
        val repo = runs.repo
        val failing = runs.failing
        val passing = runs.passing!!
        val redJob = runs.job
        val redJobs = runs.jobs
        val redLabel = runs.failingLabel
        val greenLabel = runs.passingLabel!!
        val red = runs.red
        val green = runs.green!!
        val redCapsule = red.capsule!!
        val greenCapsule = green.capsule!!

        val ranking = Ranker.rank(greenCapsule, redCapsule, Rules.all)
        val plan = Planner.from(
            ranking,
            Frame.shas(greenLabel, redLabel),
            Options(TRIAL_MINUTES, 1, 1),
        )
        val dir = (request.out ?: "${repo.root}/.drift/ci/${failing.sha.take(SHORT)}").trimEnd('/')
        write(files, dir, greenLabel to green, redLabel to red, ranking, plan)
        return CiReport(
            repo,
            runs.provider,
            failing,
            passing,
            redJob.name,
            failingStep(red, redJob.name),
            redJobs.filter { it.failed && it.id != redJob.id }.map { it.name }.distinct(),
            red.symptoms.filter { it.kind == SymptomKind.SIGNATURE }.take(ERRORS).map { it.value },
            greenCapsule,
            redCapsule,
            ranking,
            plan,
            dir,
            shown(host, repo, dir),
            ignoreLine(host, repo, dir, request.out),
        )
    }

    private fun validate(request: CiRequest) {
        fun usage(text: String): Nothing = throw CiStop(CiProblemKind.USAGE, text)
        if (request.run != null && request.commit != null) {
            usage("--run and --commit both name the failing run; pass one")
        }
        request.run?.let { if (!isRunId(it)) usage("--run takes a run id of digits, got $it") }
        request.commit?.let {
            if (!isSha(it)) usage("--commit takes a commit hash of 4 to 40 hex digits, got $it")
        }
        request.good?.let {
            if (!isRunId(it) && !isSha(it)) {
                usage("--good takes a commit hash of 4 to 40 hex digits or a run id, got $it")
            }
        }
    }

    private fun isSha(text: String) = text.length in MIN_SHA..MAX_SHA &&
        text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun failingJob(jobs: List<CiJobInfo>, name: String?, run: CiRunInfo): CiJobInfo {
        if (name != null) {
            return jobs.firstOrNull { it.name == name } ?: throw CiStop(
                CiProblemKind.NO_JOB,
                "the failing run ${run.id} has no job named $name; its jobs are " +
                    jobs.joinToString(", ") { it.name },
            )
        }
        return jobs.firstOrNull { it.failed } ?: throw CiStop(
            CiProblemKind.NO_JOB,
            "the failing run ${run.id} has no failed job to read (it may have failed before " +
                "any job started); open ${run.url} to see why",
        )
    }

    private fun imported(
        provider: CiProvider,
        log: String,
        summary: String,
        label: String,
        job: CiJobInfo,
        run: CiRunInfo,
    ): Run = when (val result = provider.import(log, summary, label, job.name)) {
        is Import.Done -> result.run.takeIf { it.capsule != null }
        is Import.Refused -> null
    } ?: throw CiStop(
        CiProblemKind.UNREADABLE,
        "cannot read the runner environment from the log of ${job.name} in run ${run.id}; " +
            "the log may be cut short or from a runner that prints no environment block",
    )

    private fun failingStep(run: Run, job: String): String? = run.steps.firstOrNull {
        it.conclusion == StepConclusion.FAILURE && (it.job == job || it.name == job)
    }?.name?.takeIf { it != job }

    private fun write(
        files: CaseFiles,
        dir: String,
        green: Pair<String, Run>,
        red: Pair<String, Run>,
        ranking: Ranking,
        plan: Plan,
    ) {
        for ((name, side) in listOf("passing" to green.second, "failing" to red.second)) {
            val capsule = side.capsule!!
            val failed = CapsuleDirectories.write(
                files,
                "$dir/capsules/$name",
                CapsuleDirectory(capsule, side.copy(capsule = null)),
            )
            if (failed != null) {
                throw CiStop(CiProblemKind.WRITE, "cannot write $failed; check the directory")
            }
        }
        val built = CaseBuilder.build(
            "${green.first} to ${red.first}",
            green.second.capsule!!,
            red.second.capsule!!,
            ranking,
            plan,
        )
        val result = CaseStore.write(files, dir, built)
        if (result is CaseWrite.Failed) {
            throw CiStop(CiProblemKind.WRITE, "cannot write ${result.path}; check the directory")
        }
    }

    private fun shown(host: Host, repo: CiRepo, dir: String): String {
        val here = host.env()["PWD"]?.trimEnd('/')
        return if (here == repo.root && dir.startsWith(repo.root + "/")) {
            dir.removePrefix(repo.root + "/")
        } else {
            dir
        }
    }

    private fun ignoreLine(host: Host, repo: CiRepo, dir: String, out: String?): String? {
        if (!dir.startsWith(repo.root + "/")) return null
        val result = host.run(listOf("git", "check-ignore", "-q", "$dir/case.json"))
        if (result?.exitCode != 1) return null
        return if (out == null) ".drift/" else dir.removePrefix(repo.root + "/") + "/"
    }

    private const val SHORT = 7
    private const val ERRORS = 2
    private const val TRIAL_MINUTES = 5L
    private const val MIN_SHA = 4
    private const val MAX_SHA = 40
}
