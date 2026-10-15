package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.upgrade.Finding
import dev.gmitch215.drift.upgrade.Group
import dev.gmitch215.drift.upgrade.Target
import dev.gmitch215.drift.upgrade.Targets
import dev.gmitch215.drift.upgrade.UpgradeCheck
import dev.gmitch215.drift.upgrade.UpgradeReport

/**
 * One move of a migration scenario. A `break` item takes the passing arm's capsule and moves its
 * cause attribute to the failing arm's value, so the measured red arm is the truth. A `control`
 * item takes the failing arm's capsule and moves to the passing value: the green arm passed every
 * trial, so nothing breaks. [downgrade] is true when the target is older than the capsule.
 */
class UpgradeItem(
    val scenario: String,
    val template: String,
    val move: String,
    val downgrade: Boolean,
    val input: Capsule,
    val target: Target,
    val signature: String,
    val explained: Boolean,
) {
    val breaks: Boolean get() = move == BREAK

    companion object {
        const val BREAK = "break"
        const val CONTROL = "control"
    }
}

class UpgradeResult(val item: UpgradeItem, val report: UpgradeReport) {
    /** The rule findings of one group, or of the three scored groups for `any`. */
    fun findings(flag: String): List<Finding> = when (flag) {
        UpgradeBench.ANY -> UpgradeBench.FLAGS.filter { it != UpgradeBench.ANY }
            .flatMap { findings(it) }

        else -> report.of(Group.entries.first { it.id == flag })
    }

    /** Findings whose rule names the measured failure of the scenario. */
    fun confirmed(flag: String): List<Finding> =
        findings(flag).filter { UpgradeBench.names(it.rule, item.signature) }

    /** Locked packages that will break; they carry no symptom text, so none is confirmed. */
    fun packages(flag: String): Int =
        if (flag == Group.WILL_BREAK.id || flag == UpgradeBench.ANY) report.packages.size else 0
}

/**
 * [reported] counts the findings of the group on `break` moves and [onControl] on `control`
 * moves; [confirmed] are the break-move findings that name the measured failure. Precision is
 * confirmed over every reported finding; recall is the share of break items with a confirmed
 * finding.
 */
class FlagScore(
    val flag: String,
    val reported: Int,
    val confirmed: Int,
    val onControl: Int,
    val precision: Rate,
    val recall: Rate,
)

/** [breaks] and [controls] count the items of the stratum; [flags] has one row per flag. */
class UpgradeStratum(
    val name: String,
    val breaks: Int,
    val controls: Int,
    val flags: List<FlagScore>,
)

class UpgradeScore(val scenarios: Int, val strata: List<UpgradeStratum>) {
    fun report(name: String): String = buildString {
        appendLine("upgrade-check $name: $scenarios scenarios, ${scenarios * 2} items")
        for (s in strata) {
            appendLine("  ${s.name}: ${s.breaks} break items, ${s.controls} control items")
            for (f in s.flags) {
                val found = "${f.reported} on break, ${f.onControl} on control, " +
                    "${f.confirmed} confirmed"
                appendLine("    ${f.flag.padEnd(16)} findings $found")
                appendLine("    ${"".padEnd(16)} precision ${rate(f.precision)}")
                appendLine("    ${"".padEnd(16)} recall    ${rate(f.recall)}")
            }
        }
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

/**
 * Upgrade-check scoring over the migration scenarios; a separate category that never enters the
 * environment tables. Dev items read the committed capsules through a [Lab]; test items need the
 * [Unsealed] proof.
 */
object UpgradeBench {
    const val ANY = "any"

    val FLAGS = listOf(
        Group.WILL_BREAK.id,
        Group.VERSION_BOUNDARY.id,
        Group.MAY_BREAK.id,
        ANY,
    )

    const val PREFIX = "migration-"

    fun scenarios(lab: Lab): List<Scenario> =
        lab.scenarios.filter { it.template.startsWith(PREFIX) && it.cause != null }

    /** Scenario counts by split, from the committed scenario list and split only. */
    fun counts(lab: Lab): Map<String, Int> {
        val all = scenarios(lab)
        return linkedMapOf(
            "dev" to all.count { it.id in lab.dev },
            "test" to all.count { it.id !in lab.dev },
        )
    }

    /** A rule names a failure when one of its symptoms contains the signature text. */
    fun names(rule: Rule, signature: String): Boolean =
        rule.symptoms.any { it.text.lowercase().contains(signature.lowercase()) }

    fun explained(signature: String, rules: List<Rule> = Rules.all): Boolean =
        rules.any { names(it, signature) }

    fun items(lab: Lab, ids: List<String>, rules: List<Rule> = Rules.all): List<UpgradeItem> {
        val wanted = ids.toSet()
        return scenarios(lab).filter { it.id in wanted }.sortedBy { it.id }.flatMap { s ->
            val (green, red) = lab.pair(s.id)
            val path = s.cause!!.path
            val g = green.attributes.firstOrNull { it.path == path }?.value
            val r = red.attributes.firstOrNull { it.path == path }?.value
            if (g == null || r == null || g == r) return@flatMap emptyList()
            val older = s.cause.direction == "lower"
            val explained = explained(s.signature, rules)
            listOf(
                UpgradeItem(
                    s.id,
                    s.template,
                    UpgradeItem.BREAK,
                    older,
                    green,
                    target(path, r),
                    s.signature,
                    explained,
                ),
                UpgradeItem(
                    s.id,
                    s.template,
                    UpgradeItem.CONTROL,
                    !older,
                    red,
                    target(path, g),
                    s.signature,
                    explained,
                ),
            )
        }
    }

    private fun target(path: String, value: String): Target =
        Targets.resolve(listOf(path to value)).single()

    fun run(items: List<UpgradeItem>, rules: List<Rule> = Rules.all): List<UpgradeResult> =
        items.map { UpgradeResult(it, UpgradeCheck.run(it.input, listOf(it.target), rules)) }

    fun runDev(lab: Lab, rules: List<Rule> = Rules.all): List<UpgradeResult> =
        run(items(lab, lab.devIds, rules), rules)

    fun runTest(lab: Lab, unsealed: Unsealed, rules: List<Rule> = Rules.all): List<UpgradeResult> {
        require(unsealed.test.isNotEmpty()) { "nothing is unsealed" }
        return run(items(lab, unsealed.test, rules), rules)
    }

    fun score(results: List<UpgradeResult>): UpgradeScore {
        val strata = listOf(
            "all" to results,
            "rule-matched" to results.filter { it.item.explained },
            "unmatched" to results.filter { !it.item.explained },
        ).map { (name, rows) -> stratum(name, rows) }
        return UpgradeScore(results.map { it.item.scenario }.distinct().size, strata)
    }

    private fun stratum(name: String, rows: List<UpgradeResult>): UpgradeStratum {
        val breaks = rows.filter { it.item.breaks }
        val controls = rows.filter { !it.item.breaks }
        val flags = FLAGS.map { flag ->
            val reported = breaks.sumOf { it.findings(flag).size + it.packages(flag) }
            val onControl = controls.sumOf { it.findings(flag).size + it.packages(flag) }
            val confirmed = breaks.sumOf { it.confirmed(flag).size }
            val hit = breaks.count { it.confirmed(flag).isNotEmpty() }
            FlagScore(
                flag,
                reported,
                confirmed,
                onControl,
                Rate(confirmed, reported + onControl),
                Rate(hit, breaks.size),
            )
        }
        return UpgradeStratum(name, breaks.size, controls.size, flags)
    }

    /** The generated record of the dev items and what upgrade-check reported for each. */
    fun toJson(lab: Lab, results: List<UpgradeResult>): String = CanonicalJson.encode(
        obj(
            "schema" to JsonInt(1L),
            "counts" to JsonObject(counts(lab).mapValues { JsonInt(it.value.toLong()) }),
            "items" to JsonArray(
                results.map { r ->
                    val i = r.item
                    obj(
                        "scenario" to JsonString(i.scenario),
                        "move" to JsonString(i.move),
                        "downgrade" to JsonBool(i.downgrade),
                        "input" to JsonString(i.input.hash()),
                        "to" to JsonString("${i.target.key}=${i.target.value}"),
                        "signature" to JsonString(i.signature),
                        "explained" to JsonBool(i.explained),
                        "groups" to JsonObject(
                            Group.entries.associate { g ->
                                g.id to JsonArray(r.report.of(g).map { JsonString(it.rule.id) })
                            } + ("will-break-packages" to JsonInt(r.report.packages.size.toLong())),
                        ),
                    )
                },
            ),
        ),
    ) + "\n"
}
