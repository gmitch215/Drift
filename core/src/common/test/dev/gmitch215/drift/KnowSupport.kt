package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.know.Env
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.RuleCompiler

const val GCC_14 =
	"""{"all":[{"a":"tool.cc.version","ver":"<14"},{"b":"tool.cc.version","ver":">=14"}]}"""

fun json(text: String): JsonValue = CanonicalJson.parse(text)

fun cc(version: String) = Env(attrs = mapOf("tool.cc.version" to version))

fun ruleJson(detect: String = GCC_14, vararg edits: Pair<String, String?>): JsonObject {
	val base = json(
		"""{"schema":1,"id":"demo-rule","title":"Demo","applies":["compiler"],"difference":"d",
		|"symptoms":[{"text":"t","basis":"inferred"}],"mechanism":"M.","detect":$detect,
		|"severity":"low","provenance":[{"url":"https://example.com/x","kind":"spec",
		|"verified":"2026-10-06"}],
		|"fixtures":{"positive":[{"name":"p","a":{"attrs":{"tool.cc.version":"13.2.1"}},
		|"b":{"attrs":{"tool.cc.version":"14.2.0"}}}],
		|"negative":[{"name":"n","a":{"attrs":{"tool.cc.version":"14.2.0"}},
		|"b":{"attrs":{"tool.cc.version":"14.2.0"}}}]}}
		""".trimMargin().replace("\n", ""),
	) as JsonObject
	val fields = base.fields.toMutableMap()
	for ((key, value) in edits) if (value == null) fields.remove(key) else fields[key] = json(value)
	return JsonObject(fields)
}

fun demoRule(detect: String = GCC_14, vararg edits: Pair<String, String?>): Rule =
	RuleCompiler.compile(ruleJson(detect, *edits))
