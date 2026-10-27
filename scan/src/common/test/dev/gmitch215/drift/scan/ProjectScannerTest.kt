package dev.gmitch215.drift.scan

import dev.gmitch215.drift.fixtures.ProjectFixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.know.Detect
import dev.gmitch215.drift.know.Env
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.know.Verdict
import dev.gmitch215.drift.scan.project.ProjectScanner
import dev.gmitch215.drift.scan.project.SourceFiles
import dev.gmitch215.drift.scan.project.nativeArgv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A host holding the fixture project [dir]; `find` lists every file, as it would on disk. */
internal fun projectFixtureHost(dir: String): FakeHost {
    val files = ProjectFixtures.names.filter { it.startsWith("$dir/") }
        .associate { it.removePrefix("$dir/") to ProjectFixtures.text(it) }
    val listing = files.keys.joinToString("\n") { "./$it" }
    val platformed = files.filter { (name, text) ->
        name.startsWith("node_modules/") && name.endsWith("/package.json") &&
            Regex("\"(os|cpu)\"\\s*:").containsMatchIn(text)
    }.keys.joinToString("\n") { "./$it" }
    return FakeHost(
        files = files,
        commands = mapOf(
            SourceFiles.argv(".") to CommandResult(0, listing),
            nativeArgv(".") to CommandResult(0, platformed),
        ),
    )
}

class ProjectScannerTest {
    private fun fixtureHost(dir: String) = projectFixtureHost(dir)

    private fun facts(dir: String): Map<String, String> =
        ProjectScanner.scan(fixtureHost(dir)).associate { it.path to it.value }

    private fun withPrefix(prefix: String, vararg pairs: Pair<String, String>) =
        pairs.associate { "$prefix${it.first}" to it.second }

    @Test
    fun aVersionFileOfTheProjectIsAPinForItsTool() {
        fun pins(files: Map<String, String>) = ProjectScanner.scan(FakeHost(files = files))
            .filter { it.path.startsWith("project.pins.") }.associate { it.path to it.value }
        assertEquals(
            mapOf("project.pins.node" to "18.19.0", "project.pins.python" to "3.9"),
            pins(mapOf(".nvmrc" to "# lts\nv18.19.0\n", ".python-version" to "3.9\n")),
        )
        assertEquals(mapOf("project.pins.node" to "20"), pins(mapOf(".node-version" to "20")))
        val sum = "3e34e52bb51b5b63087200744a69c631497c49ea948659c7225bcb8e5fef19ac"
        assertEquals(
            mapOf("project.checksums.vendor/a.txt" to sum),
            ProjectScanner.scan(
                FakeHost(files = mapOf("SHA256SUMS" to "$sum  vendor/a.txt\nbad\n")),
            )
                .filter { it.path.startsWith("project.checksums.") }
                .associate { it.path to it.value },
        )
        assertEquals(emptyMap(), pins(mapOf(".nvmrc" to "\n")))
        assertEquals(emptyMap(), pins(emptyMap()))
    }

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
                "mikey179/vfsstream" to "v1.6.8",
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
            "android.compile-sdk" to "36.1",
            "android.min-sdk" to "24",
            "android.target-sdk" to "36",
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
    fun androidSdkLevelsReadEachFormAndSkipExpressions() {
        fun sdk(build: String, catalog: String? = null) = ProjectScanner.scan(
            FakeHost(
                files = mapOf("build.gradle" to build) +
                    (catalog?.let { mapOf("gradle/libs.versions.toml" to it) } ?: emptyMap()),
            ),
        ).filter { it.path.startsWith("project.gradle.android.") }
            .associate { it.path.removePrefix("project.gradle.android.") to it.value }

        assertEquals(
            mapOf("compile-sdk" to "33", "min-sdk" to "21", "target-sdk" to "33"),
            sdk("compileSdk 33\nminSdkVersion(21)\ntargetSdk = 33\n"),
        )
        assertEquals(
            mapOf("compile-sdk" to "36"),
            sdk("compileSdk { version = release(36) }\nminSdk = flutter.minSdkVersion\n"),
        )
        assertEquals(
            mapOf("min-sdk" to "24"),
            sdk(
                "minSdk = libs.versions.android.minSdk.get().toInt()\n" +
                    "compileSdk = libs.versions.android.compileSdk.get().toInt()\n",
                "[versions]\nandroid-minSdk = \"24\"\nandroid-compileSdk = \"android-36\"\n",
            ),
        )
        assertEquals(emptyMap(), sdk("compileSdkVersion 'android-33'\n"))
    }

    @Test
    fun flutterProjectsReadTheGradleFilesUnderAndroid() {
        val got = facts("flutter")
        val gradle = got.filterKeys { it.startsWith("project.gradle.") }
        assertEquals(
            withPrefix(
                "project.gradle.",
                "wrapper" to "9.3.1",
                "properties.org.gradle.jvmargs" to
                    "-Xmx8G -XX:MaxMetaspaceSize=4G -XX:ReservedCodeCacheSize=512m " +
                    "-XX:+HeapDumpOnOutOfMemoryError",
                "properties.android.useAndroidX" to "true",
                "properties.android.newDsl" to "false",
                "properties.android.builtInKotlin" to "false",
                "plugin.com.android.application" to "9.1.0",
                "plugin.org.jetbrains.kotlin.android" to "2.4.0",
                "plugin.dev.flutter.flutter-plugin-loader" to "1.0.0",
                "plugin.dev.flutter.flutter-gradle-plugin" to "true",
                "kotlin.version" to "2.4.0",
                "agp.version" to "9.1.0",
                "dsl" to "kotlin",
            ),
            gradle,
        )
        assertEquals(">=3.16.0", got["project.dart.sdks.flutter"])
        assertEquals("1.9.1", got["project.dart.path"])
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
                "android.compile-sdk" to "34",
                "android.min-sdk" to "21",
                "android.target-sdk" to "34",
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
                "flask.requires.python" to ">=3.8",
                "sqlalchemy" to "2.0.35",
                "sqlalchemy.requires.python" to ">=3.7",
                "werkzeug" to "3.0.4",
                "werkzeug.requires.python" to ">=3.8",
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

    // #region package constraints
    private fun requires(dir: String) = facts(dir).filterKeys { ".requires." in it }

    @Test
    fun composerRecordsTheRequiredPhpOfEachLockedPackage() {
        assertEquals(
            withPrefix(
                "project.composer.",
                "lcobucci/clock.requires.php" to "^7.4 || ^8.0",
                "phpspec/prophecy.requires.php" to "^7.2 || ~8.0, <8.1",
                "mikey179/vfsstream.requires.php" to ">=5.3.0",
                "phpunit/phpunit.requires.php" to ">=7.3",
                "phpbench/dom.requires.php" to "^7.2|^8.0",
                "symfony/console.requires.php" to ">=7.2.5",
            ),
            requires("composer-real"),
        )
        assertEquals("1.12.2", facts("composer-real")["project.composer.phpspec/prophecy"])
    }

    @Test
    fun npmLockRecordsEnginesNodePerTopLevelPackageOnly() {
        assertEquals(
            withPrefix(
                "project.npm.",
                "@babel/code-frame.requires.node" to ">=6.9.0",
                "@babel/parser.requires.node" to ">=6.0.0",
                "@commitlint/cli.requires.node" to ">=v18",
                "@eslint-community/eslint-utils.requires.node" to
                    "^12.22.0 || ^14.17.0 || >=16.0.0",
                "@eslint-community/regexpp.requires.node" to
                    "^12.0.0 || ^14.0.0 || >=16.0.0",
            ),
            requires("npm-real"),
        )
        assertEquals(null, requires("npm-real")["project.npm.semver.requires.node"])
    }

    @Test
    fun pnpmReadsTheLastDocumentAndRecordsEnginesNode() {
        val got = facts("pnpm-real").filterKeys { it.startsWith("project.npm.") }
        assertEquals(
            withPrefix(
                "project.npm.",
                "@arcanis/slice-ansi" to "1.1.1",
                "@babel/code-frame" to "7.29.7",
                "@babel/code-frame.requires.node" to ">=6.9.0",
                "@babel/core" to "7.29.7",
                "@babel/core.requires.node" to ">=6.9.0",
                "@babel/parser" to "7.29.9",
                "@babel/parser.requires.node" to ">=6.0.0",
                "@commitlint/cli" to "21.2.3",
                "@commitlint/cli.requires.node" to ">=22.12.0",
            ),
            got,
        )
    }

    @Test
    fun poetryRecordsPythonVersionsAndOtherLocksRecordNone() {
        assertEquals(
            withPrefix(
                "project.python.",
                "flask.requires.python" to ">=3.8",
                "sqlalchemy.requires.python" to ">=3.7",
                "werkzeug.requires.python" to ">=3.8",
            ),
            requires("python-poetry"),
        )
        for (dir in listOf("python", "python-pip", "npm-yarn", "npm-yarn-berry", "npm-bun")) {
            assertEquals(emptyMap(), requires(dir), dir)
        }
    }

    @Test
    fun anUnconstrainedPackageAndAWildcardAddNoKey() {
        val host = FakeHost(
            files = mapOf(
                "composer.lock" to """{"packages":[{"name":"a/a","version":"1.0.0"},""" +
                    """{"name":"a/b","version":"1.0.0","require":{"php":"*"}},""" +
                    """{"name":"a/c","version":"1.0.0",""" +
                    """"require":{"php":"^8.0","ext-json":"*"}}]}""",
            ),
        )
        val got = ProjectScanner.scan(host).associate { it.path to it.value }
        assertEquals(
            listOf("project.composer.a/c.requires.php"),
            got.keys.filter { ".requires." in it },
        )
    }

    @Test
    fun theCapKeepsTheConstraintsOfTheKeptPackagesOnly() {
        val packages = (0 until 250).joinToString(",") {
            val name = "vendor/p${it.toString().padStart(3, '0')}"
            """{"name":"$name","version":"1.$it","require":{"php":">=7.4"}}"""
        }
        val got = ProjectScanner.scan(
            FakeHost(files = mapOf("composer.lock" to """{"packages":[$packages]}""")),
        ).associate { it.path to it.value }
        val kept = got.keys.filter { ".requires." in it }
        assertEquals(ProjectScanner.MAX_PACKAGES, kept.size)
        assertEquals(null, got["project.composer.vendor/p200.requires.php"])
        assertEquals(">=7.4", got["project.composer.vendor/p000.requires.php"])
    }

    @Test
    fun removedPythonModulesAndSslCallsComeFromTheSource() {
        val got = facts("python-legacy")
        val modules = "aifc audioop cgi cgitb chunk crypt imghdr mailcap msilib nis nntplib " +
            "ossaudiodev pipes sndhdr spwd sunau telnetlib uu xdrlib asynchat asyncore imp"
        for (name in modules.split(' ')) {
            assertEquals("true", got["project.python.imports.$name"], name)
        }
        assertEquals("true", got["project.python.calls.ssl.wrap_socket"])
        assertEquals("true", got["project.python.calls.ssl.match_hostname"])
    }

    @Test
    fun symfonySourceSignalsMatchTheRemovedCallsAndNothingElse() {
        val got = facts("php-symfony").filterKeys { it.startsWith("project.symfony.source.") }
        assertEquals(
            setOf(
                "command-execute-returns-null", "token-getroles-call", "user-getusername-call",
                "session-service-lookup", "command-defaultname-property",
                "parameterbag-getint-call", "controller-renderform-call", "request-get-call",
                "twig-simple-class-use",
            ).map { "project.symfony.source.$it" }.toSet(),
            got.keys,
        )
        val clean = scanned(
            mapOf(
                "a.php" to "<?php class A extends Command {\n" +
                    " protected function execute(\$i, \$o) { return 0; }\n" +
                    " public function f(\$request) { return \$request->query->get('x'); }\n}",
            ),
        )
        assertEquals(emptySet(), clean.keys.filter { it.startsWith("project.symfony.") }.toSet())
    }

    @Test
    fun anInternalInterfaceOverrideNeedsNoReturnTypeAndNoAttribute() {
        fun hit(body: String, head: String = "implements Countable") =
            scanned(mapOf("a.php" to "<?php class C $head { $body }"))
                .containsKey("project.php.internal-interface-override-no-return-type")
        assertTrue(hit("function count() { return 1; }", "implements \\Countable"))
        assertTrue(hit("function getIterator()\n {}", "implements IteratorAggregate"))
        assertFalse(hit("public function count(): int { return 1; }"))
        assertFalse(hit("#[\\ReturnTypeWillChange] function count() {}"))
        assertFalse(hit("public function count() { return 1; }", ""))
        val key = "project.php.internal-interface-override-no-return-type"
        assertTrue(facts("php-symfony").containsKey(key))
    }

    @Test
    fun javaSourceAndBuildFilesGiveTheRemovedJdkFeatureFacts() {
        val got = facts("java-legacy")
        assertEquals("true", got["project.java.dynamic-agent-load"])
        assertEquals("true", got["project.java.value-based-sync"])
        assertEquals("nashorn", got["project.java.script-engines"])
        assertEquals("default", got["project.java.keystore-type"])
        assertEquals("true", got["project.java.javah-invocation"])
        assertEquals("true", got["project.java.imports.javax.validation"])
        assertEquals("true", got["project.gradle.javaexec-without-jvm-set"])
        assertEquals("5.3.30", got["project.spring-framework.version"])
    }

    @Test
    fun javaFactsSkipLookAlikes() {
        val clean = scanned(
            mapOf(
                "A.java" to """
                    class A {
                        private final Object lock = new Object();
                        void f(String s) throws Exception {
                            synchronized (lock) { }
                            java.security.KeyStore.getInstance("PKCS12");
                            new javax.script.ScriptEngineManager().getEngineByName("graal.js");
                        }
                    }
                """.trimIndent(),
                "build.gradle" to "tasks.register('javahelper')\n",
            ),
        )
        assertEquals(null, clean["project.java.value-based-sync"])
        assertEquals(null, clean["project.java.dynamic-agent-load"])
        assertEquals(null, clean["project.java.javah-invocation"])
        assertEquals("pkcs12", clean["project.java.keystore-type"])
        assertEquals("graal.js".lowercase(), clean["project.java.script-engines"])
    }

    @Test
    fun drupalReadsRuleNamedModulesThemesSettingsAndTestBaseClasses() {
        val got = facts("drupal").filterKeys { it.startsWith("project.drupal.") }
        val modules = "actions_ui activity_tracker aggregator book ckeditor4 color forum hal " +
            "quickedit rdf statistics tour"
        val themes = "bartik classy seven stable"
        val expected = modules.split(' ').map { "project.drupal.modules.$it" } +
            themes.split(' ').map { "project.drupal.themes.$it" } +
            listOf("project.drupal.settings.db-prefix-table-array") +
            listOf("project.drupal.tests.simpletest-base-class")
        assertEquals(expected.toSet(), got.keys)
        assertEquals(emptySet(), got.filterKeys { it.endsWith("modules.node") }.keys)
    }

    @Test
    fun aSinglePrefixStringIsNotATablePrefixArray() {
        val host = FakeHost(
            files = mapOf(
                "web/sites/default/settings.php" to "<?php\n\$databases['default']['default'] = " +
                    "['prefix' => 'site_'];\n",
            ),
            commands = mapOf(SourceFiles.argv(".") to CommandResult(0, "")),
        )
        assertEquals(
            emptyList(),
            ProjectScanner.scan(host).filter { it.path.startsWith("project.drupal.") },
        )
    }

    @Test
    fun routerUtf8IsTheConfiguredValueOrUnset() {
        val key = "project.symfony.config.framework-router-utf8"
        fun utf8(yaml: String?) = ProjectScanner.scan(
            FakeHost(
                files = listOfNotNull(yaml?.let { "config/packages/framework.yaml" to it }).toMap(),
                commands = mapOf(SourceFiles.argv(".") to CommandResult(0, "")),
            ),
        ).firstOrNull { it.path == key }?.value
        assertEquals("false", facts("php-symfony")[key])
        assertEquals("true", utf8("framework:\n    router:\n        utf8: 'true'\n"))
        assertEquals("unset", utf8("framework:\n    router:\n        strict_requirements: ~\n"))
        assertEquals("unset", utf8("framework:\n    secret: x\n"))
        assertEquals("unset", utf8("framework:\n    router: {}\nother:\n    utf8: true\n"))
        assertEquals(null, utf8("other:\n    router:\n        utf8: true\n"))
        assertEquals(null, utf8(null))
    }
    // #endregion

    // #region npm frameworks and native packages
    @Test
    fun npmFrameworkFixtureReadsEveryFrameworkVersionAndItsNodeFloor() {
        val got = facts("npm-frameworks").filterKeys { it.startsWith("project.npm.") }
        assertEquals("16.4.1", got["project.npm.next"])
        assertEquals("22.2.3", got["project.npm.@angular/core"])
        assertEquals("0.88.0", got["project.npm.react-native"])
        assertEquals("7.10.0", got["project.npm.@prisma/client"])
        assertEquals("4.6.1", got["project.npm.nuxt"])
        assertEquals(">=20.9.0", got["project.npm.next.requires.node"])
        assertEquals(">=22.22.0", got["project.npm.astro.requires.node"])
        assertEquals("3", got["project.npm.lockfile-version"])
    }

    @Test
    fun npmNativeRecordsTheInstalledPlatformVariants() {
        val got = facts("npm-native").filterKeys { it.startsWith("project.npm.native") }
        assertEquals(
            withPrefix(
                "project.npm.",
                "native.@esbuild/darwin-arm64" to "0.28.2",
                "native.@esbuild/darwin-arm64.platform" to "darwin-arm64",
                "native.@next/swc-darwin-arm64" to "16.4.1",
                "native.@next/swc-darwin-arm64.platform" to "darwin-arm64",
                "native.@rollup/rollup-darwin-arm64" to "4.64.6",
                "native.@rollup/rollup-darwin-arm64.platform" to "darwin-arm64",
                "native.platforms" to "darwin-arm64",
            ),
            got,
        )
    }

    @Test
    fun npmNativeKeepsLibcAndMultipleValuesAndSkipsNestedAndBrokenManifests() {
        fun scan(vararg manifests: Pair<String, String>): Map<String, String> {
            val files = manifests.toMap()
            val listing = files.keys.joinToString("\n") { "./$it" }
            val host = FakeHost(
                files = files,
                commands = mapOf(
                    SourceFiles.argv(".") to CommandResult(0, ""),
                    nativeArgv(".") to CommandResult(0, listing),
                ),
            )
            return ProjectScanner.scan(host).associate { it.path to it.value }
                .filterKeys { it.startsWith("project.npm.native") }
        }
        val got = scan(
            "node_modules/@img/sharp-linuxmusl-x64/package.json" to
                """{"version":"0.35.5","os":["linux"],"cpu":["x64"],"libc":["musl"]}""",
            "node_modules/multi/package.json" to
                """{"version":"1.0.0","os":["darwin","linux"],"cpu":"x64"}""",
            "node_modules/a/node_modules/nested/package.json" to
                """{"version":"1.0.0","os":["linux"]}""",
            "node_modules/a/lib/package.json" to """{"version":"1.0.0","os":["linux"]}""",
            "node_modules/broken/package.json" to "{",
            "node_modules/nover/package.json" to """{"os":["linux"]}""",
        )
        assertEquals(
            withPrefix(
                "project.npm.",
                "native.@img/sharp-linuxmusl-x64" to "0.35.5",
                "native.@img/sharp-linuxmusl-x64.platform" to "linux-x64-musl",
                "native.multi" to "1.0.0",
                "native.multi.platform" to "darwin+linux-x64",
                "native.platforms" to "darwin+linux-x64,linux-x64-musl",
            ),
            got,
        )
        assertEquals(emptyMap(), scan())
    }

    @Test
    fun npmNativeCapsAtFiftyPackagesAndCountsTheRest() {
        val files = (1..53).associate {
            val name = it.toString().padStart(2, '0')
            "node_modules/p$name/package.json" to """{"version":"1.0.0","os":["linux"]}"""
        }
        val host = FakeHost(
            files = files,
            commands = mapOf(
                SourceFiles.argv(".") to CommandResult(0, ""),
                nativeArgv(".") to CommandResult(0, files.keys.joinToString("\n") { "./$it" }),
            ),
        )
        val got = ProjectScanner.scan(host).associate { it.path to it.value }
        assertEquals(101, got.keys.count { it.startsWith("project.npm.native.p") })
        assertEquals("3", got["project.npm.native-omitted"])
        assertEquals(
            null,
            ProjectScanner.scan(FakeHost(os = "windows"))
                .firstOrNull { it.path.startsWith("project.npm.native") },
        )
    }

    @Test
    fun frameworkRulesMatchTheFixtureLockfileOnNode18ButNotOnNode26() {
        val project = facts("npm-frameworks")
        fun hits(node: String): Set<String> {
            val a = Env(project + ("tool.node.version" to "26.1.0"))
            val b = Env(project + ("tool.node.version" to node))
            return Rules.all.filter { rule ->
                Detect.match(rule, a, b).verdict == Verdict.MATCH &&
                    rule.provenance.any { it.url.startsWith("https://registry.npmjs.org/") }
            }.map { it.id }.toSet()
        }
        assertEquals(
            setOf(
                "next-16-node-20-9-minimum",
                "vite-7-node-20-19-minimum",
                "react-router-7-node-20-minimum",
                "react-router-8-node-22-22-minimum",
                "angular-20-node-20-19-minimum",
                "angular-22-node-22-22-3-minimum",
                "react-native-0-81-node-20-19-4-minimum",
                "react-native-0-87-node-22-13-minimum",
                "prisma-7-node-20-19-minimum",
                "astro-6-0-6-node-22-12-minimum",
                "astro-7-4-node-22-22-minimum",
                "nuxt-3-18-node-20-19-minimum",
                "nuxt-4-4-6-node-22-12-minimum",
                "nuxt-4-6-0-node-22-22-3-minimum",
            ),
            hits("18.20.4"),
        )
        assertEquals(emptySet(), hits("26.1.0"))
    }

    @Test
    fun nativeRulesMatchTheFixtureInstallOnLinuxButNotOnMacos() {
        val project = facts("npm-native")
        val mac = Env(project + mapOf("os.name" to "macos", "os.arch" to "aarch64"))
        val linux = Env(project + mapOf("os.name" to "linux", "os.arch" to "aarch64"))
        fun hits(a: Env, b: Env) = Rules.all.filter {
            it.id.startsWith("npm-native-") && Detect.match(it, a, b).verdict == Verdict.MATCH
        }.map { it.id }
        assertEquals(listOf("npm-native-installed-on-another-os"), hits(mac, linux))
        assertEquals(emptyList(), hits(mac, mac))
    }
    // #endregion

    // #region python-php-frameworks
    @Test
    fun theFrameworkFixturesEmitTheKeysTheFrameworkRulesRead() {
        val py = facts("python-frameworks")
        assertEquals("5.0.14", py["project.python.django"])
        assertEquals("2.0.2", py["project.python.numpy"])
        assertEquals("true", py["project.python.imports.cx_Oracle"])
        for (key in listOf(
            "django.source.removed-4-0-names",
            "django.source.removed-5-0-modules",
            "django.source.removed-5-1-names",
            "numpy.source.removed-2-0-members",
            "pydantic.source.basesettings-import",
            "pydantic.source.removed-names",
            "flask.source.removed-2-3-features",
            "pandas.source.removed-2-0-members",
            "pandas.source.removed-3-0-members",
            "torch.source.load-without-weights-only",
        )) {
            assertEquals("true", py["project.$key"], key)
        }
        val php = facts("php-frameworks")
        assertEquals("v13.0.0", php["project.composer.laravel/framework"])
        assertEquals("^8.3", php["project.composer.laravel/framework.requires.php"])
        assertEquals("v13.4.0", php["project.composer.typo3/cms-core"])
        assertEquals("6.6.0", php["project.composer.johnpbloch/wordpress-core"])
        val wp = facts("wordpress")
        assertEquals("6.6-src", wp["project.wordpress.version"])
    }

    @Test
    fun wordpressVersionComesFromTheFirstRootThatHasVersionPhp() {
        fun wp(vararg files: Pair<String, String>) =
            ProjectScanner.scan(FakeHost(files = mapOf(*files)))
                .filter { it.path.startsWith("project.wordpress.") }
                .associate { it.path to it.value }
        val file = "\$wp_version = '7.0';\n"
        assertEquals(
            mapOf("project.wordpress.version" to "7.0"),
            wp("web/wp/wp-includes/version.php" to file),
        )
        assertEquals(
            mapOf("project.wordpress.version" to "7.0"),
            wp("wp-includes/version.php" to file),
        )
        assertEquals(
            mapOf("project.wordpress.version" to "6.0"),
            wp(
                "wp-includes/version.php" to "\$wp_version = '6.0';\n",
                "wp/wp-includes/version.php" to file,
            ),
        )
        assertEquals(emptyMap(), wp("wp-includes/version.php" to "<?php // no version"))
        assertEquals(emptyMap(), wp("other/wp-includes/version.php" to file))
    }

    @Test
    fun frameworkSignalsReadRemovedNamesAndSkipLookAlikes() {
        fun calls(code: String) = scanned(mapOf("a.py" to code)).keys
            .filter { it.contains(".source.") }.toSet()
        fun keys(vararg names: String) = names.map { "project.$it" }.toSet()
        val none = emptySet<String>()
        val django4 = keys("django.source.removed-4-0-names")
        assertEquals(django4, calls("from django.utils.encoding import force_text\n"))
        assertEquals(
            none,
            calls("from django.utils.encoding import force_str\ngettext_lazy('a')\n"),
        )
        val django5 = keys("django.source.removed-5-0-modules")
        assertEquals(django5, calls("from django.utils import timezone, datetime_safe\n"))
        assertEquals(django5, calls("import django.utils.baseconv\n"))
        assertEquals(none, calls("from django.utils import timezone\n"))
        val django51 = keys("django.source.removed-5-1-names")
        assertEquals(
            django51,
            calls("class M:\n    class Meta:\n        index_together = [('a', 'b')]\n"),
        )
        assertEquals(django51, calls("STATICFILES_STORAGE = 'x.Y'\n"))
        assertEquals(
            django51,
            calls("H = ['django.contrib.auth.hashers.UnsaltedMD5PasswordHasher']\n"),
        )
        assertEquals(none, calls("STORAGES = {}\nindexes = []\n"))
        val numpy = keys("numpy.source.removed-2-0-members")
        assertEquals(numpy, calls("x = np.float_(1) + numpy.NaN\n"))
        assertEquals(none, calls("np.float64(1)\nnp.nan\nnp.float_power(a, b)\nmynp.product(x)\n"))
        val settings = keys("pydantic.source.basesettings-import")
        assertEquals(settings, calls("from pydantic import BaseSettings\n"))
        assertEquals(
            settings,
            calls("from pydantic import (\n    BaseModel,\n    BaseSettings,\n)\n"),
        )
        assertEquals(settings, calls("class S(pydantic.BaseSettings):\n    pass\n"))
        assertEquals(
            none,
            calls("from pydantic_settings import BaseSettings\nfrom pydantic import BaseModel\n"),
        )
        assertEquals(keys("pydantic.source.removed-names"), calls("x: ConstrainedInt\n"))
        assertEquals(none, calls("x = constr(min_length=1)\n"))
        val flask = keys("flask.source.removed-2-3-features")
        assertEquals(flask, calls("@app.before_first_request\ndef f():\n    pass\n"))
        assertEquals(flask, calls("app.json_encoder = Encoder\n"))
        assertEquals(flask, calls("JSON_SORT_KEYS = False\n"))
        assertEquals(none, calls("@app.before_request\ndef f():\n    pass\n"))
        assertEquals(
            keys("pandas.source.removed-2-0-members"),
            calls("for k, v in df.iteritems():\n    pass\n"),
        )
        assertEquals(
            keys("pandas.source.removed-2-0-members"),
            calls("from pandas import Int64Index\n"),
        )
        assertEquals(keys("pandas.source.removed-3-0-members"), calls("df.applymap(f)\n"))
        assertEquals(
            keys("pandas.source.removed-3-0-members"),
            calls("pd.read_csv(p, delim_whitespace=True)\n"),
        )
        assertEquals(none, calls("df.items()\ndf.map(f)\nIndex([1])\n"))
        val torch = keys("torch.source.load-without-weights-only")
        assertEquals(torch, calls("m = torch.load(path)\n"))
        assertEquals(torch, calls("m = torch.load(path, map_location='cpu')\n"))
        assertEquals(
            none,
            calls("torch.load(path, weights_only=True)\ntorch.load(p, weights_only=False)\n"),
        )
        assertEquals(none, calls("mytorch.load(path)\njson.load(f)\n"))
    }

    @Test
    fun frameworkRulesMatchTheFixtureInstalls() {
        val families = listOf(
            "django", "numpy", "fastapi", "pydantic", "flask", "torch", "pandas",
            "laravel", "typo3", "wordpress",
        )
        fun hits(a: Env, b: Env) = Rules.all.filter { rule ->
            families.any { rule.id.startsWith(it) } &&
                Detect.match(rule, a, b).verdict == Verdict.MATCH
        }.map { it.id }.toSet()
        fun python(base: Map<String, String>, version: String) =
            Env(base + ("tool.python.version" to version))
        fun php(base: Map<String, String>, version: String) =
            Env(base + ("tool.php.version" to version))
        val py = facts("python-frameworks")
        assertEquals(
            setOf("django-5-python-3-10-minimum"),
            hits(python(py, "3.12.4"), python(py, "3.9.19")),
        )
        val before = Env(
            mapOf(
                "project.python.django" to "4.2.30",
                "project.python.numpy" to "1.26.4",
                "project.python.pandas" to "1.5.3",
                "project.python.pydantic" to "1.10.26",
                "project.python.flask" to "2.2.5",
                "project.python.torch" to "2.5.1",
                "project.python.fastapi" to "0.99.1",
                "project.python.scipy" to "1.12.3",
            ),
        )
        assertEquals(
            setOf(
                "django-5-0-removes-datetime-safe-and-baseconv",
                "numpy-2-0-removes-main-namespace-members",
                "numpy-2-breaks-pandas-before-2-2-2",
                "numpy-2-breaks-scipy-before-1-13",
                "pydantic-2-moves-basesettings-to-pydantic-settings",
                "pydantic-2-removes-constrained-types-and-error-wrappers",
                "flask-2-3-removes-before-first-request-and-json-encoder",
                "pandas-2-0-removes-iteritems-and-numeric-index-classes",
                "torch-2-6-load-defaults-to-weights-only",
                "fastapi-before-0-100-requires-pydantic-1",
            ),
            hits(before, Env(py)),
        )
        val lock = facts("php-frameworks")
        assertEquals(
            setOf("laravel-13-php-8-3-minimum", "typo3-13-php-8-2-minimum"),
            hits(php(lock, "8.4.1"), php(lock, "8.1.27")),
        )
        val wp = facts("wordpress")
        assertEquals(
            setOf("wordpress-6-6-php-7-2-24-minimum"),
            hits(php(wp, "8.2.20"), php(wp, "7.2.23")),
        )
    }
    // #endregion

    // #region r, dart, julia, elixir
    @Test
    fun renvReadsTheRVersionAndEveryLockedPackage() {
        assertEquals(
            withPrefix(
                "project.r.",
                "r-version" to "4.6.1",
                "R6" to "2.6.1",
                "renv" to "1.3.1",
            ),
            facts("r"),
        )
    }

    @Test
    fun pubspecLockReadsPackagesTheSdkConstraintAndItsLowerBound() {
        assertEquals(
            withPrefix(
                "project.dart.",
                "sdks.dart" to ">=3.13.0 <4.0.0",
                "sdk-min" to "3.13.0",
                "path" to "1.9.1",
            ),
            facts("dart"),
        )
        val lock = """
            packages:
              flutter:
                dependency: "direct main"
                description: flutter
                source: sdk
                version: "0.0.0"
              http:
                dependency: transitive
                description:
                  name: http
                  url: "https://pub.dev"
                source: hosted
                version: "1.2.2"
              app:
                dependency: "direct dev"
                description:
                  name: app
                source: hosted
                version: "2.0.0"
            sdks:
              dart: ">=2.9.0 <3.0.0"
              flutter: ">=3.0.0"
        """.trimIndent()
        val scanned = ProjectScanner.scan(FakeHost(files = mapOf("pubspec.lock" to lock)))
            .associate { it.path to it.value }
        assertEquals(
            withPrefix(
                "project.dart.",
                "app" to "2.0.0",
                "http" to "1.2.2",
                "sdks.dart" to ">=2.9.0 <3.0.0",
                "sdk-min" to "2.9.0",
                "sdks.flutter" to ">=3.0.0",
            ),
            scanned,
        )
    }

    @Test
    fun manifestTomlReadsTheJuliaVersionFormatAndVersionedEntries() {
        assertEquals(
            withPrefix(
                "project.julia.",
                "julia-version" to "1.13.1",
                "manifest-format" to "2.1",
                "JSON" to "1.10.0",
            ),
            facts("julia"),
        )
        val manifest = """
            julia_version = "1.12.0"
            manifest_format = "2.0"

            [[deps.Dates]]
            uuid = "ade2ca70-3891-5945-98fb-dc099432e06a"
            version = "1.11.0"

            [[deps.Printf]]
            deps = ["Unicode"]
            uuid = "de0858da-6303-5e67-8744-51eddeeeb8d7"
        """.trimIndent()
        val scanned = ProjectScanner.scan(
            FakeHost(
                files = mapOf(
                    "Manifest.toml" to manifest,
                    "Project.toml" to "[deps]\nPrintf = \"de0858da\"\nDates = \"ade2ca70\"\n",
                ),
            ),
        ).associate { it.path to it.value }
        assertEquals(
            withPrefix(
                "project.julia.",
                "julia-version" to "1.12.0",
                "manifest-format" to "2.0",
                "Dates" to "1.11.0",
            ),
            scanned,
        )
    }

    @Test
    fun mixLockReadsHexEntriesAndSkipsGitDependencies() {
        assertEquals(withPrefix("project.elixir.", "jason" to "1.4.5"), facts("elixir"))
        val lock = """
            %{
              "decimal": {:hex, :decimal, "2.3.0", "abc", [:mix], [], "hexpm", "def"},
              "plug_cowboy": {:hex, :plug_cowboy, "2.7.1", "abc", [:mix], [], "hexpm", "def"},
              "mine": {:git, "https://github.com/a/b.git", "0123abc", [branch: "main"]},
            }
        """.trimIndent()
        val scanned = ProjectScanner.scan(FakeHost(files = mapOf("mix.lock" to lock)))
            .associate { it.path to it.value }
        assertEquals(
            withPrefix(
                "project.elixir.",
                "decimal" to "2.3.0",
                "plug_cowboy" to "2.7.1",
            ),
            scanned,
        )
    }

    @Test
    fun brokenLanguageLockfilesAddNothing() {
        val broken = FakeHost(
            files = mapOf(
                "renv.lock" to "{not json",
                "pubspec.lock" to "garbage\n  more: garbage",
                "Manifest.toml" to "[[[",
                "mix.lock" to "not a map",
            ),
        )
        assertEquals(emptyList(), ProjectScanner.scan(broken))
    }

    @Test
    fun languageRulesMatchTheFixtureLocksOnlyAcrossTheirBoundary() {
        fun hits(a: Env, b: Env) = Rules.all.filter {
            it.id.startsWith("r-") || it.id.startsWith("dart-") || it.id.startsWith("julia-") ||
                it.id.startsWith("elixir-")
        }.filter { Detect.match(it, a, b).verdict == Verdict.MATCH }.map { it.id }.toSet()
        val old = Env(mapOf("tool.dart.version" to "2.19.6", "project.dart.sdk-min" to "2.9.0"))
        val now = Env(mapOf("tool.dart.version" to "3.13.5"))
        assertEquals(setOf("dart-3-pre-null-safety-sdk-lower-bound-fails-pub-get"), hits(old, now))
        val lock = facts("dart") + ("tool.dart.version" to "2.19.6")
        assertEquals(emptySet(), hits(Env(lock), now))
        assertEquals(
            setOf("elixir-1-19-regex-struct-default-raises"),
            hits(
                Env(mapOf("tool.elixir.version" to "1.18.4")),
                Env(mapOf("tool.elixir.version" to "1.19.5")),
            ),
        )
    }
    // #endregion
}
