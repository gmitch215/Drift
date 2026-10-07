package dev.gmitch215.drift.build

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.schema.FailsafeSchema

class RuleYamlException(message: String) : RuntimeException(message)

/** Reads a hand-written rule file and writes the JSON text that `Rule.fromJson` parses. */
object RuleYaml {
	private val load = Load(
		LoadSettings.builder().setSchema(FailsafeSchema()).setAllowDuplicateKeys(false).build(),
	)

	/** Every scalar stays a string except the top-level `schema`, which becomes an integer. */
	fun toJson(yaml: String, name: String, requireId: Boolean = true): String {
		val root = load(yaml, name)
		if (root !is Map<*, *>) throw RuleYamlException("$name: top level must be a mapping")
		if (requireId && root["id"] !is String) throw RuleYamlException("$name: id must be a string")
		val schema = root["schema"] as? String
		if (schema == null || !DIGITS.matches(schema)) {
			throw RuleYamlException("$name: schema must be an integer")
		}
		val out = StringBuilder()
		write(root, 0, out, topLevel = true)
		return out.append('\n').toString()
	}

	/** The parsed document: maps, lists and strings only. */
	fun load(yaml: String, name: String): Any? = try {
		load.loadFromString(yaml)
	} catch (e: RuntimeException) {
		throw RuleYamlException("$name: ${e.message}")
	}

	private val DIGITS = Regex("[0-9]+")

	private fun write(v: Any?, depth: Int, out: StringBuilder, topLevel: Boolean = false) {
		when (v) {
			is String -> quote(v, out)

			is Map<*, *> -> container(v.entries, '{', '}', depth, out) { (k, x) ->
				quote(k as String, out)
				out.append(": ")
				if (topLevel && k == "schema") out.append(x as String) else write(x, depth + 1, out)
			}

			is List<*> -> container(v, '[', ']', depth, out) { write(it, depth + 1, out) }

			else -> throw RuleYamlException("unsupported value $v")
		}
	}

	private fun <T> container(
		items: Collection<T>,
		open: Char,
		close: Char,
		depth: Int,
		out: StringBuilder,
		each: (T) -> Unit,
	) {
		out.append(open)
		if (items.isNotEmpty()) {
			val pad = "\t".repeat(depth + 1)
			items.forEachIndexed { i, item ->
				out.append(if (i == 0) "\n" else ",\n").append(pad)
				each(item)
			}
			out.append('\n').append("\t".repeat(depth))
		}
		out.append(close)
	}

	private fun quote(s: String, out: StringBuilder) {
		out.append('"')
		for (c in s) {
			when {
				c == '"' -> out.append("\\\"")
				c == '\\' -> out.append("\\\\")
				c == '\n' -> out.append("\\n")
				c == '\r' -> out.append("\\r")
				c == '\t' -> out.append("\\t")
				c == '\b' -> out.append("\\b")
				c == '\u000c' -> out.append("\\f")
				c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
				else -> out.append(c)
			}
		}
		out.append('"')
	}
}
