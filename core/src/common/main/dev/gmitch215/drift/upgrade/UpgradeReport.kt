package dev.gmitch215.drift.upgrade

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Status

enum class Group(val id: String, val heading: String) {
    WILL_BREAK("will-break", "Will break"),
    MAY_BREAK("may-break", "May break"),
    VERSION_BOUNDARY("version-boundary", "Version boundary crossed"),
    UNKNOWN("unknown", "Unknown"),
}

/** [was] and [now] are the rule's conditions on [key] before and after the move. */
data class Crossing(
    val key: String,
    val from: String?,
    val to: String,
    val was: List<String>,
    val now: List<String>,
)

/**
 * [missing] lists the attributes the rule reads that the capture or a scanner does not give;
 * [evidence] lists the non-target attributes a matched clause read.
 */
data class Finding(
    val rule: Rule,
    val group: Group,
    val basis: Basis,
    val crossings: List<Crossing>,
    val missing: List<String>,
    val evidence: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = obj(
        "rule" to JsonString(rule.id),
        "title" to JsonString(rule.title),
        "severity" to JsonString(rule.severity.id),
        "basis" to JsonString(basis.id),
        "status" to JsonString(rule.status.id),
        "mechanism" to JsonString(rule.mechanism),
        "crossings" to JsonArray(
            crossings.map {
                obj(
                    "key" to JsonString(it.key),
                    "from" to jsonOrNull(it.from),
                    "to" to JsonString(it.to),
                    "was" to JsonArray(it.was.map(::JsonString)),
                    "now" to JsonArray(it.now.map(::JsonString)),
                )
            },
        ),
        "missing" to JsonArray(missing.map(::JsonString)),
        "evidence" to JsonArray(evidence.map(::JsonString)),
        "sources" to JsonArray(
            rule.provenance.map {
                JsonObject(
                    buildMap {
                        put("url", JsonString(it.url))
                        put("kind", JsonString(it.kind.id))
                        if (it.section != null) put("section", JsonString(it.section))
                    },
                )
            },
        ),
    )
}

/** [rules] is the rule count; [touched] counts the rules that read a target key. */
data class UpgradeReport(
    val label: String,
    val targets: List<Row>,
    val rules: Int,
    val touched: Int,
    val findings: List<Finding>,
) {
    data class Row(val target: Target, val from: String?)

    fun of(group: Group) = findings.filter { it.group == group }

    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("schema", JsonInt(SCHEMA))
            put("label", JsonString(label))
            put(
                "targets",
                JsonArray(
                    targets.map {
                        obj(
                            "name" to JsonString(it.target.name),
                            "key" to JsonString(it.target.key),
                            "from" to jsonOrNull(it.from),
                            "to" to JsonString(it.target.value),
                        )
                    },
                ),
            )
            put("rules", JsonInt(rules.toLong()))
            put("touched", JsonInt(touched.toLong()))
            for (g in Group.entries) put(g.id, JsonArray(of(g).map { it.toJson() }))
        },
    )

    fun json(): String = CanonicalJson.encode(toJson())

    fun text(): String = buildString {
        val name = targets.maxOfOrNull { it.target.name.length } ?: 0
        val key = targets.maxOfOrNull { it.target.key.length } ?: 0
        appendLine("upgrade-check: $label")
        for (row in targets) {
            val t = row.target
            val line = "${t.name.padEnd(name)}  ${t.key.padEnd(key)}"
            appendLine("  $line  ${row.from ?: "unknown"} -> ${t.value}")
        }
        appendLine("$touched of $rules rules read a target key")
        for (g in Group.entries) {
            val list = of(g)
            appendLine()
            appendLine("${g.heading} (${list.size})")
            if (g == Group.VERSION_BOUNDARY && list.isNotEmpty()) appendLine(VERSION_NOTE)
            if (list.isEmpty()) appendLine("  none")
            list.forEachIndexed { i, f -> finding(i + 1, f) }
        }
    }

    private fun StringBuilder.finding(n: Int, f: Finding) {
        val r = f.rule
        appendLine("  $n. [${r.severity.id}, ${f.basis.id}] ${r.title}")
        appendLine("     rule: ${r.id}")
        for (c in f.crossings) {
            val was = c.was.joinToString("; ").ifEmpty { "any" }
            appendLine(
                "     boundary: ${c.key} ${c.from ?: "unknown"} -> ${c.to} " +
                    "(rule: was $was, now ${c.now.joinToString("; ")})",
            )
        }
        if (f.group == Group.WILL_BREAK) {
            appendLine("     evidence: ${f.evidence.joinToString(", ")}")
        }
        if (f.missing.isNotEmpty()) {
            val pending = r.status == Status.PENDING_SCANNER
            val note = if (pending) "; the rule is pending-scanner" else ""
            appendLine("     needs: ${f.missing.joinToString(", ")}$note")
        }
        appendLine("     mechanism: ${r.mechanism}")
        for (s in r.provenance) appendLine("     source: ${s.url}")
    }

    companion object {
        const val SCHEMA = 2L

        const val VERSION_NOTE =
            "  These rules apply to the versions crossed; no project fact or other " +
                "environment key in the capture matched them."
    }
}
