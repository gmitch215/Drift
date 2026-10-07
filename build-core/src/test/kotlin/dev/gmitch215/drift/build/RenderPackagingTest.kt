package dev.gmitch215.drift.build

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderPackagingTest {
	private val a = "a".repeat(64)
	private val b = "b".repeat(64)

	private val assets = listOf(
		"macos-arm64.tar.gz",
		"linux-x64.tar.gz",
		"linux-arm64.tar.gz",
		"windows-x64.zip",
		"macos-arm64.dmg",
		"windows-x64.msi",
	)

	private fun fixtureSums(version: String): String =
		assets.withIndex().joinToString("\n") { (i, name) ->
			val digest = i.toString(16).repeat(64)
			"$digest  drift-$version-$name"
		}

	@Test
	fun sumsReadsTextAndBinaryModeLines() {
		val text = "$a  one.zip\n$b *two.dmg\n\n"
		assertEquals(mapOf("one.zip" to a, "two.dmg" to b), PackagingRender.sums(text))
	}

	@Test
	fun sumsToleratesWindowsLineEndings() {
		assertEquals(mapOf("one.zip" to a), PackagingRender.sums("$a  one.zip\r\n"))
	}

	@Test
	fun sumsRejectsAShortDigestAndNamesTheLine() {
		val error = assertFailsWith<IllegalArgumentException> {
			PackagingRender.sums("$a  one.zip\n${"c".repeat(63)}  two.zip")
		}
		assertEquals("SHA256SUMS line 2 is not '<sha256>  <name>'", error.message)
	}

	@Test
	fun sumsRejectsUppercaseDigests() {
		assertFailsWith<IllegalArgumentException> { PackagingRender.sums("${a.uppercase()}  one.zip") }
	}

	@Test
	fun sumsRejectsADuplicatedName() {
		val error = assertFailsWith<IllegalArgumentException> {
			PackagingRender.sums("$a  one.zip\n$b  one.zip")
		}
		assertEquals("SHA256SUMS lists one.zip twice", error.message)
	}

	@Test
	fun renderFillsVersionDigestAndLicense() {
		val sums = mapOf("drift-1.2.3-linux-x64.tar.gz" to a)
		val out = PackagingRender.render(
			"v@version@ @sha256:linux-x64.tar.gz@ @license@",
			"1.2.3",
			sums,
			"MIT \$1 \\n",
		)
		assertEquals("v1.2.3 $a MIT \$1 \\n", out)
	}

	@Test
	fun renderFailsWhenTheAssetIsNotInTheSums() {
		val error = assertFailsWith<IllegalArgumentException> {
			PackagingRender.render("@sha256:windows-x64.msi@", "1.0.0", emptyMap(), "")
		}
		assertEquals("SHA256SUMS has no entry for drift-1.0.0-windows-x64.msi", error.message)
	}

	@Test
	fun renderOnlyAnswersToItsOwnTokens() {
		val text = "Install-ChocolateyZipPackage @packageArgs\n@gmitch215 and @a@b"
		assertEquals(text, PackagingRender.render(text, "1.0.0", emptyMap(), ""))
	}

	@Test
	fun taskRendersTheTreeAndDropsStaleOutput() {
		val root = Files.createTempDirectory("render").toFile()
		val templates = root.resolve("templates").also { it.resolve("sub/dir").mkdirs() }
		templates.resolve("top.txt").writeText("@version@\n")
		templates.resolve("sub/dir/x.rb").writeText("sha256 \"@sha256:linux-x64.tar.gz@\"\n")
		val sums = root.resolve("SHA256SUMS").also { it.writeText("$a  drift-9.9.9-linux-x64.tar.gz\n") }
		val license = root.resolve("LICENSE").also { it.writeText("MIT License\n\n") }
		val out = root.resolve("out").also { it.mkdirs() }
		out.resolve("stale.txt").writeText("old")

		val project = ProjectBuilder.builder().build()
		val task = project.tasks.register("render", RenderPackaging::class.java) {
			version.set("9.9.9")
			this.sums.set(sums)
			this.license.set(license)
			this.templates.set(templates)
			outputDir.set(out)
		}.get()
		task.render()

		assertEquals("9.9.9\n", out.resolve("top.txt").readText())
		assertEquals("sha256 \"$a\"\n", out.resolve("sub/dir/x.rb").readText())
		assertFalse(out.resolve("stale.txt").exists())
	}

	@Test
	fun theCommittedTemplatesRenderAgainstTheReleaseAssetNames() {
		val templates = File("../packaging").absoluteFile
		assertTrue(templates.isDirectory, "packaging templates not found at ${templates.absolutePath}")
		val version = "4.5.6"
		val out = Files.createTempDirectory("committed").toFile()
		val project = ProjectBuilder.builder().build()
		val sums = File.createTempFile("sums", "").also { it.writeText(fixtureSums(version)) }
		val task = project.tasks.register("render", RenderPackaging::class.java) {
			this.version.set(version)
			this.sums.set(sums)
			license.set(File("../LICENSE").absoluteFile)
			this.templates.set(templates)
			outputDir.set(out)
		}.get()
		task.render()

		val files = out.walkTopDown().filter { it.isFile }.toList()
		assertTrue(files.size >= 10, "rendered ${files.size} files")
		val tokens = Regex("@(version|license|sha256:[A-Za-z0-9._-]+)@")
		for (file in files) {
			assertFalse(tokens.containsMatchIn(file.readText()), "${file.name} still has a token")
		}

		val known = fixtureSums(version).lines().associate {
			it.substringAfter("  ") to
			it.substringBefore("  ")
		}
		val downloads = Regex("releases/download/v$version/(drift-$version-[A-Za-z0-9._-]+)")
		val cited = files.flatMap { f ->
			downloads.findAll(f.readText()).map { it.groupValues[1] }
		}.toSet()
		assertEquals(known.keys - "drift-$version-macos-arm64.dmg", cited)
		val cask = out.resolve("homebrew/Casks/drift-studio.rb").readText()
		assertTrue(cask.contains("sha256 \"${known.getValue("drift-$version-macos-arm64.dmg")}\""))
		assertTrue(cask.contains("version \"$version\""))
		assertTrue(cask.contains("/drift-#{version}-macos-arm64.dmg\""))

		val formula = out.resolve("homebrew/Formula/drift.rb").readText()
		assertTrue(formula.contains("sha256 \"${known.getValue("drift-$version-macos-arm64.tar.gz")}\""))
		val choco = out.resolve("chocolatey/drift/tools/chocolateyInstall.ps1").readText()
		assertTrue(
			choco.contains("checksum64     = '${known.getValue("drift-$version-windows-x64.zip")}'"),
		)
		val studio = out.resolve("chocolatey/drift-studio/tools/chocolateyInstall.ps1").readText()
		assertTrue(studio.contains("'${known.getValue("drift-$version-windows-x64.msi")}'"))
		assertTrue(out.resolve("chocolatey/drift/tools/LICENSE.txt").readText().contains("MIT License"))
	}
}
