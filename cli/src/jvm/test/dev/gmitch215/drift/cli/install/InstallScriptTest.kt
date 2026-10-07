package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.hash.Sha256
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallScriptTest {
	private val windows = System.getProperty("os.name").startsWith("Windows")
	private val script = File(System.getProperty("user.dir")).parentFile.resolve("install.sh")
	private val root = Files.createTempDirectory("drift-script").toFile()
	private val release = root.resolve("release").also { it.mkdirs() }
	private val tools = root.resolve("tools").also { it.mkdirs() }
	private val tmp = root.resolve("tmp").also { it.mkdirs() }
	private val log = root.resolve("log.txt")

	private class Outcome(val code: Int, val out: String, val err: String)

	private val fake = """
		#!/bin/sh
		case "${'$'}1" in
			--version) [ -z "${'$'}FAKE_BROKEN" ] || exit 1
				echo "drift version 1.0.0 (fake)"; exit 0 ;;
			install) shift; echo "${'$'}*" > "${'$'}FAKE_LOG"; echo "installed with: ${'$'}*"
				exit "${'$'}{FAKE_EXIT:-0}" ;;
		esac
	""".trimIndent() + "\n"

	init {
		for (name in listOf(
			"uname", "mktemp", "rm", "mkdir", "tar", "gzip", "chmod", "tr", "sed", "head", "cat",
			"ls", "grep", "curl", "sha256sum", "shasum", "id",
		)) {
			val found = System.getenv("PATH").split(File.pathSeparator)
				.map { File(it, name) }.firstOrNull { it.canExecute() }
			if (found != null) {
				Files.createSymbolicLink(tools.resolve(name).toPath(), found.toPath())
			}
		}
	}

	private fun stub(name: String, body: String) {
		val file = tools.resolve(name)
		Files.deleteIfExists(file.toPath())
		file.writeText("#!/bin/sh\n$body\n")
		file.setExecutable(true)
	}

	private fun asset(
		version: String,
		os: String,
		arch: String,
		binary: String = fake,
		inDir: String? = null,
	): File {
		val dir = release.resolve("v$version").also { it.mkdirs() }
		val stage = Files.createTempDirectory(root.toPath(), "stage").toFile()
		val target = if (inDir == null) stage else stage.resolve(inDir).also { it.mkdirs() }
		target.resolve("drift").writeText(binary)
		target.resolve("drift").setExecutable(true)
		val archive = dir.resolve("drift-$version-$os-$arch.tar.gz")
		val tar = ProcessBuilder("tar", "-czf", archive.path, "-C", stage.path, ".")
			.redirectErrorStream(true).start()
		assertEquals(0, tar.waitFor(), tar.inputStream.readBytes().decodeToString())
		sign(archive)
		return archive
	}

	private fun sign(archive: File, digest: String = Sha256.hex(archive.readBytes())) {
		File(archive.path + ".sha256").writeText("$digest  ${archive.name}\n")
	}

	private fun install(
		vararg args: String,
		uname: Pair<String, String> = "Linux" to "x86_64",
		version: String? = "1.0.0",
		base: String = release.toURI().toString(),
		env: Map<String, String> = emptyMap(),
	): Outcome {
		stub("uname", "case \"\$1\" in -s) echo ${uname.first} ;; -m) echo ${uname.second} ;; esac")
		val builder = ProcessBuilder(listOf("/bin/sh", script.path) + args)
		val e = builder.environment()
		e.clear()
		e["PATH"] = tools.path
		e["TMPDIR"] = tmp.path
		e["HOME"] = root.path
		e["FAKE_LOG"] = log.path
		e["DRIFT_INSTALL_BASE_URL"] = base
		if (version != null) e["DRIFT_VERSION"] = version
		e.putAll(env)
		val process = builder.start()
		process.outputStream.close()
		val out = process.inputStream.readBytes().decodeToString()
		val err = process.errorStream.readBytes().decodeToString()
		assertTrue(process.waitFor(30, TimeUnit.SECONDS))
		return Outcome(process.exitValue(), out, err)
	}

	@AfterTest
	fun cleanUp() {
		root.deleteRecursively()
	}

	private fun leftovers() = tmp.list().orEmpty().toList()

	@Test
	fun theFlagsReachTheExecutableAndTheTempDirectoryIsGone() {
		if (windows) return
		asset("1.0.0", "linux", "x64")
		val result = install("--global", "--no-modify-path", "--dry-run", "--dir", "/opt/x")
		assertEquals(0, result.code, result.err)
		assertEquals(
			"installed with: --global --no-modify-path --dry-run --dir /opt/x\n",
			result.out,
		)
		assertTrue(result.err.contains("sha256 verified"), result.err)
		assertEquals(emptyList(), leftovers())
		val equals = install("--user", "--dir=/opt/y")
		assertEquals("installed with: --user --dir /opt/y\n", equals.out)
		assertEquals("--user --dir /opt/y\n", log.readText())
	}

	private fun assertAsset(uname: Pair<String, String>, os: String, arch: String) {
		asset("1.0.0", os, arch)
		val result = install(uname = uname)
		assertEquals(0, result.code, "$uname: ${result.err}")
		assertTrue(result.err.contains("downloading drift-1.0.0-$os-$arch.tar.gz"), result.err)
	}

	@Test
	fun linuxOnIntelPicksTheX64Asset() {
		if (windows) return
		assertAsset("Linux" to "x86_64", "linux", "x64")
		assertAsset("Linux" to "amd64", "linux", "x64")
	}

	@Test
	fun linuxOnArmPicksTheArm64Asset() {
		if (windows) return
		assertAsset("Linux" to "aarch64", "linux", "arm64")
		assertAsset("Linux" to "arm64", "linux", "arm64")
	}

	@Test
	fun macOnAppleSiliconPicksTheMacosAsset() {
		if (windows) return
		assertAsset("Darwin" to "arm64", "macos", "arm64")
	}

	@Test
	fun aVersionWithALeadingVIsAccepted() {
		if (windows) return
		asset("1.0.0", "linux", "x64")
		assertEquals(0, install(version = "v1.0.0").code)
	}

	@Test
	fun aPrereleaseVersionKeepsItsSuffixInTheAssetName() {
		if (windows) return
		asset("1.2.3-rc1", "linux", "x64")
		assertEquals(0, install(version = "1.2.3-rc1").code)
	}

	@Test
	fun aCorruptedAssetIsRefusedAndNothingRuns() {
		if (windows) return
		val archive = asset("1.0.0", "linux", "x64")
		archive.appendBytes(byteArrayOf(0))
		val result = install()
		assertEquals(1, result.code)
		val message = "checksum mismatch for drift-1.0.0-linux-x64.tar.gz"
		assertTrue(result.err.contains(message), result.err)
		assertTrue(result.err.contains("nothing was installed"), result.err)
		assertFalse(log.exists())
		assertEquals(emptyList(), leftovers())
	}

	@Test
	fun theSidecarMayBeUppercaseAndMayMarkTheFileAsBinary() {
		if (windows) return
		val archive = asset("1.0.0", "linux", "x64")
		File(archive.path + ".sha256")
			.writeText(Sha256.hex(archive.readBytes()).uppercase() + " *" + archive.name + "\n")
		assertEquals(0, install().code)
	}

	@Test
	fun aSidecarThatIsNotADigestIsRefused() {
		if (windows) return
		val archive = asset("1.0.0", "linux", "x64")
		for (text in listOf("", "nothex  file\n", "abc  file\n", "g".repeat(64) + "  file\n")) {
			File(archive.path + ".sha256").writeText(text)
			val result = install()
			assertEquals(1, result.code, text)
			assertTrue(result.err.contains("does not start with a sha256 digest"), result.err)
		}
	}

	private fun assertRefused(uname: Pair<String, String>, message: String) {
		val result = install(uname = uname)
		assertEquals(1, result.code, "$uname")
		assertTrue(result.err.contains(message), result.err)
		assertFalse(log.exists())
	}

	@Test
	fun unsupportedSystemsAreNamedAndRefused() {
		if (windows) return
		assertRefused("FreeBSD" to "amd64", "unsupported operating system: FreeBSD")
		assertRefused("MINGW64_NT-10.0" to "x86_64", "use install.ps1")
	}

	@Test
	fun unsupportedArchitecturesAreNamedAndRefused() {
		if (windows) return
		assertRefused("Linux" to "riscv64", "unsupported architecture: riscv64")
		assertRefused("Darwin" to "x86_64", "no release asset is built for macos on x64")
	}

	@Test
	fun muslIsRefusedBecauseTheExecutableIsBuiltForGlibc() {
		if (windows) return
		asset("1.0.0", "linux", "x64")
		stub("ldd", "echo 'musl libc (x86_64)'; echo 'Version 1.2.5' >&2")
		val result = install()
		assertEquals(1, result.code)
		assertTrue(result.err.contains("uses musl; no asset is available"), result.err)
		stub("ldd", "echo 'ldd (Debian GLIBC 2.36-9) 2.36'")
		assertEquals(0, install().code)
	}

	@Test
	fun noNetworkAndMissingToolsFailWithAClearMessage() {
		if (windows) return
		asset("1.0.0", "linux", "x64")
		val offline = install(base = root.resolve("nowhere").toURI().toString())
		assertEquals(1, offline.code)
		assertTrue(offline.err.contains("cannot download file:"), offline.err)
		assertEquals(emptyList(), leftovers())
		val http = install(base = "http://127.0.0.1:9")
		assertEquals(1, http.code)
		assertTrue(http.err.contains("cannot download http://127.0.0.1:9/v1.0.0/"), http.err)
		val hidden = tools.resolve("curl")
		hidden.renameTo(root.resolve("curl.away"))
		assertEquals("curl or wget is required", install().err.substringAfter("error: ").trim())
		root.resolve("curl.away").renameTo(hidden)
	}

	@Test
	fun aMissingChecksumToolStopsBeforeDownloading() {
		if (windows) return
		asset("1.0.0", "linux", "x64")
		val away = root.resolve("away").also { it.mkdirs() }
		for (name in listOf("sha256sum", "shasum")) tools.resolve(name).renameTo(away.resolve(name))
		val result = install()
		assertEquals(1, result.code)
		assertTrue(result.err.contains("sha256sum or shasum is required"), result.err)
	}

	@Test
	fun usageErrorsExitTwoAndHelpExitsZero() {
		if (windows) return
		for (args in listOf(
			listOf("--bogus"),
			listOf("--user", "--global"),
			listOf("--dir"),
		)) {
			val result = install(*args.toTypedArray())
			assertEquals(2, result.code, "$args")
			assertTrue(result.err.contains("error:"), result.err)
		}
		val help = install("--help")
		assertEquals(0, help.code)
		assertTrue(help.out.contains("usage: install.sh"), help.out)
		val noVersion = install(version = null)
		assertEquals(1, noVersion.code)
		assertTrue(noVersion.err.contains("set DRIFT_VERSION when DRIFT_INSTALL_BASE_URL is set"))
	}

	@Test
	fun theExecutablesExitCodeIsTheScriptsExitCode() {
		if (windows) return
		asset("1.0.0", "linux", "x64")
		assertEquals(3, install(env = mapOf("FAKE_EXIT" to "3")).code)
	}

	@Test
	fun aFailedGlobalInstallPointsAtRunningTheInstallerAsRoot() {
		if (windows || System.getProperty("user.name") == "root") return
		asset("1.0.0", "linux", "x64")
		val result = install("--global", env = mapOf("FAKE_EXIT" to "1"))
		assertEquals(1, result.code)
		assertTrue(result.err.contains("run this installer again as root"), result.err)
		val user = install(env = mapOf("FAKE_EXIT" to "1"))
		assertFalse(user.err.contains("as root"), user.err)
	}

	@Test
	fun anExecutableThatCannotRunIsReported() {
		if (windows) return
		asset("1.0.0", "linux", "x64")
		val broken = install(env = mapOf("FAKE_BROKEN" to "1"))
		assertEquals(1, broken.code)
		assertTrue(broken.err.contains("does not run on this system"), broken.err)
	}

	@Test
	fun theExecutableMayLiveInOneSubdirectoryButMustExist() {
		if (windows) return
		asset("1.0.0", "linux", "x64", inDir = "drift-1.0.0")
		assertEquals(0, install().code)
		val empty = release.resolve("v1.0.0/drift-1.0.0-linux-x64.tar.gz")
		val stage = Files.createTempDirectory(root.toPath(), "empty").toFile()
		stage.resolve("README").writeText("x")
		ProcessBuilder("tar", "-czf", empty.path, "-C", stage.path, ".").start().waitFor()
		sign(empty)
		val result = install()
		assertEquals(1, result.code)
		assertTrue(result.err.contains("does not contain a drift executable"), result.err)
	}
}
