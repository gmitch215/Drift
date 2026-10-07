package dev.gmitch215.drift.bench

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Capsule
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
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
		val split = SplitRule.split(scenarios.map { it.id })
		return Generator.scenariosJson(scenarios) to split.toJson()
	}

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

	fun lab(dir: String): Lab = Lab.parse(
		data.resolve("scenarios.json").readText(),
		data.resolve("split.json").readText(),
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

	fun writeCalibration(): Calibration {
		val c = Calibrate.run(Inputs(lab("probed"), lab("capsules")), SEED)
		val out = data.resolve("calibration").createDirectories()
		out.resolve("fit.json").writeText(c.fit)
		out.resolve("evaluation.json").writeText(c.evaluation)
		return c
	}

	/** Writes one directory per dev scenario; a test id is refused. */
	fun prepare(out: Path, only: Set<String>): List<String> {
		val dev = devIds()
		val scenarios = loadScenarios()
		only.firstOrNull { it !in dev }?.let { throw SealedException(it) }
		val chosen = scenarios.filter { it.id in dev && (only.isEmpty() || it.id in only) }
		out.createDirectories()
		val harness = root.resolve("runner/harness.sh").readText()
		out.resolve("harness.sh").writeText(harness)
		out.resolve("run-batch.sh").writeText(root.resolve("runner/run-batch.sh").readText())
		for (s in chosen) {
			val dir = out.resolve(s.id).createDirectories()
			dir.resolve("scenario.json").writeText(s.canonical() + "\n")
			dir.resolve("harness.sh").writeText(harness)
			dir.resolve(Dimensions.runtimes.getValue(s.runtime).first).writeText(s.source)
			for ((name, arm) in listOf("green" to s.green, "red" to s.red)) {
				dir.resolve("$name.plan").writeText(Harness.plan(s, arm))
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
