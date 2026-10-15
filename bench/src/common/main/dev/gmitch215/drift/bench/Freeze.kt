package dev.gmitch215.drift.bench

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.know.Rules

/** Proof that the frozen files matched at unseal; only `Work` creates one. */
class Unsealed internal constructor(val test: List<String>)

class UnsealException(message: String) : Exception(message)

/**
 * The frozen state of the benchmark: `data/freeze.json` holds the engine commit and one value per
 * key of [Work.currentFreeze]; an unseal needs every value to match the working tree.
 */
object Freeze {
    const val HOLDOUT = "holdout_templates"

    /** Hash of every rule file in id order, as the catalog embedded in `core`. */
    fun rulesSha256(): String =
        Sha256.hex(Rules.ids.joinToString("") { it + "\n" + Rules.text(it) + "\n" })

    /** The held-out templates that have scenarios, in id order. */
    fun holdoutTemplates(scenarios: Collection<Scenario>): List<String> =
        scenarios.map { it.template }.filter { SplitRule.isHeldOut(it) }.distinct().sorted()

    fun toJson(commit: String, current: Map<String, String>): String = CanonicalJson.encode(
        JsonObject(
            buildMap {
                put("commit", JsonString(commit))
                for ((key, value) in current) {
                    put(
                        key,
                        if (key == HOLDOUT) {
                            JsonArray(value.split(',').filter { it.isNotEmpty() }.map(::JsonString))
                        } else {
                            JsonString(value)
                        },
                    )
                }
            },
        ),
    ) + "\n"

    /** The keys of [current] whose frozen value differs or is missing, with both values. */
    fun mismatches(frozenText: String, current: Map<String, String>): List<String> {
        val frozen = (CanonicalJson.parse(frozenText) as JsonObject).fields
        return current.mapNotNull { (key, now) ->
            val was = when (val v = frozen[key]) {
                null -> "missing"
                is JsonArray -> v.items.joinToString(",") { it.string() }
                else -> v.string()
            }
            if (was == now) null else "$key (frozen $was, now $now)"
        }
    }
}
