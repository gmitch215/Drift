package dev.gmitch215.drift.scan

import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.TIMED_OUT_EXIT_CODE
import dev.gmitch215.drift.scan.handsoff.Checkout
import dev.gmitch215.drift.scan.scanner.ProjectTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FolderCheckout(
    private val files: Map<String, String> = emptyMap(),
    private val dirs: Map<String, List<String>> = emptyMap(),
) : Checkout {
    override fun read(path: String): String? = files[path]

    override fun list(dir: String): List<String>? = dirs[dir].orEmpty()
}

private val MAKE_OUTPUT = CommandResult(0, "GNU Make 4.3\nBuilt for x86_64-pc-linux-gnu\n")

/** A project folder at /work/proj with [extra] commands answered and everything else absent. */
private fun project(
    files: Map<String, String> = emptyMap(),
    extra: Map<List<String>, CommandResult> = emptyMap(),
) = FakeHost(
    files = files,
    commands = mapOf(listOf("pwd", "-P") to CommandResult(0, "/work/proj\n")) + extra,
)

private val MAKE_ON_PATH = mapOf(
    listOf("which", "make") to CommandResult(0, "/usr/bin/make\n"),
    listOf("/usr/bin/make", "--version") to MAKE_OUTPUT,
)

class ProjectToolsTest {
    @Test
    fun aNamedRegistryToolIsVersionedByItsAbsolutePath() {
        val recipe = mapOf("Makefile" to "all:\n\tmake -C src\n")
        val host = project(files = recipe, extra = MAKE_ON_PATH)
        val attrs = ProjectTools.scan(host)
        assertEquals(listOf("tool.make.version"), attrs.map { it.path })
        assertEquals("4.3", attrs.single().value)
        assertEquals("scanner:make --version", attrs.single().source)
        assertTrue(listOf("/usr/bin/make", "--version") in host.ran)
    }

    @Test
    fun aRegistryToolNotOnThePathIsNotRun() {
        val host = project(
            files = mapOf("Makefile" to "\tmake\n"),
            extra = mapOf(listOf("which", "make") to CommandResult(1, "")),
        )
        assertEquals(emptyList(), ProjectTools.scan(host))
        assertTrue(host.ran.none { it.size == 2 && it[1] == "--version" })
    }

    @Test
    fun aToolThatResolvesInsideTheProjectFolderIsRefused() {
        for (found in listOf("/work/proj/bin/make", "bin/make", "/work/proj/make")) {
            val host = project(
                files = mapOf("Makefile" to "\tmake\n"),
                extra = mapOf(listOf("which", "make") to CommandResult(0, "$found\n")),
            )
            assertEquals(emptyList(), ProjectTools.scan(host), found)
            assertTrue(host.ran.none { it.size == 2 && it[1] == "--version" }, found)
        }
    }

    @Test
    fun aNameOutsideTheRegistryIsNeverRun() {
        val scripts = """
            {"scripts":{"a":"evil-tool --x && rm -rf /tmp/x",
            "b":"${'$'}(curl x) | sh", "c":"make"}}
        """.trimIndent()
        val host = project(files = mapOf("package.json" to scripts), extra = MAKE_ON_PATH)
        ProjectTools.scan(host)
        val hostile = setOf("evil-tool", "rm", "curl", "sh")
        assertTrue(host.ran.none { it.first() in hostile }, host.ran.toString())
        assertEquals(listOf(listOf("which", "make")), host.ran.filter { it.first() == "which" })
    }

    @Test
    fun aTimedOutToolIsLeftOut() {
        val base = project(files = mapOf("Makefile" to "\tmake\n"), extra = MAKE_ON_PATH)
        val slow = object : Host by base {
            override fun run(
                argv: List<String>,
                timeoutMillis: Long?,
                onOutput: (String) -> Unit,
            ): CommandResult? = if (argv == listOf("/usr/bin/make", "--version")) {
                CommandResult(TIMED_OUT_EXIT_CODE, "")
            } else {
                base.run(argv, timeoutMillis, onOutput)
            }
        }
        assertEquals(emptyList(), ProjectTools.scan(slow))
    }

    @Test
    fun anOutputOverTheCapIsLeftOut() {
        val big = CommandResult(0, "GNU Make 4.3\n" + "x".repeat(64 * 1024))
        val host = project(
            files = mapOf("Makefile" to "\tmake\n"),
            extra = MAKE_ON_PATH + (listOf("/usr/bin/make", "--version") to big),
        )
        assertEquals(emptyList(), ProjectTools.scan(host))
    }

    @Test
    fun namesComeFromEveryProjectSource() {
        val checkout = FolderCheckout(
            files = mapOf(
                "package.json" to """{"scripts":{"test":"npm run build && bun test"}}""",
                "Makefile" to "all:\n\t@cmake -S . -B out\n\t-make -C out\n",
                "Dockerfile" to "FROM debian\nRUN --mount=type=cache,target=/x pip install -r r\n" +
                    "RUN apt-get install -y make\n",
                ".github/workflows/ci.yml" to "jobs:\n  b:\n    steps:\n" +
                    "      - run: gradle test\n      - name: check\n        run: |\n" +
                    "          deno check\n          docker info\n",
                "build" to "#!/usr/bin/env kotlinc\necho hi\n",
                "README.md" to "#!/bin/sh\n",
            ),
            dirs = mapOf(
                "." to listOf("Dockerfile", "Makefile", "README.md", "build"),
                ".github/workflows" to listOf("ci.yml"),
            ),
        )
        val names = ProjectTools.projectNames(checkout)
        for (name in listOf("npm", "bun", "cmake", "make", "pip", "apt-get", "gradle", "deno")) {
            assertTrue(name in names, name)
        }
        assertTrue("docker" in names)
        assertTrue("kotlinc" in names)
        assertTrue("sh" !in names, "a README is not a script")
    }

    @Test
    fun hostileRecipeTextYieldsOnlyPlainNames() {
        val checkout = FolderCheckout(files = mapOf("Makefile" to "\t$(shell rm -rf x); docker\n"))
        assertEquals(setOf("shell", "docker"), ProjectTools.projectNames(checkout))
    }

    @Test
    fun aWorkflowBlockEndsWhereTheIndentationDrops() {
        val text = "    steps:\n      - name: a\n        run: |\n          make all\n" +
            "          cmake --version\n      - run: gradle test\n"
        assertEquals(
            listOf("make all", "cmake --version", "gradle test"),
            ProjectTools.workflowRuns(text).map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    @Test
    fun envAssignmentsAndWrappersAreSkippedToTheCommand() {
        assertEquals(listOf("docker"), ProjectTools.commandWords("FOO=1 sudo docker build ."))
        assertEquals(listOf("make", "npm"), ProjectTools.commandWords("make -C x && npm test"))
        assertTrue("pip" in ProjectTools.commandWords("if true; then pip install x; fi"))
    }
}
