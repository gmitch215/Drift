package dev.gmitch215.drift.scan

import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.model.Attribute

internal object SystemFacts {
    private val utcNames =
        setOf("Etc/UTC", "Etc/UCT", "Etc/Universal", "Etc/Zulu", "UCT", "Universal", "Zulu")

    fun scan(host: Host): List<Attribute> {
        val result = mutableListOf<Attribute>()

        fun add(path: String, value: String?, source: String) {
            if (!value.isNullOrEmpty()) result += Attribute(path, value, source)
        }

        add("cgroup.version", cgroupVersion(host.readText(CGROUP)), CGROUP)
        if (!host.os.startsWith("windows")) add("tz.system-zone", zone(host), "/etc/localtime")

        val env = host.env()
        val options = JVM_ENV.mapNotNull { env[it]?.trim()?.takeIf(String::isNotEmpty) }
        add("runtime.jvm.options", options.joinToString(" "), "env:" + JVM_ENV.joinToString("+"))
        val ipv6 = options.flatMap { it.split(Regex("\\s+")) }
            .lastOrNull { it.startsWith(IPV6_FLAG) }
        add("java.net.preferIPv6Addresses", ipv6?.removePrefix(IPV6_FLAG), "env:java-options")
        return result
    }

    internal fun cgroupVersion(text: String?): String? {
        val lines = text?.lines()?.filter { it.isNotBlank() }.orEmpty()
        return when {
            lines.isEmpty() -> null
            lines.any { !it.startsWith("0::") } -> "v1"
            else -> "v2"
        }
    }

    private fun zone(host: Host): String? {
        val link = host.run(listOf("readlink", "/etc/localtime"))
            ?.takeIf { it.exitCode == 0 }?.output?.trim().orEmpty()
        val name = link.substringAfter("zoneinfo/", "").ifEmpty {
            host.readText("/etc/timezone")?.trim().orEmpty()
        }
        return if (name in utcNames) "UTC" else name
    }

    private const val CGROUP = "/proc/self/cgroup"
    private const val IPV6_FLAG = "-Djava.net.preferIPv6Addresses="
    private val JVM_ENV = listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")
}
