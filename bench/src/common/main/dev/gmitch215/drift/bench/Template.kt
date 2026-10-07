package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue

/**
 * A hand-maintained scenario template. `variants` are rows of placeholder values (a seeded
 * shuffle picks `instances` of them), `draw` placeholders are drawn independently per instance.
 */
class Template(
	val id: String,
	val instances: Int,
	val variants: List<Map<String, String>>,
	val draw: Map<String, List<String>>,
	private val body: JsonObject,
) {
	fun build(index: Int, vars: Map<String, String>, salt: Long): Scenario {
		val where = "$id-$index"
		val f = Fields(where, substitute(body, vars) as JsonObject)
		val dimension = f.str("dimension")
		checkDimension(where, dimension)
		val runtime = f.str("runtime")
		if (runtime !in Dimensions.runtimes) {
			throw TemplateException(Problem.BAD_VALUE, "$where.runtime", "unknown runtime $runtime")
		}
		val signature = f.strOrNull("signature") ?: ""
		val exit = f.strOrNull("exit")?.let {
			it.toIntOrNull() ?: throw TemplateException(
				Problem.BAD_VALUE,
				"$where.exit",
				"not an integer",
			)
		}
		if (signature.isEmpty() && exit == null) {
			throw TemplateException(
				Problem.MISSING_FIELD,
				where,
				"need a signature or an exit code",
			)
		}
		val trials = if (f.has("trials")) f.int("trials") else 20
		if (trials !in 4..200) {
			throw TemplateException(Problem.BAD_VALUE, "$where.trials", "trials must be 4 to 200")
		}
		val gate = if (f.has("gate")) f.int("gate") else 1000
		val base = f.obj("base")?.json
		val green = arm("$where.green", merge(base, f.obj("green")?.json), gate, 0)
		val red = arm("$where.red", merge(base, f.obj("red")?.json), gate, null)
		checkArm("$where.green", green)
		checkArm("$where.red", red)
		if (red.rate == 0) {
			throw TemplateException(Problem.BAD_VALUE, "$where.red", "the red arm must fail")
		}
		if (green.rate != 0) {
			throw TemplateException(Problem.BAD_VALUE, "$where.green", "the green arm must pass")
		}
		val cause = cause(f, dimension)
		val bundle = attrs(f, "bundle", where)
		val decoys = attrs(f, "decoys", where)
		if (cause == null && bundle.isNotEmpty()) {
			throw TemplateException(
				Problem.INCONSISTENT,
				"$where.bundle",
				"a control has no bundle",
			)
		}
		val controllable = if (f.has("controllable")) f.bool("controllable") else true
		val visible = if (f.has("visible")) f.bool("visible") else true
		val overrides = f.objs("capsule-overrides").map {
			Override(it.str("path"), it.str("green"), it.str("red"))
		}
		if (controllable) {
			if (overrides.isNotEmpty()) {
				throw TemplateException(
					Problem.INCONSISTENT,
					"$where.capsule-overrides",
					"only for uncontrollable causes",
				)
			}
			(listOfNotNull(cause?.path) + bundle.map { it.path } + decoys.map { it.path }).forEach {
				if (!differs(it, green, red)) {
					throw TemplateException(
						Problem.INCONSISTENT,
						where,
						"arms do not differ at $it",
					)
				}
			}
		} else {
			if (cause == null || overrides.none { it.path == cause.path }) {
				throw TemplateException(
					Problem.INCONSISTENT,
					where,
					"an uncontrollable cause needs a capsule override",
				)
			}
			(bundle.map { it.path } + decoys.map { it.path }).forEach { path ->
				if (overrides.none { it.path == path }) {
					throw TemplateException(
						Problem.INCONSISTENT,
						where,
						"$path needs a capsule override too",
					)
				}
			}
			if (!visible) {
				throw TemplateException(
					Problem.INCONSISTENT,
					"$where.visible",
					"an override is always visible",
				)
			}
			if (!sameConfig(green, red)) {
				throw TemplateException(
					Problem.INCONSISTENT,
					where,
					"uncontrollable arms must run the same configuration",
				)
			}
		}
		return Scenario(
			id = where,
			template = id,
			title = f.strOrNull("title") ?: id,
			dimension = dimension,
			salt = salt,
			runtime = runtime,
			source = f.str("program"),
			signature = signature,
			signatureExit = exit,
			trials = trials,
			green = green,
			red = red,
			cause = cause,
			bundle = bundle,
			decoys = decoys,
			visible = visible,
			controllable = controllable,
			overrides = overrides,
			note = f.strOrNull("note") ?: "",
		)
	}

	private fun arm(where: String, json: JsonObject, gate: Int, rate: Int?): Arm {
		val f = Fields(where, json)
		val g = if (f.has("gate")) f.int("gate") else gate
		return Arm(
			image = f.str("image"),
			env = f.strings("env"),
			memory = f.strOrNull("memory") ?: "512m",
			cpus = f.strOrNull("cpus") ?: "2",
			cpuset = f.strOrNull("cpuset"),
			ulimits = f.strings("ulimits"),
			network = f.strOrNull("network") ?: "none",
			files = f.strings("files"),
			interpreter = f.strOrNull("interpreter"),
			args = f.list("args"),
			gate = g,
			rate = if (f.has("rate")) f.int("rate") else rate ?: g,
		)
	}

	private fun checkArm(where: String, arm: Arm) {
		if (arm.gate !in 0..1000) {
			throw TemplateException(Problem.BAD_VALUE, "$where.gate", "gate must be 0 to 1000")
		}
		if (arm.rate != 0 && arm.rate != arm.gate) {
			throw TemplateException(
				Problem.BAD_VALUE,
				"$where.rate",
				"rate must be 0 or equal to the gate",
			)
		}
	}

	private fun cause(f: Fields, dimension: String): Cause? {
		val raw = f.json["cause"]
		if (raw == null || raw == JsonNull) throw f.missing("cause")
		if (raw is JsonString) {
			if (raw.value != "none") {
				throw TemplateException(Problem.BAD_VALUE, "${f.where}.cause", "use none or a map")
			}
			return null
		}
		val c = f.obj("cause") ?: throw f.missing("cause")
		val cd = c.str("dimension")
		checkDimension("${f.where}.cause", cd)
		if (cd != dimension) {
			throw TemplateException(
				Problem.INCONSISTENT,
				"${f.where}.cause",
				"cause dimension $cd is not $dimension",
			)
		}
		val direction = c.str("direction")
		if (direction !in Dimensions.directions) {
			throw TemplateException(
				Problem.BAD_VALUE,
				"${f.where}.cause.direction",
				"unknown direction $direction",
			)
		}
		val path = c.str("path")
		return Cause(cd, path, c.strOrNull("attribute") ?: path, direction)
	}

	private fun attrs(f: Fields, key: String, where: String): List<Attr> = f.objs(key).filter {
		it.str("path").isNotEmpty()
	}.map {
		val d = it.str("dimension")
		checkDimension("$where.$key", d)
		Attr(d, it.str("path"))
	}

	private fun checkDimension(where: String, dimension: String) {
		if (dimension !in Dimensions.all) {
			throw TemplateException(Problem.BAD_DIMENSION, where, "unknown dimension $dimension")
		}
	}

	private fun substitute(v: JsonValue, vars: Map<String, String>): JsonValue = when (v) {
		is JsonString -> JsonString(fill(v.value, vars))

		is JsonArray -> JsonArray(v.items.map { substitute(it, vars) })

		is JsonObject -> JsonObject(
			v.fields.entries.associate { fill(it.key, vars) to substitute(it.value, vars) },
		)

		else -> v
	}

	private fun fill(text: String, vars: Map<String, String>): String {
		val out = StringBuilder()
		var i = 0
		while (i < text.length) {
			val open = text.indexOf("{{", i)
			if (open < 0) {
				out.append(text, i, text.length)
				break
			}
			val close = text.indexOf("}}", open)
			if (close < 0) throw TemplateException(Problem.BAD_VALUE, id, "unclosed placeholder")
			out.append(text, i, open)
			val name = text.substring(open + 2, close).trim()
			out.append(
				vars[name] ?: throw TemplateException(
					Problem.UNKNOWN_PLACEHOLDER,
					id,
					"no value for {{$name}}",
				),
			)
			i = close + 2
		}
		return out.toString()
	}

	companion object {
		private val ARM_MAPS = setOf("env", "ulimits", "files")
		private val PLACEHOLDER = Regex("\\{\\{([^}]*)}}")

		fun fromJson(json: JsonValue, at: String = "template"): Template {
			val f = Fields(
				at,
				json as? JsonObject ?: throw TemplateException(
					Problem.BAD_TYPE,
					at,
					"expected a map",
				),
			)
			val id = f.str("id")
			val where = id
			val instances = if (f.has("instances")) f.int("instances") else 1
			if (instances < 1) {
				throw TemplateException(
					Problem.BAD_VALUE,
					"$where.instances",
					"instances must be 1 or more",
				)
			}
			val variants = f.objs("variants").map { it.all() }
			val keys = variants.flatMap { it.keys }.toSet()
			variants.forEachIndexed { i, row ->
				if (row.keys != keys) {
					throw TemplateException(
						Problem.INCONSISTENT,
						"$where.variants[$i]",
						"every variant needs the same keys",
					)
				}
			}
			val draw = f.json["draw"].let { raw ->
				if (raw == null || raw == JsonNull) {
					emptyMap()
				} else {
					val o = raw as? JsonObject
						?: throw TemplateException(
							Problem.BAD_TYPE,
							"$where.draw",
							"expected a map",
						)
					o.fields.mapValues { (k, x) ->
						val items = (x as? JsonArray)?.items
							?.map { (it as? JsonString)?.value ?: "" }
							?: throw TemplateException(
								Problem.BAD_TYPE,
								"$where.draw.$k",
								"expected a list",
							)
						if (items.isEmpty()) {
							throw TemplateException(
								Problem.BAD_VALUE,
								"$where.draw.$k",
								"empty list",
							)
						}
						items
					}
				}
			}
			if ((keys intersect draw.keys).isNotEmpty()) {
				throw TemplateException(
					Problem.INCONSISTENT,
					where,
					"a name is both a variant key and a draw key",
				)
			}
			val capacity = when {
				variants.isNotEmpty() -> variants.size
				draw.isEmpty() -> 1
				else -> Int.MAX_VALUE
			}
			if (instances > capacity) {
				throw TemplateException(
					Problem.TOO_FEW_VARIANTS,
					where,
					"$instances instances but $capacity variants",
				)
			}
			val skip = setOf("id", "instances", "variants", "draw")
			val body = JsonObject(f.json.fields.filterKeys { it !in skip })
			val known = keys + draw.keys
			placeholders(body).forEach {
				if (it !in known) {
					throw TemplateException(
						Problem.UNKNOWN_PLACEHOLDER,
						where,
						"no value for {{$it}}",
					)
				}
			}
			return Template(id, instances, variants, draw, body)
		}

		fun listFromJson(json: JsonValue): List<Template> {
			val f = Fields(
				"file",
				json as? JsonObject ?: throw TemplateException(
					Problem.BAD_TYPE,
					"file",
					"expected a map",
				),
			)
			if (f.str("schema") != "1") {
				throw TemplateException(Problem.BAD_VALUE, "file.schema", "schema must be 1")
			}
			val list = f.json["templates"] as? JsonArray ?: throw f.missing("templates")
			val templates = list.items
				.mapIndexed { i, t -> fromJson(t, "templates[$i]") }
			val seen = mutableSetOf<String>()
			templates.forEach {
				if (!seen.add(it.id)) {
					throw TemplateException(Problem.DUPLICATE_ID, it.id, "duplicate template id")
				}
			}
			return templates
		}

		private fun placeholders(v: JsonValue): List<String> = when (v) {
			is JsonString -> PLACEHOLDER.findAll(v.value).map { it.groupValues[1].trim() }.toList()

			is JsonArray -> v.items.flatMap { placeholders(it) }

			is JsonObject -> v.fields.entries.flatMap {
				placeholders(JsonString(it.key)) + placeholders(it.value)
			}

			else -> emptyList()
		}

		private fun merge(base: JsonObject?, over: JsonObject?): JsonObject {
			val out = (base?.fields ?: emptyMap()).toMutableMap()
			for ((k, v) in over?.fields ?: emptyMap()) {
				val old = out[k]
				out[k] = if (k in ARM_MAPS && old is JsonObject && v is JsonObject) {
					JsonObject(old.fields + v.fields)
				} else {
					v
				}
			}
			return JsonObject(out)
		}

		fun differs(path: String, g: Arm, r: Arm): Boolean = when {
			path.startsWith("env.") -> path.substring(4).let { name ->
				if (name in g.env || name in r.env) {
					g.env[name] != r.env[name]
				} else {
					g.image != r.image
				}
			}

			path == "cgroup.memory.max" -> g.memory != r.memory

			path == "cgroup.cpu.max" -> g.cpus != r.cpus

			path.startsWith("limits.") -> path.substring(7).let { g.ulimits[it] != r.ulimits[it] }

			path == "cpu.count" -> g.cpuset != r.cpuset

			path.startsWith("tool.shell.") -> g.interpreter != r.interpreter

			path.startsWith("os.") || path.startsWith("tool.") -> g.image != r.image

			else -> !sameConfig(g, r)
		}

		fun sameConfig(g: Arm, r: Arm): Boolean = g.image == r.image && g.env == r.env &&
			g.memory == r.memory && g.cpus == r.cpus && g.cpuset == r.cpuset &&
			g.ulimits == r.ulimits && g.network == r.network && g.files == r.files &&
			g.interpreter == r.interpreter && g.args == r.args
	}
}
