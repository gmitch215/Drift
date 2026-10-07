package dev.gmitch215.drift.cli.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileBlockTest {
	private val block = ProfileBlock.sh("/home/u/.local/bin", "/home/u")

	@Test
	fun theShellBlockIsIdempotentAndUsesHomeWhenItCan() {
		val expected = listOf(
			"# >>> drift >>>",
			"case \":\${PATH}:\" in",
			"\t*\":\$HOME/.local/bin:\"*) ;;",
			"\t*) export PATH=\"\$HOME/.local/bin:\$PATH\" ;;",
			"esac",
			"# <<< drift <<<",
		).joinToString("\n", postfix = "\n")
		assertEquals(expected, block)
	}

	@Test
	fun directoriesOutsideHomeAreWrittenLiterallyWithTheShellSpecialsEscaped() {
		val outside = ProfileBlock.sh("/opt/my \"dir\"/\$x`y`\\z", "/home/u")
		val escaped = "/opt/my \\\"dir\\\"/\\\$x\\`y\\`\\\\z"
		assertTrue(outside.contains("export PATH=\"$escaped:\$PATH\""), outside)
		val almost = ProfileBlock.sh("/home/user/bin", "/home/u")
		assertTrue(almost.contains("\"/home/user/bin:"), almost)
		val atHome = ProfileBlock.sh("/home/u", "/home/u")
		assertTrue(atHome.contains("export PATH=\"\$HOME:\$PATH\""), atHome)
		val noHome = ProfileBlock.sh("/opt/bin", null)
		assertTrue(noHome.contains("\"/opt/bin:"), noHome)
		val rootHome = ProfileBlock.sh("/bin", "/")
		assertTrue(rootHome.contains("\"/bin:"), rootHome)
	}

	@Test
	fun theFishBlockUsesFishSyntaxAndDoesNotEscapeBackticks() {
		val fish = ProfileBlock.fish("/home/u/.local/bin", "/home/u")
		val expected = listOf(
			"# >>> drift >>>",
			"if not contains -- \"\$HOME/.local/bin\" \$PATH",
			"\tset -gx PATH \"\$HOME/.local/bin\" \$PATH",
			"end",
			"# <<< drift <<<",
		).joinToString("\n", postfix = "\n")
		assertEquals(expected, fish)
		assertTrue(ProfileBlock.fish("/a`b", null).contains("\"/a`b\""))
	}

	@Test
	fun appendAddsANewlineOnlyWhenTheFileDoesNotEndInOne() {
		assertEquals(block to false, ProfileBlock.append("", block))
		assertEquals("a\n$block" to false, ProfileBlock.append("a\n", block))
		assertEquals("a\n$block" to true, ProfileBlock.append("a", block))
	}

	@Test
	fun removeUndoesAppendForEveryShapeOfFile() {
		for (original in listOf("", "a", "a\n", "a\n\n", "\n", "a\r\n", "é")) {
			val (added, lead) = ProfileBlock.append(original, block)
			val removed = ProfileBlock.remove(added, block, lead)
			assertEquals(ProfileBlock.Removal.Removed(original), removed, "original $original")
		}
	}

	@Test
	fun removeKeepsWhatWasAddedAfterTheBlock() {
		val (added, lead) = ProfileBlock.append("a", block)
		val later = ProfileBlock.remove(added + "b\n", block, lead)
		assertEquals(ProfileBlock.Removal.Removed("a\nb\n"), later)
	}

	@Test
	fun removeReportsAbsentAndChangedBlocks() {
		assertEquals(ProfileBlock.Removal.Absent, ProfileBlock.remove("a\n", block, false))
		val midLine = ProfileBlock.remove("x # >>> drift >>>\n", block, false)
		assertEquals(ProfileBlock.Removal.Absent, midLine)
		val edited = block.replace("export", "set")
		assertEquals(ProfileBlock.Removal.Changed, ProfileBlock.remove("a\n$edited", block, false))
	}

	@Test
	fun hasSeesTheStartMarkerOnlyAtTheStartOfALine() {
		assertTrue(ProfileBlock.has(block))
		assertTrue(ProfileBlock.has("a\n$block"))
		assertFalse(ProfileBlock.has("a # >>> drift >>>\n"))
		assertFalse(ProfileBlock.has(""))
	}
}
