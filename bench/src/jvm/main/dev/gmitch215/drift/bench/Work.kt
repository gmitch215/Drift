package dev.gmitch215.drift.bench

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Capsule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** File operations behind the three commands; `root` is the `bench/` directory. */
class Work(private val root: Path) {
    private val data = root.resolve("data")

    fun templates(): List<Template> {
        val files = root.resolve("scenarios").listDirectoryEntries("*.yml").sortedBy { it.name }
        return files.flatMap { Template.listFromJson(Yaml.parse(it.readText(), it.name)) }
    }

    fun droppedIds(): Set<String> {
        val file = data.resolve("dropped.json")
        if (!file.exists()) return emptySet()
        return (CanonicalJson.parse(file.readText()) as JsonArray).items
            .map { (it as JsonObject).fields.getValue("id").string() }.toSet()
    }

    fun generate(): Pair<String, String> {
        val scenarios = Generator.generate(templates(), SEED, droppedIds())
        return Generator.scenariosJson(scenarios) to SplitRule.splitScenarios(scenarios).toJson()
    }

    /**
     * The runnable scenario ids of the given templates (dev, or test given [unsealed]); a template
     * with none is an error.
     */
    fun idsOfTemplates(templates: Set<String>, unsealed: Unsealed? = null): Set<String> {
        val allowed = unsealed?.test?.toSet() ?: devIds()
        val scenarios = loadScenarios()
        return templates.flatMap { t ->
            scenarios.filter { it.template == t && it.id in allowed }.map { it.id }
                .ifEmpty { error("template $t has no scenarios to run") }
        }.toSet()
    }

    fun split(): Split = SplitRule.splitScenarios(loadScenarios())

    fun writeGenerated() {
        val (scenarios, split) = generate()
        data.createDirectories()
        data.resolve("scenarios.json").writeText(scenarios)
        data.resolve("split.json").writeText(split)
    }

    fun loadScenarios(): List<Scenario> =
        (CanonicalJson.parse(data.resolve("scenarios.json").readText()) as JsonArray).items.map {
            Scenario.fromJson(it)
        }

    fun devIds(): Set<String> = (
        (
            CanonicalJson.parse(
                data.resolve("split.json").readText(),
            ) as JsonObject
            ).fields.getValue("dev")
        ).array()
        .map { it.string() }.toSet()

    fun lab(dir: String, unsealed: Unsealed? = null): Lab = Lab.parse(
        data.resolve("scenarios.json").readText(),
        data.resolve("split.json").readText(),
        unsealed,
    ) { data.resolve("$dir/$it").readText() }

    /**
     * Copies the passing and failing baseline captures (`arms/p0-control` and `arms/p0-treatment`,
     * probes included) of each solve case directory under [solved] to `data/probed/`. A test id
     * is refused.
     */
    fun importProbed(solved: Path): List<String> {
        val dev = devIds()
        val dirs = solved.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }
        dirs.firstOrNull { it.name !in dev }?.let { throw SealedException(it.name) }
        val out = data.resolve("probed").createDirectories()
        for (dir in dirs) {
            for ((arm, side) in listOf("p0-control" to "green", "p0-treatment" to "red")) {
                val text = dir.resolve("arms/$arm/capsule.json").readText().trimEnd()
                Capsule.parse(text)
                out.resolve("${dir.name}.$side.json").writeText(text + "\n")
            }
        }
        return dirs.map { it.name }
    }

    fun calibration(): Calibration = Calibrate.run(Inputs(lab("probed"), lab("capsules")), SEED)

    fun writeCalibration(): Calibration {
        val c = calibration()
        val out = data.resolve("calibration").createDirectories()
        out.resolve("fit.json").writeText(c.fit)
        out.resolve("evaluation.json").writeText(c.evaluation)
        return c
    }

    /** Writes `upgrade/upgrade.json` from the dev capsules and returns the dev scores. */
    fun writeUpgrade(): UpgradeScore {
        val lab = lab("capsules")
        val results = UpgradeBench.runDev(lab)
        val out = root.resolve("upgrade").createDirectories()
        out.resolve("upgrade.json").writeText(UpgradeBench.toJson(lab, results))
        return UpgradeBench.score(results)
    }

    /**
     * Writes one directory per dev scenario, or per test scenario given [unsealed]; any other id
     * is refused.
     */
    fun prepare(out: Path, only: Set<String>, unsealed: Unsealed? = null): List<String> {
        val allowed = unsealed?.test?.toSet() ?: devIds()
        val scenarios = loadScenarios()
        only.firstOrNull { it !in allowed }?.let { throw SealedException(it) }
        val chosen = scenarios.filter { it.id in allowed && (only.isEmpty() || it.id in only) }
        out.createDirectories()
        val harness = root.resolve("runner/harness.sh").readText()
        out.resolve("harness.sh").writeText(harness)
        for (name in listOf("run-batch.sh", "native.sh")) {
            out.resolve(name).writeText(root.resolve("runner/$name").readText())
        }
        for (s in chosen) {
            val dir = out.resolve(s.id).createDirectories()
            dir.resolve("scenario.json").writeText(s.canonical() + "\n")
            dir.resolve("harness.sh").writeText(harness)
            dir.resolve(Dimensions.runtimes.getValue(s.runtime).first).writeText(s.source)
            Harness.nativeRefusal(s)?.let { dir.resolve("native.skip").writeText(it + "\n") }
            for ((name, arm) in listOf("green" to s.green, "red" to s.red)) {
                dir.resolve("$name.plan").writeText(Harness.plan(s, arm))
                dir.resolve("$name.env").writeText(Harness.env(arm))
                dir.resolve("$name.sh").writeText(Harness.script(s, name, arm))
                arm.files.toSortedMap().values.forEachIndexed { i, content ->
                    val file = dir.resolve(Harness.filePath(name, i))
                    file.parent.createDirectories()
                    file.writeText(content)
                }
            }
        }
        return chosen.map { it.id }
    }

    private val freezeFile = data.resolve("freeze.json")
    private val unsealLog = data.resolve("unseal.log")

    /** One value per frozen field, read from the working tree. */
    fun currentFreeze(): Map<String, String> = linkedMapOf(
        "rules_sha256" to Freeze.rulesSha256(),
        "fit_sha256" to sha256(data.resolve("calibration/fit.json")),
        "evaluation_sha256" to sha256(data.resolve("calibration/evaluation.json")),
        "scenarios_sha256" to sha256(data.resolve("scenarios.json")),
        "split_sha256" to sha256(data.resolve("split.json")),
        Freeze.HOLDOUT to Freeze.holdoutTemplates(loadScenarios()).joinToString(","),
    )

    private fun calibrationDrifts(): Boolean {
        val c = calibration()
        val files = data.resolve("calibration")
        return c.fit != files.resolve("fit.json").readText() ||
            c.evaluation != files.resolve("evaluation.json").readText()
    }

    fun writeFreeze(commit: String) {
        unsealLines().firstOrNull()?.let { throw UnsealException("already unsealed: $it") }
        if (calibrationDrifts()) throw UnsealException("run calibrate first; the files are stale")
        freezeFile.writeText(Freeze.toJson(commit, currentFreeze()))
    }

    fun unsealLines(): List<String> =
        if (unsealLog.exists()) unsealLog.readLines().filter { it.isNotEmpty() } else emptyList()

    /**
     * The one unseal: every frozen value must match the working tree and the log must be empty.
     * With [dryRun] nothing is written.
     */
    fun unseal(commit: String, operator: String, now: String, dryRun: Boolean = false): Unsealed {
        unsealLines().firstOrNull()?.let { throw UnsealException("already unsealed: $it") }
        val unsealed = verifiedUnseal()
        if (!dryRun) {
            Files.writeString(
                unsealLog,
                "$now\t$commit\t$operator\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
        return unsealed
    }

    /** The test ids, once the log has its entry and the freeze still matches. */
    fun openUnsealed(): Unsealed {
        if (unsealLines().isEmpty()) throw UnsealException("not unsealed; run unseal first")
        return verifiedUnseal()
    }

    private fun verifiedUnseal(): Unsealed {
        if (!freezeFile.exists()) throw UnsealException("no data/freeze.json; run freeze first")
        val problems = Freeze.mismatches(freezeFile.readText(), currentFreeze()).toMutableList()
        if (calibrationDrifts()) problems += "engine_calibration (the ranker output moved)"
        if (problems.isNotEmpty()) {
            throw UnsealException("unseal refused: " + problems.joinToString("; "))
        }
        return Unsealed(split().test)
    }

    /** Reads `<arm>.out` from each prepared directory; returns id to verdict. */
    fun ingest(
        prepared: Path,
        driftSha: String?,
        requireCapsule: Boolean = true,
    ): Map<String, Verdict> {
        val verdicts = linkedMapOf<String, Verdict>()
        val dropped = readDropped().toMutableMap()
        val results = data.resolve("results").createDirectories()
        val capsules = data.resolve("capsules").createDirectories()
        val dirs = prepared.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }
        for (dir in dirs) {
            val g = dir.resolve("green.out")
            val r = dir.resolve("red.out")
            if (!g.exists() || !r.exists()) continue
            val s = Scenario.fromJson(CanonicalJson.parse(dir.resolve("scenario.json").readText()))
            val v = Ingest.scenario(
                s,
                ArmRun.parse(g.readText()),
                ArmRun.parse(r.readText()),
                driftSha,
                requireCapsule,
            )
            verdicts[s.id] = v
            results.resolve("${s.id}.json").writeText(CanonicalJson.encode(v.result) + "\n")
            if (v.valid) {
                dropped.remove(s.id)
                v.capsules.forEach { (arm, text) ->
                    capsules.resolve("${s.id}.$arm.json").writeText(text + "\n")
                }
            } else {
                dropped[s.id] = v.result
            }
        }
        data.createDirectories()
        data.resolve("dropped.json").writeText(
            CanonicalJson.encode(JsonArray(dropped.toSortedMap().values.toList())) + "\n",
        )
        return verdicts
    }

    private fun readDropped(): Map<String, JsonObject> {
        val file = data.resolve("dropped.json")
        if (!file.exists()) return emptyMap()
        return (CanonicalJson.parse(file.readText()) as JsonArray).items
            .associate { (it as JsonObject).fields.getValue("id").string() to it }
    }

    companion object {
        const val SEED = 20261006L

        fun sha256(file: Path): String = Sha256.hex(file.readBytes())
    }
}
