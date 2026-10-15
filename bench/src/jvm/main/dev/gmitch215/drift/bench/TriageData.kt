package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.string
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Builds `triage/triage.json` from `triage/items.yml` and the bodies under `fixtures/triage`. */
class TriageData(root: Path) {
    private val bench = root.toAbsolutePath().normalize()
    private val dir = bench.resolve("triage")
    private val fixtures = bench.resolveSibling("fixtures").resolve("triage")

    private val emptyFacts = Regex("(?m)^facts:[ ]*\\n(?!  - )")

    fun items(): List<TriageItem> {
        val index = Yaml.parse(dir.resolve("items.yml").readText(), "items.yml") as JsonObject
        val ids = mutableSetOf<String>()
        return index.fields.getValue("items").array().map { entry ->
            val o = (entry as JsonObject).fields
            val id = o.getValue("id").string()
            require(ids.add(id)) { "duplicate triage id $id" }
            val source = o.getValue("source").string()
            require(source == "fixture") { "$id: only fixture items are supported, got $source" }
            val name = o.getValue("body").string()
            val truth = Yaml.parse(
                emptyFacts.replace(fixtures.resolve("$name.yml").readText(), "facts: []\n"),
                "$name.yml",
            )
            val t = (truth as JsonObject).fields
            TriageItem(
                id,
                source,
                fixtures.resolve("$name.md").readText(),
                strings(t["facts"]),
                strings(t["claims"]),
                (o["correction"] as? JsonString)?.value,
            )
        }
    }

    fun generate(): String = TriageBench.toJson(items())

    fun write() = dir.resolve("triage.json").writeText(generate())

    private fun strings(v: Any?): Set<String> =
        ((v as? JsonArray)?.items ?: emptyList()).map { it.string() }.toSet()
}
