package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.upgrade.Crossing
import dev.gmitch215.drift.upgrade.Finding
import dev.gmitch215.drift.upgrade.Group
import dev.gmitch215.drift.upgrade.Target
import dev.gmitch215.drift.upgrade.TargetException
import dev.gmitch215.drift.upgrade.Targets
import dev.gmitch215.drift.upgrade.UpgradeCheck
import dev.gmitch215.drift.upgrade.UpgradeReport

const val UPGRADE_ABOUT =
    "Before you upgrade a tool or a framework, Drift reads a capsule, a saved snapshot of your " +
        "environment, and lists documented changes that would break it, such as a removed " +
        "feature. Studio only checks; it changes nothing."

const val UPGRADE_CAVEAT =
    "A quiet result does not show that a move is safe. Many rules describe a move in one " +
        "direction only, and Drift has no rule for features that a newer version only adds."

/** What a group means, in fixed words; the groups are the engine's, not Studio's. */
class GroupWords(val title: String, val about: String, val none: String)

/** A move people often check; it is offered only when the capsule holds every setting it names. */
class Preset(val label: String, val text: String)

/** The result of one check: the engine's report and the sentence that leads it. */
class UpgradeView(val report: UpgradeReport, val headline: String)

sealed interface UpgradeCheckResult {
    class Done(val view: UpgradeView) : UpgradeCheckResult

    class Bad(val message: String) : UpgradeCheckResult
}

object UpgradeStory {
    const val NO_TARGETS = "Type at least one version to move to, such as php=8.3."

    private val display = mapOf(
        "agp" to "Android Gradle Plugin",
        "composer" to "Composer",
        "docker" to "Docker",
        "drupal" to "Drupal",
        "git" to "Git",
        "go" to "Go",
        "gradle" to "Gradle",
        "java" to "Java",
        "jdk" to "Java",
        "kotlin" to "Kotlin",
        "node" to "Node.js",
        "npm" to "npm",
        "openssl" to "OpenSSL",
        "php" to "PHP",
        "python" to "Python",
        "ruby" to "Ruby",
        "symfony" to "Symfony",
        "twig" to "Twig",
        "tzdata" to "Time zone database",
    )

    val presets = listOf(
        Preset("PHP 8.3", "php=8.3"),
        Preset("PHP 8.3 and Drupal 11", "php=8.3,drupal=11"),
        Preset("Node.js 22", "node=22"),
        Preset("Java 21", "java=21"),
        Preset("Python 3.13", "python=3.13"),
        Preset("Gradle 9", "gradle=9"),
    )

    fun words(group: Group): GroupWords = when (group) {
        Group.WILL_BREAK -> GroupWords(
            "Will Break",
            "A rule matches this move on a fact in the capsule beyond the version, and every " +
                "fact it reads is known.",
            "No rule matches this move on a fact in the capsule beyond the version.",
        )

        Group.MAY_BREAK -> GroupWords(
            "May Break",
            "A rule could match, but a fact it reads is missing or not collected yet.",
            "No rule is waiting on a missing fact.",
        )

        Group.VERSION_BOUNDARY -> GroupWords(
            "Version Boundary Crossed",
            "The change applies to the versions this move crosses. Nothing else in the capsule " +
                "matched, so the project may not use the affected feature.",
            "No rule matches on the version change alone.",
        )

        Group.OUTSIDE_RANGE -> GroupWords(
            "Outside the Tested Range",
            "A source lists the compatible versions and this move leaves them. It states no " +
                "failure, so the move may still work.",
            "No move leaves a documented compatible range.",
        )

        Group.UNKNOWN -> GroupWords(
            "Unknown",
            "Drift cannot judge these: the capsule lacks a current value Drift needs, or the " +
                "rule cannot compare the version.",
            "Nothing is outside what Drift can judge.",
        )
    }

    /** The presets whose settings the capsule has. */
    fun presetsFor(capsule: Capsule): List<Preset> {
        val paths = capsule.attributes.map { it.path }.toSet()
        return presets.filter { preset ->
            Targets.resolve(Targets.parseList(preset.text)).all { it.key in paths }
        }
    }

    fun check(capsule: Capsule, text: String): UpgradeCheckResult {
        val entries = try {
            Targets.parseList(text)
        } catch (e: TargetException) {
            return UpgradeCheckResult.Bad(e.message.orEmpty())
        }
        if (entries.isEmpty()) return UpgradeCheckResult.Bad(NO_TARGETS)
        val targets = try {
            Targets.resolve(entries)
        } catch (e: TargetException) {
            return UpgradeCheckResult.Bad(e.message.orEmpty())
        }
        val report = UpgradeCheck.run(capsule, targets)
        return UpgradeCheckResult.Done(UpgradeView(report, headline(report)))
    }

    /** One sentence from the group counts; every phrase is fixed text or a value from [report]. */
    fun headline(report: UpgradeReport): String {
        val will = report.of(Group.WILL_BREAK).size
        val may = report.of(Group.MAY_BREAK).size
        val boundary = report.of(Group.VERSION_BOUNDARY).size
        val range = report.of(Group.OUTSIDE_RANGE).size
        val unknown = report.of(Group.UNKNOWN).size
        val move = "moving to ${moveTo(report.targets.map { it.target })}"
        val first = if (will > 0) {
            "${changes(will)} will likely break this environment when $move."
        } else {
            "No documented change is known to break this environment when $move."
        }
        val more = will > 0
        val second = if (may > 0) " ${changes(may, more)} may break." else ""
        val crossed = if (boundary > 0) {
            " ${changes(boundary, more)} apply to these versions; nothing in the capsule shows " +
                "they are used."
        } else {
            ""
        }
        val outside = if (range > 0) {
            " ${changes(range, more)} leave a documented compatible range."
        } else {
            ""
        }
        val third = if (unknown > 0) " ${changes(unknown, more)} cannot be judged." else ""
        return first + second + crossed + outside + third
    }

    private fun changes(n: Int, more: Boolean = false): String {
        val noun = if (n == 1) "change" else "changes"
        return if (more) "$n more $noun" else "$n $noun"
    }

    fun moveTo(targets: List<Target>): String {
        val parts = targets.map { "${name(it)} ${it.value}" }.sorted()
        if (parts.size < 2) return parts.joinToString("")
        return parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    private fun name(target: Target): String =
        display[target.name.lowercase()] ?: Plain.title(target.key)

    /** The setting a key holds, such as `PHP version` for `tool.php.version`. */
    fun keyName(key: String): String {
        val alias = Targets.aliases.entries.firstOrNull { it.value == key }?.key
        return if (alias != null) "${display.getValue(alias)} version" else Plain.title(key)
    }

    fun severity(finding: Finding): String =
        "Severity: ${finding.rule.severity.id}. " + when (finding.basis) {
            Basis.VERIFIED -> "The source states this symptom."
            Basis.INFERRED -> "The source describes the mechanism, not this symptom."
            Basis.RANGE -> "The source lists a compatible range and states no failure."
        }

    fun boundary(c: Crossing): String {
        val was = if (c.was.isEmpty()) "" else " Before: ${c.was.joinToString("; ")}."
        return "${keyName(c.key)}: ${c.from ?: "not in the capsule"} to ${c.to}. " +
            "Rule condition after the move: ${c.now.joinToString("; ")}.$was"
    }

    fun evidence(finding: Finding): String? = when (finding.group) {
        Group.WILL_BREAK, Group.OUTSIDE_RANGE ->
            "Matched on: ${finding.evidence.joinToString(", ")}."

        else -> null
    }

    fun needs(finding: Finding): String? {
        if (finding.missing.isEmpty()) return null
        val pending = if (finding.rule.status == Status.PENDING_SCANNER) {
            " This rule reads facts that no scanner collects yet."
        } else {
            ""
        }
        return "Drift needs: ${finding.missing.joinToString(", ")}.$pending"
    }
}
