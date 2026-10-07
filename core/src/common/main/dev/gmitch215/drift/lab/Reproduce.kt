package dev.gmitch215.drift.lab

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.redact.Anonymizer
import dev.gmitch215.drift.redact.Redactor

enum class Status(val id: String) {
	MIRRORED("mirrored"),
	PARTIAL("partial"),
	NOT_MIRRORED("not-mirrored"),
}

/**
 * What became of one capsule attribute; [Entry.bundle] marks one that changes with the base image.
 */
data class Entry(
	val path: String,
	val status: Status,
	val detail: String,
	val bundle: Boolean = false,
)

sealed interface ReproduceProblem {
	fun message(): String

	data object EmptyCommand : ReproduceProblem {
		override fun message() = "the test command is empty"
	}

	data class BadDigest(val image: String, val value: String) : ReproduceProblem {
		override fun message() = "digest for $image is not sha256:<64 hex digits>"
	}

	data class UnknownEnv(val name: String) : ReproduceProblem {
		override fun message() = "--env $name does not name an env.* attribute of the capsule"
	}
}

sealed interface ReproduceResult {
	data class Built(val reproduction: Reproduction) : ReproduceResult

	data class Refused(val problem: ReproduceProblem) : ReproduceResult
}

/**
 * A container that mirrors what a capsule can honestly say about its environment, and the
 * account of every attribute it could not. Nothing here runs docker.
 */
class Reproduction internal constructor(
	val label: String,
	val capsuleHash: String,
	val command: String,
	val imageTag: String,
	val digest: String?,
	val platform: String?,
	val distro: String,
	val basis: String,
	val entries: List<Entry>,
	val flags: List<String>,
	val bundleAttributes: List<String>,
	val bundleOthers: List<String>,
	val unusedDigests: List<String>,
	val envNamed: List<String>,
	val envAll: Boolean,
	val dockerfile: String,
	val runScript: String,
) {
	val image: String get() = if (digest == null) imageTag else "$imageTag@$digest"
	val total: Int get() = entries.size
	val mirrored: Int get() = count(Status.MIRRORED)
	val partial: Int get() = count(Status.PARTIAL)
	val notMirrored: Int get() = count(Status.NOT_MIRRORED)

	val summary: String
		get() = "$total attributes: $mirrored mirrored, $partial partially mirrored, " +
			"$notMirrored not mirrored; base image $image"

	fun toJson(): JsonObject = obj(
		"schema" to JsonInt(Reproduce.SCHEMA),
		"capsule" to obj("label" to JsonString(label), "hash" to JsonString(capsuleHash)),
		"command" to JsonString(command),
		"image" to obj(
			"ref" to JsonString(image),
			"tag" to JsonString(imageTag),
			"digest" to (digest?.let(::JsonString) ?: JsonNull),
			"pinned" to JsonString(if (digest == null) "tag" else "digest"),
			"platform" to (platform?.let(::JsonString) ?: JsonNull),
			"distro" to JsonString(distro),
			"basis" to JsonString(basis),
		),
		"summary" to obj(
			"total" to JsonInt(total.toLong()),
			"mirrored" to JsonInt(mirrored.toLong()),
			"partial" to JsonInt(partial.toLong()),
			"notMirrored" to JsonInt(notMirrored.toLong()),
			"line" to JsonString(summary),
		),
		"attributes" to JsonArray(
			entries.map {
				obj(
					"path" to JsonString(it.path),
					"status" to JsonString(it.status.id),
					"detail" to JsonString(it.detail),
					"bundle" to JsonBool(it.bundle),
				)
			},
		),
		"bundle" to obj(
			"image" to JsonString(imageTag),
			"attributes" to JsonArray(bundleAttributes.map(::JsonString)),
			"others" to JsonArray(bundleOthers.map(::JsonString)),
		),
		"run" to obj("flags" to JsonArray(flags.map(::JsonString))),
		"conventions" to JsonArray(Reproduce.conventions.map(::JsonString)),
		"unusedDigests" to JsonArray(unusedDigests.map(::JsonString)),
		"env" to obj(
			"allowlist" to JsonArray(Reproduce.envAllowlist.map(::JsonString)),
			"named" to JsonArray(envNamed.map(::JsonString)),
			"all" to JsonBool(envAll),
		),
	)

	fun files(): Map<String, String> = mapOf(
		"Dockerfile" to dockerfile,
		"manifest.json" to CanonicalJson.encode(toJson()) + "\n",
		"run.sh" to runScript,
	)

	private fun count(status: Status) = entries.count { it.status == status }

	/** The local tag run.sh builds, so an executor can build once and run many trials. */
	val tag: String get() = Reproduce.tagOf(dockerfile)

	/** The shell line that builds the image from the directory [context]. */
	fun buildLine(context: String): String = "docker build" +
		(platform?.let { " --platform $it" } ?: "") + " --tag $tag ${Reproduce.quote(context)}"

	/**
	 * The shell line of one container run of [shellCommand] (the arm's own command by default);
	 * [extra] flags come last and override the capsule's.
	 */
	fun runLine(extra: List<String> = emptyList(), shellCommand: String = command): String =
		"docker run --rm " + (flags + extra).joinToString(" ") + " $tag sh -c " +
			Reproduce.quote(shellCommand)
}

object Reproduce {
	const val SCHEMA = 1L

	val conventions = listOf(
		"run.sh mounts the directory it is run from at /work and runs the command there",
		"the container runs as the invoking uid and gid with HOME=/tmp; the capsule's env.USER, " +
			"env.HOME and env.PWD are host identity and are not mirrored",
		"arguments after run.sh come last on the docker run line and override its flags, for " +
			"example --cpus when the Docker host has fewer CPUs than the capsule's limit",
		"run.sh needs sh and docker; Drift writes it and never runs docker",
		"only the env.* attributes in the allowlist or named with --env are mirrored; the others " +
			"are listed by name and their values are never written",
		"the image build pulls the base image and any listed packages, so it needs network access",
	)

	val envAllowlist = listOf("LANG", "LC_*", "TZ")

	fun synthesize(
		capsule: Capsule,
		command: String,
		digests: Map<String, String> = emptyMap(),
		envNames: Set<String> = emptySet(),
		envAll: Boolean = false,
	): ReproduceResult {
		if (command.isBlank()) return ReproduceResult.Refused(ReproduceProblem.EmptyCommand)
		for ((image, value) in digests) {
			if (!DIGEST.matches(value)) {
				return ReproduceResult.Refused(ReproduceProblem.BadDigest(image, value))
			}
		}
		for (name in envNames.sorted()) {
			if (capsule.attributes.none { it.path == "env.$name" }) {
				return ReproduceResult.Refused(ReproduceProblem.UnknownEnv(name))
			}
		}
		return ReproduceResult.Built(Synthesis(capsule, command, digests, envNames, envAll).build())
	}

	private val DIGEST = Regex("^sha256:[0-9a-f]{64}$")

	internal fun tagOf(dockerfile: String): String =
		"drift-reproduce:" + Sha256.hex(dockerfile).take(IMAGE_HASH)

	internal fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

	private class Base(
		val tag: String,
		val digest: String?,
		val row: Row?,
		val pick: Pick?,
		val image: ImageDistro,
		val basis: String,
	) {
		val ref: String get() = if (digest == null) tag else "$tag@$digest"
	}

	private class Synthesis(
		val capsule: Capsule,
		val command: String,
		val digests: Map<String, String>,
		val envNames: Set<String>,
		val envAll: Boolean,
	) {
		val attrs: Map<String, String> =
			capsule.attributes.reversed().associate { it.path to it.value }
		val distro = Distro.of(attrs)
		val owners: Map<String, String> = Anonymizer.owners(attrs)
		val platform: String? = when (attrs["os.arch"]) {
			"x86_64" -> "linux/amd64"
			"aarch64" -> "linux/arm64"
			else -> null
		}
		val base = chooseBase()
		val env = mutableMapOf<String, String>()
		val locales = mutableSetOf<String>()
		var tz = false
		var npm: String? = null
		var memory: String? = null
		var cpus: String? = null
		val ulimits = mutableMapOf<String, String>()

		fun build(): Reproduction {
			val bundlePrefixes = ImageTable.distroPrefixes + (base.row?.prefixes ?: emptyList())
			val bundleOthers = (
				if (base.image.alpine) ImageTable.alpineOthers else ImageTable.debianOthers
				) + (base.row?.others ?: emptyList())
			val entries = capsule.attributes.sortedBy { it.path }.map {
				val e = classify(it)
				e.copy(bundle = bundlePrefixes.any { p -> e.path.startsWith(p) })
			}
			val flags = flags()
			val dockerfile = dockerfile()
			val used = if (base.digest != null) setOf(base.tag) else emptySet()
			return Reproduction(
				label = Redactor.redactText(capsule.label),
				capsuleHash = capsule.hash(),
				command = command,
				imageTag = base.tag,
				digest = base.digest,
				platform = platform,
				distro = base.image.id + (base.image.release?.let { " $it" } ?: ""),
				basis = base.basis,
				entries = entries,
				flags = flags,
				bundleAttributes = entries.filter { it.bundle }.map { it.path },
				bundleOthers = bundleOthers,
				unusedDigests = digests.keys.filter { it !in used }.sorted(),
				envNamed = envNames.sorted(),
				envAll = envAll,
				dockerfile = dockerfile,
				runScript = runScript(flags, dockerfile),
			)
		}

		// #region base image

		fun chooseBase(): Base {
			val usable = ImageTable.rows.mapNotNull { row ->
				row.pick(attrs, distro)?.let { row to it }
			}
			val named = words(command).firstNotNullOfOrNull { word ->
				usable.firstOrNull { word in it.first.commands }
			}
			val chosen = named ?: usable.firstOrNull()
			if (chosen != null) {
				val (row, pick) = chosen
				val why = when {
					named != null -> "the command names a ${row.tool} tool"
					usable.size == 1 -> "the only toolchain with a version an image tag can pin"
					else -> "the first of ${usable.size} toolchains in table order"
				}
				val basis = "${row.versionPath} ${attrs.getValue(row.versionPath)}; $why"
				return Base(pick.tag, digests[pick.tag], row, pick, pick.image, basis)
			}
			val id = distro.id
			val release = distro.release
			val (tag, image) = when {
				id == "ubuntu" && release != null ->
					"ubuntu:$release" to ImageDistro(id, release)

				id == "debian" && release != null ->
					"debian:$release-slim" to ImageDistro(id, release)

				id == "alpine" ->
					(release?.let { "alpine:$it" } ?: "alpine") to ImageDistro(id, release)

				else -> "debian:12-slim" to ImageDistro("debian", "12")
			}
			val known = (release != null && (id == "ubuntu" || id == "debian")) || id == "alpine"
			val basis = if (known) {
				"no toolchain with a usable version; the distribution attributes pick the base"
			} else {
				"no toolchain with a usable version and no distribution attribute; generic base"
			}
			return Base(tag, digests[tag], null, null, image, basis)
		}

		fun words(text: String): List<String> =
			text.split(Regex("[\\s;&|()<>`$\"']+")).filter { it.isNotEmpty() }
				.map { it.substringAfterLast('/') }

		// #endregion

		// #region attributes

		fun classify(a: Attribute): Entry {
			val p = a.path
			val (status, detail) = when {
				p.startsWith("env.") -> env(a)
				p == "cgroup.memory.max" -> memory(a.value.trim())
				p == "cgroup.cpu.max" -> cpu(a.value.trim())
				p.startsWith("limits.") -> limit(a)
				p == "os.arch" -> arch()
				p == "os.name" -> osName(a.value)
				p.startsWith("os.release.") || p == "os.version" -> osRelease(a)
				p.startsWith("tool.") -> tool(a)
				else -> Status.NOT_MIRRORED to unmapped(p)
			}
			return Entry(p, status, detail)
		}

		fun unmapped(path: String): String = when {
			path.startsWith("ci.") ->
				"hosted runner attribute: the runner provider owns the image and provisioner; " +
					"a container cannot reproduce them"

			path.startsWith("kernel.") ->
				"a container runs on the Docker host's kernel (version, clocksource, scheduler)"

			path.startsWith("hw.cpu.") || path.startsWith("cpu.") ->
				"a container runs on the Docker host's CPU (model, cache, count, steal time)"

			path.startsWith("hw.") || path.startsWith("memory.") || path.startsWith("os.build") ->
				"hardware, hypervisor and storage belong to the Docker host"

			path.startsWith("deps.") ->
				"installed by the test command from the lockfile in the mounted workspace"

			DRIFT_META.any { path.startsWith(it) } ->
				"describes the Drift binary that took the capsule, not the environment"

			path.startsWith("network.") -> "container networking is the Docker default bridge"

			path.startsWith("browser.") -> "no browser image is mapped"

			path.startsWith("cgroup.") -> "no docker run flag for this cgroup file"

			else -> "no mapping for this attribute"
		}

		fun env(a: Attribute): Pair<Status, String> {
			val name = a.path.removePrefix("env.")
			val value = a.value
			val secret = value == Redactor.MASK || Redactor.redact(a).value != value
			return when {
				secret -> Status.NOT_MIRRORED to "redacted: the capsule holds no usable value"

				!ENV_NAME.matches(name) ->
					Status.NOT_MIRRORED to "not a valid shell variable name"

				a.stability == Stability.VOLATILE ->
					Status.NOT_MIRRORED to "volatile: set per process by the shell"

				name in HOST_NAMES || HOST_PREFIXES.any { name.startsWith(it) } ->
					Status.NOT_MIRRORED to "host identity: the container has its own"

				!envAll && name !in envNames && !allowlisted(name) ->
					Status.NOT_MIRRORED to "not allowlisted"

				else -> mirror(name, value)
			}
		}

		fun allowlisted(name: String) = name == "LANG" || name == "TZ" || name.startsWith("LC_")

		fun mirror(name: String, raw: String): Pair<Status, String> {
			val value = Anonymizer.anonymize(raw, owners)
			val changed = value != raw
			val (status, detail) = when {
				!changed && HOST_PATH.containsMatchIn(value) ->
					return Status.NOT_MIRRORED to "the value is a path on the capsule's host"

				name == "TZ" -> {
					env[name] = value
					tz = true
					val zones = if (base.image.id == "debian") "has" else "gets"
					Status.MIRRORED to "run.sh: --env TZ=$value; the image $zones tzdata"
				}

				name == "LANG" || name.startsWith("LC_") -> locale(name, value)

				else -> {
					env[name] = value
					Status.MIRRORED to "run.sh: --env $name=$value"
				}
			}
			return if (changed) {
				Status.PARTIAL to "$detail; path anonymized: a home path or account name in the " +
					"value was replaced"
			} else {
				status to detail
			}
		}

		fun locale(name: String, value: String): Pair<Status, String> {
			env[name] = value
			if (value in C_LOCALES) {
				return Status.MIRRORED to "run.sh: --env $name=$value; every image has it"
			}
			if (base.image.alpine) {
				return Status.PARTIAL to "run.sh: --env $name=$value; musl does not generate " +
					"glibc locales, so behavior can differ"
			}
			val m = UTF8_LOCALE.matchEntire(value)
				?: return Status.PARTIAL to "run.sh: --env $name=$value; only xx_YY.UTF-8 " +
					"locales are generated, so this one is set but not installed"
			locales += "${m.groupValues[1]}.UTF-8 UTF-8"
			return Status.MIRRORED to "run.sh: --env $name=$value; the locale is generated in " +
				"the image"
		}

		fun memory(value: String): Pair<Status, String> {
			if (value == "max") return Status.MIRRORED to "no limit: no --memory flag"
			val n = value.toLongOrNull()
				?: return Status.NOT_MIRRORED to "value is not a byte count"
			if (n >= UNLIMITED) {
				return Status.MIRRORED to "no limit (the cgroup v1 sentinel): no --memory flag"
			}
			if (n < MIN_MEMORY) {
				return Status.NOT_MIRRORED to "below Docker's 6 MiB minimum for --memory"
			}
			val text = when {
				n % GIB == 0L -> "${n / GIB}g"
				n % MIB == 0L -> "${n / MIB}m"
				n % KIB == 0L -> "${n / KIB}k"
				else -> "${n}b"
			}
			memory = text
			return Status.MIRRORED to "run.sh: --memory $text"
		}

		fun cpu(value: String): Pair<Status, String> {
			val parts = value.split(' ').filter { it.isNotEmpty() }
			if (parts.firstOrNull() == "max") return Status.MIRRORED to "no limit: no --cpus flag"
			val quota = parts.getOrNull(0)?.toLongOrNull()
			val period = if (parts.size > 1) parts[1].toLongOrNull() else DEFAULT_PERIOD
			if (quota == null || period == null || quota <= 0 || period <= 0) {
				return Status.NOT_MIRRORED to "value is not a quota and a period"
			}
			val millis = quota * 1000 / period
			if (millis < MIN_MILLIS) {
				return Status.NOT_MIRRORED to "below Docker's 0.01 minimum for --cpus"
			}
			val frac = (millis % 1000).toString().padStart(3, '0').trimEnd('0')
			val text = (millis / 1000).toString() + if (frac.isEmpty()) "" else ".$frac"
			cpus = text
			return if (quota * 1000 % period == 0L) {
				Status.MIRRORED to "run.sh: --cpus $text"
			} else {
				Status.PARTIAL to "run.sh: --cpus $text; the quota $quota per $period is rounded " +
					"down to a thousandth of a CPU"
			}
		}

		fun limit(a: Attribute): Pair<Status, String> {
			val name = a.path.removePrefix("limits.")
			if (name !in ULIMITS) return Status.NOT_MIRRORED to "docker has no --ulimit named $name"
			val parts = a.value.trim().split(':')
			val numbers = parts.map {
				if (it == "unlimited") -1L else it.toLongOrNull()?.takeIf { n -> n >= 0 }
			}
			if (parts.size > 2 || numbers.any { it == null }) {
				return Status.NOT_MIRRORED to "value is not N, unlimited or soft:hard"
			}
			val text = numbers.joinToString(":")
			ulimits[name] = text
			return Status.MIRRORED to "run.sh: --ulimit $name=$text"
		}

		fun arch(): Pair<Status, String> = if (platform != null) {
			Status.MIRRORED to "--platform $platform in run.sh, for the build and the run " +
				"(emulated when the Docker host has another architecture)"
		} else {
			Status.NOT_MIRRORED to "no linux platform for this architecture"
		}

		fun osName(value: String): Pair<Status, String> = if (value == "linux") {
			Status.PARTIAL to "the image is linux; its kernel is the Docker host's"
		} else {
			Status.NOT_MIRRORED to "the capsule is $value and the container is linux"
		}

		fun osRelease(a: Attribute): Pair<Status, String> {
			val image = base.image
			val same = distro.id == image.id
			val key = a.path.removePrefix("os.release.")
			val versionLike = a.path == "os.version" || key == "VERSION_ID"
			return when {
				!same ->
					Status.NOT_MIRRORED to "the image is ${image.id}, the capsule says " +
					(distro.id ?: "nothing about the distribution")

				key == "ID" || key == "name" -> Status.MIRRORED to "the image is ${image.id}"

				versionLike && image.release != null && distro.release == image.release ->
					if (a.value == image.release) {
						Status.MIRRORED to "the image is ${image.id} ${image.release}"
					} else {
						Status.PARTIAL to "the image is ${image.id} ${image.release}; the point " +
							"release is whatever the image holds"
					}

				versionLike ->
					Status.NOT_MIRRORED to "the image is ${image.id} " +
					(image.release ?: "(release not pinned by its tag)")

				else -> Status.PARTIAL to "the text comes from the image's own os-release"
			}
		}

		fun tool(a: Attribute): Pair<Status, String> {
			val rest = a.path.removePrefix("tool.")
			val group = rest.substringBefore('.')
			val key = rest.substringAfter('.', "")
			val row = base.row
			return when {
				row != null && group == row.tool -> selected(row, key, a.value)

				group == "libc" && key == "family" -> {
					val image = if (base.image.alpine) "musl" else "glibc"
					if (a.value == image) {
						Status.MIRRORED to "the image's libc is $image"
					} else {
						Status.NOT_MIRRORED to
							"the capsule's libc is ${a.value}; the image's is $image"
					}
				}

				group == "libc" ->
					Status.NOT_MIRRORED to
					"comes with the base image; its version is not known offline"

				group == "coreutils" && key == "flavor" -> {
					val image = if (base.image.alpine) "busybox" else "gnu"
					if (a.value == image) {
						Status.MIRRORED to "the image's coreutils are $image"
					} else {
						Status.NOT_MIRRORED to
							"the capsule's coreutils are ${a.value}; the image's are $image"
					}
				}

				group == "coreutils" ->
					Status.NOT_MIRRORED to
					"comes with the base image; its version is not known offline"

				group == "git" ->
					Status.NOT_MIRRORED to
					"the base image ships its own git, or none; the version is not pinned"

				group == "npm" && key == "version" && row?.tool == "node" ->
					if (NPM_VERSION.matches(a.value)) {
						npm = a.value
						Status.MIRRORED to "RUN npm install -g npm@${a.value} at build"
					} else {
						Status.NOT_MIRRORED to "not a plain version, so it is not pinned"
					}

				group == "npm" -> Status.NOT_MIRRORED to "no node base image to install it into"

				else -> {
					val other = ImageTable.rows.firstOrNull { it.tool == group }
					if (other == null) {
						Status.NOT_MIRRORED to "no official image is mapped for $group"
					} else if (other.pick(attrs, distro) == null) {
						Status.NOT_MIRRORED to "the version has no official image tag"
					} else {
						Status.NOT_MIRRORED to "second toolchain: one base image per container, " +
							"and it is ${base.tag}"
					}
				}
			}
		}

		fun selected(row: Row, key: String, value: String): Pair<Status, String> {
			val pick = base.pick!!
			return when {
				key == "version" -> if (pick.note == null) {
					Status.MIRRORED to "FROM ${base.tag}"
				} else {
					Status.PARTIAL to "FROM ${base.tag}; ${pick.note}"
				}

				row.tool == "java" && key == "implementation" ->
					if (value == "openjdk") {
						Status.PARTIAL to "the image is Eclipse Temurin, an OpenJDK build; the " +
							"vendor build differs"
					} else {
						Status.NOT_MIRRORED to "the image is Eclipse Temurin"
					}

				row.tool == "python" && key == "externally-managed" ->
					if (value == "false") {
						Status.MIRRORED to "the official python image is not externally managed"
					} else {
						Status.NOT_MIRRORED to "the capsule's python is externally managed " +
							"(PEP 668); the official image's is not"
					}

				row.tool == "go" && key == "os" ->
					if (value == "linux") {
						Status.MIRRORED to "the image is linux"
					} else {
						Status.NOT_MIRRORED to "the capsule is $value and the container is linux"
					}

				row.tool == "go" && key == "arch" ->
					if (platform != null && platform.endsWith("/$value")) {
						Status.MIRRORED to "--platform $platform"
					} else {
						Status.NOT_MIRRORED to "the container platform is ${platform ?: "unpinned"}"
					}

				row.tool == "php" && key == "sapi" ->
					if (value == "cli") {
						Status.MIRRORED to "the image is the cli variant"
					} else {
						Status.NOT_MIRRORED to "the capsule's sapi is $value; the image is cli"
					}

				row.tool == "php" && key == "thread-safety" -> {
					val image = if ("-zts-" in base.tag) "ZTS" else "NTS"
					if (value == image) {
						Status.MIRRORED to "the image is $image"
					} else {
						Status.NOT_MIRRORED to "the image is $image"
					}
				}

				row.tool == "cc" && key == "family" -> Status.MIRRORED to "the image is gcc"

				else -> Status.NOT_MIRRORED to "not used to choose the base image"
			}
		}

		// #endregion

		// #region files

		fun flags(): List<String> {
			val list = mutableListOf<String>()
			platform?.let { list += "--platform $it" }
			list += "--user \"$(id -u):$(id -g)\""
			list += "--volume \"\$PWD:/work\""
			list += "--env ${quote("HOME=/tmp")}"
			memory?.let { list += "--memory $it" }
			cpus?.let { list += "--cpus $it" }
			for ((name, value) in ulimits.toList().sortedBy { it.first }) {
				list += "--ulimit $name=$value"
			}
			for ((name, value) in env.toList().sortedBy { it.first }) {
				list += "--env ${quote("$name=$value")}"
			}
			return list
		}

		val header = "# drift reproduce: capsule ${capsule.hash()}; " +
			"manifest.json says what is not mirrored\n"

		fun dockerfile(): String {
			val out = StringBuilder()
			out.append(header)
			out.append("FROM ${base.ref}\n")
			val packages = mutableListOf<String>()
			if (locales.isNotEmpty() && !base.image.alpine) packages += "locales"
			if (tz && base.image.id != "debian") packages += "tzdata"
			if (packages.isNotEmpty()) {
				if (base.image.alpine) {
					out.append("RUN apk add --no-cache ${packages.joinToString(" ")}\n")
				} else {
					out.append("RUN apt-get update \\\n")
					out.append("\t&& apt-get install -y --no-install-recommends ")
					out.append(packages.joinToString(" ")).append(" \\\n")
					out.append("\t&& rm -rf /var/lib/apt/lists/*")
					for (line in locales.sorted()) {
						out.append(" \\\n\t&& echo '$line' >> /etc/locale.gen")
					}
					if (locales.isNotEmpty()) out.append(" \\\n\t&& locale-gen")
					out.append("\n")
				}
			}
			npm?.let { out.append("RUN npm install -g npm@$it\n") }
			out.append("WORKDIR /work\n")
			return out.toString()
		}

		fun runScript(flags: List<String>, dockerfile: String): String {
			val tag = tagOf(dockerfile)
			val build = "docker build" + (platform?.let { " --platform $it" } ?: "") +
				" --tag \"\$image\" \"\$here\" >&2"
			val out = StringBuilder()
			out.append("#!/bin/sh\n")
			out.append(header)
			out.append("set -eu\n")
			out.append("here=\$(dirname \"\$0\")\n")
			out.append("image=$tag\n")
			out.append("$build\n")
			out.append("exec docker run --rm \\\n")
			for (flag in flags) out.append("\t$flag \\\n")
			out.append("\t\"\$@\" \\\n")
			out.append("\t\"\$image\" sh -c ${quote(command)}\n")
			return out.toString()
		}

		// #endregion
	}

	private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
	private val HOST_PATH = Regex("^(/|~|\\\\\\\\|[A-Za-z]:[\\\\/])|/Users/|/home/")
	private val UTF8_LOCALE = Regex("^([a-z]{2,3}_[A-Z]{2})\\.(?:UTF-8|utf8|UTF8|utf-8)$")
	private val NPM_VERSION = Regex("^\\d+\\.\\d+\\.\\d+$")
	private val DRIFT_META = listOf("drift.", "kotlin.", "runtime.")
	private val C_LOCALES = setOf("C", "POSIX", "C.UTF-8", "C.utf8")

	private val HOST_NAMES = setOf(
		"PATH", "HOME", "USER", "LOGNAME", "SHELL", "HOSTNAME", "PWD", "OLDPWD", "SHLVL", "_",
		"TMPDIR", "TERM", "TERM_PROGRAM", "TERM_SESSION_ID", "DISPLAY", "COLORTERM",
	)
	private val HOST_PREFIXES = listOf("XDG_", "SSH_", "XPC_", "__CF", "DOCKER_")

	private val ULIMITS = setOf(
		"core", "cpu", "data", "fsize", "locks", "memlock", "msgqueue", "nice", "nofile",
		"nproc", "rss", "rtprio", "rttime", "sigpending", "stack",
	)

	private const val KIB = 1024L
	private const val MIB = KIB * 1024
	private const val GIB = MIB * 1024
	private const val MIN_MEMORY = 6 * MIB
	private const val UNLIMITED = 1L shl 62
	private const val DEFAULT_PERIOD = 100_000L
	private const val MIN_MILLIS = 10L
	private const val IMAGE_HASH = 12
}
