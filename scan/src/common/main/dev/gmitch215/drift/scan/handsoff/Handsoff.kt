package dev.gmitch215.drift.scan.handsoff

import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.DirectoryRead
import dev.gmitch215.drift.lab.Reproduce
import dev.gmitch215.drift.lab.ReproduceResult
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.StepConclusion

/**
 * Says which tools a checkout uses, which commands CI ran, where they would run and what the
 * plan leaves out. It reads files and nothing else: no process starts here (a [Checkout] may
 * list a directory) and no network call is made.
 */
object Handsoff {
    private val runtimeTools = mapOf(
        "node" to "node",
        "python" to "python",
        "jdk" to "java",
        "php" to "php",
        "go" to "go",
    )

    private val setupActions = mapOf(
        "setup-node" to ("node" to "node-version"),
        "setup-python" to ("python" to "python-version"),
        "setup-java" to ("java" to "java-version"),
        "setup-go" to ("go" to "go-version"),
        "setup-php" to ("php" to "php-version"),
    )

    private val imageTools = mapOf(
        "node" to "node",
        "python" to "python",
        "php" to "php",
        "golang" to "go",
        "eclipse-temurin" to "java",
        "openjdk" to "java",
    )

    /** [caseFiles] reads the case directory that `--from-ci` names; it may be null. */
    fun plan(
        checkout: Checkout,
        label: String,
        original: HandsoffRequest,
        caseFiles: ((String) -> String?)? = null,
    ): HandsoffPlan {
        val request = original.copy(root = original.root.trim('/'))
        val eco = Ecosystems(checkout, request.root)
        val stops = mutableListOf<Stop>()
        val loaded = request.fromCi?.let { loadCase(it, caseFiles, stops) }
        val wanted = when {
            request.job != null -> WantedJob(request.job, false)
            loaded?.job != null -> WantedJob(loaded.job, true)
            else -> null
        }
        val chosenCase = loaded?.let { CiCase(it.dir, it.job.orEmpty(), null, null) }
        val ci = if (request.fromCi != null && loaded == null) {
            null
        } else {
            CiConfig.read(checkout, request, wanted, chosenCase)
        }
        ci?.let { stops += it.stops }
        val job = ci?.facts?.job
        val facts = eco.all()
        val locks = facts.associate { it.id to chooseLock(it, request, stops) }
        val resolved = facts.map { f ->
            f.copy(
                install = eco.install(f, locks[f.id]),
                named = f.named.map { it.copy(command = runCommand(f, locks[f.id], it)) },
                pins = f.pins + request.runtime.filter { it.first == runtimeTools[f.id] }
                    .map { Pin(it.first, it.second, "--runtime", Source("--runtime")) },
            )
        }
        for (f in resolved) checkEcosystem(f, locks[f.id], request, job, stops)
        request.lockfile?.let { wanted ->
            val prefix = prefixOf(request)
            if (resolved.none { f -> f.lockfiles.any { it.file.removePrefix(prefix) == wanted } }) {
                stops += Stop(
                    StopKind.TWO_LOCKFILES,
                    "--lockfile $wanted names no lockfile in the checkout",
                    "Which lockfile? Pass --lockfile with a file that exists.",
                )
            }
        }
        if (facts.isEmpty() && job == null && stops.isEmpty()) {
            stops += Stop(
                StopKind.NOTHING_FOUND,
                "no manifest or lockfile of an ecosystem this reads is in " +
                    request.root.ifEmpty { "the checkout root" },
                "Is this the right directory? Pass the checkout as the argument, or --root DIR.",
            )
        }
        val named = request.testCommand?.let { PlannedCommand(it, "--test-command") }
        val commands = if (stops.isNotEmpty()) {
            emptyList()
        } else if (job != null) {
            jobCommands(job, named, stops)
        } else {
            repoCommands(resolved, named, stops)
        }
        val ready = stops.isEmpty()
        val (image, basis) = if (ready) image(job, loaded?.capsule, commands) else null to null
        val by = if (ready) byHand(request.root, commands) else emptyList()
        return HandsoffPlan(
            label,
            request.root,
            resolved,
            eco.notRead(),
            ci?.facts?.copy(case = ci.facts.case?.copy(image = image, basis = basis)),
            stops,
            commands,
            image,
            basis,
            by,
            (eco.readFiles + ci?.readFiles.orEmpty()).distinct().sorted(),
        )
    }

    private fun prefixOf(request: HandsoffRequest) =
        request.root.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    // #region case

    private class Loaded(val dir: String, val job: String?, val capsule: Capsule)

    private fun loadCase(
        dir: String,
        files: ((String) -> String?)?,
        stops: MutableList<Stop>,
    ): Loaded? {
        fun stop(text: String): Loaded? {
            stops += Stop(
                StopKind.CI_CONFIG,
                text,
                "Which case? Pass the directory that drift ci wrote, or use --job.",
            )
            return null
        }
        val read = files ?: return stop("--from-ci needs a way to read $dir")
        val failing = "${dir.trimEnd('/')}/capsules/failing"
        return when (val result = CapsuleDirectories.read(read, failing)) {
            null -> stop("$failing holds no capsule.json; $dir is not a case that drift ci wrote")

            is DirectoryRead.Failed -> stop(result.reason)

            is DirectoryRead.Loaded -> {
                val run = result.directory.run
                val job = run?.steps?.firstOrNull { it.conclusion == StepConclusion.FAILURE }?.job
                    ?: run?.steps?.firstNotNullOfOrNull { it.job }
                if (job == null) {
                    stop("$failing/run.json names no job; use --job to choose one")
                } else {
                    Loaded(dir, job, result.directory.capsule)
                }
            }
        }
    }

    // #endregion

    // #region stop rules

    private fun chooseLock(
        facts: EcosystemFacts,
        request: HandsoffRequest,
        stops: MutableList<Stop>,
    ): String? {
        val prefix = prefixOf(request)
        val names = facts.lockfiles.map { it.file.removePrefix(prefix) }
        val picked = request.lockfile?.takeIf { it in names }
        if (picked != null) return picked
        if (names.size <= 1) return names.firstOrNull()
        stops += Stop(
            StopKind.TWO_LOCKFILES,
            "${facts.id} has ${names.size} lockfiles: ${names.joinToString(", ")}",
            "Which lockfile do installs follow? Pass --lockfile with one of them.",
        )
        return null
    }

    private fun checkEcosystem(
        facts: EcosystemFacts,
        lock: String?,
        request: HandsoffRequest,
        job: CiJobFacts?,
        stops: MutableList<Stop>,
    ) {
        if (facts.workspaces.isNotEmpty() && request.root.isEmpty()) {
            stops += Stop(
                StopKind.MONOREPO,
                "${facts.id} declares workspaces: " +
                    facts.workspaces.joinToString(", ") { "${it.pattern} (${it.source})" },
                "Which workspace should be planned? Pass --root DIR.",
            )
        }
        if (facts.lockfiles.isEmpty() && facts.needsLockfile && !request.acceptNoLockfile) {
            stops += Stop(
                StopKind.NO_LOCKFILE,
                "${facts.id} has a manifest (${facts.manifests.joinToString { "$it" }}) and no " +
                    "lockfile, so the install resolves versions on the day it runs",
                "Is an unlocked install acceptable? Pass --accept-no-lockfile to say so.",
            )
        }
        val manager = lock?.let { managerOf(it) }
        if (facts.id == "node" && facts.manager != null && manager != null &&
            facts.manager != manager
        ) {
            stops += Stop(
                StopKind.PACKAGE_MANAGER,
                "packageManager names ${facts.manager} (${facts.managerSource}) and the lockfile " +
                    "is $lock, which belongs to $manager",
                "Which package manager? Fix one of the two, or pass --lockfile to follow $lock.",
            )
        }
        val tool = runtimeTools[facts.id] ?: return
        runtimeStop(facts, tool, request, job)?.let { stops += it }
    }

    private fun managerOf(lock: String) = when (lock) {
        "package-lock.json", "npm-shrinkwrap.json" -> "npm"
        "yarn.lock" -> "yarn"
        "pnpm-lock.yaml" -> "pnpm"
        "bun.lock", "bun.lockb" -> "bun"
        else -> null
    }

    private fun runtimeStop(
        facts: EcosystemFacts,
        tool: String,
        request: HandsoffRequest,
        job: CiJobFacts?,
    ): Stop? {
        if (request.runtime.any { it.first == tool }) return null
        val pins = facts.pins.filter { it.tool == tool } + ciPins(job, tool)
        val question = "Which $tool version should be used? Pass --runtime $tool=VERSION."
        if (pins.isEmpty()) {
            return Stop(
                StopKind.RUNTIME_VERSION,
                "no $tool version is pinned by ${facts.manifests.joinToString { "$it" }} or the " +
                    "chosen CI job",
                question,
            )
        }
        val moving = pins.filter { Versions.classify(it.value) == Version.Moving }
        if (moving.isNotEmpty()) {
            return Stop(
                StopKind.RUNTIME_VERSION,
                "$tool is pinned to a name that moves: " + moving.joinToString { describe(it) },
                question,
            )
        }
        val clash = conflict(pins) ?: return null
        return Stop(
            StopKind.RUNTIME_VERSION,
            "$tool pins disagree: ${describe(clash.first)} and ${describe(clash.second)}",
            question,
        )
    }

    private fun describe(pin: Pin) = "${pin.value} (${pin.origin})"

    private fun conflict(pins: List<Pin>): Pair<Pin, Pin>? {
        for ((i, a) in pins.withIndex()) {
            for (b in pins.drop(i + 1)) {
                if (disagree(a.value, b.value)) return a to b
            }
        }
        return null
    }

    private fun disagree(a: String, b: String): Boolean {
        val x = Versions.classify(a)
        val y = Versions.classify(b)
        return when {
            x is Version.Exact && y is Version.Exact -> !Versions.agree(x, y)

            x is Version.Exact && y is Version.Constraint ->
                Versions.satisfies(x, b) == false

            x is Version.Constraint && y is Version.Exact -> disagree(b, a)

            else -> false
        }
    }

    private fun ciPins(job: CiJobFacts?, tool: String): List<Pin> {
        if (job == null) return emptyList()
        val fromActions = job.actions.mapNotNull { action ->
            val name = action.uses.substringBefore('@').substringAfterLast('/')
            val (actionTool, key) = setupActions[name] ?: return@mapNotNull null
            val value = action.with.firstOrNull { it.first == key }?.second
            if (actionTool != tool || value.isNullOrBlank() || "\${{" in value) {
                null
            } else {
                Pin(tool, value, "$name $key", action.source)
            }
        }
        val image = job.image?.let { ref ->
            val repo = ref.substringBefore(':').substringAfterLast('/')
            val tag = if (':' in ref) ref.substringAfter(':') else "latest"
            if (imageTools[repo] == tool) {
                listOf(Pin(tool, tag, "job image $ref", Source(job.file, job.line)))
            } else {
                emptyList()
            }
        }.orEmpty()
        return fromActions + image
    }

    // #endregion

    // #region commands

    private fun jobCommands(
        job: CiJobFacts,
        named: PlannedCommand?,
        stops: MutableList<Stop>,
    ): List<PlannedCommand> {
        if (job.steps.isEmpty() && named != null) return listOf(named)
        if (job.steps.isEmpty()) {
            stops += Stop(
                StopKind.NO_TEST_COMMAND,
                "job ${job.id} in ${job.file} has no run steps",
                "Which command runs the tests? Pass --test-command COMMAND.",
            )
        }
        return job.steps.map {
            val condition = it.condition?.let { c -> ", if: $c" }.orEmpty()
            PlannedCommand(stepText(it), "${it.source}$condition")
        }
    }

    private fun stepText(step: CiStep) =
        if (step.directory == null) step.command else "(cd ${step.directory} && ${step.command})"

    private fun repoCommands(
        facts: List<EcosystemFacts>,
        named: PlannedCommand?,
        stops: MutableList<Stop>,
    ): List<PlannedCommand> {
        val out = mutableListOf<PlannedCommand>()
        val tests = mutableListOf<PlannedCommand>()
        val listed = mutableListOf<String>()
        for (f in facts) {
            f.install?.let { out += PlannedCommand(it.command, it.why) }
            for (n in f.named) {
                val command = n.command
                val where = "${n.kind} ${n.name} (${n.source})"
                if (n.test && command != null) {
                    tests += PlannedCommand(command, "${n.kind} ${n.name}, ${n.source}")
                } else if (n.test || n.kind == "tox environment") {
                    listed += where
                }
            }
        }
        when {
            named != null -> out += named

            tests.size == 1 -> out += tests.single()

            tests.isEmpty() -> stops += Stop(
                StopKind.NO_TEST_COMMAND,
                "the repository names no test command Drift can run" +
                    if (listed.isEmpty()) {
                        ""
                    } else {
                        "; named, no documented command: " +
                            listed.joinToString()
                    },
                "Which command runs the tests? Pass --test-command COMMAND.",
            )

            else -> stops += Stop(
                StopKind.SEVERAL_TEST_COMMANDS,
                "the repository names ${tests.size} test commands: " +
                    tests.joinToString { "${it.command} (${it.source})" },
                "Which one runs the tests? Pass --test-command with it.",
            )
        }
        return if (stops.isEmpty()) out else emptyList()
    }

    private fun runCommand(facts: EcosystemFacts, lock: String?, named: Named): String? {
        if (named.command != null) return named.command
        if (named.kind == "script" && facts.id == "node") {
            val manager = lock?.let { managerOf(it) } ?: facts.manager
            return manager?.takeIf { it in setOf("npm", "pnpm", "yarn") }
                ?.let { "$it run ${named.name}" }
        }
        return null
    }

    private fun byHand(root: String, commands: List<PlannedCommand>): List<String> {
        val cd = if (root.isEmpty()) emptyList() else listOf("cd $root")
        return cd + commands.flatMap { it.command.split('\n') }
    }

    // #endregion

    private fun image(
        job: CiJobFacts?,
        capsule: Capsule?,
        commands: List<PlannedCommand>,
    ): Pair<String?, String?> {
        val first = commands.firstOrNull()?.command
        if (capsule != null && first != null) {
            val result = Reproduce.synthesize(capsule, first)
            if (result is ReproduceResult.Built) {
                val r = result.reproduction
                return r.image to "from the failing capsule: ${r.basis}; ${r.summary}"
            }
        }
        job?.image?.let { return it to "the image the job names" }
        return null to null
    }
}
