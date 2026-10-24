package dev.gmitch215.drift.scan.handsoff

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj

object HandsoffRender {
    const val SCHEMA = 1L

    private const val WHERE = "a rootless container, never the host"
    private const val NETWORK = "install step only; this plan makes no call and starts nothing"
    private val willNot = listOf(
        "pass secrets or the environment of this machine",
        "mount the home directory",
        "start the services the job names",
        "run anything on the host",
    )

    fun text(plan: HandsoffPlan): String {
        val out = StringBuilder()
        fun line(text: String = "") {
            out.append(text).append('\n')
        }
        val root = if (plan.root.isEmpty()) "" else " (root ${plan.root})"
        line("handsoff plan: ${plan.checkout}$root")
        line("files read: ${plan.readFiles.size}; nothing was run and no network call was made")
        line()
        line(
            if (plan.ready) {
                "result: ready"
            } else {
                "result: stopped, ${plan.stops.size} ${plural(plan.stops.size)} to answer"
            },
        )
        line()
        line("ecosystems")
        if (plan.ecosystems.isEmpty()) line("  none found")
        for (e in plan.ecosystems) ecosystem(e, ::line)
        val missing = plan.notRead.joinToString(", ")
        line(
            "not read: " + (if (missing.isEmpty()) "" else "found $missing; ") +
                "any ecosystem not listed above (for example Cargo, Gemfile, .NET project files)",
        )
        plan.ci?.let { ci ->
            line()
            ci(ci, ::line)
        }
        if (plan.ready) {
            line()
            ready(plan, ::line)
        } else {
            plan.stops.forEachIndexed { i, stop ->
                line()
                line("stop ${i + 1} of ${plan.stops.size}: ${stop.kind.id}")
                line("  what: ${stop.what}")
                line("  question: ${stop.question}")
                line("  answer with: ${stop.kind.flag}")
            }
            line()
            line("no plan is printed until these are answered; Drift does not guess past a stop")
        }
        return out.toString()
    }

    private fun plural(n: Int) = if (n == 1) "question" else "questions"

    private fun ecosystem(e: EcosystemFacts, line: (String) -> Unit) {
        line("  ${e.id}")
        line("    files        ${e.manifests.joinToString(", ")}")
        e.manager?.let {
            val from = e.managerSource?.let { s -> " (packageManager, $s)" }.orEmpty()
            line("    manager      $it$from")
        }
        line("    lockfiles    " + e.lockfiles.joinToString(", ").ifEmpty { "none" })
        for (pin in e.pins) {
            line("    pin          ${pin.tool} ${pin.value} (${pin.origin})")
        }
        e.install?.let { line("    install      ${it.command} (${it.why})") }
        for (w in e.workspaces) line("    workspace    ${w.pattern} (${w.source})")
        for (n in e.named) {
            val body = n.detail?.let { " = $it" }.orEmpty()
            val how = n.command?.let { " -> $it" } ?: " (no documented command)"
            line("    ${n.kind.padEnd(KIND)} ${n.name}$body (${n.source})$how")
        }
        for (note in e.notes) line("    note         $note")
    }

    private fun ci(ci: CiFacts, line: (String) -> Unit) {
        line("ci")
        line("  files        " + ci.files.joinToString(", ").ifEmpty { "none found" })
        ci.case?.let { line("  case         ${it.dir} (failing job: ${it.job})") }
        val job = ci.job
        if (job == null) {
            line("  job          none read; jobs found: ${ci.jobsFound.size}")
            ci.jobsFound.forEach { line("    $it") }
        } else {
            line("  job          ${job.id} (${job.file}:${job.line})")
            if (job.cell.isNotEmpty()) {
                line("  cell         " + job.cell.joinToString(", ") { "${it.first}=${it.second}" })
            }
            line("  runs on      " + (job.runner ?: "not set"))
            line("  image        " + (job.image ?: "not set"))
            val services = job.services.joinToString(", ") { "${it.name} (line ${it.line})" }
            line("  services     " + services.ifEmpty { "none" })
            if (job.actions.isNotEmpty()) line("  actions, not run")
            for (a in job.actions) {
                val with = a.with.joinToString(", ") {
                    "${it.first}: ${it.second.lines().joinToString(" ") { l -> l.trim() }}"
                }
                line("    ${a.source}  ${a.uses}" + if (with.isEmpty()) "" else "  ($with)")
            }
            line("  steps, in order")
            if (job.steps.isEmpty()) line("    none")
            for (s in job.steps) steps(s, line)
            for (note in job.notes) line("  note         $note")
        }
        for (note in ci.notes) line("  note         $note")
    }

    private fun steps(s: CiStep, line: (String) -> Unit) {
        val head = s.name?.let { " [$it]" }.orEmpty()
        val cond = s.condition?.let { " if: $it" }.orEmpty()
        val first = s.command.lines()
        line("    step ${s.index}$head  ${first.first()}  (${s.source})$cond")
        first.drop(1).forEach { line("       $it") }
    }

    private fun ready(plan: HandsoffPlan, line: (String) -> Unit) {
        line("plan")
        line("  would run, in order")
        plan.commands.forEachIndexed { i, c ->
            val rows = c.command.lines()
            line("    ${i + 1}. ${rows.first()}  (${c.source})")
            rows.drop(1).forEach { line("       $it") }
        }
        line("  where        $WHERE")
        line("  image        " + imageText(plan))
        line("  network      $NETWORK")
        line("  will not")
        willNot.forEach { line("    $it") }
        line("  by hand")
        plan.byHand.forEach { line("    $it") }
    }

    private fun imageText(plan: HandsoffPlan): String = when {
        plan.image != null -> "${plan.image} (${plan.imageBasis})"
        else -> "not chosen (--from-ci CASE builds one from the failing capsule)"
    }

    // #region json

    fun json(plan: HandsoffPlan): JsonObject = obj(
        "schema" to JsonInt(SCHEMA),
        "checkout" to JsonString(plan.checkout),
        "root" to JsonString(plan.root),
        "ready" to JsonBool(plan.ready),
        "readFiles" to strings(plan.readFiles),
        "ecosystems" to JsonArray(plan.ecosystems.map(::ecosystem)),
        "notRead" to strings(plan.notRead),
        "ci" to (plan.ci?.let(::ci) ?: jsonOrNull(null)),
        "stops" to JsonArray(
            plan.stops.map {
                obj(
                    "id" to JsonString(it.kind.id),
                    "what" to JsonString(it.what),
                    "question" to JsonString(it.question),
                    "flag" to JsonString(it.kind.flag),
                )
            },
        ),
        "plan" to (if (plan.ready) planJson(plan) else jsonOrNull(null)),
    )

    private fun planJson(plan: HandsoffPlan) = obj(
        "commands" to JsonArray(
            plan.commands.map {
                obj("command" to JsonString(it.command), "source" to JsonString(it.source))
            },
        ),
        "where" to JsonString(WHERE),
        "image" to jsonOrNull(plan.image),
        "imageBasis" to jsonOrNull(plan.imageBasis),
        "network" to JsonString(NETWORK),
        "willNot" to strings(willNot),
        "byHand" to strings(plan.byHand),
    )

    private fun strings(items: List<String>) = JsonArray(items.map { JsonString(it) })

    private fun source(s: Source?): JsonValue = jsonOrNull(s?.toString())

    private fun ecosystem(e: EcosystemFacts) = obj(
        "id" to JsonString(e.id),
        "files" to strings(e.manifests.map { "$it" }),
        "manager" to jsonOrNull(e.manager),
        "managerSource" to source(e.managerSource),
        "lockfiles" to strings(e.lockfiles.map { "$it" }),
        "pins" to JsonArray(
            e.pins.map {
                obj(
                    "tool" to JsonString(it.tool),
                    "value" to JsonString(it.value),
                    "label" to JsonString(it.label),
                    "source" to source(it.source),
                )
            },
        ),
        "install" to (
            e.install?.let {
                obj("command" to JsonString(it.command), "why" to JsonString(it.why))
            } ?: jsonOrNull(null)
            ),
        "workspaces" to JsonArray(
            e.workspaces.map {
                obj("pattern" to JsonString(it.pattern), "source" to source(it.source))
            },
        ),
        "named" to JsonArray(
            e.named.map {
                obj(
                    "kind" to JsonString(it.kind),
                    "name" to JsonString(it.name),
                    "detail" to jsonOrNull(it.detail),
                    "command" to jsonOrNull(it.command),
                    "source" to source(it.source),
                )
            },
        ),
        "notes" to strings(e.notes),
    )

    private fun ci(ci: CiFacts) = obj(
        "files" to strings(ci.files),
        "jobsFound" to strings(ci.jobsFound),
        "case" to (
            ci.case?.let {
                obj(
                    "dir" to JsonString(it.dir),
                    "job" to JsonString(it.job),
                    "image" to jsonOrNull(it.image),
                )
            } ?: jsonOrNull(null)
            ),
        "job" to (ci.job?.let(::job) ?: jsonOrNull(null)),
        "notes" to strings(ci.notes),
    )

    private fun job(job: CiJobFacts) = obj(
        "provider" to JsonString(job.provider),
        "file" to JsonString(job.file),
        "id" to JsonString(job.id),
        "line" to JsonInt(job.line.toLong()),
        "runner" to jsonOrNull(job.runner),
        "image" to jsonOrNull(job.image),
        "cell" to JsonArray(
            job.cell.map { obj("key" to JsonString(it.first), "value" to JsonString(it.second)) },
        ),
        "services" to JsonArray(
            job.services.map {
                obj("name" to JsonString(it.name), "image" to jsonOrNull(it.image))
            },
        ),
        "actions" to JsonArray(
            job.actions.map {
                obj(
                    "uses" to JsonString(it.uses),
                    "source" to source(it.source),
                    "with" to JsonArray(
                        it.with.map { w ->
                            obj("key" to JsonString(w.first), "value" to JsonString(w.second))
                        },
                    ),
                )
            },
        ),
        "steps" to JsonArray(
            job.steps.map {
                obj(
                    "index" to JsonInt(it.index.toLong()),
                    "name" to jsonOrNull(it.name),
                    "command" to JsonString(it.command),
                    "source" to source(it.source),
                    "directory" to jsonOrNull(it.directory),
                    "condition" to jsonOrNull(it.condition),
                )
            },
        ),
        "notes" to strings(job.notes),
    )

    // #endregion

    private const val KIND = 12
}
