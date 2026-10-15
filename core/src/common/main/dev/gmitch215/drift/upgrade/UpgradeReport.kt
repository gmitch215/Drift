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
    OUTSIDE_RANGE("outside-range", "Outside the tested range"),
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

/**
 * A locked package whose recorded [runtime] [constraint] excludes the target ([to]) and allows
 * the version the capture has ([from]); [file] is the lockfile that records it.
 */
data class PackageBreak(
    val ecosystem: String,
    val name: String,
    val version: String?,
    val runtime: String,
    val constraint: String,
    val from: String,
    val to: String,
    val file: String,
) {
    fun toJson(): JsonObject = obj(
        "ecosystem" to JsonString(ecosystem),
        "package" to JsonString(name),
        "version" to jsonOrNull(version),
        "runtime" to JsonString(runtime),
        "constraint" to JsonString(constraint),
        "from" to JsonString(from),
        "to" to JsonString(to),
        "file" to JsonString(file),
    )
}

/** Package constraints that could not be checked: [packages] names them, or is empty for all. */
data class Unchecked(
    val file: String,
    val runtime: String,
    val reason: String,
    val packages: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = obj(
        "file" to JsonString(file),
        "runtime" to JsonString(runtime),
        "reason" to JsonString(reason),
        "packages" to JsonArray(packages.map(::JsonString)),
    )
}

/**
 * [rules] is the rule count; [touched] counts the rules that read a target key. [packages] are
 * the locked packages that will break and [unchecked] the constraints that could not be read.
 */
data class UpgradeReport(
    val label: String,
    val targets: List<Row>,
    val rules: Int,
    val touched: Int,
    val findings: List<Finding>,
    val packages: List<PackageBreak> = emptyList(),
    val unchecked: List<Unchecked> = emptyList(),
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
            put("will-break-packages", JsonArray(packages.map { it.toJson() }))
            put("unchecked-packages", JsonArray(unchecked.map { it.toJson() }))
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
            if (g == Group.OUTSIDE_RANGE && list.isEmpty()) continue
            appendLine()
            appendLine("${g.heading} (${list.size})")
            if (g == Group.VERSION_BOUNDARY && list.isNotEmpty()) appendLine(VERSION_NOTE)
            if (g == Group.OUTSIDE_RANGE) appendLine(RANGE_NOTE)
            if (list.isEmpty()) appendLine("  none")
            list.forEachIndexed { i, f -> finding(i + 1, f) }
        }
        if (packages.isNotEmpty()) {
            appendLine()
            appendLine("Will break: locked packages (${packages.size})")
            packages.forEachIndexed { i, p ->
                val version = p.version?.let { " $it" }.orEmpty()
                appendLine("  ${i + 1}. ${p.name}$version (${p.ecosystem}, ${p.file})")
                val need = "requires ${p.runtime} ${p.constraint}"
                appendLine("     $need; ${p.runtime} ${p.to} is outside it")
            }
        }
        if (unchecked.isNotEmpty()) {
            appendLine()
            appendLine("Unchecked package constraints (${unchecked.size})")
            unchecked.forEachIndexed { i, u ->
                appendLine("  ${i + 1}. ${u.file}, ${u.runtime}: ${u.reason}")
                if (u.packages.isNotEmpty()) appendLine("     ${u.packages.joinToString(", ")}")
            }
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
        if (f.group == Group.WILL_BREAK || f.group == Group.OUTSIDE_RANGE) {
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

        const val RANGE_NOTE =
            "  The source lists a range of compatible versions and states no failure outside it."
    }
}
