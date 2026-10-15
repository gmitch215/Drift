package dev.gmitch215.drift.bench

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj

object Generator {
    const val SALT_TRIES = 64

    fun expand(template: Template, seed: Long, tries: Int = SALT_TRIES): List<Scenario> {
        val rng = SplitMix64(seed xor Hashing.hash64(template.id))
        val rows = template.variants.ifEmpty { listOf(emptyMap()) }
        val order = rng.shuffled(rows.indices.toList())
        return (1..template.instances).map { k ->
            val picks = template.draw.entries.sortedBy { it.key }.associate { (name, choices) ->
                name to choices[rng.below(choices.size)]
            }
            val vars = rows[order[(k - 1) % order.size]] + picks
            var found: Scenario? = null
            var drawn = 0
            while (found == null && drawn < tries) {
                drawn++
                val candidate = template.build(k, vars, rng.next() ushr 32)
                if (candidate.accepts()) found = candidate
            }
            found ?: throw TemplateException(
                Problem.NO_SALT,
                "${template.id}-$k",
                "no salt in $tries draws fits the rate",
            )
        }
    }

    /** Every template expanded and sorted by id; ids in `exclude` are left out. */
    fun generate(
        templates: List<Template>,
        seed: Long,
        exclude: Set<String> = emptySet(),
    ): List<Scenario> {
        val all = templates.flatMap { expand(it, seed) }
            .filter { it.id !in exclude }
            .sortedBy { it.id }
        val seen = mutableSetOf<String>()
        all.forEach {
            if (!seen.add(it.id)) {
                throw TemplateException(Problem.DUPLICATE_ID, it.id, "duplicate scenario id")
            }
        }
        return all
    }

    /** One scenario per line so a diff shows which scenario moved. */
    fun scenariosJson(scenarios: List<Scenario>): String =
        scenarios.joinToString(",\n", "[\n", "\n]\n") { it.canonical() }
}

class Split(val dev: List<String>, val test: List<String>) {
    val testSha256: String get() = Sha256.hex(test.joinToString("") { it + "\n" })

    fun toJson(): String = CanonicalJson.encode(
        obj(
            "rule" to JsonString(SplitRule.DESCRIPTION),
            "dev_percent" to JsonInt(SplitRule.DEV_PERCENT.toLong()),
            "dev" to JsonArray(dev.map { JsonString(it) }),
            "test" to JsonArray(test.map { JsonString(it) }),
            "test_sha256" to JsonString(testSha256),
        ),
    ) + "\n"
}

object SplitRule {
    const val DEV_PERCENT = 60
    const val DESCRIPTION =
        "dev iff first 4 bytes of sha256(id) as an unsigned integer mod 100 < dev_percent; " +
            "a template outside LEGACY_TEMPLATES whose own id fails the same test is held out " +
            "and has only test instances"

    /** The templates that predate the held-out rule; they keep the instance-level split. */
    val LEGACY_TEMPLATES = setOf(
        "browser-engine-api", "browser-engine-flaky-std", "build-lockfile-pin",
        "build-npm-sbom-plain", "compiler-c2x-plain", "control-env-noise",
        "control-identical-fixed", "control-locale-noise-fixed", "control-runtime-noise",
        "cpu-count-pool", "cpu-limit-quota-basic", "cpu-limit-quota-decoy", "cpu-model-flag-base",
        "env-missing-var-plain", "env-pool-size", "env-pool-size-decoy", "env-pythonioencoding",
        "env-retries-flaky-plain", "kernel-clocksource-flaky-basic", "kernel-release-gate-basic",
        "limits-fsize", "limits-nofile", "limits-nofile-decoy", "locale-setlocale",
        "locale-tz-date", "locale-tz-decoy", "memory-oom", "memory-oom-decoy",
        "migration-jdk-list-getfirst", "migration-node-array-fromasync",
        "migration-php-json-validate", "migration-python-copy", "network-hosts", "network-proxy",
        "network-proxy-decoy", "os-distro-family-basic", "os-glibc-floor", "os-version-gate",
        "runtime-java-virtual-basic", "runtime-node-api", "runtime-python-feature",
        "userland-cp-update", "userland-shell-bashism",
    )

    fun isDev(id: String): Boolean = Hashing.hash32(id) % 100 < DEV_PERCENT

    fun isHeldOut(template: String): Boolean = template !in LEGACY_TEMPLATES && !isDev(template)

    fun split(ids: Collection<String>): Split {
        val sorted = ids.sorted()
        return Split(sorted.filter { isDev(it) }, sorted.filterNot { isDev(it) })
    }

    fun splitScenarios(scenarios: Collection<Scenario>): Split {
        val sorted = scenarios.sortedBy { it.id }
        val dev = sorted.filter { !isHeldOut(it.template) && isDev(it.id) }.map { it.id }
        val devSet = dev.toSet()
        return Split(dev, sorted.map { it.id }.filter { it !in devSet })
    }
}
