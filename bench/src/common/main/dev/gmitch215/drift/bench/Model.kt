package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj

class Arm(
	val image: String,
	val env: Map<String, String>,
	val memory: String,
	val cpus: String,
	val cpuset: String?,
	val ulimits: Map<String, String>,
	val network: String,
	val files: Map<String, String>,
	val interpreter: String?,
	val args: List<String>,
	val gate: Int,
	val rate: Int,
) {
	fun toJson(): JsonObject = obj(
		"image" to JsonString(image),
		"env" to strings(env),
		"memory" to JsonString(memory),
		"cpus" to JsonString(cpus),
		"cpuset" to (cpuset?.let { JsonString(it) } ?: JsonNull),
		"ulimits" to strings(ulimits),
		"network" to JsonString(network),
		"files" to strings(files),
		"interpreter" to (interpreter?.let { JsonString(it) } ?: JsonNull),
		"args" to JsonArray(args.map { JsonString(it) }),
		"gate" to JsonInt(gate.toLong()),
		"rate" to JsonInt(rate.toLong()),
	)

	companion object {
		fun fromJson(f: Fields): Arm = Arm(
			image = f.str("image"),
			env = f.strings("env"),
			memory = f.str("memory"),
			cpus = f.str("cpus"),
			cpuset = f.strOrNull("cpuset"),
			ulimits = f.strings("ulimits"),
			network = f.str("network"),
			files = f.strings("files"),
			interpreter = f.strOrNull("interpreter"),
			args = f.list("args"),
			gate = f.int("gate"),
			rate = f.int("rate"),
		)
	}
}

class Attr(val dimension: String, val path: String) {
	fun toJson(): JsonObject = obj("dimension" to JsonString(dimension), "path" to JsonString(path))
}

class Cause(val dimension: String, val path: String, val attribute: String, val direction: String) {
	fun toJson(): JsonObject = obj(
		"dimension" to JsonString(dimension),
		"path" to JsonString(path),
		"attribute" to JsonString(attribute),
		"direction" to JsonString(direction),
	)
}

class Override(val path: String, val green: String, val red: String) {
	fun toJson(): JsonObject = obj(
		"path" to JsonString(path),
		"green" to JsonString(green),
		"red" to JsonString(red),
	)
}

class Scenario(
	val id: String,
	val template: String,
	val title: String,
	val dimension: String,
	val salt: Long,
	val runtime: String,
	val source: String,
	val signature: String,
	val signatureExit: Int?,
	val trials: Int,
	val green: Arm,
	val red: Arm,
	val cause: Cause?,
	val bundle: List<Attr>,
	val decoys: List<Attr>,
	val visible: Boolean,
	val controllable: Boolean,
	val overrides: List<Override>,
	val note: String,
) {
	/** control, intermittent, bundle, decoy or single, in that order of precedence */
	val klass: String
		get() = when {
			cause == null -> "control"
			red.rate < 1000 -> "intermittent"
			bundle.isNotEmpty() -> "bundle"
			decoys.isNotEmpty() -> "decoy"
			else -> "single"
		}

	fun predicted(arm: Arm): List<Int> =
		if (arm.rate == 0) emptyList() else Gate.failures(trials, salt, arm.gate)

	fun accepts(): Boolean = listOf(green, red).all {
		Gate.within(trials, it.rate, predicted(it).size)
	}

	fun toJson(): JsonObject = obj(
		"id" to JsonString(id),
		"template" to JsonString(template),
		"title" to JsonString(title),
		"dimension" to JsonString(dimension),
		"class" to JsonString(klass),
		"synthetic" to JsonBool(true),
		"salt" to JsonInt(salt),
		"program" to obj(
			"runtime" to JsonString(runtime),
			"file" to JsonString(Dimensions.runtimes.getValue(runtime).first),
			"source" to JsonString(source),
			"signature" to JsonString(signature),
			"signature_exit" to (signatureExit?.let { JsonInt(it.toLong()) } ?: JsonNull),
		),
		"trials" to JsonInt(trials.toLong()),
		"arms" to obj("green" to green.toJson(), "red" to red.toJson()),
		"predicted_failures" to obj(
			"green" to ints(predicted(green)),
			"red" to ints(predicted(red)),
		),
		"cause" to (cause?.toJson() ?: JsonNull),
		"bundle" to JsonArray(bundle.map { it.toJson() }),
		"decoys" to JsonArray(decoys.map { it.toJson() }),
		"visible" to JsonBool(visible),
		"controllable" to JsonBool(controllable),
		"capsule_overrides" to JsonArray(overrides.map { it.toJson() }),
		"expected" to expected(),
		"note" to JsonString(note),
	)

	private fun expected(): JsonObject = if (cause == null) {
		obj("kind" to JsonString("none"))
	} else {
		obj(
			"kind" to JsonString("cause"),
			"dimension" to JsonString(cause.dimension),
			"path" to JsonString(cause.path),
			"direction" to JsonString(cause.direction),
			"bundle" to JsonArray(bundle.map { JsonString(it.path) }),
			"manual" to JsonBool(!controllable),
		)
	}

	fun canonical(): String = CanonicalJson.encode(toJson())

	companion object {
		fun fromJson(json: JsonValue): Scenario {
			val f = Fields(
				"scenario",
				json as? JsonObject ?: throw TemplateException(
				Problem.BAD_TYPE,
				"scenario",
				"expected an object",
			),
			)
			val program = f.obj("program") ?: throw f.missing("program")
			val arms = f.obj("arms") ?: throw f.missing("arms")
			return Scenario(
				id = f.str("id"),
				template = f.str("template"),
				title = f.str("title"),
				dimension = f.str("dimension"),
				salt = f.long("salt"),
				runtime = program.str("runtime"),
				source = program.str("source"),
				signature = program.str("signature"),
				signatureExit = program.strOrNull("signature_exit")?.toInt(),
				trials = f.int("trials"),
				green = Arm.fromJson(arms.obj("green") ?: throw arms.missing("green")),
				red = Arm.fromJson(arms.obj("red") ?: throw arms.missing("red")),
				cause = f.obj("cause")?.let {
					Cause(
						it.str("dimension"),
						it.str("path"),
						it.str("attribute"),
						it.str("direction"),
					)
				},
				bundle = f.objs("bundle").map { Attr(it.str("dimension"), it.str("path")) },
				decoys = f.objs("decoys").map { Attr(it.str("dimension"), it.str("path")) },
				visible = f.bool("visible"),
				controllable = f.bool("controllable"),
				overrides = f.objs("capsule_overrides").map {
					Override(it.str("path"), it.str("green"), it.str("red"))
				},
				note = f.str("note"),
			)
		}
	}
}

private fun strings(map: Map<String, String>): JsonObject =
	JsonObject(map.mapValues { JsonString(it.value) })

private fun ints(list: List<Int>): JsonArray = JsonArray(list.map { JsonInt(it.toLong()) })
