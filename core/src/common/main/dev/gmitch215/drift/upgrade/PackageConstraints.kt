package dev.gmitch215.drift.upgrade

import dev.gmitch215.drift.diff.Version
import dev.gmitch215.drift.diff.VersionScheme
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Stability

/**
 * Checks the runtime constraint each locked package records against the target runtime, with no
 * rule per package. The scanner writes `project.<ecosystem>.<package>.requires.<runtime>` for
 * the formats that carry the constraint (`composer.lock`, `package-lock.json`, `pnpm-lock.yaml`,
 * `poetry.lock`); a lockfile that does not (`yarn.lock`, `bun.lock`, `uv.lock`,
 * `requirements.txt`, Gradle and Maven files) is listed as unchecked, never fetched.
 */
internal object PackageConstraints {
    private class Runtime(
        val name: String,
        val key: String,
        val ecosystem: String,
        val dialect: Dialect,
        val registryFiles: Set<String>,
    )

    private val runtimes = listOf(
        Runtime("php", "tool.php.version", "composer", Dialect.COMPOSER, emptySet()),
        Runtime("node", "tool.node.version", "npm", Dialect.NPM, setOf("yarn.lock", "bun.lock")),
        Runtime(
            "python",
            "tool.python.version",
            "python",
            Dialect.PYTHON,
            setOf("uv.lock", "requirements.txt"),
        ),
    )

    private val jvmKey = "tool.java.version"
    private val jvmBuildFiles = setOf("build.gradle", "build.gradle.kts", "pom.xml")
    private val coordinate = Regex("^project\\.(gradle|maven)\\..+:.+")

    fun check(
        capsule: Capsule,
        targets: List<Target>,
        current: Map<String, String>,
    ): Pair<List<PackageBreak>, List<Unchecked>> {
        val attrs = capsule.attributes.filter { it.stability == Stability.STATIC }
        val breaks = mutableListOf<PackageBreak>()
        val unchecked = mutableListOf<Unchecked>()
        for (runtime in runtimes) {
            val target = targets.firstOrNull { it.key == runtime.key } ?: continue
            val to = Version.parse(target.value, VersionScheme.of(runtime.key)) ?: continue
            val from = current[runtime.key]
            val fromVersion = from?.let { Version.parse(it, VersionScheme.of(runtime.key)) }
            val suffix = ".requires.${runtime.name}"
            val prefix = "project.${runtime.ecosystem}."
            val unread = mutableListOf<String>()
            val outside = mutableListOf<Attribute>()
            for (attr in attrs.filter { it.path.startsWith(prefix) && it.path.endsWith(suffix) }) {
                val constraint = RuntimeConstraint.parse(attr.value, runtime.dialect)
                when {
                    constraint == null -> unread += "${name(attr, prefix, suffix)} ${attr.value}"

                    constraint.allows(to) -> Unit

                    fromVersion == null -> outside += attr

                    constraint.allows(fromVersion) -> breaks += PackageBreak(
                        runtime.ecosystem,
                        name(attr, prefix, suffix),
                        current[attr.path.removeSuffix(suffix)],
                        runtime.name,
                        attr.value,
                        from,
                        target.value,
                        attr.source.removePrefix("project:"),
                    )
                }
            }
            val file = attrs.firstOrNull { it.path.startsWith(prefix) }?.source
                ?.removePrefix("project:")
            if (unread.isNotEmpty()) {
                unchecked += Unchecked(
                    file.orEmpty(),
                    runtime.name,
                    "the constraint is not a range this check reads",
                    unread.sorted(),
                )
            }
            if (outside.isNotEmpty()) {
                unchecked += Unchecked(
                    file.orEmpty(),
                    runtime.name,
                    "the capture has no ${runtime.key}, so a package that already excludes it " +
                        "cannot be told from one the move breaks",
                    outside.map { name(it, prefix, suffix) }.sorted(),
                )
            }
            for (registry in runtime.registryFiles) {
                if (attrs.any { it.path.startsWith(prefix) && it.source == "project:$registry" }) {
                    unchecked += Unchecked(
                        registry,
                        runtime.name,
                        "the lockfile does not record a ${runtime.name} constraint; " +
                            "the registry has it",
                    )
                }
            }
        }
        if (targets.any { it.key == jvmKey }) {
            val files = attrs.filter { coordinate.matches(it.path) }
                .map { it.source.removePrefix("project:") }
                .filter { it in jvmBuildFiles }.distinct().sorted()
            if (files.isNotEmpty()) {
                unchecked += Unchecked(
                    files.joinToString(", "),
                    "java",
                    "a dependency's JVM level is in its artifact, which is not fetched",
                )
            }
        }
        val order = compareBy<PackageBreak>({ it.ecosystem }, { it.name })
        return breaks.sortedWith(order) to unchecked
    }

    private fun name(attr: Attribute, prefix: String, suffix: String) =
        attr.path.removePrefix(prefix).removeSuffix(suffix)
}
