package dev.gmitch215.drift.scan

import dev.gmitch215.drift.fixtures.ProjectFixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.scan.project.ProjectScanner
import dev.gmitch215.drift.scan.project.SourceFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A host holding the fixture project [dir]; `find` lists every file, as it would on disk. */
internal fun projectFixtureHost(dir: String): FakeHost {
    val files = ProjectFixtures.names.filter { it.startsWith("$dir/") }
        .associate { it.removePrefix("$dir/") to ProjectFixtures.text(it) }
    val listing = files.keys.joinToString("\n") { "./$it" }
    return FakeHost(
        files = files,
        commands = mapOf(SourceFiles.argv(".") to CommandResult(0, listing)),
    )
}

class ProjectScannerTest {
    private fun fixtureHost(dir: String) = projectFixtureHost(dir)

    private fun facts(dir: String): Map<String, String> =
        ProjectScanner.scan(fixtureHost(dir)).associate { it.path to it.value }

    private fun withPrefix(prefix: String, vararg pairs: Pair<String, String>) =
        pairs.associate { "$prefix${it.first}" to it.second }

    @Test
    fun composerReadsPlatformConstraintsAndLockedVersionsWithDirectFirst() {
        assertEquals(
            withPrefix(
                "project.composer.",
                "require.php" to "^8.1",
                "require.ext-json" to "*",
                "platform.php" to "8.1.2",
                "json.config.policy" to "true",
                "json.plugins.unlisted" to "drupal/core-composer-scaffold",
                "composer/installers" to "2.3.0",
                "drupal/core-composer-scaffold" to "10.3.6",
                "drupal/core-recommended" to "10.3.6",
                "drush/drush" to "13.2.3",
                "phpunit/phpunit" to "10.5.20",
                "drupal/core" to "10.3.6",
                "symfony/console" to "v6.4.12",
                "symfony/http-kernel" to "v6.4.12",
                "twig/twig" to "v3.14.0",
            ) + withPrefix(
                "project.php.",
                "imports.Doctrine" to "true",
                "imports.Doctrine.ORM" to "true",
                "imports.Doctrine.ORM.EntityManager" to "true",
                "imports.Doctrine.ORM.Mapping" to "true",
                "imports.Doctrine.ORM.Mapping.Entity" to "true",
                "imports.Symfony" to "true",
                "imports.Symfony.Component" to "true",
                "imports.Symfony.Component.Console" to "true",
                "imports.Symfony.Component.Console.Command" to "true",
                "imports.Symfony.Component.Console.Command.Command" to "true",
                "imports.strlen" to "true",
                "calls.each" to "true",
                "calls.create_function" to "true",
                "calls.mcrypt" to "true",
                "calls.json_validate" to "true",
                "calls.array_is_list" to "true",
                "calls.str_contains" to "true",
                "calls.str_starts_with" to "true",
                "calls.str_ends_with" to "true",
                "calls.fdiv" to "true",
                "calls.get_debug_type" to "true",
                "calls.mb_trim" to "true",
                "calls.array_find" to "true",
                "calls.array_find_key" to "true",
                "calls.array_any" to "true",
                "calls.array_all" to "true",
            ),
            facts("composer"),
        )
    }

    @Test
    fun composerPlatformKeepsConcreteVersionsAndRequireKeepsConstraints() {
        fun composer(manifest: String, lock: String? = null) = ProjectScanner.scan(
            FakeHost(
                files = mapOf("composer.json" to manifest) +
                    (lock?.let { mapOf("composer.lock" to it) } ?: emptyMap()),
            ),
        ).associate { it.path to it.value }

        val got = composer(
            """{"require":{"php":">=8.1"},"config":{"platform":{"php":"8.1.2","ext-gd":"^1"}}}""",
        )
        assertEquals(">=8.1", got["project.composer.require.php"])
        assertEquals("8.1.2", got["project.composer.platform.php"])
        assertEquals(null, got["project.composer.platform.ext-gd"])
        assertEquals(null, got["project.composer.json.config.policy"])

        val wildcard = composer("""{"config":{"platform":{"php":"8.1.*"}}}""")
        assertEquals(null, wildcard["project.composer.platform.php"])

        val plugin = """{"packages":[{"name":"a/p","version":"1.0.0","type":"composer-plugin"}]}"""
        val unlisted = composer("""{"config":{"allow-plugins":{"a/other":true}}}""", plugin)
        assertEquals("a/p", unlisted["project.composer.json.plugins.unlisted"])
        val listed = composer("""{"config":{"allow-plugins":{"a/p":false}}}""", plugin)
        assertEquals(null, listed["project.composer.json.plugins.unlisted"])
        val all = composer("""{"config":{"allow-plugins":true}}""", plugin)
        assertEquals(null, all["project.composer.json.plugins.unlisted"])
        val lockOnly = ProjectScanner.scan(FakeHost(files = mapOf("composer.lock" to plugin)))
        assertTrue(lockOnly.none { it.path == "project.composer.json.plugins.unlisted" })
    }

    @Test
    fun npmReadsEnginesAndTheHoistedVersionOfEveryPackage() {
        assertEquals(
            withPrefix(
                "project.npm.",
                "engines.node" to ">=20",
                "engines.npm" to ">=10",
                "@types/node" to "20.14.12",
                "lodash" to "4.17.21",
                "react" to "18.3.1",
                "js-tokens" to "4.0.0",
                "loose-envify" to "1.4.0",
                "lockfile-version" to "3",
                "ts-entry" to "true",
            ) + withPrefix(
                "project.js.",
                "imports.@scope/pkg" to "true",
                "imports.@scope/pkg/sub" to "true",
                "imports.chalk" to "true",
                "imports.express" to "true",
                "imports.lodash" to "true",
                "imports.lodash/fp" to "true",
                "imports.node:fs" to "true",
                "imports.punycode" to "true",
                "calls.exitcode-string" to "true",
                "calls.import-assert" to "true",
                "calls.Array.fromAsync" to "true",
                "calls.Object.groupBy" to "true",
                "calls.Promise.withResolvers" to "true",
                "calls.fs.globSync" to "true",
            ),
            facts("npm"),
        )
    }

    @Test
    fun pnpmReadsTheRootImporterAndThePackagesSection() {
        val got = facts("npm-pnpm")
        assertEquals("4.17.21", got["project.npm.lodash"])
        assertEquals("18.3.1", got["project.npm.react"])
        assertEquals("20.14.12", got["project.npm.@types/node"])
        assertEquals("4.0.0", got["project.npm.js-tokens"])
        assertEquals("1.4.0", got["project.npm.loose-envify"])
    }

    @Test
    fun yarnClassicAndBerryResolveEachDirectRange() {
        val classic = facts("npm-yarn")
        assertEquals("18.3.1", classic["project.npm.react"])
        assertEquals("4.17.21", classic["project.npm.lodash"])
        assertEquals("20.14.12", classic["project.npm.@types/node"])
        val berry = facts("npm-yarn-berry").filterKeys { it.startsWith("project.npm.") }
        assertEquals(
            withPrefix(
                "project.npm.",
                "engines.node" to ">=20",
                "engines.npm" to ">=10",
                "@types/node" to "20.14.12",
                "lodash" to "4.17.21",
                "react" to "18.3.1",
            ),
            berry,
        )
    }

    @Test
    fun bunKeepsTopLevelEntriesAndSkipsNestedOnes() {
        val got = facts("npm-bun")
        assertEquals("4.0.0", got["project.npm.js-tokens"])
        assertEquals("18.3.1", got["project.npm.react"])
        assertEquals(null, got["project.npm.react/js-tokens"])
    }

    @Test
    fun gradleReadsCatalogPluginsTargetsAndSettings() {
        val got = facts("gradle")
        val expected = withPrefix(
            "project.gradle.",
            "wrapper" to "9.1.0",
            "jdk.version" to "21",
            "properties.org.gradle.jvmargs" to "-Xmx4g",
            "properties.android.builtInKotlin" to "false",
            "properties.android.useAndroidX" to "true",
            "properties.kotlin.code.style" to "official",
            "plugin.org.jetbrains.kotlin.multiplatform" to "2.4.0",
            "plugin.com.android.library" to "9.0.0",
            "plugin.com.android.application" to "9.0.0",
            "plugin.org.gradle.toolchains.foojay-resolver-convention" to "0.9.0",
            "kmp-android-module" to ":shared",
            "kotlin.version" to "2.4.0",
            "agp.version" to "9.0.0",
            "kotlin.target.jvm" to "true",
            "kotlin.target.wasmJs" to "true",
            "kotlin.target.js" to "true",
            "kotlin.target.androidTarget" to "true",
            "kotlin.toolchain" to "25",
            "plugin.org.springframework.boot" to "3.3.4",
            "dsl" to "kotlin",
            "calls.project-exec" to "true",
            "calls.kotlin-dsl-quoted-notation" to "true",
            "kotlin.language-version" to "2.3",
            "kotlin.annotation-default-target" to "param-property",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" to "1.11.0",
            "org.jetbrains.kotlinx:kotlinx-serialization-json" to "1.11.0",
            "junit:junit" to "4.13.2",
            "com.squareup.okio:okio" to "3.9.0",
            "com.example:literal" to "1.2.3",
        )
        assertEquals(expected, got)
    }

    @Test
    fun gradleGroovyBuildsGiveLegacyPluginsAndClasspathVersions() {
        assertEquals(
            withPrefix(
                "project.gradle.",
                "plugin.com.android.application" to "true",
                "plugin.org.jetbrains.kotlin.android" to "true",
                "plugin.org.jetbrains.kotlin.kapt" to "true",
                "com.android.tools.build:gradle" to "8.0.2",
                "androidx.core:core-ktx" to "1.13.1",
                "com.squareup.okhttp3:okhttp" to "4.12.0",
                "kotlin.version" to "1.9.24",
                "agp.version" to "8.0.2",
                "kotlin.jvm-target" to "17",
                "dsl" to "groovy",
                "calls.compile-configuration" to "true",
                "calls.archive-basename" to "true",
                "calls.convention-api" to "true",
            ),
            facts("gradle-groovy"),
        )
    }

    @Test
    fun mavenReadsTheParentAndDirectDependenciesOnly() {
        assertEquals(
            withPrefix(
                "project.maven.",
                "property.java.version" to "21",
                "org.springframework.boot:spring-boot-starter-parent" to "3.3.4",
                "com.fasterxml.jackson.core:jackson-databind" to "2.17.2",
                "org.postgresql:postgresql" to "42.7.4",
            ) + withPrefix(
                "project.java.",
                "imports.com" to "true",
                "imports.com.sun" to "true",
                "imports.com.sun.net" to "true",
                "imports.com.sun.net.httpserver" to "true",
                "imports.com.sun.net.httpserver.HttpServer" to "true",
                "imports.java" to "true",
                "imports.java.lang" to "true",
                "imports.java.lang.Math" to "true",
                "imports.java.lang.Math.max" to "true",
                "imports.java.lang.foreign" to "true",
                "imports.java.lang.foreign.Linker" to "true",
                "imports.java.rmi" to "true",
                "imports.java.rmi.activation" to "true",
                "imports.java.rmi.activation.Activatable" to "true",
                "imports.java.util" to "true",
                "imports.java.util.List" to "true",
                "imports.javax" to "true",
                "imports.javax.persistence" to "true",
                "imports.javax.servlet" to "true",
                "imports.javax.servlet.http" to "true",
                "imports.javax.servlet.http.HttpServlet" to "true",
                "imports.javax.xml" to "true",
                "imports.javax.xml.bind" to "true",
                "imports.javax.xml.bind.JAXBContext" to "true",
                "imports.sun" to "true",
                "imports.sun.misc" to "true",
                "imports.sun.misc.Unsafe" to "true",
                "imports.sun.nio" to "true",
                "imports.sun.nio.ch" to "true",
                "imports.sun.nio.ch.DirectBuffer" to "true",
                "calls.System.setSecurityManager" to "true",
                "calls.virtual-threads" to "true",
                "calls.sequenced-first-last" to "true",
                "calls.Math.clamp" to "true",
                "calls.Files.readString" to "true",
                "calls.Files.writeString" to "true",
                "calls.Files.mismatch" to "true",
                "calls.Path.of" to "true",
                "calls.Predicate.not" to "true",
                "calls.Stream.mapMulti" to "true",
            ),
            facts("maven"),
        )
    }

    @Test
    fun pythonReadsUvPoetryAndPinnedRequirements() {
        assertEquals(
            withPrefix(
                "project.python.",
                "requires-python" to ">=3.12",
                "django" to "5.1.2",
                "requests" to "2.32.3",
                "asgiref" to "3.8.1",
                "imports.ast" to "true",
                "imports.asyncio" to "true",
                "imports.asyncore" to "true",
                "imports.multiprocessing" to "true",
                "imports.os" to "true",
                "imports.ssl" to "true",
                "imports.sys" to "true",
                "imports.unittest" to "true",
                "imports.urllib" to "true",
                "imports.urllib.request" to "true",
                "imports.tomllib" to "true",
                "imports.typing" to "true",
                "calls.asyncio.get_event_loop" to "true",
                "calls.ast-removed-nodes" to "true",
                "calls.urllib-urlopener" to "true",
                "calls.ssl.wrap_socket" to "true",
                "calls.unittest-deprecated-aliases" to "true",
                "calls.annotation-readers" to "true",
                "calls.multiprocessing-start-method" to "true",
                "calls.open-without-encoding" to "true",
                "calls.copy.replace" to "true",
                "calls.itertools.batched" to "true",
                "calls.datetime.UTC" to "true",
                "calls.str.removeprefix" to "true",
                "calls.math.lcm" to "true",
                "calls.itertools.pairwise" to "true",
                "calls.contextlib.chdir" to "true",
                "calls.hashlib.file_digest" to "true",
                "calls.asyncio.TaskGroup" to "true",
                "calls.typing.override" to "true",
                "calls.warnings.deprecated" to "true",
            ),
            facts("python"),
        )
        assertEquals(
            withPrefix(
                "project.python.",
                "requires-python" to "^3.11",
                "flask" to "3.0.3",
                "sqlalchemy" to "2.0.35",
                "werkzeug" to "3.0.4",
            ),
            facts("python-poetry"),
        )
        assertEquals(
            withPrefix(
                "project.python.",
                "django" to "5.1.2",
                "requests" to "2.32.3",
                "pillow-simd" to "9.0.0.post1",
            ),
            facts("python-pip"),
        )
    }

    @Test
    fun goReadsDirectivesAndPutsDirectModulesFirst() {
        assertEquals(
            withPrefix(
                "project.go.",
                "version" to "1.22.1",
                "toolchain" to "1.22.3",
                "github.com/spf13/cobra" to "v1.8.1",
                "golang.org/x/net" to "v0.29.0",
                "github.com/inconshreveable/mousetrap" to "v1.1.0",
                "golang.org/x/text" to "v0.18.0",
            ),
            facts("go"),
        )
    }

    @Test
    fun anEmptyOrBrokenProjectAddsNothing() {
        assertEquals(emptyList(), ProjectScanner.scan(FakeHost()))
        val broken = FakeHost(
            files = mapOf(
                "composer.lock" to "{not json",
                "package.json" to "[]",
                "go.mod" to "garbage",
                "pom.xml" to "<project>",
                "pyproject.toml" to "[[[",
            ),
        )
        assertEquals(emptyList(), ProjectScanner.scan(broken))
    }

    @Test
    fun aDirectoryPrefixSelectsTheProject() {
        val host = FakeHost(files = mapOf("work/app/go.mod" to "go 1.21\n"))
        assertEquals(emptyList(), ProjectScanner.scan(host))
        val attr = ProjectScanner.scan(host, "work/app/").single()
        assertEquals("project.go.version", attr.path)
        assertEquals("project:go.mod", attr.source)
    }

    @Test
    fun theCapKeepsDirectPackagesAndCountsTheRest() {
        val packages = (0 until 250).joinToString(",") {
            """{"name":"vendor/p${it.toString().padStart(3, '0')}","version":"1.$it"}"""
        }
        val host = FakeHost(
            files = mapOf(
                "composer.json" to """{"require":{"vendor/p249":"*"}}""",
                "composer.lock" to """{"packages":[$packages]}""",
            ),
        )
        val got = ProjectScanner.scan(host).associate { it.path to it.value }
        val keys = got.keys.filter { it.startsWith("project.composer.vendor/") }
        assertEquals(ProjectScanner.MAX_PACKAGES, keys.size)
        assertEquals("1.249", got["project.composer.vendor/p249"])
        assertEquals("1.0", got["project.composer.vendor/p000"])
        assertEquals(null, got["project.composer.vendor/p200"])
        assertEquals("50", got["project.composer.omitted"])
    }

    @Test
    fun theCapKeepsARuleNamedPackageThatIsNotDirectAndSortsLast() {
        val packages = (0 until 250).joinToString(",") {
            """{"name":"aaa/p${it.toString().padStart(3, '0')}","version":"1.$it"}"""
        }
        val drupal = """{"name":"drupal/core","version":"10.3.6"}"""
        val host = FakeHost(
            files = mapOf(
                "composer.json" to """{"require":{"aaa/p249":"*"}}""",
                "composer.lock" to """{"packages":[$packages,$drupal]}""",
            ),
        )
        val got = ProjectScanner.scan(host).associate { it.path to it.value }
        assertEquals("10.3.6", got["project.composer.drupal/core"])
        assertEquals("1.249", got["project.composer.aaa/p249"])
        assertEquals("1.197", got["project.composer.aaa/p197"])
        assertEquals(null, got["project.composer.aaa/p198"])
        assertEquals("51", got["project.composer.omitted"])
    }

    @Test
    fun captureAddsProjectFactsOnlyOnRequest() {
        val host = fixtureHost("go")
        assertTrue(Capture.run(host, "t").attributes.none { it.path.startsWith("project.") })
        val on = Capture.run(host, "t", project = true).attributes
        assertEquals("v0.29.0", on.single { it.path == "project.go.golang.org/x/net" }.value)
    }

    private fun listing(files: Map<String, String>, order: List<String> = files.keys.toList()) =
        FakeHost(
            files = files,
            commands = mapOf(
                SourceFiles.argv(".") to CommandResult(0, order.joinToString("\n") { "./$it" }),
            ),
        )

    private fun scanned(files: Map<String, String>) =
        ProjectScanner.scan(listing(files)).associate { it.path to it.value }

    @Test
    fun theFindCommandPrunesVendoredAndBuildDirectories() {
        val argv = SourceFiles.argv("app")
        assertEquals(listOf("find", "app", "-maxdepth", "12", "("), argv.take(5))
        for (dir in listOf("node_modules", "vendor", "build", ".gradle", "venv", ".venv")) {
            assertTrue(dir in argv, dir)
        }
        assertTrue("target" in argv && "dist" in argv)
        assertEquals(listOf(")", "-print"), argv.takeLast(2))
    }

    @Test
    fun filesUnderVendoredOrBuildDirectoriesAreNotRead() {
        val got = scanned(
            mapOf(
                "src/a.py" to "import json\n",
                "node_modules/x/b.py" to "import hidden_one\n",
                "build/c.py" to "import hidden_two\n",
                "a/venv/lib/d.py" to "import hidden_three\n",
                "notes.txt" to "import hidden_four\n",
            ),
        )
        assertEquals(setOf("project.python.imports.json"), got.keys)
    }

    @Test
    fun sourceFactsDoNotDependOnTheOrderFindListsFiles() {
        val files = mapOf(
            "b.py" to "import beta\nopen('x')\n",
            "a.py" to "import alpha\n",
            "c.js" to "const m = require('gamma')\n",
        )
        val forward = ProjectScanner.scan(listing(files, listOf("b.py", "a.py", "c.js")))
        val backward = ProjectScanner.scan(listing(files, listOf("c.js", "b.py", "a.py")))
        assertEquals(forward.sortedBy { it.path }, backward.sortedBy { it.path })
        assertEquals(forward, ProjectScanner.scan(listing(files, listOf("b.py", "a.py", "c.js"))))
        assertTrue(forward.all { it.source == "project:source" })
    }

    @Test
    fun noSourceFactsWithoutFindOrOnWindows() {
        val files = mapOf("a.py" to "import json\n")
        assertEquals(emptyList(), ProjectScanner.scan(FakeHost(files = files)))
        assertEquals(emptyList(), ProjectScanner.scan(FakeHost(files = files, canRun = false)))
        val windows = FakeHost(
            os = "windows",
            files = files,
            commands = mapOf(SourceFiles.argv(".") to CommandResult(0, "./a.py")),
        )
        assertEquals(emptyList(), ProjectScanner.scan(windows))
        assertTrue(windows.ran.isEmpty())
    }

    @Test
    fun aDirectoryPrefixListsAndReadsThatProject() {
        val host = FakeHost(
            files = mapOf("work/app/m.py" to "import json\n", "other/n.py" to "import nope\n"),
            commands = mapOf(
                SourceFiles.argv("work/app") to CommandResult(0, "work/app/m.py\nfind: x: denied"),
            ),
        )
        val got = ProjectScanner.scan(host, "work/app/").map { it.path }
        assertEquals(listOf("project.python.imports.json"), got)
    }

    @Test
    fun pythonImportsKeepTheModuleAndItsTopLevelPackage() {
        val got = scanned(
            mapOf(
                "a.py" to """
                    import os.path, sys as system
                    from collections.abc import Mapping
                    from . import sibling
                        import nested
                    # import commented
                    x = 1  # import trailing
                """.trimIndent(),
            ),
        )
        assertEquals(
            setOf("os", "os.path", "sys", "collections", "collections.abc", "nested").map {
                "project.python.imports.$it"
            }.toSet(),
            got.keys,
        )
    }

    @Test
    fun pythonSignalsSeparateTheirCallFormsFromLookAlikes() {
        val hits = scanned(
            mapOf(
                "a.py" to """
                    loop = policy.get_event_loop()
                    handle = obj.open("x")
                    def open(self, path):
                        pass
                    with open("b.bin", mode="rb") as f, open("t", "w", encoding="utf-8") as g:
                        pass
                    import ssl
                """.trimIndent(),
            ),
        ).keys.filter { it.contains(".calls.") }
        assertEquals(emptyList(), hits)
        val positives = scanned(
            mapOf(
                "a.py" to "get_event_loop()\nopen(f(1), 'w')\nssl.match_hostname(c, h)\n",
                "b.py" to "asyncio.get_event_loop()\nfrom ast import Num\nast.NameConstant\n",
            ),
        ).keys.filter { it.contains(".calls.") }.toSet()
        assertEquals(
            setOf("asyncio.get_event_loop", "open-without-encoding", "ssl.match_hostname")
                .plus("ast-removed-nodes").map { "project.python.calls.$it" }.toSet(),
            positives,
        )
    }

    @Test
    fun javaImportsHandleWildcardsStaticAndKotlinAliases() {
        val got = scanned(
            mapOf(
                "A.java" to "import javax.persistence.*;\nimport static a.b.C.d;\n",
                "B.kt" to "import x.y.Z as W\nimport q.R\n",
            ),
        ).keys.map { it.removePrefix("project.java.imports.") }.toSet()
        assertEquals(
            setOf(
                "javax.persistence", "javax", "a.b.C.d", "a.b.C", "a.b", "a",
                "x.y.Z", "x.y", "x", "q.R", "q",
            ),
            got,
        )
    }

    @Test
    fun phpEachIsACallOnlyWhenNotAMethod() {
        fun calls(code: String) = scanned(mapOf("a.php" to code)).keys
            .filter { it.contains(".calls.") }
        assertEquals(emptyList(), calls("<?php \$c->each(fn(\$i) => 1); Foo::each(1);"))
        assertEquals(
            listOf("project.php.calls.each"),
            calls("<?php while (list(\$k, \$v) = each(\$a)) {}"),
        )
    }

    @Test
    fun jsImportsCoverFromRequireAndDynamicForms() {
        val got = scanned(
            mapOf(
                "a.ts" to """
                    export { x } from "@a/b/c"
                    import "side-effect"
                    const y = require("req/sub")
                    const z = await import('dyn')
                    import rel from "./rel"
                    import url from "https://example.com/m.js"
                """.trimIndent(),
            ),
        ).keys.map { it.removePrefix("project.js.imports.") }.toSet()
        assertEquals(
            setOf("@a/b", "@a/b/c", "side-effect", "req", "req/sub", "dyn"),
            got,
        )
    }

    @Test
    fun jsSignalsNeedAStringExitCodeOrAnAssertClause() {
        fun calls(code: String) = scanned(mapOf("a.js" to code)).keys
            .filter { it.contains(".calls.") }.toSet()
        val clean = "process.exitCode = 1;\nimport j from './j.json' with { type: 'json' };"
        assertEquals(emptySet(), calls(clean))
        assertEquals(
            setOf("project.js.calls.exitcode-string"),
            calls("process.exitCode = String(code);"),
        )
        assertEquals(
            setOf("project.js.calls.import-assert"),
            calls("const j = await import('./j.json', { assert: { type: 'json' } });"),
        )
    }

    @Test
    fun addedApiSignalsReadOnlyTheCallSite() {
        fun calls(path: String, code: String) = scanned(mapOf(path to code)).keys
            .filter { it.contains(".calls.") }.toSet()
        assertEquals(
            setOf("project.php.calls.json_validate", "project.php.calls.array_is_list"),
            calls("a.php", "<?php json_validate(\$s); array_is_list(\$a);"),
        )
        assertEquals(
            emptySet(),
            calls("a.php", "<?php \$v->json_validate(\$s); \$o::array_is_list(1);"),
        )
        assertEquals(
            setOf(
                "project.python.calls.copy.replace",
                "project.python.calls.itertools.batched",
                "project.python.calls.datetime.UTC",
            ),
            calls(
                "a.py",
                "new = copy.replace(obj, x=1)\nxs = itertools.batched(ys, 2)\nt = datetime.UTC\n",
            ),
        )
        assertEquals(
            emptySet(),
            calls("a.py", "obj.copy.replace(x)\nmycopy.replace(x)\nmy_itertools.batched(x)\n"),
        )
        assertEquals(
            setOf("project.java.calls.sequenced-first-last", "project.java.calls.Math.clamp"),
            calls(
                "A.java",
                "int f(List<Integer> l) { return l.getFirst() + Math.clamp(l.size(), 0, 9); }",
            ),
        )
        assertEquals(
            emptySet(),
            calls("A.java", "Object a = list.getFirst; getFirst(); Math.max(1, 2);"),
        )
        assertEquals(
            setOf(
                "project.js.calls.Array.fromAsync",
                "project.js.calls.Object.groupBy",
                "project.js.calls.Promise.withResolvers",
            ),
            calls(
                "a.js",
                "await Array.fromAsync(g()); Object.groupBy(xs, f); Promise.withResolvers();",
            ),
        )
        assertEquals(emptySet(), calls("a.js", "fromAsync(x); Array.from(x); Object.keys(x);"))
    }

    @Test
    fun laterAddedApiSignalsReadOnlyTheCallSite() {
        fun calls(path: String, code: String) = scanned(mapOf(path to code)).keys
            .filter { it.contains(".calls.") }.toSet()
        fun keys(eco: String, vararg names: String) = names.map { "project.$eco.calls.$it" }.toSet()
        assertEquals(
            keys(
                "php",
                "str_contains",
                "str_starts_with",
                "str_ends_with",
                "fdiv",
                "get_debug_type",
                "mb_trim",
                "array_find",
                "array_find_key",
                "array_any",
                "array_all",
            ),
            calls(
                "a.php",
                "<?php str_contains(\$a, 'b');" +
                    " str_starts_with(\$a, 'b'); str_ends_with(\$a, 'b');" +
                    " fdiv(1, 2); get_debug_type(\$x); mb_trim(\$s); array_find(\$a, \$f);" +
                    " array_find_key(\$a, \$f); array_any(\$a, \$f); array_all(\$a, \$f);",
            ),
        )
        assertEquals(
            emptySet(),
            calls(
                "a.php",
                "<?php \$v->str_contains(\$a); Foo::fdiv(1); \$o->mb_trim();" +
                    " my_array_find(\$a);",
            ),
        )
        assertEquals(
            keys(
                "python",
                "str.removeprefix",
                "math.lcm",
                "itertools.pairwise",
                "contextlib.chdir",
                "hashlib.file_digest",
                "asyncio.TaskGroup",
                "typing.override",
                "warnings.deprecated",
            ),
            calls(
                "a.py",
                "x = math.lcm(2, 3)\nys = obj.removeprefix('a')\nzs = itertools.pairwise(p)\n" +
                    "w = contextlib.chdir(d)\nh = hashlib.file_digest(f, 'sha256')\n" +
                    "async with asyncio.TaskGroup() as tg:\n    pass\n@typing.override\n" +
                    "@warnings.deprecated('x')\ndef f():\n    pass\n",
            ),
        )
        assertEquals(
            keys("python", "itertools.pairwise", "typing.override"),
            calls("a.py", "from itertools import pairwise\nfrom typing import override\n"),
        )
        assertEquals(
            emptySet(),
            calls("a.py", "my_math.lcm(2)\nlcm(2)\nmath.gcd(2)\nmyitertools.pairwise(x)\n"),
        )
        assertEquals(
            keys(
                "java",
                "Files.readString",
                "Files.writeString",
                "Files.mismatch",
                "Path.of",
                "Predicate.not",
                "Stream.mapMulti",
            ),
            calls(
                "A.java",
                "Files.readString(p); Files.writeString(p, s); Files.mismatch(a, b);" +
                    " Path.of(\"a\"); Predicate.not(s::isEmpty);" +
                    " xs.stream().mapMulti((i, s) -> s.accept(i));",
            ),
        )
        assertEquals(
            emptySet(),
            calls(
                "A.java",
                "myFiles.readString(p); Files.read(p); Path.get(\"a\"); Predicate.and(a);" +
                    " map.mapMultiple(x);",
            ),
        )
        assertEquals(keys("js", "fs.globSync"), calls("a.js", "fs.globSync('*.js');"))
        assertEquals(
            emptySet(),
            calls("a.js", "myfs.globSync(x); fs.glob(x); fs.globSyncX(x);"),
        )
    }

    @Test
    fun theFileAndByteCapsKeepASortedPrefixAndCountTheRest() {
        val many = (0 until SourceFiles.MAX_FILES + 3).associate {
            val n = it.toString().padStart(4, '0')
            "m$n.py" to "import mod$n\n"
        }
        val big = "import big\n" + "#".repeat(SourceFiles.MAX_FILE_CHARS)
        val got = scanned(many + ("big.py" to big))
        assertEquals("4", got["project.python.source-omitted"])
        assertEquals(null, got["project.python.imports.big"])
        assertEquals("true", got["project.python.imports.mod0000"])
        assertEquals(null, got["project.python.imports.mod${SourceFiles.MAX_FILES}"])
    }

    @Test
    fun theImportCapKeepsRuleNamedNamesFirstAndCountsTheRest() {
        val wildcards = (0 until 600).joinToString("\n") {
            "import a.p${it.toString().padStart(3, '0')}.*;"
        }
        val got = scanned(mapOf("A.java" to "$wildcards\nimport sun.misc.Unsafe;\n"))
        val imports = got.keys.filter { it.startsWith("project.java.imports.") }
        assertEquals(ProjectScanner.MAX_IMPORTS, imports.size)
        assertEquals("true", got["project.java.imports.sun.misc.Unsafe"])
        assertEquals("true", got["project.java.imports.sun"])
        assertEquals("true", got["project.java.imports.a.p000"])
        assertEquals(null, got["project.java.imports.a.p599"])
        assertEquals(null, got["project.java.imports.sun.misc"])
        assertEquals("104", got["project.java.imports-omitted"])
    }

    @Test
    fun npmTsEntryComesFromMainBinOrANodeScript() {
        fun facts(manifest: String, lock: String? = null) = ProjectScanner.scan(
            FakeHost(
                files = mapOf("package.json" to manifest) +
                    (lock?.let { mapOf("package-lock.json" to it) } ?: emptyMap()),
            ),
        ).associate { it.path to it.value }

        assertEquals("true", facts("""{"main":"src/index.ts"}""")["project.npm.ts-entry"])
        assertEquals("true", facts("""{"bin":{"x":"bin/x.mts"}}""")["project.npm.ts-entry"])
        assertEquals("true", facts("""{"bin":"cli.cts"}""")["project.npm.ts-entry"])
        assertEquals(
            "true",
            facts("""{"scripts":{"s":"NODE_ENV=1 node --watch app.ts && echo"}}""")
            ["project.npm.ts-entry"],
        )
        assertEquals(null, facts("""{"main":"types/index.d.ts"}""")["project.npm.ts-entry"])
        assertEquals(null, facts("""{"scripts":{"s":"tsc app.ts"}}""")["project.npm.ts-entry"])
        assertEquals(null, facts("""{"main":"dist/index.js"}""")["project.npm.ts-entry"])
        val locked = facts("{}", """{"lockfileVersion":2,"packages":{}}""")
        assertEquals("2", locked["project.npm.lockfile-version"])
        val text = facts("{}", """{"lockfileVersion":"x"}""")
        assertEquals(null, text["project.npm.lockfile-version"])
    }

    @Test
    fun archiveBaseNameNeedsAnArchiveTaskNotANativeBinary() {
        fun calls(script: String) = ProjectScanner.scan(
            FakeHost(files = mapOf("build.gradle.kts" to script)),
        ).filter { it.path.contains(".calls.") }.map { it.path }

        val native = "kotlin { binaries { executable { baseName = \"drift\" } } }"
        assertEquals(emptyList(), calls(native))
        val jar = "tasks.named<Jar>(\"jar\") {\n    baseName = \"x\"\n}"
        assertEquals(listOf("project.gradle.calls.archive-basename"), calls(jar))
        val dotted = "jar.baseName = 'x'"
        assertEquals(listOf("project.gradle.calls.archive-basename"), calls(dotted))
        val newName = "tasks.jar {\n    archiveBaseName.set(\"x\")\n}"
        assertEquals(emptyList(), calls(newName))
    }
}
