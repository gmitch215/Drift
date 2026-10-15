package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.triage.Side
import dev.gmitch215.drift.triage.Triage

/**
 * One labelled issue body. [facts] are `key=value` (or `key=value@passing`) strings, [claims] the
 * claim ids the claim check should raise and [correction] what a maintainer would answer. [source]
 * is `fixture`; a corpus item would carry its own body.
 */
class TriageItem(
    val id: String,
    val source: String,
    val body: String,
    val facts: Set<String>,
    val claims: Set<String>,
    val correction: String?,
) {
    fun toJson(): JsonObject = obj(
        "id" to JsonString(id),
        "source" to JsonString(source),
        "body" to JsonString(body),
        "facts" to JsonArray(facts.sorted().map { JsonString(it) }),
        "claims" to JsonArray(claims.sorted().map { JsonString(it) }),
        "correction" to jsonOrNull(correction),
    )

    companion object {
        fun fromJson(v: JsonValue): TriageItem {
            val o = (v as JsonObject).fields
            return TriageItem(
                o.getValue("id").string(),
                o.getValue("source").string(),
                o.getValue("body").string(),
                o.getValue("facts").array().map { it.string() }.toSet(),
                o.getValue("claims").array().map { it.string() }.toSet(),
                (o["correction"] as? JsonString)?.value,
            )
        }
    }
}

class TriageScore(
    val items: Int,
    val precision: Rate,
    val recall: Rate,
    val brier: Long,
    val brierLow: Long,
    val brierHigh: Long,
    val facts: Int,
    val claimPrecision: Rate,
    val claimRecall: Rate,
) {
    fun report(name: String): String = buildString {
        appendLine("triage $name: $items items, $facts facts scored")
        appendLine("  fact precision ${rate(precision)}")
        appendLine("  fact recall ${rate(recall)}")
        appendLine("  fact Brier ${micro(brier)} (95% ${micro(brierLow)} to ${micro(brierHigh)})")
        appendLine("  claim precision ${rate(claimPrecision)} (experimental)")
        appendLine("  claim recall ${rate(claimRecall)}")
    }

    private fun micro(v: Long) =
        "${v / Metrics.MICRO}." + (v % Metrics.MICRO).toString().padStart(6, '0')

    private fun rate(r: Rate): String {
        if (r.total == 0) return "n/a (0 of 0)"
        val w = r.wilson()
        val ci = "Wilson ${micro(w.low)} to ${micro(w.high)}"
        return "${micro(r.micro)} (${r.count} of ${r.total}, $ci)"
    }
}

/** Triage scoring; a separate category that never enters the environment tables. */
object TriageBench {
    const val SEED = 20261015L
    private const val DRAWS = 2000

    fun parse(text: String): List<TriageItem> =
        (CanonicalJson.parse(text) as JsonArray).items.map { TriageItem.fromJson(it) }

    fun toJson(items: List<TriageItem>): String =
        CanonicalJson.encode(JsonArray(items.sortedBy { it.id }.map { it.toJson() })) + "\n"

    fun split(items: List<TriageItem>): Split = SplitRule.split(items.map { it.id })

    /** Scores the dev items only. */
    fun scoreDev(items: List<TriageItem>, rules: List<Rule> = Rules.all): TriageScore {
        val dev = split(items).dev.toSet()
        return score(items.filter { it.id in dev }, rules)
    }

    /** Scores the test items; [unsealed] is the proof that the freeze matched. */
    fun scoreTest(
        items: List<TriageItem>,
        unsealed: Unsealed,
        rules: List<Rule> = Rules.all,
    ): TriageScore {
        require(unsealed.test.isNotEmpty()) { "nothing is unsealed" }
        val test = split(items).test.toSet()
        return score(items.filter { it.id in test }, rules)
    }

    private fun score(items: List<TriageItem>, rules: List<Rule>): TriageScore {
        var tp = 0
        var fp = 0
        var fn = 0
        var claimHits = 0
        var claimRight = 0
        var claimWanted = 0
        var claimFound = 0
        val perItem = mutableListOf<List<Pair<Long, Boolean>>>()
        for (item in items) {
            val result = Triage.run(item.body, rules = rules)
            val forecasts = mutableListOf<Pair<Long, Boolean>>()
            val got = mutableSetOf<String>()
            for (f in result.facts) {
                val key = "${f.key}=${f.value}" + if (f.side == Side.PASSING) "@passing" else ""
                val ok = key in item.facts
                got += key
                forecasts += f.confidence.permille * 1000L to ok
                if (ok) tp++ else fp++
            }
            perItem += forecasts
            fn += (item.facts - got).size
            val ids = result.claims.map { it.id }
            claimHits += ids.size
            claimRight += ids.count { it in item.claims }
            claimWanted += item.claims.size
            claimFound += item.claims.count { it in ids }
        }
        val rng = SplitMix64(SEED)
        val draws = if (items.isEmpty()) {
            listOf(0L)
        } else {
            List(DRAWS) {
                Metrics.brier(List(items.size) { perItem[rng.below(items.size)] }.flatten())
            }.sorted()
        }
        return TriageScore(
            items.size,
            Rate(tp, tp + fp),
            Rate(tp, tp + fn),
            Metrics.brier(perItem.flatten()),
            draws[draws.size * 25 / 1000],
            draws[draws.size * 975 / 1000],
            perItem.sumOf { it.size },
            Rate(claimRight, claimHits),
            Rate(claimFound, claimWanted),
        )
    }
}
