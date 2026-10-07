package dev.gmitch215.drift.scan

import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.redact.Anonymizer
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.scan.probe.Probe
import dev.gmitch215.drift.scan.probe.ProbeRunner
import dev.gmitch215.drift.scan.scanner.Scanners

object Capture {
	private val volatileEnv =
		setOf("PWD", "OLDPWD", "SHLVL", "_", "RANDOM", "SECONDS", "LINENO")

	fun run(
		host: Host,
		label: String,
		probes: List<Probe> = emptyList(),
		tools: Boolean = false,
		hardware: Boolean = false,
		keepIdentifiers: Boolean = false,
	): Capsule {
		val attrs = linkedMapOf<String, Attribute>()

		fun add(path: String, value: String?, source: String, kind: Stability = Stability.STATIC) {
			val v = value?.trim()
			if (!v.isNullOrEmpty()) attrs[path] = Attribute(path, v, source, kind)
		}

		fun command(vararg argv: String): String? {
			val result = host.run(argv.toList())
			return if (result != null && result.exitCode == 0) result.output else null
		}

		add("drift.platform", host.platform, "drift")
		add("os.name", host.os, "host")
		add("os.arch", normalizeArch(host.arch), "host")
		add("kotlin.version", KotlinBuild.VERSION, "kotlin")
		add("kotlin.target", host.platform, "drift")

		val env = host.env()
		val declared = env[RUNTIME_ENV]?.takeIf { it.isNotBlank() }
		add("runtime.platform", declared ?: host.platform, if (declared == null) "drift" else "env")
		add("ci.runner.label", env[RUNNER_ENV], "env")
		for ((name, value) in env) {
			val stable = Stability.VOLATILE.takeIf { name in volatileEnv } ?: Stability.STATIC
			add("env.$name", value, "env", stable)
		}

		val windows = host.os.startsWith("windows")
		if (windows) {
			add("os.version", command("cmd", "/c", "ver"), "ver")
			add("cpu.count", env["NUMBER_OF_PROCESSORS"], "env")
		} else {
			add("kernel.release", command("uname", "-r"), "uname")
			add("cpu.count", command("getconf", "_NPROCESSORS_ONLN"), "getconf")
		}

		host.readText("/etc/os-release")?.lines()?.forEach { line ->
			val key = line.substringBefore('=', "")
			if (key in OS_KEYS) {
				val value = line.substringAfter('=').trim('"')
				add("os.release.$key", value, OS_RELEASE)
			}
		}
		for ((path, attr) in CGROUP_FILES) add(attr, host.readText(path), path)

		if (tools) Scanners.scan(host).forEach { attrs[it.path] = it }
		if (hardware) host.facts().forEach { attrs[it.path] = it }

		val identity = env.mapKeys { "env.${it.key}" }
		val owners = Anonymizer.owners(identity)
		val home = Anonymizer.home(identity)
		fun anonymize(a: Attribute): Attribute {
			val value = Anonymizer.anonymize(a.value, owners, home)
			return if (value == a.value) a else a.copy(value = value, source = a.source + ANON)
		}

		return Capsule(
			label,
			attrs.values.map(Redactor::redact)
				.map { if (keepIdentifiers) it else anonymize(it) }
				.sortedBy { it.path },
			ProbeRunner.run(host, probes),
		)
	}

	fun normalizeArch(arch: String): String = when (arch.lowercase()) {
		"x64", "amd64", "x86_64" -> "x86_64"
		"arm64", "aarch64" -> "aarch64"
		else -> arch.lowercase()
	}

	const val ANON = " (anonymized)"
	private const val RUNTIME_ENV = "DRIFT_RUNTIME_PLATFORM"
	private const val RUNNER_ENV = "DRIFT_RUNNER_LABEL"
	private const val OS_RELEASE = "/etc/os-release"
	private val OS_KEYS = setOf("ID", "VERSION_ID", "PRETTY_NAME")
	private val CGROUP_FILES = mapOf(
		"/sys/fs/cgroup/cpu.max" to "cgroup.cpu.max",
		"/sys/fs/cgroup/memory.max" to "cgroup.memory.max",
	)
}
