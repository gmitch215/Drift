package dev.gmitch215.drift.know

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.obj

/**
 * The learned-rule store: one canonical JSON file, `learned.json`, in a directory the user
 * names. It holds rules in the schema of `rules/`, each checked by [RuleCompiler] on every read.
 * A learned rule never replaces a shipped one, so an id that a shipped rule already uses is
 * refused.
 */
object Learned {
    const val FILE = "learned.json"
    private const val SCHEMA = 1L

    /** The store file inside [dir]. */
    fun path(dir: String) = dir.trimEnd('/', '\\') + "/" + FILE

    /** Every rule of the store [text]; throws [RuleException] on the first problem. */
    fun parse(text: String): List<Rule> {
        val root = try {
            CanonicalJson.parse(text) as? JsonObject
        } catch (e: JsonException) {
            fail("", e.message.orEmpty())
        } ?: fail("", "the store is not a JSON object")
        if ((root.fields["schema"] as? JsonInt)?.value != SCHEMA) fail("schema", "expected $SCHEMA")
        val items = (root.fields["rules"] as? JsonArray)?.items ?: fail("rules", "expected array")
        val rules = items.map { RuleCompiler.compile(it) }
        val seen = mutableSetOf<String>()
        for (rule in rules) {
            if (rule.id in Rules.ids) {
                throw RuleException(Violation(rule.id, "id", "a shipped rule has this id"))
            }
            if (!seen.add(rule.id)) {
                throw RuleException(Violation(rule.id, "id", "listed twice in the store"))
            }
        }
        return rules
    }

    /** The store text with [rule] added, or replaced when its id is already there. */
    fun add(text: String?, rule: Rule): String {
        val problem = RuleCompiler.validate(rule).firstOrNull()
        if (problem != null) throw RuleException(problem)
        if (rule.id in Rules.ids) {
            throw RuleException(Violation(rule.id, "id", "a shipped rule has this id"))
        }
        val kept = text?.let(::parse).orEmpty().filter { it.id != rule.id }
        val all = (kept + rule).sortedBy { it.id }
        return CanonicalJson.encode(
            obj("schema" to JsonInt(SCHEMA), "rules" to JsonArray(all.map { it.toJson() })),
        ) + "\n"
    }
}
