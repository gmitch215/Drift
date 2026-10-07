package dev.gmitch215.drift.lab

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
 * the image does, on top of the distribution members every image has.
 */
internal class Row(
	val tool: String,
	val commands: Set<String>,
	val prefixes: List<String>,
	val others: List<String>,
	val pick: (Map<String, String>, Distro) -> Pick?,
) {
	val versionPath: String get() = "tool.$tool.version"
}

internal object ImageTable {
	private val debian = mapOf("11" to "bullseye", "12" to "bookworm", "13" to "trixie")
	private val ubuntu = mapOf("20.04" to "focal", "22.04" to "jammy", "24.04" to "noble")
	private val exact = Regex("^\\d+\\.\\d+\\.\\d+$")
	private val minor = Regex("^\\d+\\.\\d+(\\.\\d+)?$")

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

	private fun slimFirst(version: String, f: Flavor, repository: String): Pick {
		val tag = if (f.image.alpine) "$version-${f.suffix}" else "$version-slim-${f.suffix}"
		return Pick("$repository:$tag", null, f.image)
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
			val note = if (d.id == "alpine") {
				"$pins; the alpine images are published for linux/amd64 only, so the build " +
					"fails on another platform"
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
			val note = if (v.count { it == '.' } == 1) {
				"the tag pins $v only; the patch level is whatever the tag points to"
			} else {
				null
			}
			Pick("gcc:$v", note, ImageDistro("debian", null))
		},
	)
}
