package dev.gmitch215.drift.scan.handsoff

/** The job to read: [name] is what the person typed or what the failing capsule names. */
internal class WantedJob(val name: String, val fromCase: Boolean)

internal class CiSelection(val facts: CiFacts, val stops: List<Stop>, val readFiles: List<String>)

/**
 * Reads GitHub workflow files and `.gitlab-ci.yml` for one job: its steps in order, `setup-*`
 * actions, the chosen matrix cell, the image or runner label and the services. Nothing is
 * evaluated except `matrix.*` expressions, and nothing is run.
 */
internal object CiConfig {
    private const val GITLAB_FILE = ".gitlab-ci.yml"
    private const val WORKFLOWS = ".github/workflows"
    private val matrixRef = Regex("\\$\\{\\{\\s*matrix\\.([A-Za-z0-9_-]+)\\s*}}")
    private val reserved = setOf(
        "stages", "variables", "default", "include", "workflow", "image", "services",
        "before_script", "after_script", "cache", "pages",
    )

    private class Candidate(
        val provider: String,
        val file: String,
        val id: String,
        val body: YMap,
        val root: YMap,
        val line: Int,
    ) {
        val name: String? = body["name"].scalar()
    }

    fun read(
        checkout: Checkout,
        request: HandsoffRequest,
        wanted: WantedJob?,
        case: CiCase?,
    ): CiSelection {
        val notes = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val files = workflowFiles(checkout, request, notes)
        val candidates = mutableListOf<Candidate>()
        for (file in files) {
            val text = checkout.read(file) ?: continue
            seen += file
            val doc = YamlLite.parse(text)
            val root = doc.root.map()
            if (doc.gaps.isNotEmpty() || root == null) {
                val gap = doc.gaps.firstOrNull()
                notes += if (gap == null) {
                    "$file is not a mapping and was not read"
                } else {
                    "$file was not read: line ${gap.line} uses ${gap.what}, which this reader " +
                        "does not follow"
                }
                continue
            }
            candidates += if (file == GITLAB_FILE) {
                gitlabJobs(file, root)
            } else {
                githubJobs(file, root)
            }
        }
        val listing = candidates.map { "${it.file}: ${it.id}" }
        val base = CiFacts(null, listing, files, case, notes)
        if (wanted == null) return CiSelection(base, emptyList(), seen.sorted())
        val matches = candidates.filter { matches(it, wanted) }
        val stop = when {
            matches.isEmpty() -> Stop(
                StopKind.CI_CONFIG,
                "no job matches \"${wanted.name}\" in ${files.ifEmpty { listOf("any CI file") }
                    .joinToString(", ")}" + notes.joinToString("") { "; $it" },
                "Which job? Jobs found: ${listing.joinToString(", ").ifEmpty { "none" }}.",
            )

            matches.size > 1 -> Stop(
                StopKind.CI_CONFIG,
                "\"${wanted.name}\" matches ${matches.size} jobs: " +
                    matches.joinToString(", ") { "${it.file}: ${it.id}" },
                "Which file? Pass --workflow with one of them.",
            )

            else -> null
        }
        if (stop != null) return CiSelection(base, listOf(stop), seen.sorted())
        val chosen = matches.single()
        return job(chosen, request, wanted, base, seen.sorted())
    }

    private fun workflowFiles(
        checkout: Checkout,
        request: HandsoffRequest,
        notes: MutableList<String>,
    ): List<String> {
        request.workflow?.let { return listOf(it) }
        val files = mutableListOf<String>()
        if (checkout.read(GITLAB_FILE) != null) files += GITLAB_FILE
        val listed = checkout.list(WORKFLOWS)
        if (listed == null) {
            notes += "$WORKFLOWS could not be listed on this host; pass --workflow FILE"
        } else {
            files += listed.filter { it.endsWith(".yml") || it.endsWith(".yaml") }
                .map { "$WORKFLOWS/$it" }
        }
        return files
    }

    // #region choosing

    private fun githubJobs(file: String, root: YMap): List<Candidate> =
        root["jobs"].map()?.entries.orEmpty().mapNotNull { entry ->
            (entry.value as? YMap)?.let {
                Candidate("github", file, entry.key, it, root, entry.line)
            }
        }

    private fun gitlabJobs(file: String, root: YMap): List<Candidate> =
        root.entries.filter { it.key !in reserved && !it.key.startsWith(".") }.mapNotNull {
            val body = it.value as? YMap ?: return@mapNotNull null
            val runs = body["script"] != null || body["extends"] != null || body["trigger"] != null
            if (runs) Candidate("gitlab", file, it.key, body, root, it.line) else null
        }

    private fun matches(job: Candidate, wanted: WantedJob): Boolean {
        val name = wanted.name
        if (name == job.id) return true
        val template = job.name
        if (template != null && (name == template || fits(template, name))) return true
        if (!wanted.fromCase) return false
        val base = name.substringBefore(" (").substringBefore(": [").trim()
        return base == job.id || (template != null && fits(template, base))
    }

    private fun fits(template: String, actual: String): Boolean {
        if ("\${{" !in template) return false
        val parts = template.split(Regex("\\$\\{\\{.*?}}"))
        val pattern = parts.joinToString(".*") { Regex.escape(it) }
        return Regex("^$pattern$").matches(actual)
    }

    // #endregion

    // #region matrix

    private class Matrix(
        val axes: Map<String, List<String>>,
        val include: List<Map<String, String>>,
        val exclude: List<Map<String, String>>,
    ) {
        fun cells(): List<Map<String, String>> {
            var cells = listOf<Map<String, String>>(emptyMap())
            for ((key, values) in axes) {
                cells = cells.flatMap { cell -> values.map { cell + (key to it) } }
            }
            if (axes.isEmpty()) return include
            cells = cells.filter { cell ->
                exclude.none { ex -> ex.all { cell[it.key] == it.value } }
            }
            val out = cells.toMutableList()
            for (extra in include) {
                val shared = extra.filterKeys { it in axes }
                var added = false
                for ((i, cell) in out.withIndex()) {
                    if (shared.all { cell[it.key] == it.value }) {
                        out[i] = cell + extra.filterKeys { it !in axes }
                        added = true
                    }
                }
                if (!added) out += extra
            }
            return out
        }
    }

    private fun scalars(value: Yaml?): List<String>? {
        val items = (value as? YSeq)?.items ?: return null
        val text = items.map { (it as? YScalar)?.text ?: return null }
        return text
    }

    private fun entries(value: Yaml?): List<Map<String, String>>? {
        val items = (value as? YSeq)?.items ?: return if (value == null) emptyList() else null
        return items.map { item ->
            val map = item as? YMap ?: return null
            map.entries.associate { it.key to ((it.value as? YScalar)?.text ?: return null) }
        }
    }

    private fun githubMatrix(strategy: Yaml?): Pair<Matrix?, String?> {
        val raw = strategy.map()?.get("matrix") ?: return null to null
        val map = raw as? YMap
            ?: return null to "the matrix is computed (${raw.scalar().orEmpty()})"
        val axes = linkedMapOf<String, List<String>>()
        for (entry in map.entries) {
            if (entry.key == "include" || entry.key == "exclude") continue
            axes[entry.key] = scalars(entry.value)
                ?: return null to "matrix axis ${entry.key} is not a list of plain values"
        }
        val include = entries(map["include"]) ?: return null to "matrix include is computed"
        val exclude = entries(map["exclude"]) ?: return null to "matrix exclude is computed"
        return Matrix(axes, include, exclude) to null
    }

    private fun gitlabMatrix(parallel: Yaml?): Pair<Matrix?, String?> {
        val list = (parallel.map()?.get("matrix") as? YSeq)?.items ?: return null to null
        val cells = mutableListOf<Map<String, String>>()
        for (item in list) {
            val map = item as? YMap ?: return null to "parallel matrix entry is not a mapping"
            var product = listOf<Map<String, String>>(emptyMap())
            for (entry in map.entries) {
                val values = scalars(entry.value)
                    ?: entry.value.scalar()?.let { listOf(it) }
                    ?: return null to "a matrix value is not plain"
                product = product.flatMap { cell -> values.map { cell + (entry.key to it) } }
            }
            cells += product
        }
        return Matrix(emptyMap(), cells, emptyList()) to null
    }

    private fun display(job: Candidate, cell: Map<String, String>): String {
        val template = job.name
        return if (template != null) {
            subst(template, cell).first
        } else {
            "${job.id} (${cell.values.joinToString(", ")})"
        }
    }

    private fun subst(text: String, cell: Map<String, String>): Pair<String, Boolean> {
        val out = matrixRef.replace(text) { cell[it.groupValues[1]] ?: it.value }
        return out to ("\${{" in out)
    }

    // #endregion

    private fun job(
        job: Candidate,
        request: HandsoffRequest,
        wanted: WantedJob,
        base: CiFacts,
        read: List<String>,
    ): CiSelection {
        val github = job.provider == "github"
        val (matrix, problem) =
            if (github) githubMatrix(job.body["strategy"]) else gitlabMatrix(job.body["parallel"])
        val stops = mutableListOf<Stop>()
        var cell = emptyMap<String, String>()
        if (problem != null) {
            stops += Stop(
                StopKind.MATRIX_CELL,
                "${job.id}: $problem",
                "Which values should the job run with? Pass --cell KEY=VALUE for each.",
            )
        } else if (matrix != null) {
            val cells = matrix.cells()
            val picked = cells.filter { c -> request.cell.all { c[it.first] == it.second } }
                .let { found ->
                    if (request.cell.isEmpty()) {
                        found.filter { display(job, it) == wanted.name }.ifEmpty { found }
                    } else {
                        found
                    }
                }
            when {
                picked.size == 1 -> cell = picked.single()

                picked.isEmpty() -> stops += Stop(
                    StopKind.MATRIX_CELL,
                    "no matrix cell of ${job.id} has " +
                        request.cell.joinToString(", ") { "${it.first}=${it.second}" },
                    "Which cell? ${axesText(matrix)}",
                )

                else -> stops += Stop(
                    StopKind.MATRIX_CELL,
                    "${job.id} has ${cells.size} matrix cells and " + if (request.cell.isEmpty()) {
                        "none was chosen"
                    } else {
                        "${picked.size} fit the values given"
                    },
                    "Which cell? ${axesText(matrix)}",
                )
            }
        }
        if (stops.isNotEmpty()) {
            val found = "job ${job.id} (${job.file}:${job.line}) was found; its steps are read " +
                "once the question is answered"
            return CiSelection(base.copy(job = null, notes = base.notes + found), stops, read)
        }
        val facts = if (github) githubFacts(job, cell.toList()) else gitlabFacts(job, cell.toList())
        return CiSelection(base.copy(job = facts), emptyList(), read)
    }

    private fun axesText(matrix: Matrix): String {
        val axes = matrix.axes.entries
            .joinToString("; ") { "${it.key}: ${it.value.joinToString(", ")}" }
        val extra = if (matrix.include.isEmpty()) "" else "; ${matrix.include.size} include entries"
        val head = if (axes.isEmpty()) "Cells come from include entries" else "Axes are $axes"
        return "$head$extra."
    }

    // #region github

    private fun githubFacts(job: Candidate, cell: List<Pair<String, String>>): CiJobFacts {
        val values = cell.toMap()
        val body = job.body
        var expressions = false
        fun sub(text: String): String {
            val (out, left) = subst(text, values)
            expressions = expressions || left
            return out
        }
        val runner = when (val on = body["runs-on"]) {
            is YScalar -> sub(on.text)

            is YSeq -> on.items.mapNotNull { it.scalar() }.joinToString(", ") { sub(it) }

            is YMap -> on.entries.joinToString(", ") {
                "${it.key}=${sub(it.value.scalar().orEmpty())}"
            }

            else -> null
        }
        val container = body["container"]
        val image = (container.scalar() ?: container.map()?.get("image").scalar())?.let { sub(it) }
        val services = body["services"].map()?.entries.orEmpty().map {
            val img = it.value.scalar() ?: it.value.map()?.get("image").scalar()
            CiService(it.key, img?.let { value -> sub(value) }, it.line)
        }
        val defaults = body["defaults"].map()?.get("run").map()?.get("working-directory").scalar()
        val actions = mutableListOf<CiAction>()
        val steps = mutableListOf<CiStep>()
        val notes = mutableListOf<String>()
        body["steps"].seq().forEachIndexed { i, item ->
            val step = item as? YMap ?: return@forEachIndexed
            val at = Source(job.file, step.line)
            step["uses"].scalar()?.let { uses ->
                val with = step["with"].map()?.entries.orEmpty()
                    .map { it.key to sub(it.value.scalar().orEmpty()) }
                actions += CiAction(sub(uses), with, at)
            }
            step["run"].scalar()?.let { run ->
                val directory = (step["working-directory"].scalar() ?: defaults)?.let { sub(it) }
                steps += CiStep(
                    i + 1,
                    step["name"].scalar(),
                    sub(run),
                    Source(job.file, step.entry("run")?.line ?: step.line),
                    directory,
                    step["if"].scalar(),
                )
            }
        }
        if (expressions) {
            notes += "expressions other than matrix.* are shown as written and are not evaluated"
        }
        if (steps.any { it.condition != null }) {
            notes += "steps with an if: condition are listed; whether they run is not decided"
        }
        return CiJobFacts(
            "github", job.file, job.id, job.line, runner, image, services, actions, steps,
            cell, notes,
        )
    }

    // #endregion

    // #region gitlab

    private fun gitlabFacts(job: Candidate, cell: List<Pair<String, String>>): CiJobFacts {
        val notes = mutableListOf<String>()
        val body = resolve(job.body, job.root, notes)
        val default = job.root["default"].map()
        fun inherited(key: String): Yaml? = body[key] ?: default?.get(key) ?: job.root[key]
        val imageValue = inherited("image")
        val image = imageValue.scalar() ?: imageValue.map()?.get("name").scalar()
        val services = inherited("services").seq().map {
            val name = it.scalar() ?: it.map()?.get("name").scalar()
            CiService(name.orEmpty(), name, it.line)
        }
        val tags = body["tags"].seq().mapNotNull { it.scalar() }
        val runner = if (tags.isEmpty()) null else "tags: ${tags.joinToString(", ")}"
        val steps = mutableListOf<CiStep>()
        for (section in listOf("before_script", "script", "after_script")) {
            val value = if (section == "script") body[section] else inherited(section)
            flatten(value).forEach { item ->
                steps += CiStep(steps.size + 1, section, item.text, Source(job.file, item.line))
            }
        }
        if (job.root["include"] != null) {
            notes += "include: is not followed; only this file was read"
        }
        if (body["trigger"] != null) notes += "this job triggers a pipeline and has no script"
        return CiJobFacts(
            "gitlab", job.file, job.id, job.line, runner, image, services, emptyList(), steps,
            cell, notes,
        )
    }

    private fun resolve(body: YMap, root: YMap, notes: MutableList<String>): YMap {
        val extends = body["extends"]
        val parents = extends.seq().mapNotNull { it.scalar() } + listOfNotNull(extends.scalar())
        if (parents.isEmpty()) return body
        val merged = linkedMapOf<String, YEntry>()
        for (parent in parents) {
            val p = root[parent] as? YMap
            if (p == null) {
                notes += "extends $parent names a job that is not in this file"
            } else {
                resolve(p, root, notes).entries.forEach { merged[it.key] = it }
            }
        }
        body.entries.forEach { merged[it.key] = it }
        return YMap(merged.values.toList(), body.line)
    }

    private fun flatten(value: Yaml?): List<YScalar> = when (value) {
        is YScalar -> if (value.text.isEmpty()) emptyList() else listOf(value)
        is YSeq -> value.items.flatMap { flatten(it) }
        else -> emptyList()
    }

    // #endregion
}
