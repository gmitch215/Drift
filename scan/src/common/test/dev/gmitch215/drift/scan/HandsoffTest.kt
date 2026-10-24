package dev.gmitch215.drift.scan

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.CapsuleDirectory
import dev.gmitch215.drift.fixtures.HandsoffFixtures
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.scan.handsoff.Checkout
import dev.gmitch215.drift.scan.handsoff.Handsoff
import dev.gmitch215.drift.scan.handsoff.HandsoffPlan
import dev.gmitch215.drift.scan.handsoff.HandsoffRender
import dev.gmitch215.drift.scan.handsoff.HandsoffRequest
import dev.gmitch215.drift.scan.handsoff.StopKind
import dev.gmitch215.drift.scan.handsoff.Version
import dev.gmitch215.drift.scan.handsoff.Versions
import dev.gmitch215.drift.scan.handsoff.YScalar
import dev.gmitch215.drift.scan.handsoff.YamlLite
import dev.gmitch215.drift.scan.handsoff.map
import dev.gmitch215.drift.scan.handsoff.scalar
import dev.gmitch215.drift.scan.handsoff.seq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MapCheckout(
    private val files: Map<String, String>,
    private val canList: Boolean = true,
) : Checkout {
    override fun read(path: String): String? = files[path]

    override fun list(dir: String): List<String>? {
        if (!canList) return null
        return files.keys.filter { it.startsWith("$dir/") }
            .map { it.removePrefix("$dir/").substringBefore('/') }.distinct().sorted()
    }
}

private fun fixture(name: String) = MapCheckout(
    HandsoffFixtures.names.filter { it.startsWith("$name/repo/") }
        .associate { it.removePrefix("$name/repo/") to HandsoffFixtures.text(it) },
)

private class Golden(
    val fixture: String,
    val name: String,
    val request: HandsoffRequest,
    val ready: Boolean,
)

private val goldens = listOf(
    Golden("node-clean", "plain", HandsoffRequest(), true),
    Golden("node-clean", "job", HandsoffRequest(job = "test"), true),
    Golden("node-matrix", "stop", HandsoffRequest(job = "test"), false),
    Golden(
        "node-matrix",
        "cell",
        HandsoffRequest(job = "test", cell = listOf("node" to "20", "os" to "ubuntu-latest")),
        true,
    ),
    Golden("python-clean", "plain", HandsoffRequest(), true),
    Golden("python-clean", "job", HandsoffRequest(job = "test"), true),
    Golden("gradle-clean", "job", HandsoffRequest(job = "build"), true),
    Golden("stop-two-lockfiles", "plain", HandsoffRequest(), false),
    Golden("stop-two-lockfiles", "answered", HandsoffRequest(lockfile = "package-lock.json"), true),
    Golden("stop-no-lockfile", "plain", HandsoffRequest(), false),
    Golden(
        "stop-no-lockfile",
        "answered",
        HandsoffRequest(acceptNoLockfile = true, testCommand = "npm test"),
        true,
    ),
    Golden("stop-no-test-command", "plain", HandsoffRequest(), false),
    Golden(
        "stop-no-test-command",
        "answered",
        HandsoffRequest(testCommand = "./gradlew test"),
        true,
    ),
    Golden("stop-runtime-version", "plain", HandsoffRequest(), false),
    Golden(
        "stop-runtime-version",
        "answered",
        HandsoffRequest(runtime = listOf("node" to "20")),
        true,
    ),
    Golden("stop-monorepo", "plain", HandsoffRequest(), false),
    Golden("stop-monorepo", "root", HandsoffRequest(root = "packages/app"), true),
)

private fun plan(files: Map<String, String>, request: HandsoffRequest = HandsoffRequest()) =
    Handsoff.plan(MapCheckout(files), "repo", request)

private fun HandsoffPlan.kinds() = stops.map { it.kind }

private val NODE = mapOf(
    "package.json" to """{"scripts":{"test":"jest"},"devDependencies":{"jest":"29.0.0"}}""",
    "package-lock.json" to "{}",
    ".nvmrc" to "20\n",
)

private fun workflow(job: String) = mapOf(".github/workflows/ci.yml" to "jobs:\n$job")

class HandsoffTest {
    @Test
    fun everyFixtureHasItsGolden() {
        for (g in goldens) {
            val plan = Handsoff.plan(fixture(g.fixture), "repo", g.request)
            val expected = HandsoffFixtures.text("${g.fixture}/golden/${g.name}.txt")
            assertEquals(expected, HandsoffRender.text(plan), "${g.fixture} ${g.name}")
            assertEquals(g.ready, plan.ready, "${g.fixture} ${g.name}")
        }
        val names = HandsoffFixtures.names.filter { "/golden/" in it && it.endsWith(".txt") }
        assertEquals(goldens.map { "${it.fixture}/golden/${it.name}.txt" }.sorted(), names.sorted())
    }

    @Test
    fun jsonMatchesItsGoldenAndIsStable() {
        val plan = Handsoff.plan(fixture("node-clean"), "repo", HandsoffRequest(job = "test"))
        val text = CanonicalJson.encode(HandsoffRender.json(plan)) + "\n"
        assertEquals(HandsoffFixtures.text("node-clean/golden/job.json"), text)
        assertEquals(text, CanonicalJson.encode(HandsoffRender.json(plan)) + "\n")
        val parsed = CanonicalJson.parse(text) as JsonObject
        assertEquals(1L, (parsed["schema"] as dev.gmitch215.drift.json.JsonInt).value)
    }

    @Test
    fun aStopHasNoPlanAndEveryStopNamesItsFlag() {
        for (g in goldens.filter { !it.ready }) {
            val plan = Handsoff.plan(fixture(g.fixture), "repo", g.request)
            assertTrue(plan.commands.isEmpty() && plan.byHand.isEmpty() && plan.image == null)
            val text = HandsoffRender.text(plan)
            for (stop in plan.stops) assertTrue("answer with: ${stop.kind.flag}" in text, text)
            assertTrue("\nplan\n" !in text, text)
        }
    }

    @Test
    fun yamlReadsMappingsSequencesScalarsAndKeepsLines() {
        val doc = YamlLite.parse(
            """
            # comment
            name: ci  # trailing
            on: [push, pull_request]
            env: {A: '1', B: "two"}
            jobs:
              test:
                steps:
                  - uses: actions/checkout@v4
                  - name: Build
                    run: |
                      make
                      make test
                  - run: >
                      echo a
                      echo b
                  - plain
            list:
            - x
            - y: 1
            """.trimIndent(),
        )
        assertEquals(emptyList(), doc.gaps)
        val root = doc.root.map()!!
        assertEquals("ci", root["name"].scalar())
        assertEquals(listOf("push", "pull_request"), root["on"].seq().map { it.scalar() })
        assertEquals("two", root["env"].map()!!["B"].scalar())
        val steps = root["jobs"].map()!!["test"].map()!!["steps"].seq()
        assertEquals(4, steps.size)
        assertEquals("make\nmake test", steps[1].map()!!["run"].scalar())
        assertEquals(10, steps[1].map()!!.entry("run")!!.line)
        assertEquals("echo a echo b", steps[2].map()!!["run"].scalar())
        assertEquals("plain", steps[3].scalar())
        val list = root["list"].seq()
        assertEquals("x", list[0].scalar())
        assertEquals("1", list[1].map()!!["y"].scalar())
    }

    @Test
    fun yamlReportsWhatItDoesNotRead() {
        fun gaps(text: String) = YamlLite.parse(text).gaps.map { it.what }
        assertEquals(listOf("an anchor, alias or tag"), gaps("a: &x 1\n"))
        assertEquals(listOf("an anchor, alias or tag"), gaps("a: *x\n"))
        assertEquals(listOf("an anchor, alias or tag"), gaps("a: !!str 1\n"))
        assertEquals(listOf("a merge key"), gaps("a:\n  <<: b\n"))
        assertEquals(listOf("a flow collection that spans lines"), gaps("a: [x,\n  y]\n"))
        assertEquals(listOf("a second document"), gaps("a: 1\n---\nb: 2\n"))
        assertEquals(listOf("a tab used as indentation"), gaps("a:\n\tb: 1\n"))
        assertEquals(emptyList(), gaps("a: 'it''s #1'\nb: \"x: y\"\n"))
        assertEquals(YScalar("it's #1", 1), YamlLite.parse("a: 'it''s #1'").root.map()!!["a"])
    }

    @Test
    fun versionsClassifyAgreeAndSatisfy() {
        assertEquals(Version.Exact(listOf(20, 11, 0)), Versions.classify("v20.11.0"))
        assertEquals(Version.Exact(listOf(20)), Versions.classify("20.x"))
        assertEquals(Version.Exact(listOf(21, 0, 2)), Versions.classify("temurin-21.0.2"))
        assertEquals(Version.Exact(listOf(21, 0, 2)), Versions.classify("21.0.2-tem"))
        assertEquals(Version.Exact(listOf(1, 22, 3)), Versions.classify("go1.22.3"))
        for (moving in listOf("lts/*", "lts/iron", "latest", "node", "stable", "")) {
            assertEquals(Version.Moving, Versions.classify(moving), moving)
        }
        assertEquals(Version.Constraint, Versions.classify(">=20"))
        assertTrue(Versions.agree(Version.Exact(listOf(20)), Version.Exact(listOf(20, 11))))
        assertTrue(!Versions.agree(Version.Exact(listOf(18)), Version.Exact(listOf(20, 11))))
        val v20 = Version.Exact(listOf(20, 11, 0))
        assertEquals(true, Versions.satisfies(v20, ">=20"))
        assertEquals(false, Versions.satisfies(v20, ">=22"))
        assertEquals(true, Versions.satisfies(v20, "^20.1"))
        assertEquals(false, Versions.satisfies(v20, "^18"))
        assertEquals(true, Versions.satisfies(v20, "~20.11"))
        assertEquals(false, Versions.satisfies(v20, "~20.10"))
        assertEquals(true, Versions.satisfies(v20, ">=18 <22"))
        assertEquals(true, Versions.satisfies(v20, "18 || 20"))
        assertEquals(true, Versions.satisfies(Version.Exact(listOf(3, 12)), ">=3.11,<4"))
        assertEquals(true, Versions.satisfies(Version.Exact(listOf(3, 12)), "~=3.11"))
        assertEquals(false, Versions.satisfies(Version.Exact(listOf(4, 0)), "~=3.11"))
        assertEquals(true, Versions.satisfies(v20, "<=20"))
        assertNull(Versions.satisfies(v20, "^0.2.3"))
        assertNull(Versions.satisfies(v20, "something"))
    }

    @Test
    fun yarnLockfilesPickTheInstallThatTheirFormatDocuments() {
        val metadata = "__metadata:\n  version: 8\n"
        val berry = plan(NODE - "package-lock.json" + ("yarn.lock" to metadata))
        assertEquals("yarn install --immutable", berry.ecosystems.single().install?.command)
        assertTrue(berry.commands.any { it.command == "yarn run test" })
        val classic = plan(NODE - "package-lock.json" + ("yarn.lock" to "# yarn lockfile v1\n"))
        assertEquals("yarn install --frozen-lockfile", classic.ecosystems.single().install?.command)
        val unknown = plan(NODE - "package-lock.json" + ("yarn.lock" to "x\n"))
        assertNull(unknown.ecosystems.single().install)
        val bun = plan(NODE - "package-lock.json" + ("bun.lock" to "{}"))
        assertNull(bun.ecosystems.single().install)
        assertTrue(bun.stops.any { it.kind == StopKind.NO_TEST_COMMAND })
    }

    @Test
    fun aPackageManagerThatDisagreesWithTheLockfileStops() {
        val manifest =
            """{"packageManager":"pnpm@9.1.0","scripts":{"test":"t"},"dependencies":{"a":"1"}}"""
        val files = NODE + ("package.json" to manifest)
        val plan = plan(files)
        assertEquals(listOf(StopKind.PACKAGE_MANAGER), plan.kinds())
        assertTrue("pnpm" in plan.stops.single().what && "npm" in plan.stops.single().what)
        val fine = plan(files - "package-lock.json" + ("pnpm-lock.yaml" to "lockfileVersion: 9\n"))
        assertTrue(fine.ready, fine.stops.toString())
        assertEquals("pnpm install --frozen-lockfile", fine.commands.first().command)
        assertEquals("pnpm run test", fine.commands.last().command)
    }

    @Test
    fun aVersionThatMovesOrIsMissingOrConflictsStops() {
        val engines =
            """{"engines":{"node":">=20"},"scripts":{"test":"t"},"dependencies":{"a":"1"}}"""
        val moving = plan(NODE + (".nvmrc" to "lts/*\n"))
        assertEquals(listOf(StopKind.RUNTIME_VERSION), moving.kinds())
        assertTrue("moves" in moving.stops.single().what, moving.stops.single().what)
        val none = plan(NODE - ".nvmrc")
        assertTrue("no node version is pinned" in none.stops.single().what)
        val clash = plan(
            NODE + (".nvmrc" to "18\n") + ("package.json" to engines),
        )
        assertTrue("disagree" in clash.stops.single().what, clash.stops.single().what)
        val fine = plan(NODE - ".nvmrc", HandsoffRequest(runtime = listOf("node" to "22")))
        assertTrue(fine.ready, fine.stops.toString())
        assertTrue(fine.ecosystems.single().pins.any { it.label == "--runtime" })
    }

    @Test
    fun theChosenJobsSetupVersionAndImageCountAsPins() {
        val ci = workflow(
            """
              test:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/setup-node@v4
                    with:
                      node-version: 22
                  - run: npm test
            """.trimIndent().prependIndent("  "),
        )
        val none = plan(NODE - ".nvmrc" + ci, HandsoffRequest(job = "test"))
        assertTrue(none.ready, none.stops.toString())
        val clash = plan(NODE + ci, HandsoffRequest(job = "test"))
        assertTrue("20 (.nvmrc:1)" in clash.stops.single().what, clash.stops.single().what)
        assertTrue("setup-node node-version" in clash.stops.single().what)
        val latest = workflow(
            "  test:\n    container: node:latest\n    steps:\n      - run: npm test\n",
        )
        val moving = plan(NODE - ".nvmrc" + latest, HandsoffRequest(job = "test"))
        assertTrue("moves" in moving.stops.single().what, moving.stops.single().what)
    }

    @Test
    fun aWorkflowThatUsesYamlThisDoesNotReadIsRefusedNotGuessed() {
        val anchored = workflow("  test: &t\n    steps:\n      - run: npm test\n")
        val plan = plan(NODE + anchored, HandsoffRequest(job = "test"))
        assertEquals(listOf(StopKind.CI_CONFIG), plan.kinds())
        assertTrue("line 2 uses an anchor, alias or tag" in plan.stops.single().what)
        assertTrue(plan.ci!!.job == null)
    }

    @Test
    fun matrixIncludeExcludeAndComputedMatrices() {
        val matrix = workflow(
            """
              test:
                runs-on: ${'$'}{{ matrix.os }}
                strategy:
                  matrix:
                    os: [linux, mac]
                    node: [18, 20]
                    exclude:
                      - os: mac
                        node: 18
                    include:
                      - os: linux
                        extra: yes
                      - os: windows
                        node: 20
                steps:
                  - run: echo ${'$'}{{ matrix.os }} ${'$'}{{ secrets.TOKEN }}
            """.trimIndent().prependIndent("  "),
        )
        val files = NODE + matrix
        val request = HandsoffRequest(job = "test")
        assertEquals(listOf(StopKind.MATRIX_CELL), plan(files, request).kinds())
        val windows = plan(files, request.copy(cell = listOf("os" to "windows")))
        val job = windows.ci!!.job!!
        assertEquals("windows", job.runner)
        assertEquals("echo windows ${'$'}{{ secrets.TOKEN }}", job.steps.single().command)
        assertTrue(job.notes.any { "not evaluated" in it })
        val linux = plan(files, request.copy(cell = listOf("os" to "linux", "node" to "18")))
        val cell = linux.ci!!.job!!.cell
        assertTrue(cell.contains("extra" to "yes"), cell.toString())
        val missing = plan(files, request.copy(cell = listOf("os" to "mac", "node" to "18")))
        assertTrue("no matrix cell" in missing.stops.single().what)
        val several = plan(files, request.copy(cell = listOf("node" to "20")))
        assertTrue("3 fit" in several.stops.single().what, several.stops.single().what)
        val computed = workflow(
            "  test:\n    strategy:\n      matrix: \${{ fromJson(needs.x.outputs.m) }}\n" +
                "    steps:\n      - run: t\n",
        )
        val dynamic = plan(NODE + computed, request)
        assertTrue("computed" in dynamic.stops.single().what, dynamic.stops.single().what)
    }

    @Test
    fun aFailingCaseNamesTheJobAndTheCellAndTheImage() {
        val ci = workflow(
            """
              test:
                strategy:
                  matrix:
                    os: [ubuntu-latest, macos-latest]
                    node: [18, 20]
                runs-on: ${'$'}{{ matrix.os }}
                steps:
                  - uses: actions/setup-node@v4
                    with:
                      node-version: ${'$'}{{ matrix.node }}
                  - run: npm test
            """.trimIndent().prependIndent("  "),
        )
        val disk = MemoryCaseFiles()
        val failing = Run(
            "1",
            RunOutcome.FAIL,
            listOf(
                Step("test (ubuntu-latest, 20)", "Set up job", StepConclusion.SUCCESS, null),
                Step("test (ubuntu-latest, 20)", "Run npm test", StepConclusion.FAILURE, null),
            ),
        )
        val capsule = Capsule(
            "failing",
            listOf(
                Attribute("os.release.ID", "ubuntu", "x"),
                Attribute("os.release.VERSION_ID", "22.04", "x"),
            ),
        )
        assertNull(
            CapsuleDirectories.write(
                disk,
                ".drift/ci/abc/capsules/failing",
                CapsuleDirectory(capsule, failing),
            ),
        )
        val plan = Handsoff.plan(
            MapCheckout(NODE - ".nvmrc" + ci),
            "repo",
            HandsoffRequest(fromCi = ".drift/ci/abc"),
            disk::read,
        )
        assertTrue(plan.ready, plan.stops.toString())
        val facts = plan.ci!!
        assertEquals(listOf("os" to "ubuntu-latest", "node" to "20"), facts.job!!.cell)
        assertEquals("test (ubuntu-latest, 20)", facts.case!!.job)
        assertEquals("ubuntu:22.04", plan.image)
        assertTrue("attributes" in plan.imageBasis!!, plan.imageBasis)
        assertEquals("ubuntu:22.04", facts.case.image)
        val missing = Handsoff.plan(
            MapCheckout(NODE + ci),
            "repo",
            HandsoffRequest(fromCi = "nope"),
            disk::read,
        )
        assertEquals(listOf(StopKind.CI_CONFIG), missing.kinds())
        assertTrue("holds no capsule.json" in missing.stops.single().what)
    }

    @Test
    fun aJobIsFoundByIdOrNameAndAnAmbiguousNameStops() {
        val twoFiles = mapOf(
            ".github/workflows/a.yml" to
                "jobs:\n  build:\n    name: Build it\n    steps:\n      - run: a\n",
            ".github/workflows/b.yml" to "jobs:\n  build:\n    steps:\n      - run: b\n",
        )
        val byName = plan(NODE + twoFiles, HandsoffRequest(job = "Build it"))
        assertEquals("a", byName.ci!!.job!!.steps.single().command)
        val ambiguous = plan(NODE + twoFiles, HandsoffRequest(job = "build"))
        assertTrue("matches 2 jobs" in ambiguous.stops.single().what)
        val chosen = plan(
            NODE + twoFiles,
            HandsoffRequest(job = "build", workflow = ".github/workflows/b.yml"),
        )
        assertEquals("b", chosen.ci!!.job!!.steps.single().command)
        val none = plan(NODE + twoFiles, HandsoffRequest(job = "nope"))
        assertTrue("no job matches" in none.stops.single().what)
    }

    @Test
    fun githubJobFieldsAreReadAsWritten() {
        val ci = workflow(
            """
              deploy:
                runs-on: [self-hosted, linux]
                container:
                  image: node:20-alpine
                services:
                  db:
                    image: postgres:16
                  cache: redis:7
                defaults:
                  run:
                    working-directory: app
                steps:
                  - uses: actions/setup-node@v4
                    with: {node-version: '20'}
                  - name: Test
                    if: github.ref == 'main'
                    run: npm test
                    working-directory: other
                  - run: npm run lint
            """.trimIndent().prependIndent("  "),
        )
        val job = plan(NODE - ".nvmrc" + ci, HandsoffRequest(job = "deploy")).ci!!.job!!
        assertEquals("self-hosted, linux", job.runner)
        assertEquals("node:20-alpine", job.image)
        assertEquals(
            listOf("db" to "postgres:16", "cache" to "redis:7"),
            job.services.map { it.name to it.image },
        )
        assertEquals("actions/setup-node@v4", job.actions.single().uses)
        assertEquals(listOf("node-version" to "20"), job.actions.single().with)
        assertEquals(listOf("other", "app"), job.steps.map { it.directory })
        assertEquals("github.ref == 'main'", job.steps.first().condition)
        assertTrue(job.notes.any { "if: condition" in it })
    }

    @Test
    fun gitlabJobsResolveExtendsParallelMatrixImageAndServices() {
        val gitlab = """
            .base:
              image: python:3.12
              before_script:
                - pip install -r requirements.txt
            test:
              extends: .base
              tags: [docker]
              services:
                - postgres:16
              parallel:
                matrix:
                  - DB: [pg, my]
                    PY: ["3.11"]
              script:
                - pytest
                - |
                  echo done
                  echo twice
            deploy:
              script: [make deploy]
            include:
              - local: other.yml
        """.trimIndent()
        val files = mapOf(".gitlab-ci.yml" to gitlab, "requirements.txt" to "pytest==8.0.0\n")
        val request = HandsoffRequest(job = "test", runtime = listOf("python" to "3.12"))
        assertEquals(listOf(StopKind.MATRIX_CELL), plan(files, request).kinds())
        val chosen = plan(files, request.copy(cell = listOf("DB" to "my")))
        val job = chosen.ci!!.job ?: error(chosen.stops.toString() + chosen.ci.notes)
        assertEquals("python:3.12", job.image)
        assertEquals("tags: docker", job.runner)
        assertEquals(listOf("postgres:16"), job.services.map { it.name })
        assertEquals(
            listOf("pip install -r requirements.txt", "pytest", "echo done\necho twice"),
            job.steps.map { it.command },
        )
        assertEquals(listOf("DB" to "my", "PY" to "3.11"), job.cell)
        assertTrue(job.notes.any { "include:" in it })
        val answer = HandsoffRequest(job = "deploy", runtime = listOf("python" to "3.12"))
        val deploy = plan(files, answer)
        assertEquals("make deploy", deploy.ci!!.job!!.steps.single().command)
        val listed = plan(files, HandsoffRequest(testCommand = "pytest")).ci!!.jobsFound
        assertEquals(listOf(".gitlab-ci.yml: test", ".gitlab-ci.yml: deploy"), listed)
    }

    @Test
    fun namedCommandsComeFromMakefileJustfileToxAndNox() {
        val make = plan(
            mapOf(
                "Makefile" to
                    "CC := gcc\n.PHONY: test\nbuild lint:\n\tgcc\ntest: build\n\tgo test\n",
            ),
        )
        val targets = make.ecosystems.single().named
        assertEquals(listOf("build", "lint", "test"), targets.map { it.name })
        assertEquals(listOf(5), targets.filter { it.name == "test" }.map { it.source.line })
        assertEquals("make test", make.commands.single().command)
        assertEquals("make test", targets.single { it.test }.command)
        val just = plan(mapOf("justfile" to "set x := 1\ntest *args:\n  cargo test\n@lint:\n  c\n"))
        assertEquals(listOf("test", "lint"), just.ecosystems.single().named.map { it.name })
        assertEquals("just test", just.commands.single().command)
        val tox = plan(
            mapOf(
                "requirements.txt" to "pytest==8\n",
                ".python-version" to "3.12\n",
                "tox.ini" to "[tox]\nenv_list =\n    py312\n    lint\n[testenv:docs]\n",
                "noxfile.py" to "@nox.session(name=\"tests\")\ndef run(session):\n    pass\n",
            ),
        )
        val envs = tox.ecosystems.single().named
        assertEquals(listOf("py312", "lint", "docs", "tests"), envs.map { it.name })
        assertEquals("nox -s tests", tox.commands.last().command)
    }

    @Test
    fun aJobWithoutRunStepsStopsAndTheTestCommandFlagAnswers() {
        val ci = workflow("  test:\n    steps:\n      - uses: actions/checkout@v4\n")
        val stopped = plan(NODE + ci, HandsoffRequest(job = "test"))
        assertEquals(listOf(StopKind.NO_TEST_COMMAND), stopped.kinds())
        assertTrue("has no run steps" in stopped.stops.single().what)
        val answered = plan(NODE + ci, HandsoffRequest(job = "test", testCommand = "npm test"))
        assertEquals(listOf("npm test"), answered.commands.map { it.command })
    }

    @Test
    fun severalTestCommandsStopAndTheFlagAnswers() {
        val files = NODE + ("justfile" to "test:\n  x\n")
        val stopped = plan(files)
        assertEquals(listOf(StopKind.SEVERAL_TEST_COMMANDS), stopped.kinds())
        val answered = plan(files, HandsoffRequest(testCommand = "just test"))
        assertEquals("just test", answered.commands.last().command)
        assertEquals("--test-command", answered.commands.last().source)
    }

    @Test
    fun composerGoPythonAndMavenAreRead() {
        val php = plan(
            mapOf(
                "composer.json" to
                    """{"require":{"php":"^8.2","vendor/x":"1"},"scripts":{"test":"phpunit"}}""",
                "composer.lock" to "{}",
                ".tool-versions" to "php 8.3.1\n",
            ),
        )
        val composer = php.ecosystems.single()
        assertEquals("composer install", composer.install?.command)
        assertEquals(listOf("8.3.1", "^8.2"), composer.pins.map { it.value })
        assertEquals(listOf(StopKind.NO_TEST_COMMAND), php.kinds())
        val go = plan(
            mapOf(
                "go.mod" to "module m\n\ngo 1.22\ntoolchain go1.22.3\nrequire x v1\n",
                "go.sum" to "x\n",
            ),
        )
        assertEquals("go mod download", go.ecosystems.single().install?.command)
        assertEquals(listOf(">=1.22", "1.22.3"), go.ecosystems.single().pins.map { it.value })
        val work = plan(
            mapOf(
                "go.mod" to "module m\ngo 1.22\n",
                "go.work" to "go 1.22\nuse (\n\t./a\n\t./b\n)\n",
            ),
        )
        assertTrue(work.stops.any { it.kind == StopKind.MONOREPO && "./b (go.work:4)" in it.what })
        val maven = plan(
            mapOf(
                "pom.xml" to "<project><properties>" +
                    "<maven.compiler.release>17</maven.compiler.release></properties></project>",
            ),
            HandsoffRequest(testCommand = "mvn test"),
        )
        assertEquals(">=17", maven.ecosystems.single().pins.single().value)
        assertEquals("maven", maven.ecosystems.single().manager)
    }

    @Test
    fun twoPythonLockfilesStopAndTheFlagChoosesOne() {
        val files = mapOf(
            "pyproject.toml" to "[project]\nrequires-python = \">=3.11\"\ndependencies = [\"a\"]\n",
            "poetry.lock" to "x",
            "uv.lock" to "x",
            ".python-version" to "3.12\n",
        )
        assertEquals(listOf(StopKind.TWO_LOCKFILES), plan(files).kinds())
        val uv = plan(files, HandsoffRequest(lockfile = "uv.lock", testCommand = "pytest"))
        assertEquals(listOf("uv sync", "pytest"), uv.commands.map { it.command })
        val absent = plan(files, HandsoffRequest(lockfile = "Pipfile.lock"))
        assertTrue(absent.stops.any { "names no lockfile" in it.what })
    }

    @Test
    fun anEmptyDirectoryStopsAndOtherEcosystemsAreNamedNotRead() {
        val empty = plan(emptyMap())
        assertEquals(listOf(StopKind.NOTHING_FOUND), empty.kinds())
        val rust = plan(mapOf("Cargo.toml" to "[package]\n"))
        assertEquals(listOf("Cargo.toml (rust)"), rust.notRead)
        assertTrue("found Cargo.toml (rust)" in HandsoffRender.text(rust))
    }

    @Test
    fun withoutListingTheWorkflowFlagStillWorks() {
        val ci = workflow("  test:\n    steps:\n      - run: npm test\n")
        val blindFiles = MapCheckout(NODE + ci, canList = false)
        val blind = Handsoff.plan(blindFiles, "repo", HandsoffRequest())
        val notes = blind.ci!!.notes
        assertTrue(notes.single().contains("--workflow"), notes.toString())
        val named = Handsoff.plan(
            MapCheckout(NODE + ci, canList = false),
            "repo",
            HandsoffRequest(job = "test", workflow = ".github/workflows/ci.yml"),
        )
        val facts = named.ci!!
        assertEquals("npm test", facts.job!!.steps.single().command)
        assertNotNull(facts.files.single())
    }

    @Test
    fun aRootReadsThatDirectoryAndPrintsTheCdLine() {
        val plan = plan(
            mapOf(
                "sub/package.json" to """{"packageManager":"npm@10","scripts":{"test":"t"}}""",
                "sub/.nvmrc" to "20\n",
            ),
            HandsoffRequest(root = "sub/"),
        )
        assertTrue(plan.ready, plan.stops.toString())
        assertEquals(listOf("cd sub", "npm run test"), plan.byHand)
        assertEquals(listOf("sub/.nvmrc", "sub/package.json"), plan.readFiles)
    }
}
