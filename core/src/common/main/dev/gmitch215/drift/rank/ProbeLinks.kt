package dev.gmitch215.drift.rank

/**
 * The attribute paths each probe's output depends on, keyed by probe id. A probe that differs
 * between two captures supports a changed attribute only when the attribute path starts with one
 * of the probe's prefixes; a probe with an empty list informs no captured attribute. The `kotlin.`
 * probes carry no dimension and no entry.
 *
 * The numeric, text, collection and duration probes run inside the Drift process, so they see
 * the platform and JVM that run it and not the tools they scan. The shell probes see the
 * coreutils, the distribution and the OS.
 */
object ProbeLinks {
	private val drift = listOf("runtime.platform", "tool.java.")
	private val shell = listOf("tool.coreutils.", "os.name", "os.release.")

	val attributes: Map<String, List<String>> = mapOf(
		"collections.hash-order" to drift,
		"collections.random-seeded" to drift,
		"collections.sort-edge-cases" to drift,
		"numeric.double-tostring" to drift,
		"numeric.float-to-int" to drift,
		"numeric.float-tostring" to drift,
		"numeric.int-overflow" to drift,
		"numeric.math-bits" to drift + "os.arch",
		"numeric.parse" to drift,
		"numeric.rounding" to drift,
		"process.echo" to shell,
		"process.env-visible" to listOf("env.HOME"),
		"process.exit-code" to shell,
		"process.large-output" to shell,
		"process.missing-command" to emptyList(),
		"process.quoting" to shell,
		"process.resolve-localhost" to listOf("tool.libc.", "os.release."),
		"process.stderr-merged" to shell,
		"process.timezone" to listOf("env.TZ"),
		"resources.cgroup-cpu" to listOf("cgroup.cpu."),
		"resources.cgroup-memory" to listOf("cgroup.memory."),
		"resources.locale-env" to listOf("env.LANG", "env.LC_", "env.TZ"),
		"resources.missing-file" to listOf("runtime.platform"),
		"text.case-mapping" to drift,
		"text.char-classes" to drift,
		"text.compare" to drift,
		"text.default-tostring" to drift,
		"text.exception-messages" to drift,
		"text.hashcode" to drift,
		"text.regex" to drift,
		"text.supplementary" to drift,
		"time.duration-format" to drift,
		"time.monotonic" to emptyList(),
	)

	/** True when [probe] has a prefix that [path] starts with. */
	fun informs(probe: String, path: String): Boolean =
		attributes[probe].orEmpty().any { path.startsWith(it) }
}
