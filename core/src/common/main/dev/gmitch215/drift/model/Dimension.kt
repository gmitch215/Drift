package dev.gmitch215.drift.model

enum class Dimension(val id: String) {
	OS("os"),
	KERNEL("kernel"),
	CPU("cpu"),
	CPU_LIMIT("cpu-limit"),
	MEMORY("memory"),
	RUNTIME("runtime"),
	COMPILER("compiler"),
	BUILD("build"),
	USERLAND("userland"),
	ENV("env"),
	LOCALE("locale"),
	LIMITS("limits"),
	NETWORK("network"),
	BROWSER("browser"),
	;

	companion object {
		private val attributes: Map<String, Dimension> = mapOf(
			"os.name" to OS,
			"os.version" to OS,
			"os.release." to OS,
			"tool.libc." to OS,
			"kernel." to KERNEL,
			"os.arch" to CPU,
			"cpu." to CPU,
			"cgroup.cpu." to CPU_LIMIT,
			"cgroup.memory." to MEMORY,
			"memory." to MEMORY,
			"tool.java." to RUNTIME,
			"tool.go." to RUNTIME,
			"tool.node." to RUNTIME,
			"tool.python." to RUNTIME,
			"tool.php." to RUNTIME,
			"tool.rust." to RUNTIME,
			"tool.kotlin." to RUNTIME,
			"tool.dotnet." to RUNTIME,
			"tool.workerd." to RUNTIME,
			"runtime." to RUNTIME,
			"tool.cc." to COMPILER,
			"tool.visualstudio." to COMPILER,
			"tool.npm." to BUILD,
			"tool.gradle." to BUILD,
			"tool.pip." to BUILD,
			"ci." to BUILD,
			"deps." to BUILD,
			"tool.coreutils." to USERLAND,
			"tool.git." to USERLAND,
			"tool.make." to USERLAND,
			"env." to ENV,
			"env.LANG" to LOCALE,
			"env.LC_" to LOCALE,
			"env.TZ" to LOCALE,
			"env.HTTP_PROXY" to NETWORK,
			"env.HTTPS_PROXY" to NETWORK,
			"env.NO_PROXY" to NETWORK,
			"env.http_proxy" to NETWORK,
			"env.https_proxy" to NETWORK,
			"env.no_proxy" to NETWORK,
			"limits." to LIMITS,
			"network." to NETWORK,
			"browser." to BROWSER,
		)

		private val probes: Map<String, Dimension> = mapOf(
			"numeric." to RUNTIME,
			"text." to RUNTIME,
			"collections." to RUNTIME,
			"time." to RUNTIME,
			"process." to USERLAND,
			"process.env-visible" to ENV,
			"process.timezone" to LOCALE,
			"process.resolve-localhost" to NETWORK,
			"resources." to OS,
			"resources.cgroup-cpu" to CPU_LIMIT,
			"resources.cgroup-memory" to MEMORY,
			"resources.locale-env" to LOCALE,
		)

		/** `null` for capture metadata (`drift.`) and for any path no prefix claims. */
		fun of(path: String): Dimension? = longest(attributes, path)

		fun ofProbe(id: String): Dimension? = longest(probes, id)

		private fun longest(table: Map<String, Dimension>, key: String): Dimension? =
			table.entries.filter { key.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
	}
}
