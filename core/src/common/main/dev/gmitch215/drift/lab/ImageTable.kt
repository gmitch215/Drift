package dev.gmitch215.drift.lab

import kotlin.math.abs

/** What an image provides; [release] is null when its tag does not pin one. */
internal data class ImageDistro(val id: String, val release: String?) {
    val alpine: Boolean get() = id == "alpine"
}

/**
 * What a capsule says about its distribution through os-release. [release] is the major version
 * for debian and major.minor for ubuntu and alpine; [version] is the value as captured.
 */
internal data class Distro(val id: String?, val release: String?, val version: String?) {
    companion object {
        fun of(attrs: Map<String, String>): Distro {
            val id = (attrs["os.release.ID"] ?: attrs["os.release.name"])?.lowercase()
            val version = attrs["os.release.VERSION_ID"] ?: attrs["os.version"]
            if (id == null || version == null) return Distro(id, null, null)
            val parts = version.split('.')
            val release = when (id) {
                "debian" -> parts[0]
                "ubuntu", "alpine" -> if (parts.size >= 2) "${parts[0]}.${parts[1]}" else version
                else -> version
            }
            return Distro(id, release, version)
        }
    }
}

/** The tag chosen for a row; [note] says what the tag does not pin, null when it pins all. */
internal data class Pick(val tag: String, val note: String?, val image: ImageDistro)

/**
 * One row of the mapping from a scanned toolchain to an official image. [commands] are the words
 * in a test command that name the toolchain; [prefixes] and [others] are what else changes when
 * the image does, on top of the distribution members every image has. [versionPaths] are the
 * attributes that can carry the version, the first one the capsule has first; [locales] are the
 * glibc locales the image already generates.
 */
internal class Row(
    val tool: String,
    val commands: Set<String>,
    val prefixes: List<String>,
    val others: List<String>,
    val versionPaths: List<String> = listOf("tool.$tool.version"),
    val locales: Set<String> = emptySet(),
    private val formula: (Map<String, String>, Distro) -> Pick?,
) {
    fun versionPath(attrs: Map<String, String>): String =
        versionPaths.firstOrNull { it in attrs } ?: versionPaths.first()

    /** The formula's tag, or the nearest published one when the formula's tag does not exist. */
    fun pick(attrs: Map<String, String>, distro: Distro): Pick? =
        formula(attrs, distro)?.let(ImageTable::resolve)
}

internal object ImageTable {
    private val debian =
        mapOf("10" to "buster", "11" to "bullseye", "12" to "bookworm", "13" to "trixie")
    private val tagForm = Regex("^(\\d+(?:\\.\\d+)*)(.*)$")
    private const val ALPINE_ARM64_FROM = 21
    private val ubuntu = mapOf("20.04" to "focal", "22.04" to "jammy", "24.04" to "noble")
    private val exact = Regex("^\\d+\\.\\d+\\.\\d+$")
    private val minor = Regex("^\\d+\\.\\d+(\\.\\d+)?$")
    private val otpMajor = Regex("^(\\d+)(?:\\.\\d+)*$")

    const val DEFAULT_DEBIAN = "bookworm"

    val distroPrefixes = listOf(
        "os.release.",
        "os.version",
        "tool.libc.",
        "tool.coreutils.",
        "tool.git.",
    )

    val debianOthers = listOf(
        "glibc",
        "tzdata",
        "ca-certificates",
        "dash as /bin/sh",
        "GNU coreutils",
        "openssl",
    )

    val alpineOthers = listOf(
        "musl",
        "tzdata",
        "ca-certificates",
        "busybox ash as /bin/sh",
        "busybox utilities",
        "openssl",
    )

    private class Flavor(val suffix: String, val image: ImageDistro)

    private fun flavor(d: Distro): Flavor {
        val release = d.release.orEmpty()
        return when {
            d.id == "alpine" && d.release != null ->
                Flavor("alpine$release", ImageDistro("alpine", release))

            d.id == "alpine" -> Flavor("alpine", ImageDistro("alpine", null))

            d.id == "debian" && release in debian ->
                Flavor(debian.getValue(release), ImageDistro("debian", release))

            else -> Flavor(DEFAULT_DEBIAN, ImageDistro("debian", "12"))
        }
    }

    private fun versioned(attrs: Map<String, String>, path: String, pattern: Regex): String? =
        attrs[path]?.takeIf { pattern.matches(it) }

    private fun firstVersion(attrs: Map<String, String>, paths: List<String>): String? =
        paths.firstNotNullOfOrNull { attrs[it]?.removePrefix("v")?.takeIf(exact::matches) }

    private fun slimFirst(version: String, f: Flavor, repository: String): Pick {
        val tag = if (f.image.alpine) "$version-${f.suffix}" else "$version-slim-${f.suffix}"
        return Pick("$repository:$tag", null, f.image)
    }

    /** Replaces a tag the registry does not carry; [Pick.note] says what changed. */
    internal fun resolve(pick: Pick): Pick {
        val repository = pick.tag.substringBefore(':')
        val known = ImageTags.repositories[repository] ?: return pick
        val (version, rest) = tagForm.matchEntire(pick.tag.substringAfter(':'))?.destructured
            ?: return pick
        if (known[rest]?.contains(version) == true) return pick
        val own = debian.entries.firstOrNull { it.value in rest }
        val sibling = own?.let { mine ->
            debian.entries.filter { it.key != mine.key }
                .filter { known[rest.replace(mine.value, it.value)]?.contains(version) == true }
                .minWithOrNull(
                    compareBy<Map.Entry<String, String>> {
                        abs(it.key.toInt() - mine.key.toInt())
                    }.thenBy { it.key.toInt() },
                )?.let { mine.value to it }
        }
        if (sibling != null) {
            val (was, now) = sibling
            val fixed = "$repository:$version${rest.replace(was, now.value)}"
            val why = "${pick.tag} is not published; $fixed has the same version on Debian " +
                now.key
            return Pick(fixed, joinNotes(pick.note, why), ImageDistro("debian", now.key))
        }
        val near = nearest(known[rest].orEmpty(), version)
            ?: return Pick(
                pick.tag,
                joinNotes(pick.note, "no tag of this form is in the pinned tag list"),
                pick.image,
            )
        val why = "${pick.tag} is not published; the nearest published version is $near"
        return Pick("$repository:$near$rest", joinNotes(pick.note, why), pick.image)
    }

    private fun joinNotes(first: String?, second: String): String =
        if (first == null) second else "$first; $second"

    private fun parts(version: String): List<Int> = version.split('.').map { it.toInt() }

    private fun nearest(versions: Set<String>, wanted: String): String? {
        val want = parts(wanted)
        fun gap(v: String, i: Int) = abs(parts(v).getOrElse(i) { 0 } - want.getOrElse(i) { 0 })
        val pool = versions.filter { parts(it).size == want.size }.ifEmpty { versions.toList() }
        return pool.minWithOrNull(
            compareBy<String>({ gap(it, 0) }, { gap(it, 1) }, { gap(it, 2) })
                .thenComparator { a, b -> compareParts(parts(a), parts(b)) },
        )
    }

    private fun compareParts(x: List<Int>, y: List<Int>): Int {
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    val rows: List<Row> = listOf(
        Row(
            "java",
            setOf("java", "javac", "jar", "mvn", "mvnw", "gradle", "gradlew"),
            listOf("tool.gradle."),
            listOf("vendor build of the JDK", "bundled cacerts", "bundled tzdb"),
        ) { attrs, d ->
            val raw = attrs["tool.java.version"] ?: return@Row null
            val major = Regex("^(?:1\\.)?(\\d+)").find(raw)?.groupValues?.get(1)
                ?: return@Row null
            val (suffix, image) = when {
                d.id == "ubuntu" && d.release in ubuntu ->
                    "-${ubuntu.getValue(d.release.orEmpty())}" to ImageDistro("ubuntu", d.release)

                d.id == "alpine" -> {
                    val tag = d.release?.let { "-alpine-$it" } ?: "-alpine"
                    tag to ImageDistro("alpine", d.release)
                }

                else -> "" to ImageDistro("ubuntu", null)
            }
            val pins = "the tag pins major version $major only; the patch level is whatever the " +
                "tag points to when it is pulled"
            val note = if (d.id == "alpine" && major.toInt() < ALPINE_ARM64_FROM) {
                "$pins; the alpine images of JDK $major are published for linux/amd64 only, so " +
                    "the build fails on another platform"
            } else {
                pins
            }
            Pick("eclipse-temurin:$major-jdk$suffix", note, image)
        },
        Row(
            "node",
            setOf("node", "npm", "npx", "yarn", "pnpm", "corepack"),
            listOf("tool.npm.", "tool.yarn."),
            listOf("bundled npm", "bundled OpenSSL", "corepack"),
        ) { attrs, d ->
            val v = versioned(attrs, "tool.node.version", exact) ?: return@Row null
            val f = flavor(d)
            val tag = if (f.image.alpine) "$v-${f.suffix}" else "$v-${f.suffix}-slim"
            Pick("node:$tag", null, f.image)
        },
        Row(
            "python",
            setOf("python", "python3", "pip", "pip3", "pytest", "poetry", "uv"),
            listOf("tool.pip."),
            listOf("bundled pip", "bundled setuptools", "sqlite"),
        ) { attrs, d ->
            val v = versioned(attrs, "tool.python.version", exact) ?: return@Row null
            slimFirst(v, flavor(d), "python")
        },
        Row(
            "go",
            setOf("go"),
            emptyList(),
            listOf("bundled go toolchain"),
        ) { attrs, d ->
            val v = versioned(attrs, "tool.go.version", minor) ?: return@Row null
            val f = flavor(d)
            val note = if (v.count { it == '.' } == 1) {
                "the tag pins $v only; the patch level is whatever the tag points to"
            } else {
                null
            }
            Pick("golang:$v-${f.suffix}", note, f.image)
        },
        Row(
            "php",
            setOf("php", "composer", "phpunit"),
            emptyList(),
            listOf("bundled extensions", "php.ini defaults"),
        ) { attrs, d ->
            val raw = attrs["tool.php.version"] ?: return@Row null
            val v = Regex("^\\d+\\.\\d+\\.\\d+").find(raw)?.value ?: return@Row null
            val variant = if (attrs["tool.php.thread-safety"] == "ZTS") "zts" else "cli"
            val f = flavor(d)
            val note = if (raw == v) {
                null
            } else {
                "the distribution suffix ${raw.removePrefix(v)} is dropped"
            }
            Pick("php:$v-$variant-${f.suffix}", note, f.image)
        },
        Row(
            "rust",
            setOf("cargo", "rustc", "rustup"),
            emptyList(),
            listOf("cargo", "rustfmt", "clippy"),
        ) { attrs, d ->
            val v = versioned(attrs, "tool.rust.version", exact) ?: return@Row null
            slimFirst(v, flavor(d), "rust")
        },
        Row(
            "cc",
            setOf("gcc", "g++", "cc", "c++", "make", "cmake"),
            listOf("tool.make."),
            listOf("binutils", "make"),
        ) { attrs, _ ->
            if (attrs["tool.cc.family"] != "gcc") return@Row null
            val v = versioned(attrs, "tool.cc.version", minor) ?: return@Row null
            // gcc has no tag for every patch release (13.2.1 is missing), so pin the line
            val line = v.split('.').take(2).joinToString(".")
            val note = if (line == v) {
                null
            } else {
                "the tag pins $line only; the patch level is whatever the tag points to"
            }
            Pick("gcc:$line", note, ImageDistro("debian", null))
        },
        Row(
            "ruby",
            setOf("ruby", "irb", "gem", "bundle", "bundler", "rake"),
            emptyList(),
            listOf("bundled gems", "bundler"),
        ) { attrs, d ->
            val v = versioned(attrs, "tool.ruby.version", exact) ?: return@Row null
            slimFirst(v, flavor(d), "ruby")
        },
        Row(
            "r",
            setOf("R", "Rscript"),
            emptyList(),
            listOf("base and recommended packages"),
            listOf("tool.r.version", "env.R_BASE_VERSION"),
            setOf("en_US.UTF-8"),
        ) { attrs, _ ->
            val v = firstVersion(attrs, listOf("tool.r.version", "env.R_BASE_VERSION"))
                ?: return@Row null
            val note = "the tag does not pin the Debian release; r-base follows Debian testing"
            Pick("r-base:$v", note, ImageDistro("debian", null))
        },
        Row(
            "elixir",
            setOf("elixir", "elixirc", "iex", "mix"),
            emptyList(),
            listOf("Erlang/OTP", "rebar3", "hex"),
            listOf("tool.elixir.version", "env.ELIXIR_VERSION"),
        ) { attrs, _ ->
            val v = firstVersion(attrs, listOf("tool.elixir.version", "env.ELIXIR_VERSION"))
                ?: return@Row null
            val otp = listOf("env.OTP_VERSION", "tool.erlang.otp")
                .firstNotNullOfOrNull { attrs[it]?.let(otpMajor::find)?.groupValues?.get(1) }
            val note = "the tag does not pin the Debian release" + if (otp == null) {
                " or the Erlang/OTP version"
            } else {
                "; Erlang/OTP $otp is pinned to its major version only"
            }
            val tag = if (otp == null) "elixir:$v" else "elixir:$v-otp-$otp"
            Pick(tag, note, ImageDistro("debian", null))
        },
    )
}
