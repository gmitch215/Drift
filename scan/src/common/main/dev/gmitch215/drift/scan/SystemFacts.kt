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
        add("cgroup.memory.limit", memoryLimit(host), MEMORY_V2)
        add("kernel.cmdline", cmdline(host.readText(CMDLINE)), CMDLINE)
        add(
            "sysctl.kernel.apparmor_restrict_unprivileged_userns",
            host.readText(USERNS)?.trim(),
            USERNS,
        )
        add("crypto.policy", cryptoPolicy(host.readText(CRYPTO)), CRYPTO)
        add("tz.database.version", tzVersion(host), "/usr/share/zoneinfo")
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

    internal fun tzVersion(host: Host): String? = TZ_FILES.firstNotNullOfOrNull { (path, pattern) ->
        host.readText(path)?.lineSequence()?.firstNotNullOfOrNull { pattern.find(it) }
            ?.groupValues?.get(1)
    }

    private fun memoryLimit(host: Host): String? {
        val v2 = host.readText(MEMORY_V2)?.trim()
        val v1 = host.readText(MEMORY_V1)?.trim()
        val bytes = (v2 ?: v1)?.toLongOrNull() ?: return null
        return "true".takeIf { bytes < UNLIMITED_BYTES }
    }

    internal fun cmdline(text: String?): String? = text?.trim()?.split(Regex("\\s+"))
        ?.filter { token -> token.substringBefore('=') in CMDLINE_KEYS }
        ?.joinToString(" ")

    internal fun cryptoPolicy(text: String?): String? = text?.lineSequence()?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private fun zone(host: Host): String? {
        val link = host.run(listOf("readlink", "/etc/localtime"))
            ?.takeIf { it.exitCode == 0 }?.output?.trim().orEmpty()
        val name = link.substringAfter("zoneinfo/", "").ifEmpty {
            host.readText("/etc/timezone")?.trim().orEmpty()
        }
        return if (name in utcNames) "UTC" else name
    }

    private const val CGROUP = "/proc/self/cgroup"
    private const val MEMORY_V2 = "/sys/fs/cgroup/memory.max"
    private const val MEMORY_V1 = "/sys/fs/cgroup/memory/memory.limit_in_bytes"
    private const val CMDLINE = "/proc/cmdline"
    private const val USERNS = "/proc/sys/kernel/apparmor_restrict_unprivileged_userns"
    private const val CRYPTO = "/etc/crypto-policies/config"
    private const val UNLIMITED_BYTES = 1L shl 60
    private val CMDLINE_KEYS = setOf(
        "net.ifnames", "biosdevname", "ipv6.disable", "selinux", "enforcing", "audit",
        "apparmor", "security", "mitigations", "transparent_hugepage",
        "systemd.unified_cgroup_hierarchy", "cgroup_no_v1", "ro", "quiet", "nomodeset",
    )
    private val TZ_FILES = listOf(
        "/usr/share/zoneinfo/tzdata.zi" to Regex("^# version (\\d{4}[a-z])"),
        "/usr/share/zoneinfo/+VERSION" to Regex("^(\\d{4}[a-z])"),
    )
    private const val IPV6_FLAG = "-Djava.net.preferIPv6Addresses="
    private val JVM_ENV = listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")
}
