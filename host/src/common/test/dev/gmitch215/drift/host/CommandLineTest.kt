package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CommandLineTest {
    private val env = mapOf("Path" to "C:\\a;C:\\b", "PATHEXT" to ".COM;.EXE;.BAT;.CMD")

    @Test
    fun plainArgumentsAreNotQuotedSoNoShellSeesThemAsSyntax() {
        assertEquals("a%PATH%b", windowsCommandLine(listOf("a%PATH%b")))
        assertEquals("a&calc", windowsCommandLine(listOf("a&calc")))
        assertEquals("trail\\", windowsCommandLine(listOf("trail\\")))
    }

    @Test
    fun argumentsWithSpacesTabsAndEmptyArgumentsAreQuoted() {
        assertEquals(
            "\"C:\\Program Files (x86)\\x\"",
            windowsCommandLine(listOf("C:\\Program Files (x86)\\x")),
        )
        assertEquals("\"a\tb\"", windowsCommandLine(listOf("a\tb")))
        assertEquals("\"\"", windowsCommandLine(listOf("")))
    }

    @Test
    fun quotesAreEscapedAndTheBackslashesBeforeThemDoubled() {
        assertEquals("\"say \\\"hi\\\"\"", windowsCommandLine(listOf("say \"hi\"")))
        assertEquals("\"x\\\\\\\"y\"", windowsCommandLine(listOf("x\\\"y")))
    }

    @Test
    fun trailingBackslashesAreDoubledOnlyWhenTheArgumentIsQuoted() {
        assertEquals("\"dir with\\\\\"", windowsCommandLine(listOf("dir with\\")))
    }

    @Test
    fun argumentsAreJoinedWithSpaces() {
        assertEquals(
            "reg query \"HKLM\\a b\"",
            windowsCommandLine(listOf("reg", "query", "HKLM\\a b")),
        )
    }

    @Test
    fun bareProgramResolvesToItsBatchShimThroughPathAndPathext() {
        val files = setOf("C:\\b\\npm.CMD")
        assertEquals("C:\\b\\npm.CMD", windowsBatchScript("npm", env) { it in files })
    }

    @Test
    fun exeBeforeBatchInPathextWinsAndIsNotRoutedThroughCmd() {
        val files = setOf("C:\\b\\npm.EXE", "C:\\b\\npm.CMD")
        assertNull(windowsBatchScript("npm", env) { it in files })
    }

    @Test
    fun missingProgramAndExplicitExeAreNotBatchScripts() {
        assertNull(windowsBatchScript("npm", env) { false })
        assertNull(windowsBatchScript("node.exe", env) { true })
    }

    @Test
    fun explicitBatchPathIsUsedWithoutSearchingPath() {
        val files = setOf("D:\\x\\run.bat")
        assertEquals("D:\\x\\run.bat", windowsBatchScript("D:\\x\\run.bat", env) { it in files })
        assertNull(windowsBatchScript("D:\\x\\run", env) { it in files })
        assertEquals(
            "D:\\x\\run.BAT",
            windowsBatchScript("D:\\x\\run", env) { it == "D:\\x\\run.BAT" },
        )
    }

    @Test
    fun batchCommandLineQuotesTheScriptAndEscapesArgumentsForCmd() {
        assertEquals(
            "\"C:\\b\\npm.cmd\" build \"a&calc\" \"a b\" plain",
            windowsBatchCommandLine("C:\\b\\npm.cmd", listOf("build", "a&calc", "a b", "plain")),
        )
    }

    @Test
    fun percentQuoteAndLineBreaksInBatchArgumentsAreRefused() {
        assertFailsWith<WindowsArgumentException> {
            windowsBatchCommandLine("C:\\b\\npm.cmd", listOf("a%PATH%b"))
        }
        assertFailsWith<WindowsArgumentException> {
            windowsBatchCommandLine("C:\\b\\npm.cmd", listOf("say \"hi\""))
        }
        assertFailsWith<WindowsArgumentException> {
            windowsBatchCommandLine("C:\\b\\npm.cmd", listOf("a\nb"))
        }
        assertFailsWith<WindowsArgumentException> {
            windowsBatchCommandLine("C:\\b\\npm.cmd", listOf("a\r\nb"))
        }
    }

    @Test
    fun batchLaunchRunsThroughCmdWithAFixedSystemPath() {
        val files = setOf("C:\\b\\npm.CMD")
        assertEquals(
            "\"C:\\Windows\\System32\\cmd.exe\" /d /e:ON /v:OFF /c \"\"C:\\b\\npm.CMD\" build\"",
            windowsLaunchLine(listOf("npm", "build"), env, "C:\\Windows\\System32") { it in files },
        )
    }

    @Test
    fun otherProgramsLaunchDirectlyWithoutCmd() {
        assertEquals(
            "reg query \"HKLM\\a b\"",
            windowsLaunchLine(listOf("reg", "query", "HKLM\\a b"), env, "C:\\Windows\\System32") {
                false
            },
        )
        assertEquals(
            "git a%PATH%b",
            windowsLaunchLine(listOf("git", "a%PATH%b"), env, "C:\\Windows\\System32") { false },
        )
    }

    @Test
    fun batchLaunchRefusesAnArgumentCmdCannotCarry() {
        assertFailsWith<WindowsArgumentException> {
            windowsLaunchLine(listOf("npm", "a%PATH%b"), env, "C:\\Windows\\System32") {
                it == "C:\\b\\npm.CMD"
            }
        }
    }
}
