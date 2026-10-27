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
        val raised = host.platform in RAISES_NOFILE
        for ((name, value) in limits(host.readText(LIMITS))) {
            if (!raised || name != "nofile") add("limits.$name", value, LIMITS)
        }
        add("kernel.cmdline", cmdline(host.readText(CMDLINE)), CMDLINE)
        add(
            "sysctl.kernel.apparmor_restrict_unprivileged_userns",
            host.readText(USERNS)?.trim(),
            USERNS,
        )
        add("crypto.policy", cryptoPolicy(host.readText(CRYPTO)), CRYPTO)
        add("tz.database.version", tzVersion(host), "/usr/share/zoneinfo")
        if (!host.os.startsWith("windows")) add("tz.system-zone", zone(host), "/etc/localtime")

        val mounts = host.readText(MOUNTINFO)
        add("fs.tmp.type", fsType(mounts, "/tmp"), MOUNTINFO)
        add("fs.cwd.type", fsType(mounts, currentDir(host)), MOUNTINFO)
        val archive = host.readText(LOCALE_ARCHIVE)
        val localeDirs = host.run(listOf("ls", "-1", LOCALE_DIR))?.takeIf { it.exitCode == 0 }
        if (archive != null || localeDirs != null) {
            add("locale.installed", locales(archive, localeDirs?.output), LOCALE_DIR)
        }
        val env = host.env()
        val hosts = hostNames(host.readText(HOSTS), env["HOSTNAME"])
        add("network.hosts.names", hosts?.distinct()?.sorted()?.joinToString(","), HOSTS)
        add("network.nsswitch.hosts", nsswitchHosts(host.readText(NSSWITCH)), NSSWITCH)
        val interfaces = host.run(listOf("ls", "-1", SYS_NET))?.takeIf { it.exitCode == 0 }
        add("network.interfaces", lines(interfaces?.output).sorted().joinToString(","), SYS_NET)
        add("cpu.affinity", cpuAffinity(host.readText(STATUS)), STATUS)
        if (!host.os.startsWith("windows")) add("tool.sh.flavor", shellFlavor(host), SH)
        for ((name, path) in SYSCTLS) add("sysctl.$name", oneWord(host.readText(path)), path)
        add("clock.source", oneWord(host.readText(CLOCKSOURCE)), CLOCKSOURCE)
        add("network.ipv6", ipv6(host.readText(INET6)), INET6)
        add("network.ipv6.disabled", ipv6Disabled(host.readText(DISABLE_IPV6)), DISABLE_IPV6)
        for ((name, value) in resolver(host.readText(RESOLV_CONF))) {
            add(name, value, RESOLV_CONF)
        }

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

    /** `soft:hard` per resource, each side a number or `unlimited`; other lines are left out. */
    internal fun limits(text: String?): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in text?.lines().orEmpty()) {
            val match = LIMIT_LINE.matchEntire(line.trim()) ?: continue
            val name = LIMIT_NAMES[match.groupValues[1]] ?: continue
            result[name] = match.groupValues[2] + ":" + match.groupValues[3]
        }
        return result
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

    /** Filesystem type of the longest mount that holds [path], from mountinfo text. */
    internal fun fsType(mountinfo: String?, path: String?): String? {
        if (mountinfo == null || path == null) return null
        var best: Pair<Int, String>? = null
        for (line in mountinfo.lineSequence()) {
            val fields = line.split(' ')
            if (fields.size < 6) continue
            val tail = fields.drop(6)
            val dash = tail.indexOf("-")
            if (dash < 0 || dash + 1 >= tail.size) continue
            val mount = unescapeMount(fields[4])
            val covers = mount == "/" || path == mount || path.startsWith("$mount/")
            if (covers && (best == null || mount.length >= best.first)) {
                best = mount.length to tail[dash + 1]
            }
        }
        return best?.second
    }

    /** Sorted, comma-free list of locale names from the archive text and the directory listing. */
    internal fun locales(archive: String?, dirs: String?): String =
        (archive.orEmpty().split(NAME_BREAK) + lines(dirs))
            .filter { LOCALE_NAME.matches(it) }.distinct().sorted().joinToString(",")

    /**
     * One token per name of a custom `/etc/hosts` entry: `name@address` when the address is a
     * loopback one, else the bare name (other addresses are not recorded). The stock aliases and
     * the `127.0.1.1` machine entry and the machine's own name ([own], as `HOSTNAME` has it)
     * are left out.
     */
    internal fun hostNames(text: String?, own: String? = null): List<String>? {
        val rows = text?.lines() ?: return null
        val name = own?.trim().orEmpty()
        val mine = if (name.isEmpty()) emptySet() else setOf(name, name.substringBefore('.'))
        return rows.map { it.substringBefore('#').trim().split(WHITESPACE) }
            .filter { it.size > 1 && it[0] != MACHINE_ADDRESS }
            .flatMap { row ->
                row.drop(1).filter { it !in STOCK_HOSTS && it !in mine }
                    .map { if (isLoopback(row[0])) "$it@${row[0]}" else it }
            }
    }

    /** The sources of the `hosts` database in `/etc/nsswitch.conf`, such as `files dns`. */
    internal fun nsswitchHosts(text: String?): String? =
        text?.lineSequence()?.map { it.substringBefore('#').trim() }
            ?.firstOrNull { it.startsWith("hosts:") }
            ?.substringAfter(':')?.trim()?.split(WHITESPACE)?.joinToString(" ")

    /** The number of CPUs in `Cpus_allowed_list`, such as `0-3,8`. */
    internal fun cpuAffinity(text: String?): String? {
        val list = text?.lineSequence()?.firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')?.trim() ?: return null
        var count = 0
        for (part in list.split(',')) {
            val bounds = part.split('-').map { it.toIntOrNull() ?: return null }
            count += when (bounds.size) {
                1 -> 1
                2 -> bounds[1] - bounds[0] + 1
                else -> return null
            }
        }
        return count.toString()
    }

    private fun shellFlavor(host: Host): String? {
        val resolved = host.run(listOf("readlink", "-f", SH))?.takeIf { it.exitCode == 0 }
        return shellName(resolved?.output)
    }

    /** The base name of a `readlink -f /bin/sh` target; null when `/bin/sh` is not a link. */
    internal fun shellName(resolved: String?): String? {
        val path = resolved?.trim()?.takeIf { it.startsWith("/") && it != SH } ?: return null
        return path.substringAfterLast('/')
    }

    internal fun oneWord(text: String?): String? = text?.trim()?.takeIf { WORD.matches(it) }

    /** `true` when any address other than `::1` is listed in `/proc/net/if_inet6`. */
    internal fun ipv6(text: String?): String? = text?.let { list ->
        list.lines().map { it.trim().substringBefore(' ') }
            .any { it.isNotEmpty() && it != IPV6_LOOPBACK }.toString()
    }

    internal fun ipv6Disabled(text: String?): String? = when (text?.trim()) {
        "1" -> "true"
        "0" -> "false"
        else -> null
    }

    /**
     * Counts and flags from `/etc/resolv.conf` only; no address or domain name is kept. The
     * search list is the last `search` or `domain` line, as the resolver reads it.
     */
    internal fun resolver(text: String?): List<Pair<String, String>> {
        val rows = text?.lines()
            ?.map { it.substringBefore('#').substringBefore(';').trim().split(WHITESPACE) }
            ?.filter { it[0].isNotEmpty() } ?: return emptyList()
        val servers = rows.filter { it[0] == "nameserver" }.map { it.getOrElse(1) { "" } }
        val search = rows.lastOrNull { it[0] == "search" || it[0] == "domain" }
        val options = rows.filter { it[0] == "options" }.flatMap { it.drop(1) }
            .map { it.substringBefore(':') }.filter { OPTION_NAME.matches(it) }
            .distinct().sorted()
        return buildList {
            add("network.dns.nameservers" to servers.size.toString())
            if (servers.isNotEmpty()) {
                add("network.dns.loopback" to servers.all { isLoopback(it) }.toString())
            }
            if (search != null) {
                val count = if (search[0] == "domain") 1 else search.size - 1
                add("network.dns.search" to count.toString())
            }
            if (options.isNotEmpty()) add("network.dns.options" to options.joinToString(","))
        }
    }

    private fun isLoopback(address: String) = address.startsWith("127.") || address == "::1"

    private fun unescapeMount(text: String): String =
        OCTAL_ESCAPE.replace(text) { it.groupValues[1].toInt(8).toChar().toString() }

    private fun currentDir(host: Host): String? =
        host.run(listOf("readlink", "/proc/self/cwd"))?.takeIf { it.exitCode == 0 }
            ?.output?.trim()?.takeIf { it.startsWith("/") }

    private fun lines(text: String?): List<String> =
        text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private const val CGROUP = "/proc/self/cgroup"
    private const val MOUNTINFO = "/proc/self/mountinfo"
    private const val STATUS = "/proc/self/status"
    private const val HOSTS = "/etc/hosts"
    private const val NSSWITCH = "/etc/nsswitch.conf"
    private const val MACHINE_ADDRESS = "127.0.1.1"
    private val STOCK_HOSTS = setOf(
        "localhost",
        "ip6-localhost",
        "ip6-loopback",
        "ip6-localnet",
        "ip6-mcastprefix",
        "ip6-allnodes",
        "ip6-allrouters",
        "broadcasthost",
    )
    private const val LOCALE_DIR = "/usr/lib/locale"
    private const val LOCALE_ARCHIVE = "/usr/lib/locale/locale-archive"
    private const val SYS_NET = "/sys/class/net"
    private val OCTAL_ESCAPE = Regex("\\\\([0-7]{3})")
    private val WHITESPACE = Regex("\\s+")
    private val NAME_BREAK = Regex("[^A-Za-z0-9_.@-]+")
    private val LOCALE_NAME =
        Regex("(?:C|POSIX|[a-z]{2,3}_[A-Z]{2,3})(?:\\.[A-Za-z0-9-]+)?(?:@[a-z0-9]+)?")
    private const val MEMORY_V2 = "/sys/fs/cgroup/memory.max"
    private const val MEMORY_V1 = "/sys/fs/cgroup/memory/memory.limit_in_bytes"
    private const val CMDLINE = "/proc/cmdline"
    private const val LIMITS = "/proc/self/limits"
    private const val USERNS = "/proc/sys/kernel/apparmor_restrict_unprivileged_userns"
    private const val CRYPTO = "/etc/crypto-policies/config"
    private const val UNLIMITED_BYTES = 1L shl 60
    private const val SH = "/bin/sh"
    private const val INET6 = "/proc/net/if_inet6"
    private const val IPV6_LOOPBACK = "00000000000000000000000000000001"
    private const val DISABLE_IPV6 = "/proc/sys/net/ipv6/conf/all/disable_ipv6"
    private const val RESOLV_CONF = "/etc/resolv.conf"
    private const val CLOCKSOURCE =
        "/sys/devices/system/clocksource/clocksource0/current_clocksource"
    private val SYSCTLS = listOf(
        "vm.max_map_count",
        "fs.inotify.max_user_watches",
        "fs.inotify.max_user_instances",
        "fs.file-max",
        "kernel.pid_max",
        "net.core.somaxconn",
        "kernel.threads-max",
    ).map { it to "/proc/sys/" + it.replace('.', '/') }
    private val WORD = Regex("[A-Za-z0-9_-]+")
    private val OPTION_NAME = Regex("[a-z0-9_-]+")

    // the jvm and node raise the soft open files limit to the hard one at start
    private val RAISES_NOFILE = setOf("jvm", "wasm")
    private val LIMIT_LINE =
        Regex("(Max [a-z ]+?)\\s+(unlimited|\\d+)\\s+(unlimited|\\d+)(?:\\s+\\S+)?")
    private val LIMIT_NAMES = mapOf(
        "Max cpu time" to "cpu",
        "Max file size" to "fsize",
        "Max data size" to "data",
        "Max stack size" to "stack",
        "Max core file size" to "core",
        "Max processes" to "nproc",
        "Max open files" to "nofile",
        "Max locked memory" to "memlock",
        "Max address space" to "as",
    )
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
