package dev.gmitch215.drift.cli.why

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.host.Host

/**
 * The two sides of a comparison in the words the answer uses: [works] is where it works and
 * [fails] where it fails. [assumed] is true when Drift chose that direction instead of reading it
 * from a run.
 */
internal data class Sides(val works: String, val fails: String, val assumed: Boolean = true) {
    val direction: String get() = "it works $works and fails $fails"

    fun swapped() = Sides(fails, works, assumed)

    companion object {
        val HERE_THERE = Sides("here", "there")
        val BASELINE = Sides("when it last passed", "now", assumed = false)
        val CI = Sides("here", "in CI")
    }
}

/** One change in the plain words of the answer. */
internal class Described(
    val label: String,
    val works: String,
    val fails: String,
    val because: String,
    val fix: String,
)

/** The attribute families `drift why` has plain words for; anything else gets [generic]. */
internal object Families {
    private const val MAX_SHOWN = 60

    private val TOOL_NAMES = mapOf(
        "node" to "Node",
        "npm" to "npm",
        "python" to "Python",
        "java" to "Java",
        "go" to "Go",
        "php" to "PHP",
        "rust" to "Rust",
        "git" to "Git",
        "cc" to "The C compiler",
        "ruby" to "Ruby",
        "openssl" to "OpenSSL",
        "curl" to "curl",
        "composer" to "Composer",
        "libc" to "libc",
        "coreutils" to "coreutils",
    )

    private val PIN_FILES = mapOf(
        "node" to listOf(".nvmrc", ".node-version", ".tool-versions", "package.json"),
        "npm" to listOf("package.json"),
        "python" to listOf(".python-version", ".tool-versions", "runtime.txt", "pyproject.toml"),
        "ruby" to listOf(".ruby-version", ".tool-versions"),
        "java" to listOf(".java-version", ".sdkmanrc", ".tool-versions"),
        "go" to listOf("go.mod", ".tool-versions"),
        "php" to listOf("composer.json", ".tool-versions"),
        "rust" to listOf("rust-toolchain.toml", "rust-toolchain"),
    )

    private val LIMITS = mapOf(
        "nofile" to Limit("open-file limit", "n"),
        "nproc" to Limit("process limit", "u"),
        "core" to Limit("core-file size limit", "c"),
        "fsize" to Limit("file-size limit", "f"),
        "data" to Limit("data-size limit", "d"),
        "stack" to Limit("stack-size limit", "s"),
        "memlock" to Limit("locked-memory limit", "l"),
        "cpu" to Limit("CPU-time limit", "t"),
        "as" to Limit("address-space limit", "v"),
    )

    private class Limit(val label: String, val flag: String)

    private val KERNEL_SETTINGS = mapOf(
        "vm.max_map_count" to "memory maps",
        "fs.inotify.max_user_watches" to "file watches",
        "fs.inotify.max_user_instances" to "inotify instances",
        "fs.file-max" to "open files",
        "kernel.pid_max" to "process IDs",
        "net.core.somaxconn" to "listen queue slots",
        "kernel.threads-max" to "threads",
    )

    private val LOCALE_NAMES = setOf("LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE", "LC_TIME")
    private val PROJECT_META = setOf("lockfile-version", "ts-entry", "version")

    /** The project file that pins [tool] where the checkout in [host] shows one. */
    fun pin(host: Host, tool: String): String? = PIN_FILES[tool].orEmpty().firstOrNull { file ->
        val text = host.readText(file) ?: return@firstOrNull false
        file != "package.json" || "\"engines\"" in text
    }

    fun describe(change: AttributeDiff, sides: Sides, pin: (String) -> String? = { null }) =
        describe(change.path, change.before, change.after, sides, pin)

    fun describe(
        path: String,
        works: String?,
        fails: String?,
        sides: Sides,
        pin: (String) -> String? = { null },
    ): Described {
        val parts = path.split('.')
        return when {
            parts[0] == "tool" && parts.size >= 3 && parts.last() == "version" ->
                tool(parts.subList(1, parts.size - 1).joinToString("."), works, fails, sides, pin)

            parts[0] == "tool" && parts.size >= 3 && parts.last() == "installed" ->
                installed(parts.subList(1, parts.size - 1).joinToString("."), works, fails, sides)

            parts[0] == "ci" && parts.size >= 2 -> simple(
                "CI runner " + parts.drop(1).dropWhile { it == "runner" }.joinToString(" "),
                works,
                fails,
                sides,
                "the CI runner ${parts.drop(1).joinToString(".")}",
                "Pin the CI runner image (for example a fixed ubuntu version instead of " +
                    "latest), or run your tests on the new image before it rolls out.",
            )

            parts[0] == "env" -> env(path.removePrefix("env."), works, fails, sides)

            parts[0] == "limits" -> limit(parts.drop(1).joinToString("."), works, fails, sides)

            parts[0] == "project" && parts.size >= 3 -> project(path, works, fails, sides)

            path == "cgroup.memory.max" || path == "cgroup.memory.limit" ||
                parts[0] == "memory" -> memory(works, fails, sides)

            path == "cgroup.cpu.max" || path == "cpu.count" -> cpu(path, works, fails, sides)

            path == "tz.system-zone" -> zone(works, fails, sides)

            path == "tz.database.version" -> simple(
                "Time zone database",
                works,
                fails,
                sides,
                "the time zone database",
                "Update tzdata there, or use the same base image as ${sides.works}.",
            )

            path == "os.arch" -> simple(
                "CPU architecture",
                works,
                fails,
                sides,
                "the CPU architecture",
                "Use a build of the program, image or tool for the architecture ${sides.fails} " +
                    "(for Docker, pass --platform).",
            )

            path.startsWith("os.release.") || path == "os.version" || path == "os.name" ->
                simple(
                    "Operating system",
                    works,
                    fails,
                    sides,
                    "the operating system",
                    "Use the same base image or OS release ${sides.fails} as ${sides.works}.",
                )

            path == "kernel.release" -> simple(
                "Kernel",
                works,
                fails,
                sides,
                "the kernel",
                "The kernel comes from the host machine; compare the two hosts, not the images.",
            )

            path == "tool.sh.flavor" -> simple(
                "Shell behind /bin/sh",
                works,
                fails,
                sides,
                "the shell behind /bin/sh",
                "Name the shell the script needs (#!/bin/bash, or run it with bash -c), so " +
                    "both machines run the same shell.",
            )

            parts[0] == "sysctl" && parts.size >= 2 -> kernelSetting(
                parts.drop(1).joinToString("."),
                works,
                fails,
                sides,
            )

            path == "clock.source" -> simple(
                "Clock source",
                works,
                fails,
                sides,
                "the clock source",
                "The kernel and the hardware choose the clock source, so compare the two hosts, " +
                    "not the images.",
            )

            path == "network.ipv6" || path == "network.ipv6.disabled" ->
                ipv6(path == "network.ipv6.disabled", works, fails, sides)

            path.startsWith("network.dns.") ->
                dns(path.removePrefix("network.dns."), works, fails, sides)

            parts[0] == "tool" && parts.size >= 3 ->
                toolSetting(parts, works, fails, sides)

            else -> generic(path, works, fails, sides)
        }
    }

    private fun shown(value: String?, absent: String) = when {
        value == null -> absent
        value.length > MAX_SHOWN -> value.take(MAX_SHOWN) + "..."
        else -> value
    }

    private fun tool(
        name: String,
        works: String?,
        fails: String?,
        sides: Sides,
        pin: (String) -> String?,
    ): Described {
        val display = TOOL_NAMES[name] ?: name
        val subject = if (display == name) name else display
        val lead = if (display.startsWith("The ")) display.lowercase() else subject
        val label = if (display.startsWith("The ")) "C compiler" else "$display version"
        return when {
            works != null && fails == null -> Described(
                label,
                works,
                "not installed",
                "$subject is not installed ${sides.fails} ($works ${sides.works})",
                "Install $subject ${sides.fails}, or put it on the PATH there.",
            )

            works == null && fails != null -> Described(
                label,
                "not installed",
                fails,
                "$subject is $fails ${sides.fails} and not installed ${sides.works}",
                "Install $subject ${sides.works}, or stop depending on it ${sides.fails}.",
            )

            else -> {
                val file = pin(name)
                val pinned = if (file == null) {
                    "Pin the version so the two stay equal, for example in .tool-versions or " +
                        "your CI image."
                } else {
                    "Pin the version so the two stay equal: your project records it in $file."
                }
                Described(
                    label,
                    works.orEmpty(),
                    fails.orEmpty(),
                    "$lead is $fails ${sides.fails} and $works ${sides.works}",
                    "Use $subject $works ${sides.fails}. $pinned If there is no version " +
                        "manager ${sides.fails}, change the image or package that provides " +
                        "$subject.",
                )
            }
        }
    }

    private fun env(name: String, works: String?, fails: String?, sides: Sides): Described {
        val w = shown(works, "not set")
        val f = shown(fails, "not set")
        return when {
            name == "TZ" -> Described(
                "Time zone (TZ)",
                w,
                f,
                "the time zone differs: TZ is $f ${sides.fails} and $w ${sides.works}",
                if (works == null) {
                    "Unset TZ ${sides.fails}. That fixes only that machine. Making the code " +
                        "name its zone gives the same answer everywhere."
                } else {
                    "Set TZ=$w ${sides.fails}. That fixes only that machine. Making the code " +
                        "name its zone ($w) gives the same answer everywhere."
                },
            )

            name in LOCALE_NAMES || name.startsWith("LC_") -> Described(
                "Locale ($name)",
                w,
                f,
                "the locale differs: $name is $f ${sides.fails} and $w ${sides.works}",
                if (works == null) {
                    "Unset $name ${sides.fails}, or set it where it works too."
                } else {
                    "Set $name=$w ${sides.fails} (in a container, pass -e $name=$w) and " +
                        "install that locale if it is missing."
                },
            )

            works != null && fails == null -> Described(
                "Variable $name",
                w,
                f,
                "$name is set to $w ${sides.works} and not set ${sides.fails}",
                "Set $name ${sides.fails} the same way as ${sides.works}.",
            )

            works == null -> Described(
                "Variable $name",
                w,
                f,
                "$name is set to $f ${sides.fails} and not set ${sides.works}",
                "Unset $name ${sides.fails}, or set it ${sides.works} to see the same failure.",
            )

            else -> Described(
                "Variable $name",
                w,
                f,
                "$name is set to $f ${sides.fails} and $w ${sides.works}",
                "Set $name to $w ${sides.fails}, or remove the difference.",
            )
        }
    }

    private fun softOf(value: String?, absent: String): String {
        if (value == null) return absent
        val soft = value.substringBefore(':')
        return if (soft.all { it in '0'..'9' }) soft else soft.ifEmpty { value }
    }

    private fun limit(name: String, works: String?, fails: String?, sides: Sides): Described {
        val known = LIMITS[name]
        val label = known?.label ?: "$name limit"
        val w = softOf(works, "not set")
        val f = softOf(fails, "not set")
        val flag = known?.flag
        val how = if (flag == null || w == "not set") {
            "Raise it ${sides.fails} to $w, the value ${sides.works}."
        } else if (name == "nofile") {
            "Raise it ${sides.fails} to at least $w: run ulimit -$flag $w before the command, or " +
                "start the container with --ulimit nofile=$w:$w (in CI, set it on the runner " +
                "or service). " + hardCap(fails, w)
        } else {
            "Raise it ${sides.fails} to $w: run ulimit -$flag $w before the command, or start " +
                "the container with the matching --ulimit option."
        }
        return Described(
            label.replaceFirstChar { it.uppercase() },
            w,
            f,
            "the $label is $f ${sides.fails} and $w ${sides.works}",
            how,
        )
    }

    private fun hardCap(fails: String?, soft: String): String {
        val hard = fails?.substringAfter(':', "")
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val there = hard?.let { " ($it there)" }.orEmpty()
        return "If ulimit -n $soft says 'Operation not permitted', the hard limit is the cap" +
            "$there: raise it in the service unit (LimitNOFILE), /etc/security/limits.conf or " +
            "the container's --ulimit."
    }

    private fun project(path: String, works: String?, fails: String?, sides: Sides): Described {
        val rest = path.removePrefix("project.")
        val eco = rest.substringBefore('.')
        val name = rest.substringAfter('.')
        val meta = name in PROJECT_META || name.startsWith("engines.") ||
            name.startsWith("imports.") || eco == "pins" || eco == "checksums"
        if (meta) return generic(path, works, fails, sides)
        val w = shown(works, "not installed")
        val f = shown(fails, "not installed")
        val fix = when {
            works == null || fails == null ->
                "Make the lockfile list $name in both places, then reinstall."

            eco == "node" ->
                "Install $name $w ${sides.fails} (npm install $name@$w), commit " +
                    "the lockfile and install from it (npm ci)."

            eco == "python" ->
                "Install $name $w ${sides.fails} (pip install $name==$w) and " +
                    "pin it in your requirements file."

            else ->
                "Install $name $w ${sides.fails} and commit the lockfile so both places " +
                    "install the same version."
        }
        val because = when {
            works == null -> "$name is locked to $f ${sides.fails} and missing ${sides.works}"
            fails == null -> "$name is locked to $w ${sides.works} and missing ${sides.fails}"
            else -> "$name is locked to $f ${sides.fails} and $w ${sides.works}"
        }
        return Described("Locked package $name", w, f, because, fix)
    }

    private fun memory(works: String?, fails: String?, sides: Sides): Described {
        val w = bytes(works)
        val f = bytes(fails)
        return Described(
            "Memory limit",
            w,
            f,
            "the memory limit is $f ${sides.fails} and $w ${sides.works}",
            "Give it more memory ${sides.fails}: in a container pass --memory, in CI pick a " +
                "larger runner. Or lower what the program needs.",
        )
    }

    private fun cpu(path: String, works: String?, fails: String?, sides: Sides): Described {
        val label = if (path == "cpu.count") "CPU count" else "CPU limit"
        val w = if (path == "cpu.count") shown(works, "unknown") else cpus(works)
        val f = if (path == "cpu.count") shown(fails, "unknown") else cpus(fails)
        return Described(
            label,
            w,
            f,
            "the number of CPUs is $f ${sides.fails} and $w ${sides.works}",
            "Give it the same CPUs ${sides.fails}: in a container pass --cpus, in CI pick a " +
                "runner with the same size. Or make the code not depend on the count.",
        )
    }

    private fun zone(works: String?, fails: String?, sides: Sides): Described {
        val w = shown(works, "not set")
        val f = shown(fails, "not set")
        return Described(
            "System time zone",
            w,
            f,
            "the time zone differs: it is $f ${sides.fails} and $w ${sides.works}",
            "Set the same time zone ${sides.fails} (TZ=$w). That fixes only that machine. " +
                "Making the code name its zone ($w) gives the same answer everywhere.",
        )
    }

    private fun installed(name: String, works: String?, fails: String?, sides: Sides): Described {
        val display = TOOL_NAMES[name] ?: name
        fun word(value: String?) = when (value) {
            "yes" -> "installed"
            "no" -> "not installed"
            else -> value
        }
        return simple(
            display,
            word(works),
            word(fails),
            sides,
            display,
            "Install $display on the machine where it fails, or run the command on one where it " +
                "is installed.",
        )
    }

    private fun simple(
        label: String,
        works: String?,
        fails: String?,
        sides: Sides,
        subject: String,
        fix: String,
    ): Described {
        val w = shown(works, "absent")
        val f = shown(fails, "absent")
        return Described(
            label,
            w,
            f,
            "$subject is $f ${sides.fails} and $w ${sides.works}",
            fix,
        )
    }

    private fun kernelSetting(
        name: String,
        works: String?,
        fails: String?,
        sides: Sides,
    ): Described {
        val w = shown(works, "not set")
        val f = shown(fails, "not set")
        val what = KERNEL_SETTINGS[name]?.let { "allows $f $it" } ?: "is $f"
        return Described(
            "Kernel setting $name",
            w,
            f,
            "the kernel $what ${sides.fails} and $w ${sides.works}",
            "Set it ${sides.fails} to $w with sysctl -w $name=$w as root, and put it in " +
                "/etc/sysctl.d/ to keep it. The kernel belongs to the host, so in a container " +
                "change it on the host.",
        )
    }

    /** [disabledSwitch] is true for `network.ipv6.disabled`, where a `true` value means off. */
    private fun ipv6(
        disabledSwitch: Boolean,
        works: String?,
        fails: String?,
        sides: Sides,
    ): Described {
        fun state(value: String?) = when {
            value == null -> "unknown"
            (value == "true") != disabledSwitch -> "enabled"
            else -> "disabled"
        }
        val w = state(works)
        val f = state(fails)
        return Described(
            "IPv6",
            w,
            f,
            "IPv6 is $f ${sides.fails} and $w ${sides.works}",
            "Enable IPv6 ${sides.fails}, or make the program fall back to IPv4 when it has no " +
                "IPv6 address.",
        )
    }

    private fun dns(name: String, works: String?, fails: String?, sides: Sides): Described =
        when (name) {
            "loopback" -> {
                fun where(value: String?) = when (value) {
                    "true" -> "a local stub"
                    "false" -> "remote servers"
                    else -> "unknown"
                }
                Described(
                    "DNS resolver",
                    where(works),
                    where(fails),
                    "the DNS resolver uses ${where(fails)} ${sides.fails} and " +
                        "${where(works)} ${sides.works}",
                    "Point both machines at the same resolver, or run the same local stub on " +
                        "both.",
                )
            }

            "nameservers" -> simple(
                "DNS nameservers",
                works,
                fails,
                sides,
                "the number of DNS nameservers",
                "Make both machines use the same nameservers in /etc/resolv.conf (in a " +
                    "container, pass --dns), or do not depend on the lookup.",
            )

            "search" -> simple(
                "DNS search domains",
                works,
                fails,
                sides,
                "the number of DNS search domains",
                "Use full host names, or give both machines the same search list.",
            )

            else -> simple(
                "DNS resolver options",
                works,
                fails,
                sides,
                "the DNS resolver options",
                "Set the same resolver options on both machines in /etc/resolv.conf.",
            )
        }

    /** `tool.NAME.SUFFIX` with no row of its own: the tool's name and the suffix in words. */
    private fun toolSetting(
        parts: List<String>,
        works: String?,
        fails: String?,
        sides: Sides,
    ): Described {
        val name = parts.subList(1, parts.size - 1).joinToString(".")
        val tool = TOOL_NAMES[name] ?: name
        val suffix = parts.last()
        val setting = if (suffix == "sapi") "interface (sapi)" else suffix.replace('-', ' ')
        val label = "$tool $setting"
        val w = shown(works, "absent")
        val f = shown(fails, "absent")
        return Described(
            label,
            w,
            f,
            "the $label is $f ${sides.fails} and $w ${sides.works}",
            "Make the $label the same ${sides.fails} as ${sides.works}, then run it again.",
        )
    }

    private fun generic(path: String, works: String?, fails: String?, sides: Sides): Described {
        val w = shown(works, "absent")
        val f = shown(fails, "absent")
        return Described(
            path,
            w,
            f,
            "$path is $f ${sides.fails} and $w ${sides.works}",
            "Make $path the same ${sides.fails} as ${sides.works}, then run it again.",
        )
    }

    private fun bytes(value: String?): String {
        val text = value?.trim() ?: return "not limited"
        if (text == "max") return "unlimited"
        if (text == "true") return "limited"
        val n = text.toLongOrNull() ?: return shown(text, "unknown")
        val mib = n / MIB
        return if (mib >= GIB_IN_MIB && mib % GIB_IN_MIB == 0L) {
            "${mib / GIB_IN_MIB} GiB"
        } else {
            "$mib MiB"
        }
    }

    private fun cpus(value: String?): String {
        val parts = value?.trim()?.split(' ') ?: return "not limited"
        val quota = parts.firstOrNull()?.toLongOrNull() ?: return "unlimited"
        val period = parts.getOrNull(1)?.toLongOrNull() ?: return "unlimited"
        if (period == 0L) return "unlimited"
        val tenths = quota * TENTHS / period
        val whole = "${tenths / TENTHS}"
        return if (tenths % TENTHS == 0L) whole else "$whole.${tenths % TENTHS}"
    }

    private const val MIB = 1024L * 1024L
    private const val GIB_IN_MIB = 1024L
    private const val TENTHS = 10L
}
