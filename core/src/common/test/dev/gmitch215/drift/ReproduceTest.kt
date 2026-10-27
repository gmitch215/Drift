package dev.gmitch215.drift

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.fixtures.LabFixtures
import dev.gmitch215.drift.fixtures.RunFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.lab.Distro
import dev.gmitch215.drift.lab.ImageDistro
import dev.gmitch215.drift.lab.ImageTable
import dev.gmitch215.drift.lab.ImageTags
import dev.gmitch215.drift.lab.NpmEngines
import dev.gmitch215.drift.lab.Pick
import dev.gmitch215.drift.lab.Reproduce
import dev.gmitch215.drift.lab.ReproduceProblem
import dev.gmitch215.drift.lab.ReproduceRender
import dev.gmitch215.drift.lab.ReproduceResult
import dev.gmitch215.drift.lab.Reproduction
import dev.gmitch215.drift.lab.Status
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.redact.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReproduceTest {
    private val sum = "3e34e52bb51b5b63087200744a69c631497c49ea948659c7225bcb8e5fef19ac"

    private fun capsule(vararg pairs: Pair<String, String>) =
        Capsule("t", pairs.map { Attribute(it.first, it.second, "test") })

    private fun build(
        capsule: Capsule,
        command: String = "make test",
        digests: Map<String, String> = emptyMap(),
        envNames: Set<String> = emptySet(),
        envAll: Boolean = false,
    ): Reproduction = assertIs<ReproduceResult.Built>(
        Reproduce.synthesize(capsule, command, digests, envNames, envAll),
    ).reproduction

    private fun Reproduction.entry(path: String) = entries.single { it.path == path }

    private fun Reproduction.status(path: String) = entry(path).status

    private val fixtures = mapOf(
        "jvm" to Capsule.parse(LabFixtures.text("jvm.json")),
        "node" to Capsule.parse(LabFixtures.text("node.json")),
        "node-alpine" to Capsule.parse(LabFixtures.text("node-alpine.json")),
        "drangler-green" to Capsule.parse(RunFixtures.text("capsules/36702683742.json")),
        "drangler-red" to Capsule.parse(RunFixtures.text("capsules/36995781138.json")),
    )

    private val golden = mapOf(
        "jvm" to listOf(
            "e53252058c8623131ef74c52b74b82157f383ad14f2d7440e96401de8265ca1e",
            "466946e9ea8e9bc2136842a808ee5e6f2ad7c6a68ff6bdf9c64a1fa22fc6c0f7",
            "df912d7e71f5f597b6714f5e7ad8828a7ed8ef292da7c2cfd4ebcea8c6ade2c7",
        ),
        "node" to listOf(
            "aca2de505d34df6e337a224db4bccc19fc2d825dc84444fc0ede08026f07e786",
            "0cc24a1ca2fab3c9f36e884bcd862b9b12a133916067c089ae5e2456b327dfe9",
            "9a3ab7e6f560ed2bab27906ae58647786ab225f0b4d30b7f059444196cac4641",
        ),
        "node-alpine" to listOf(
            "f47eacf4b6acd22940547256bfcf3a0d34914a52bc0fd6b8254a6192797f7305",
            "2697a96f2b1eb65ec1ad9ebcd6294570fd47d3537312e18ecbdc145333dcef56",
            "d58f19006418c668478e69e5b452646bd0491691c4a4d02721199cb50f8e3012",
        ),
        "drangler-red" to listOf(
            "31046eebbeec44898217b9f3db0492b6a8d68356eb37d489c9c743914b26b932",
            "890e214f6b39badd45cb7f623448b7c52e1facf0a2cec14f354caa8af0d6ad65",
            "7df22b80a2a4a8870342007ddede9ee3321960e48efd2f6d30921b6e234dffca",
        ),
    )

    private class Base(
        val name: String,
        val attrs: List<Pair<String, String>>,
        val command: String,
        val image: String,
        val basis: String,
    )

    private val ubuntu2204 = listOf("os.release.ID" to "ubuntu", "os.release.VERSION_ID" to "22.04")
    private val debian12 = listOf("os.release.ID" to "debian", "os.release.VERSION_ID" to "12")
    private val debian10 = listOf("os.release.ID" to "debian", "os.release.VERSION_ID" to "10")
    private val alpine320 = listOf("os.release.ID" to "alpine", "os.release.VERSION_ID" to "3.20.3")

    private val bases = listOf(
        Base(
            "node on debian",
            debian12 + ("tool.node.version" to "22.12.0"),
            "node x.js",
            "node:22.12.0-bookworm-slim",
            "tool.node.version 22.12.0; the command names a node tool",
        ),
        Base(
            "node on debian 11",
            listOf("os.release.ID" to "debian", "os.release.VERSION_ID" to "11") +
                ("tool.node.version" to "20.1.0"),
            "npm test",
            "node:20.1.0-bullseye-slim",
            "the command names a node tool",
        ),
        Base(
            "node on ubuntu falls back to the default debian",
            ubuntu2204 +
                ("tool.node.version" to "22.12.0"),
            "npm test",
            "node:22.12.0-bookworm-slim",
            "the command names a node tool",
        ),
        Base(
            "node on alpine",
            alpine320 + ("tool.node.version" to "22.12.0"),
            "npm test",
            "node:22.12.0-alpine3.20",
            "the command names a node tool",
        ),
        Base(
            "node without a distribution takes the nearest Debian that has the tag",
            listOf("tool.node.version" to "18.0.0"),
            "npm test",
            "node:18.0.0-bullseye-slim",
            "the command names a node tool",
        ),
        Base(
            "node pre-release has no tag",
            listOf("tool.node.version" to "23.0.0-nightly"),
            "npm test",
            "debian:12-slim",
            "generic base",
        ),
        Base(
            "python on debian",
            debian12 + ("tool.python.version" to "3.12.4"),
            "pytest",
            "python:3.12.4-slim-bookworm",
            "the command names a python tool",
        ),
        Base(
            "python on alpine",
            alpine320 + ("tool.python.version" to "3.12.4"),
            "pytest",
            "python:3.12.4-alpine3.20",
            "the command names a python tool",
        ),
        Base(
            "go on debian",
            debian12 + ("tool.go.version" to "1.23.1"),
            "go test ./...",
            "golang:1.23.1-bookworm",
            "the command names a go tool",
        ),
        Base(
            "go minor only",
            listOf("tool.go.version" to "1.21"),
            "go test ./...",
            "golang:1.21-bookworm",
            "the command names a go tool",
        ),
        Base(
            "php cli",
            debian12 + ("tool.php.version" to "8.3.10"),
            "phpunit",
            "php:8.3.10-cli-bookworm",
            "the command names a php tool",
        ),
        Base(
            "php zts",
            debian12 + ("tool.php.version" to "8.3.10") +
                ("tool.php.thread-safety" to "ZTS"),
            "phpunit",
            "php:8.3.10-zts-bookworm",
            "the command names a php tool",
        ),
        Base(
            "php with a distribution suffix and no tag on the default debian",
            listOf("tool.php.version" to "8.1.2-1ubuntu2.14"),
            "phpunit",
            "php:8.1.2-cli-bullseye",
            "the command names a php tool",
        ),
        Base(
            "rust",
            debian12 + ("tool.rust.version" to "1.80.1"),
            "cargo test",
            "rust:1.80.1-slim-bookworm",
            "the command names a rust tool",
        ),
        Base(
            "rust on alpine",
            alpine320 + ("tool.rust.version" to "1.80.1"),
            "cargo test",
            "rust:1.80.1-alpine3.20",
            "the command names a rust tool",
        ),
        Base(
            "gcc",
            listOf("tool.cc.family" to "gcc", "tool.cc.version" to "13.2.0"),
            "make",
            "gcc:13.2",
            "the command names a cc tool",
        ),
        Base(
            "clang has no image",
            listOf("tool.cc.family" to "clang", "tool.cc.version" to "17.0.6"),
            "make",
            "debian:12-slim",
            "generic base",
        ),
        Base(
            "java on ubuntu 22.04",
            ubuntu2204 + ("tool.java.version" to "17.0.10"),
            "./gradlew test",
            "eclipse-temurin:17-jdk-jammy",
            "the command names a java tool",
        ),
        Base(
            "java 8",
            listOf("tool.java.version" to "1.8.0_392"),
            "mvn test",
            "eclipse-temurin:8-jdk",
            "the command names a java tool",
        ),
        Base(
            "java on alpine",
            alpine320 + ("tool.java.version" to "21.0.5"),
            "java -jar a.jar",
            "eclipse-temurin:21-jdk-alpine-3.20",
            "the command names a java tool",
        ),
        Base(
            "java on debian keeps the temurin default",
            debian12 + ("tool.java.version" to "21"),
            "mvn test",
            "eclipse-temurin:21-jdk",
            "the command names a java tool",
        ),
        Base(
            "two toolchains and a command that names the second",
            listOf("tool.node.version" to "22.12.0", "tool.python.version" to "3.12.4"),
            "pytest -q",
            "python:3.12.4-slim-bookworm",
            "the command names a python tool",
        ),
        Base(
            "two toolchains and a command that names the first",
            listOf("tool.node.version" to "22.12.0", "tool.python.version" to "3.12.4"),
            "cd app && npm test",
            "node:22.12.0-bookworm-slim",
            "the command names a node tool",
        ),
        Base(
            "a path names its tool by basename",
            listOf("tool.node.version" to "22.12.0", "tool.python.version" to "3.12.4"),
            "./node_modules/.bin/node a.js",
            "node:22.12.0-bookworm-slim",
            "names a node tool",
        ),
        Base(
            "two toolchains and no hint take table order",
            listOf("tool.node.version" to "22.12.0", "tool.python.version" to "3.12.4"),
            "./run-tests",
            "node:22.12.0-bookworm-slim",
            "the first of 2 toolchains in table order",
        ),
        Base(
            "node 16 on debian 10 uses the buster tag",
            debian10 + ("tool.node.version" to "16.13.0"),
            "node x.js",
            "node:16.13.0-buster-slim",
            "the command names a node tool",
        ),
        Base(
            "node 18 on debian 10 has no buster tag and takes bullseye",
            debian10 + ("tool.node.version" to "18.20.4"),
            "node x.js",
            "node:18.20.4-bullseye-slim",
            "the command names a node tool",
        ),
        Base(
            "gcc 13.2.1 has no tag and takes the 13.2 line",
            listOf("tool.cc.family" to "gcc", "tool.cc.version" to "13.2.1"),
            "make",
            "gcc:13.2",
            "the command names a cc tool",
        ),
        Base(
            "r from the base version the r-base image records",
            listOf("env.R_BASE_VERSION" to "4.6.1", "tool.cc.family" to "gcc") +
                ("tool.cc.version" to "16.2.0"),
            "Rscript prog.R",
            "r-base:4.6.1",
            "env.R_BASE_VERSION 4.6.1; the command names a r tool",
        ),
        Base(
            "r from the version the scanner records",
            listOf("tool.r.version" to "4.3.3"),
            "BENCH_FLAKE=1 Rscript prog.R",
            "r-base:4.3.3",
            "tool.r.version 4.3.3; the command names a r tool",
        ),
        Base(
            "elixir from the versions the scanner records",
            listOf(
                "tool.elixir.version" to "1.16.3",
                "tool.erlang.otp" to "26",
                "tool.elixir.compiled-otp" to "26",
            ),
            "elixir prog.exs",
            "elixir:1.16.3-otp-26",
            "tool.elixir.version 1.16.3; the command names a elixir tool",
        ),
        Base(
            "an old r version with an exact tag",
            listOf("env.R_BASE_VERSION" to "4.0.5"),
            "R --version",
            "r-base:4.0.5",
            "the command names a r tool",
        ),
        Base(
            "elixir pins the erlang major",
            debian12 + listOf(
                "env.ELIXIR_VERSION" to "v1.16.3",
                "env.OTP_VERSION" to "26.2.5.21",
                "tool.python.version" to "3.11.2",
            ),
            "elixir prog.exs",
            "elixir:1.16.3-otp-26",
            "env.ELIXIR_VERSION v1.16.3; the command names a elixir tool",
        ),
        Base(
            "elixir through mix without an otp version",
            listOf("env.ELIXIR_VERSION" to "1.20.4"),
            "mix test",
            "elixir:1.20.4",
            "the command names a elixir tool",
        ),
        Base(
            "elixir with no tag for its version takes the nearest",
            listOf("env.ELIXIR_VERSION" to "1.16.9", "env.OTP_VERSION" to "26.2.5.21"),
            "elixir prog.exs",
            "elixir:1.16.3-otp-26",
            "the command names a elixir tool",
        ),
        Base(
            "ruby on debian 13",
            listOf("os.release.ID" to "debian", "os.release.VERSION_ID" to "13") +
                ("tool.ruby.version" to "3.4.11"),
            "bundle lock",
            "ruby:3.4.11-slim-trixie",
            "the command names a ruby tool",
        ),
        Base(
            "a toolchain in a script is not named by sh and the first usable row wins",
            debian12 + listOf("tool.python.version" to "3.12.4", "tool.ruby.version" to "3.4.11"),
            "sh prog.sh",
            "python:3.12.4-slim-bookworm",
            "the first of 2 toolchains in table order",
        ),
        Base(
            "a capsule taken in the r image has python from the distribution",
            debian12 + listOf("tool.python.version" to "3.12.4", "env.R_BASE_VERSION" to "4.6.1"),
            "sh prog.sh",
            "r-base:4.6.1",
            "the capsule's env.R_BASE_VERSION comes from the r image",
        ),
        Base(
            "a capsule taken in the gcc image has python from the distribution",
            listOf(
                "tool.cc.family" to "gcc",
                "tool.cc.version" to "13.2.0",
                "tool.python.version" to "3.11.2",
                "env.GCC_VERSION" to "13.2.0",
            ),
            "BENCH_FLAKE=1 sh prog.sh",
            "gcc:13.2",
            "the capsule's env.GCC_VERSION comes from the cc image",
        ),
        Base(
            "the command still wins over the image the capsule was taken in",
            listOf(
                "tool.cc.family" to "gcc",
                "tool.cc.version" to "13.2.0",
                "tool.python.version" to "3.12.4",
                "env.GCC_VERSION" to "13.2.0",
            ),
            "python3 prog.py",
            "python:3.12.4-slim-bookworm",
            "the command names a python tool",
        ),
        Base("only a distribution: ubuntu", ubuntu2204, "make", "ubuntu:22.04", "distribution"),
        Base("only a distribution: debian", debian12, "make", "debian:12-slim", "distribution"),
        Base("only a distribution: alpine", alpine320, "make", "alpine:3.20.3", "distribution"),
        Base("nothing at all", listOf("env.A" to "b"), "make", "debian:12-slim", "generic base"),
    )

    @Test
    fun theMappingTablePicksTheImageAndSaysWhy() {
        for (b in bases) {
            val r = build(capsule(*b.attrs.toTypedArray()), b.command)
            assertEquals(b.image, r.image, b.name)
            assertTrue(b.basis in r.basis, "${b.name}: ${r.basis}")
            assertTrue(r.dockerfile.contains("FROM ${b.image}\n"), b.name)
        }
    }

    @Test
    fun anAlpinePointReleaseThePublishedTagsHoldPinsTheExactTag() {
        val exact = build(capsule(*alpine320.toTypedArray()), "make")
        assertEquals("alpine:3.20.3", exact.imageTag)
        assertEquals(Status.MIRRORED, exact.status("os.release.VERSION_ID"))
        assertEquals("the image is alpine 3.20.3", exact.entry("os.release.VERSION_ID").detail)
        val unpublished = build(
            capsule("os.release.ID" to "alpine", "os.release.VERSION_ID" to "3.20.99"),
            "make",
        )
        assertEquals("alpine:3.20", unpublished.imageTag)
        assertEquals(Status.PARTIAL, unpublished.status("os.release.VERSION_ID"))
        assertTrue("point release" in unpublished.entry("os.release.VERSION_ID").detail)
        val minor = build(
            capsule("os.release.ID" to "alpine", "os.release.VERSION_ID" to "3.20"),
            "make",
        )
        assertEquals("alpine:3.20", minor.imageTag)
        assertEquals(Status.MIRRORED, minor.status("os.release.VERSION_ID"))
    }

    @Test
    fun anEnvironmentVariableForXdgBaseDirsAndAContainerPathIsMirroredWhenNamed() {
        val c = Capsule(
            "t",
            listOf(
                Attribute("env.XDG_CACHE_HOME", "~/xdg", "env"),
                Attribute("env.XDG_CONFIG_HOME", "/tmp/conf", "env"),
                Attribute("env.XDG_SEAT", "seat0", "env"),
                Attribute("env.SCRATCH", "/Users/me/scratch", "env"),
                Attribute("env.CACHE", "/opt/cache", "env"),
            ),
        )
        val r = build(c, envNames = setOf("XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_SEAT"))
        assertEquals(Status.MIRRORED, r.status("env.XDG_CACHE_HOME"))
        assertEquals(Status.MIRRORED, r.status("env.XDG_CONFIG_HOME"))
        assertTrue("--env 'XDG_CACHE_HOME=~/xdg' \\\n" in r.runScript)
        assertTrue("host identity" in r.entry("env.XDG_SEAT").detail)
        val host = build(c, envNames = setOf("SCRATCH", "CACHE"))
        assertTrue("path on the capsule's host" in host.entry("env.CACHE").detail)
        assertNotEquals(Status.MIRRORED, host.status("env.SCRATCH"))
    }

    @Test
    fun theRAndElixirRowsReadTheEnvironmentTheImagesRecordAndSayWhatTheTagLeavesOpen() {
        val r = build(
            capsule("env.R_BASE_VERSION" to "4.0.5", "os.arch" to "aarch64"),
            "Rscript a.R",
        )
        assertEquals(Status.PARTIAL, r.status("env.R_BASE_VERSION"))
        assertTrue("FROM r-base:4.0.5; " in r.entry("env.R_BASE_VERSION").detail)
        assertTrue("Debian testing" in r.entry("env.R_BASE_VERSION").detail)
        assertTrue("--platform linux/arm64" in r.runScript)
        assertEquals("debian", r.distro)
        val generated = build(
            capsule("env.R_BASE_VERSION" to "4.0.5", "env.LC_ALL" to "en_US.UTF-8"),
            "Rscript a.R",
        )
        assertFalse("apt-get" in generated.dockerfile)
        assertTrue(generated.packages.isEmpty())
        assertEquals(Status.MIRRORED, generated.status("env.LC_ALL"))
        assertTrue("the image generates the locale" in generated.entry("env.LC_ALL").detail)
        val other = build(
            capsule("env.R_BASE_VERSION" to "4.0.5", "env.LC_ALL" to "de_DE.UTF-8"),
            "Rscript a.R",
        )
        assertTrue("apt-get install" in other.dockerfile)
        assertTrue("de_DE.UTF-8 UTF-8" in other.dockerfile)
        val odd = build(capsule("env.R_BASE_VERSION" to "4.0"), "Rscript a.R")
        assertEquals("debian:12-slim", odd.imageTag)
        assertEquals(Status.NOT_MIRRORED, odd.status("env.R_BASE_VERSION"))
        val elixir = build(
            capsule("env.ELIXIR_VERSION" to "v1.16.3", "env.OTP_VERSION" to "26.2.5.21"),
            "elixir a.exs",
        )
        assertEquals("elixir:1.16.3-otp-26", elixir.imageTag)
        assertTrue("major version only" in elixir.entry("env.ELIXIR_VERSION").detail)
        val bare = build(capsule("env.ELIXIR_VERSION" to "1.16.3"), "elixir a.exs")
        assertEquals("elixir:1.16.3", bare.imageTag)
        assertTrue("or the Erlang/OTP version" in bare.entry("env.ELIXIR_VERSION").detail)
        val unknown = build(capsule("env.ELIXIR_VERSION" to "latest"), "elixir a.exs")
        assertEquals("debian:12-slim", unknown.imageTag)
        val attrs = mapOf("env.ELIXIR_VERSION" to "1.16.9", "env.OTP_VERSION" to "26.1")
        val nearest = ImageTable.rows.single { it.tool == "elixir" }
            .pick(attrs, Distro.of(emptyMap()))
        assertEquals("elixir:1.16.3-otp-26", nearest?.tag)
        assertTrue("the nearest published version is 1.16.3" in nearest?.note.orEmpty())
    }

    @Test
    fun aTagThatPinsLessThanTheCapsuleSaysSoAndIsPartial() {
        val java = build(capsule("tool.java.version" to "17.0.10"), "java -version")
        assertEquals(Status.PARTIAL, java.status("tool.java.version"))
        assertTrue("major version 17 only" in java.entry("tool.java.version").detail)
        val alpine = build(
            capsule(
                "os.release.ID" to "alpine",
                "os.release.VERSION_ID" to "3.20.3",
                "tool.java.version" to "17.0.10",
            ),
            "java -version",
        )
        assertTrue(
            "JDK 17 are published for linux/amd64 only" in
                alpine.entry("tool.java.version").detail,
        )
        val alpine21 = build(
            capsule(
                "os.release.ID" to "alpine",
                "os.release.VERSION_ID" to "3.20.3",
                "tool.java.version" to "21.0.5",
            ),
            "java -version",
        )
        assertFalse("amd64 only" in alpine21.entry("tool.java.version").detail)
        val go = build(capsule("tool.go.version" to "1.21"), "go test")
        assertEquals(Status.PARTIAL, go.status("tool.go.version"))
        val php = build(capsule("tool.php.version" to "8.1.2-1ubuntu2.14"), "php -v")
        assertTrue("-1ubuntu2.14 is dropped" in php.entry("tool.php.version").detail)
        val node = build(capsule("tool.node.version" to "22.12.0"), "node -v")
        assertEquals(Status.MIRRORED, node.status("tool.node.version"))
    }

    @Test
    fun theToolchainAttributesOfTheChosenImageAreAccountedFor() {
        val r = build(
            capsule(
                "tool.java.implementation" to "openjdk",
                "tool.java.version" to "17.0.10",
                "tool.node.version" to "22.12.0",
                "tool.node.raw" to "v22",
                "tool.python.version" to "unparsed",
                "tool.git.version" to "2.34.1",
                "tool.bun.version" to "1.4.2",
                "tool.libc.family" to "glibc",
                "tool.libc.version" to "2.35",
                "tool.coreutils.flavor" to "gnu",
                "tool.coreutils.version" to "8.32",
            ),
            "java -version",
        )
        assertEquals(Status.PARTIAL, r.status("tool.java.implementation"))
        assertEquals(Status.MIRRORED, r.status("tool.libc.family"))
        assertEquals(Status.NOT_MIRRORED, r.status("tool.libc.version"))
        assertEquals(Status.MIRRORED, r.status("tool.coreutils.flavor"))
        assertEquals(Status.NOT_MIRRORED, r.status("tool.coreutils.version"))
        assertTrue("one base image per container" in r.entry("tool.node.version").detail)
        assertTrue("no official image tag" in r.entry("tool.python.version").detail)
        assertTrue("no official image is mapped for bun" in r.entry("tool.bun.version").detail)
        assertTrue("ships its own git" in r.entry("tool.git.version").detail)
        assertTrue(r.entries.filter { it.path.startsWith("tool.libc.") }.all { it.bundle })
        assertFalse(r.entry("tool.java.version").bundle)
    }

    @Test
    fun theLibcAndCoreutilsOfAnAlpineImageAreMuslAndBusybox() {
        val r = build(
            capsule(
                "tool.node.version" to "22.12.0",
                "os.release.ID" to "alpine",
                "os.release.VERSION_ID" to "3.20.3",
                "tool.libc.family" to "glibc",
                "tool.coreutils.flavor" to "gnu",
            ),
            "node a.js",
        )
        assertEquals(Status.NOT_MIRRORED, r.status("tool.libc.family"))
        assertEquals(Status.NOT_MIRRORED, r.status("tool.coreutils.flavor"))
        assertEquals(Status.PARTIAL, r.status("os.release.VERSION_ID"))
        assertTrue("musl" in r.bundleOthers && "busybox ash as /bin/sh" in r.bundleOthers)
    }

    @Test
    fun projectPinsAreWrittenIntoACopyOfTheWorkspace() {
        val r = build(
            capsule(
                "tool.node.version" to "20.11.1",
                "project.pins.node" to "18",
                "project.python.schemakit" to "3.0.0",
                "project.python.widgetkit" to "1.4.2",
                "project.checksums.vendor/a.txt" to sum,
            ),
            "sh prog.sh",
        )
        assertEquals(Status.MIRRORED, r.status("project.checksums.vendor/a.txt"))
        assertTrue("'$sum  vendor/a.txt' >> /pins/SHA256SUMS" in r.dockerfile, r.dockerfile)
        assertEquals(Status.MIRRORED, r.status("project.pins.node"))
        assertEquals(Status.MIRRORED, r.status("project.python.schemakit"))
        assertTrue(r.pinned)
        assertTrue("printf '%s\\n' '18' >> /pins/.nvmrc" in r.dockerfile, r.dockerfile)
        assertTrue("'schemakit==3.0.0' >> /pins/requirements.txt" in r.dockerfile)
        assertTrue("'widgetkit==1.4.2' >> /pins/requirements.txt" in r.dockerfile)
        assertTrue(
            "--volume \"\$PWD:/src:ro\"" in r.flags && "--tmpfs /work:exec,mode=1777" in r.flags,
        )
        assertTrue("--volume \"\$PWD:/work\"" !in r.flags)
        assertTrue(
            "sh -c 'cp -R /src/. /work/ && cp -R /pins/. /work/ && sh prog.sh'" in r.runScript,
        )
        assertTrue(r.runLine(shellCommand = "id").contains("cp -R /pins/. /work/ && id"))
        val plain = build(capsule("tool.node.version" to "20.11.1"), "sh prog.sh")
        assertFalse(plain.pinned)
        assertTrue("/pins" !in plain.dockerfile)
        assertTrue("--volume \"\$PWD:/work\"" in plain.flags)
        val odd = build(capsule("project.pins.node" to "lts/*"), "sh prog.sh")
        assertEquals(Status.NOT_MIRRORED, odd.status("project.pins.node"))
        assertFalse(odd.pinned)
    }

    @Test
    fun theShellBehindBinShIsMirroredByTheImageOrALink() {
        val debian = arrayOf("os.release.ID" to "debian", "os.release.VERSION_ID" to "12")
        val bash = build(capsule(*debian, "tool.sh.flavor" to "bash"), "sh a.sh")
        assertEquals(Status.MIRRORED, bash.status("tool.sh.flavor"))
        assertTrue("RUN ln -sf bash /bin/sh\n" in bash.dockerfile, bash.dockerfile)
        val dash = build(capsule(*debian, "tool.sh.flavor" to "dash"), "sh a.sh")
        assertEquals(Status.MIRRORED, dash.status("tool.sh.flavor"))
        assertFalse("ln -sf" in dash.dockerfile)
        val busybox = build(
            capsule(
                "os.release.ID" to "alpine",
                "os.release.VERSION_ID" to "3.20.3",
                "tool.sh.flavor" to "busybox",
            ),
            "sh a.sh",
        )
        assertEquals(Status.MIRRORED, busybox.status("tool.sh.flavor"))
        val zsh = build(capsule(*debian, "tool.sh.flavor" to "zsh"), "sh a.sh")
        assertEquals(Status.NOT_MIRRORED, zsh.status("tool.sh.flavor"))
        val alpineBash = build(
            capsule(
                "os.release.ID" to "alpine",
                "os.release.VERSION_ID" to "3.20.3",
                "tool.sh.flavor" to "bash",
            ),
            "sh a.sh",
        )
        assertEquals(Status.NOT_MIRRORED, alpineBash.status("tool.sh.flavor"))
    }

    @Test
    fun aGnuUserlandOnAnAlpineImageInstallsTheGnuTools() {
        val attrs = arrayOf(
            "tool.node.version" to "22.12.0",
            "os.release.ID" to "alpine",
            "os.release.VERSION_ID" to "3.20.3",
        )
        val edited = Attribute("tool.coreutils.flavor", "gnu", "intervention")
        val gnu = build(
            Capsule("edited", capsule(*attrs).attributes + edited),
            "node a.js",
        )
        val recorded = build(capsule(*attrs, "tool.coreutils.flavor" to "gnu"), "node a.js")
        assertEquals(Status.NOT_MIRRORED, recorded.status("tool.coreutils.flavor"))
        assertTrue(recorded.packages.none { it.name == "findutils" })
        assertEquals(
            "apk add coreutils diffutils findutils grep sed",
            gnu.entry(
                "tool.coreutils.flavor",
            ).detail,
        )
        for (name in listOf("coreutils", "diffutils", "findutils", "grep", "sed")) {
            assertTrue(gnu.packages.any { it.name == name && it.manager == "apk" }, name)
        }
        val busybox = build(capsule(*attrs, "tool.coreutils.flavor" to "busybox"), "node a.js")
        assertTrue(busybox.packages.none { it.name == "findutils" })
        val debian = build(
            capsule("tool.node.version" to "22.12.0", "tool.coreutils.flavor" to "busybox"),
            "node a.js",
        )
        assertEquals(Status.NOT_MIRRORED, debian.status("tool.coreutils.flavor"))
    }

    @Test
    fun pythonExternallyManagedIsMirroredOnlyWhenFalse() {
        val no = build(
            capsule("tool.python.version" to "3.12.4", "tool.python.externally-managed" to "false"),
            "pytest",
        )
        assertEquals(Status.MIRRORED, no.status("tool.python.externally-managed"))
        val yes = build(
            capsule("tool.python.version" to "3.12.4", "tool.python.externally-managed" to "true"),
            "pytest",
        )
        assertEquals(Status.NOT_MIRRORED, yes.status("tool.python.externally-managed"))
        assertTrue("PEP 668" in yes.entry("tool.python.externally-managed").detail)
    }

    @Test
    fun goAndPhpAndGccSubAttributesFollowTheImage() {
        val go = build(
            capsule(
                "tool.go.version" to "1.23.1",
                "tool.go.os" to "darwin",
                "tool.go.arch" to "arm64",
                "os.arch" to "aarch64",
            ),
            "go test",
        )
        assertEquals(Status.NOT_MIRRORED, go.status("tool.go.os"))
        assertEquals(Status.MIRRORED, go.status("tool.go.arch"))
        val php = build(
            capsule(
                "tool.php.version" to "8.3.10",
                "tool.php.sapi" to "fpm",
                "tool.php.thread-safety" to "NTS",
            ),
            "php -v",
        )
        assertEquals(Status.NOT_MIRRORED, php.status("tool.php.sapi"))
        assertEquals(Status.MIRRORED, php.status("tool.php.thread-safety"))
        val gcc = build(capsule("tool.cc.family" to "gcc", "tool.cc.version" to "13.2.0"), "make")
        assertEquals(Status.MIRRORED, gcc.status("tool.cc.family"))
        assertEquals(Status.PARTIAL, gcc.status("tool.cc.version"))
        assertTrue("pins 13.2 only" in gcc.entry("tool.cc.version").detail)
    }

    @Test
    fun aTagTheRegistryLacksIsReplacedByTheNearestPublishedOne() {
        val debian12 = ImageDistro("debian", "12")

        fun pick(tag: String, note: String? = null) = ImageTable.resolve(Pick(tag, note, debian12))

        val kept = pick("node:22.12.0-bookworm-slim")
        assertEquals("node:22.12.0-bookworm-slim", kept.tag)
        assertNull(kept.note)

        val sibling = pick("php:8.3.35-cli-bullseye", "pins the line")
        assertEquals("php:8.3.35-cli-bookworm", sibling.tag)
        assertEquals(ImageDistro("debian", "12"), sibling.image)
        assertEquals(
            "pins the line; php:8.3.35-cli-bullseye is not published; " +
                "php:8.3.35-cli-bookworm has the same version on Debian 12",
            sibling.note,
        )

        val older = pick("node:16.13.0-bookworm-slim")
        assertEquals("node:16.13.0-bullseye-slim", older.tag)
        assertEquals(ImageDistro("debian", "11"), older.image)

        val patch = pick("gcc:13.2.1")
        assertEquals("gcc:13.2.0", patch.tag)
        assertTrue("the nearest published version is 13.2.0" in patch.note.orEmpty())

        assertEquals("golang:1.22.12-bookworm", pick("golang:1.22.99-bookworm").tag)

        val unknownShape = pick("python:3.12.4-slimline")
        assertEquals("python:3.12.4-slimline", unknownShape.tag)
        assertTrue("no tag of this form" in unknownShape.note.orEmpty())

        assertEquals("swift:5.10", pick("swift:5.10").tag)
        assertNull(pick("swift:5.10").note)
        assertEquals("r-base:4.6.1", pick("r-base:4.6.1").tag)
        assertEquals("r-base:4.6.1", pick("r-base:4.6.9").tag)
    }

    @Test
    fun everyTagOfTheFormulaRowsExistsOnTheList() {
        val capsules = listOf(
            listOf("tool.node.version" to "16.13.0") + debian10,
            listOf("tool.python.version" to "3.12.4") + alpine320,
            listOf("tool.rust.version" to "1.80.0") + debian12,
            listOf("tool.php.version" to "8.1.2") + debian12,
            listOf("tool.go.version" to "1.22") + debian12,
            listOf("tool.java.version" to "21.0.5") + alpine320,
            listOf("tool.cc.family" to "gcc", "tool.cc.version" to "13.2.1"),
        )
        for (pairs in capsules) {
            val tag = build(capsule(*pairs.toTypedArray()), "x").imageTag
            val repository = tag.substringBefore(':')
            val version = tag.substringAfter(':').substringBefore('-')
            val rest = tag.substringAfter(':').removePrefix(version)
            assertTrue(
                ImageTags.repositories.getValue(repository).getValue(rest).contains(version),
                tag,
            )
        }
    }

    @Test
    fun anNpmThatItsNodeCannotRunIsNotPinned() {
        val old = build(capsule("tool.node.version" to "18.16.0", "tool.npm.version" to "10.9.0"))
        assertFalse("npm install" in old.dockerfile)
        assertEquals(Status.NOT_MIRRORED, old.status("tool.npm.version"))
        assertTrue("does not run on node 18.16.0" in old.entry("tool.npm.version").detail)
        val fine = build(capsule("tool.node.version" to "18.17.0", "tool.npm.version" to "10.9.0"))
        assertTrue("RUN npm install -g npm@10.9.0\n" in fine.dockerfile)
    }

    @Test
    fun theNpmEngineRangesFollowTheReleasedPackages() {
        assertTrue(NpmEngines.supports("9.5.1", "14.17.0"))
        assertFalse(NpmEngines.supports("9.5.1", "14.16.9"))
        assertTrue(NpmEngines.supports("9.5.1", "16.13.0"))
        assertFalse(NpmEngines.supports("9.5.1", "15.0.0"))
        assertTrue(NpmEngines.supports("10.2.0", "20.5.0"))
        assertFalse(NpmEngines.supports("10.2.0", "20.4.9"))
        assertFalse(NpmEngines.supports("10.2.0", "19.0.0"))
        assertTrue(NpmEngines.supports("11.0.0", "22.9.0"))
        assertFalse(NpmEngines.supports("11.0.0", "21.7.3"))
        assertTrue(NpmEngines.supports("12.0.0", "24.15.0"))
        assertFalse(NpmEngines.supports("12.0.0", "24.14.9"))
        assertTrue(NpmEngines.supports("6.14.0", "8.0.0"), "an unknown major is not refused")
        assertTrue(NpmEngines.supports("10.9.0", "lts"), "an unreadable node is not refused")
    }

    @Test
    fun npmIsPinnedOnANodeImageAndNotMirroredElsewhere() {
        val node = build(capsule("tool.node.version" to "22.12.0", "tool.npm.version" to "10.9.2"))
        assertTrue("RUN npm install -g npm@10.9.2\n" in node.dockerfile)
        assertEquals(Status.MIRRORED, node.status("tool.npm.version"))
        assertTrue(node.entry("tool.npm.version").bundle)
        val java = build(capsule("tool.java.version" to "17.0.1", "tool.npm.version" to "10.9.2"))
        assertFalse("npm" in java.dockerfile)
        assertEquals(Status.NOT_MIRRORED, java.status("tool.npm.version"))
        val odd = build(
            capsule("tool.node.version" to "22.12.0", "tool.npm.version" to "10.9.2; id"),
        )
        assertFalse("npm install" in odd.dockerfile)
        assertEquals(Status.NOT_MIRRORED, odd.status("tool.npm.version"))
    }

    @Test
    fun onlyAllowlistedOrNamedVariablesAreMirrored() {
        val r = build(
            Capsule(
                "t",
                listOf(
                    Attribute("env.CI", "true", "env"),
                    Attribute("env.NODE_ENV", "production", "env"),
                    Attribute("env.PATH", "/usr/bin", "env"),
                    Attribute("env.HOME", "/Users/me", "env"),
                    Attribute("env.PWD", "/work", "env", Stability.VOLATILE),
                    Attribute("env.XDG_RUNTIME_DIR", "x", "env"),
                    Attribute("env.NODE_OPTIONS", "--require /Users/me/x.js", "env"),
                    Attribute("env.A.B", "1", "env"),
                    Attribute("env.JAVA_HOME", "C:\\Java", "env"),
                    Attribute("env.TZ", "UTC", "env"),
                ),
            ),
        )
        assertEquals(Status.MIRRORED, r.status("env.TZ"))
        for (path in listOf("env.CI", "env.NODE_ENV", "env.NODE_OPTIONS", "env.JAVA_HOME")) {
            assertEquals(Status.NOT_MIRRORED, r.status(path), path)
            assertEquals("not allowlisted", r.entry(path).detail, path)
        }
        for (path in listOf("env.PATH", "env.HOME", "env.XDG_RUNTIME_DIR")) {
            assertTrue("host identity" in r.entry(path).detail, path)
        }
        assertTrue("volatile" in r.entry("env.PWD").detail)
        assertTrue("not a valid shell variable name" in r.entry("env.A.B").detail)
        assertFalse("NODE_ENV" in r.runScript)
        val named = build(envCapsule, envNames = setOf("CI", "NODE_OPTIONS", "JAVA_HOME"))
        assertEquals(Status.MIRRORED, named.status("env.CI"))
        assertEquals(Status.NOT_MIRRORED, named.status("env.NODE_ENV"))
        assertTrue("path on the capsule's host" in named.entry("env.JAVA_HOME").detail)
        assertTrue("--env 'CI=true' \\\n" in named.runScript)
        val all = build(envCapsule, envAll = true)
        assertEquals(Status.MIRRORED, all.status("env.NODE_ENV"))
        assertTrue("--env 'NODE_ENV=production' \\\n" in all.runScript)
        assertEquals(Status.NOT_MIRRORED, all.status("env.PATH"))
        assertFalse("--env 'PATH" in all.runScript)
    }

    private val envCapsule = Capsule(
        "t",
        listOf(
            Attribute("env.CI", "true", "env"),
            Attribute("env.NODE_ENV", "production", "env"),
            Attribute("env.PATH", "/usr/bin", "env"),
            Attribute("env.NODE_OPTIONS", "--no-warnings", "env"),
            Attribute("env.JAVA_HOME", "C:\\Java", "env"),
        ),
    )

    @Test
    fun anUnknownEnvNameIsRefused() {
        val refused = assertIs<ReproduceResult.Refused>(
            Reproduce.synthesize(envCapsule, "make", envNames = setOf("NOPE")),
        )
        assertEquals(ReproduceProblem.UnknownEnv("NOPE"), refused.problem)
        assertTrue("NOPE" in refused.problem.message())
    }

    @Test
    fun homePathsAndAccountNamesInMirroredValuesAreAnonymized() {
        val r = build(
            Capsule(
                "t",
                listOf(
                    Attribute("env.USER", "alice", "env"),
                    Attribute("env.HOME", "/Users/alice", "env"),
                    Attribute("env.HOSTNAME", "buildbox-7", "env"),
                    Attribute("env.TZ", "UTC", "env"),
                    Attribute("env.A", "/Users/alice/x:/home/bob/y:C:\\Users\\carol\\z", "env"),
                    Attribute("env.B", "alice@buildbox-7 and alicenew", "env"),
                ),
            ),
            envNames = setOf("A", "B"),
        )
        assertEquals(Status.MIRRORED, r.status("env.TZ"))
        assertEquals(Status.PARTIAL, r.status("env.A"))
        assertTrue("path anonymized" in r.entry("env.A").detail)
        assertTrue(
            "--env 'A=/Users/user/x:/home/user/y:C:\\Users\\user\\z' \\\n" in r.runScript,
            r.runScript,
        )
        assertTrue("--env 'B=user@host and alicenew' \\\n" in r.runScript, r.runScript)
        assertEquals(r.total, r.mirrored + r.partial + r.notMirrored)
    }

    @Test
    fun noIdentifierReachesTheGeneratedFilesWithOrWithoutEnvAll() {
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2"
        val account = "zelda-the-account"
        val host = "ci-box-0451.corp.example"
        val email = "zelda.q@corp.example"
        val acct = "123456789012"
        val capsule = Capsule(
            "t",
            listOf(
                Attribute("env.USER", account, "env"),
                Attribute("env.HOME", "/Users/$account", "env"),
                Attribute("env.HOSTNAME", host, "env"),
                Attribute("env.TZ", "/Users/$account/zoneinfo/UTC", "env"),
                Attribute("env.LANG", "C.UTF-8", "env"),
                Attribute("env.LC_ALL", "/home/$account/locale", "env"),
                Attribute("env.AWS_ACCOUNT_ID", acct, "env"),
                Attribute("env.GIT_AUTHOR_EMAIL", email, "env"),
                Attribute("env.BUILD_HOST", host, "env"),
                Attribute("env.NOTE", "paths /home/$account/src and $token", "env"),
                Attribute("env.GH", token, "env"),
            ),
        )
        val always = listOf(account, token)
        val never = always + listOf(host, email, acct)
        for (envAll in listOf(false, true)) {
            val r = build(capsule, envAll = envAll)
            val files = r.files().values.joinToString("\n")
            for (secret in if (envAll) always else never) {
                assertFalse(secret in files, "envAll=$envAll leaked $secret")
            }
            assertEquals(r.total, r.mirrored + r.partial + r.notMirrored)
            assertEquals(capsule.attributes.size, r.total)
        }
        val off = build(capsule)
        assertEquals(Status.NOT_MIRRORED, off.status("env.AWS_ACCOUNT_ID"))
        assertEquals(Status.PARTIAL, off.status("env.TZ"))
        assertTrue("path anonymized" in off.entry("env.LC_ALL").detail)
        assertTrue("--env 'TZ=/Users/user/zoneinfo/UTC' \\\n" in off.runScript, off.runScript)
    }

    @Test
    fun aSecretValueNeverAppearsInAnyOutput() {
        val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2"
        val aws = "AKIA" + "ABCDEFGHIJKLMNOP"
        val secrets = listOf(
            "hunter2-plain-secret",
            "s3cr3t-in-a-name",
            token,
            aws,
            "https://deploy:pa55w0rd-in-url@example.com/x",
        )
        val capsule = Capsule(
            "label $token",
            listOf(
                Attribute("env.API_TOKEN", secrets[0], "env"),
                Attribute("env.DB_PASSWORD", Redactor.MASK, "env"),
                Attribute("env.MY_SECRET", secrets[1], "env"),
                Attribute("env.GREETING", "hello $token", "env"),
                Attribute("env.AWS_THING", aws, "env"),
                Attribute("env.REGISTRY", secrets[4], "env"),
                Attribute("env.CI", "true", "env"),
            ),
        )
        val r = build(capsule, "echo ok")
        val output = r.files().values.joinToString("\n") + ReproduceRender.render(r, Detail.FULL)
        for (s in secrets + "pa55w0rd-in-url") assertFalse(s in output, s)
        for (path in listOf("env.API_TOKEN", "env.DB_PASSWORD", "env.MY_SECRET", "env.GREETING")) {
            assertEquals(Status.NOT_MIRRORED, r.status(path), path)
            assertTrue("redacted" in r.entry(path).detail, path)
        }
        assertEquals(Status.NOT_MIRRORED, r.status("env.REGISTRY"))
        assertEquals(Status.NOT_MIRRORED, r.status("env.CI"))
        assertEquals(Status.MIRRORED, build(capsule, "echo ok", envAll = true).status("env.CI"))
        val all = build(capsule, "echo ok", envAll = true)
        val allOutput = all.files().values.joinToString("\n")
        for (s in secrets + "pa55w0rd-in-url") assertFalse(s in allOutput, s)
    }

    @Test
    fun theCommandAndValuesAreShellQuoted() {
        val r = build(
            capsule("env.QUOTE" to "it's \$HOME \"x\"\nline2", "env.EMPTY" to ""),
            "echo 'a b' && echo \$HOME",
            envAll = true,
        )
        assertTrue("--env 'QUOTE=it'\\''s \$HOME \"x\"\nline2' \\\n" in r.runScript, r.runScript)
        assertTrue("sh -c 'echo '\\''a b'\\'' && echo \$HOME'\n" in r.runScript, r.runScript)
    }

    @Test
    fun memoryAndCpuLimitsBecomeDockerFlags() {
        val memory = listOf(
            "max" to null,
            "2147483648" to "2g",
            "536870912" to "512m",
            "1572864" to null,
            "6291456" to "6m",
            "1048576000" to "1000m",
            "3000" to null,
            "9223372036854771712" to null,
            "12345678" to "12345678b",
            "4096000" to null,
            "lots" to null,
        )
        for ((value, flag) in memory) {
            val r = build(capsule("cgroup.memory.max" to value))
            val has = r.flags.firstOrNull { it.startsWith("--memory") }
            val shown = "$value -> $has"
            assertEquals(flag?.let { "--memory $it" }, has, shown)
        }
        assertEquals(
            Status.MIRRORED,
            build(capsule("cgroup.memory.max" to "max")).status("cgroup.memory.max"),
        )
        assertEquals(
            Status.NOT_MIRRORED,
            build(capsule("cgroup.memory.max" to "3000")).status("cgroup.memory.max"),
        )
        assertEquals(
            Status.NOT_MIRRORED,
            build(capsule("cgroup.memory.max" to "lots")).status("cgroup.memory.max"),
        )
        val cpus = listOf(
            Triple("max 100000", null, Status.MIRRORED),
            Triple("max", null, Status.MIRRORED),
            Triple("200000 100000", "2", Status.MIRRORED),
            Triple("150000 100000", "1.5", Status.MIRRORED),
            Triple("50000 100000", "0.5", Status.MIRRORED),
            Triple("120000 100000", "1.2", Status.MIRRORED),
            Triple("100000", "1", Status.MIRRORED),
            Triple("33333 100000", "0.333", Status.PARTIAL),
            Triple("500 100000", null, Status.NOT_MIRRORED),
            Triple("0 100000", null, Status.NOT_MIRRORED),
            Triple("two 100000", null, Status.NOT_MIRRORED),
            Triple("100000 0", null, Status.NOT_MIRRORED),
        )
        for ((value, flag, status) in cpus) {
            val r = build(capsule("cgroup.cpu.max" to value))
            val has = r.flags.firstOrNull { it.startsWith("--cpus") }
            assertEquals(flag?.let { "--cpus $it" }, has, value)
            assertEquals(status, r.status("cgroup.cpu.max"), value)
        }
        val other = build(capsule("cgroup.cpu.weight" to "100"))
        assertTrue("no docker run flag" in other.entry("cgroup.cpu.weight").detail)
    }

    @Test
    fun ulimitsBecomeUlimitFlags() {
        val cases = listOf(
            Triple("nofile", "1024:4096", "--ulimit nofile=1024:4096"),
            Triple("nofile", "2048", "--ulimit nofile=2048"),
            Triple("core", "unlimited", "--ulimit core=-1"),
            Triple("stack", "8388608:unlimited", "--ulimit stack=8388608:-1"),
            Triple("nofile", "1:2:3", null),
            Triple("nofile", "many", null),
            Triple("bogus", "1", null),
            Triple("as", "unlimited:unlimited", null),
        )
        for ((name, value, flag) in cases) {
            val r = build(capsule("limits.$name" to value))
            assertEquals(flag, r.flags.firstOrNull { it.startsWith("--ulimit") }, "$name=$value")
            val status = if (flag == null) Status.NOT_MIRRORED else Status.MIRRORED
            assertEquals(status, r.status("limits.$name"), "$name=$value")
        }
    }

    @Test
    fun everyCapturedLimitBecomesOneFlagExceptTheAddressSpace() {
        val r = build(
            capsule(
                "limits.as" to "unlimited:unlimited",
                "limits.core" to "0:0",
                "limits.cpu" to "unlimited:unlimited",
                "limits.data" to "unlimited:unlimited",
                "limits.fsize" to "100000:100000",
                "limits.memlock" to "8388608:8388608",
                "limits.nofile" to "1024:2048",
                "limits.nproc" to "63000:63000",
                "limits.stack" to "8388608:unlimited",
            ),
        )
        assertEquals(
            listOf(
                "--ulimit core=0:0",
                "--ulimit cpu=-1:-1",
                "--ulimit data=-1:-1",
                "--ulimit fsize=100000:100000",
                "--ulimit memlock=8388608:8388608",
                "--ulimit nofile=1024:2048",
                "--ulimit nproc=63000:63000",
                "--ulimit stack=8388608:-1",
            ),
            r.flags.filter { it.startsWith("--ulimit") },
        )
        assertEquals(Status.NOT_MIRRORED, r.status("limits.as"))
    }

    @Test
    fun architectureBecomesAPlatform() {
        val arm = build(capsule("os.arch" to "aarch64"))
        assertFalse("--platform" in arm.dockerfile)
        assertTrue("--platform linux/arm64 \\\n" in arm.runScript)
        assertTrue("docker build --platform linux/arm64 " in arm.runScript)
        assertEquals("linux/amd64", build(capsule("os.arch" to "x86_64")).platform)
        val odd = build(capsule("os.arch" to "riscv64"))
        assertEquals(null, odd.platform)
        assertEquals(Status.NOT_MIRRORED, odd.status("os.arch"))
        assertFalse("--platform" in odd.dockerfile + odd.runScript)
    }

    @Test
    fun localeAndTimezoneAreInstalledAndPassedOnDebian() {
        val r = build(
            capsule(
                "tool.node.version" to "22.12.0",
                "env.LANG" to "en_US.UTF-8",
                "env.LC_ALL" to "C.UTF-8",
                "env.LC_TIME" to "de_DE.utf8",
                "env.LC_NUMERIC" to "fr_FR.ISO-8859-1",
                "env.TZ" to "Asia/Tokyo",
            ),
            "node a.js",
        )
        assertTrue("apt-get install -y --no-install-recommends locales \\\n" in r.dockerfile)
        assertFalse("tzdata" in r.dockerfile)
        assertTrue("echo 'de_DE.UTF-8 UTF-8' >> /etc/locale.gen" in r.dockerfile)
        assertTrue("echo 'en_US.UTF-8 UTF-8' >> /etc/locale.gen" in r.dockerfile)
        assertFalse("fr_FR" in r.dockerfile)
        assertTrue(r.dockerfile.contains("&& locale-gen\n"))
        assertEquals(Status.MIRRORED, r.status("env.LANG"))
        assertEquals(Status.MIRRORED, r.status("env.LC_ALL"))
        assertEquals(Status.MIRRORED, r.status("env.LC_TIME"))
        assertEquals(Status.PARTIAL, r.status("env.LC_NUMERIC"))
        assertEquals(Status.MIRRORED, r.status("env.TZ"))
        for (name in listOf("LANG=en_US.UTF-8", "LC_ALL=C.UTF-8", "TZ=Asia/Tokyo")) {
            assertTrue("--env '$name' \\\n" in r.runScript, name)
        }
    }

    @Test
    fun aLocaleTheCapsuleDoesNotListIsNotGeneratedInTheArm() {
        val r = build(
            capsule(
                "tool.node.version" to "22.12.0",
                "env.LANG" to "en_US.UTF-8",
                "env.LC_TIME" to "de_DE.utf8",
                "locale.installed" to "C.utf8,en_US.utf8",
            ),
            "node a.js",
        )
        assertTrue("echo 'en_US.UTF-8 UTF-8' >> /etc/locale.gen" in r.dockerfile)
        assertFalse("de_DE" in r.dockerfile)
        assertEquals(Status.MIRRORED, r.status("env.LC_TIME"))
        assertTrue("--env 'LC_TIME=de_DE.utf8' \\\n" in r.runScript)
    }

    @Test
    fun aRestrictedCpuAffinityBecomesACpuset() {
        val limited = build(capsule("cpu.count" to "24", "cpu.affinity" to "2"))
        assertEquals("--cpuset-cpus 0-1", limited.flags.single { it.startsWith("--cpuset") })
        assertEquals(Status.MIRRORED, limited.status("cpu.affinity"))
        val single = build(capsule("cpu.count" to "24", "cpu.affinity" to "1"))
        assertEquals("--cpuset-cpus 0", single.flags.single { it.startsWith("--cpuset") })
        val all = build(capsule("cpu.count" to "24", "cpu.affinity" to "24"))
        assertTrue(all.flags.none { it.startsWith("--cpuset") })
        assertEquals(Status.MIRRORED, all.status("cpu.affinity"))
        val bad = build(capsule("cpu.affinity" to "many"))
        assertEquals(Status.NOT_MIRRORED, bad.status("cpu.affinity"))
    }

    @Test
    fun aLoopbackOnlyInterfaceListBecomesNetworkNone() {
        val none = build(capsule("network.interfaces" to "lo"))
        assertTrue("--network none" in none.flags)
        assertEquals(Status.MIRRORED, none.status("network.interfaces"))
        val bridge = build(capsule("network.interfaces" to "eth0,lo"))
        assertTrue(bridge.flags.none { it.startsWith("--network") })
        assertEquals(Status.MIRRORED, bridge.status("network.interfaces"))
        val other = build(capsule("network.interfaces" to "lo,wlan0"))
        assertEquals(Status.PARTIAL, other.status("network.interfaces"))
    }

    @Test
    fun hostsEntriesWithALoopbackAddressBecomeAddHostFlags() {
        val r = build(capsule("network.hosts.names" to "db.internal@127.0.0.1,cache@127.0.0.2"))
        assertEquals(
            listOf("--add-host cache:127.0.0.2", "--add-host db.internal:127.0.0.1"),
            r.flags.filter { it.startsWith("--add-host") },
        )
        assertEquals(Status.MIRRORED, r.status("network.hosts.names"))
        val mixed = build(capsule("network.hosts.names" to "db.internal@127.0.0.1,vault"))
        assertEquals(Status.PARTIAL, mixed.status("network.hosts.names"))
        assertEquals(1, mixed.flags.count { it.startsWith("--add-host") })
        val bare = build(capsule("network.hosts.names" to "host"))
        assertEquals(Status.NOT_MIRRORED, bare.status("network.hosts.names"))
        assertTrue(bare.flags.none { it.startsWith("--add-host") })
    }

    @Test
    fun theNsswitchHostsLineIsSetInTheImageAndOnlyForSafeValues() {
        val r = build(capsule("network.nsswitch.hosts" to "files [NOTFOUND=return] dns"))
        assertTrue(
            "&& echo 'hosts: files [NOTFOUND=return] dns' >> /etc/nsswitch.conf" in r.dockerfile,
        )
        assertTrue("sed -i '/^hosts:/d' /etc/nsswitch.conf" in r.dockerfile)
        assertEquals(Status.MIRRORED, r.status("network.nsswitch.hosts"))
        val unsafe = build(capsule("network.nsswitch.hosts" to "dns'; rm -rf /"))
        assertFalse("nsswitch" in unsafe.dockerfile)
        assertEquals(Status.NOT_MIRRORED, unsafe.status("network.nsswitch.hosts"))
        val alpine = build(
            capsule(
                "os.release.ID" to "alpine",
                "os.release.VERSION_ID" to "3.20.3",
                "network.nsswitch.hosts" to "dns",
            ),
        )
        assertFalse("nsswitch" in alpine.dockerfile)
        assertEquals(Status.PARTIAL, alpine.status("network.nsswitch.hosts"))
    }

    @Test
    fun aCLocaleNeedsNoPackageAndAnAlpineImageGetsApk() {
        val c = build(capsule("env.LANG" to "C.UTF-8", "env.LC_ALL" to "POSIX"))
        assertFalse("apt-get" in c.dockerfile || "apk" in c.dockerfile)
        val tzOnly = build(
            capsule("tool.node.version" to "22.12.0", "env.TZ" to "Asia/Tokyo"),
            "node a.js",
        )
        assertFalse("apt-get" in tzOnly.dockerfile)
        assertTrue("the image has tzdata" in tzOnly.entry("env.TZ").detail)
        val ubuntu = build(
            capsule(
                "os.release.ID" to "ubuntu",
                "os.release.VERSION_ID" to "22.04",
                "env.TZ" to "UTC",
                "env.LANG" to "en_US.UTF-8",
            ),
            "make",
        )
        assertTrue("--no-install-recommends locales tzdata \\\n" in ubuntu.dockerfile)
        assertTrue("the image gets tzdata" in ubuntu.entry("env.TZ").detail)
        val alpine = build(
            capsule(
                "tool.node.version" to "22.12.0",
                "os.release.ID" to "alpine",
                "os.release.VERSION_ID" to "3.20.3",
                "env.LANG" to "en_US.UTF-8",
                "env.TZ" to "UTC",
            ),
            "node a.js",
        )
        assertTrue("RUN apk add --no-cache gcompat libgcc tzdata\n" in alpine.dockerfile)
        assertFalse("locale" in alpine.dockerfile)
        assertEquals(Status.PARTIAL, alpine.status("env.LANG"))
        assertTrue("musl" in alpine.entry("env.LANG").detail)
    }

    @Test
    fun anAlpineArmGetsGcompatForTheGlibcDriftBinaryAndADebianArmDoesNot() {
        val node = "tool.node.version" to "22.12.0"
        val alpine = build(capsule(*(alpine320 + node).toTypedArray()), "node a.js")
        assertTrue("RUN apk add --no-cache gcompat libgcc\n" in alpine.dockerfile)
        val debian = build(capsule(*(debian12 + node).toTypedArray()), "node a.js")
        assertFalse("apk" in debian.dockerfile)
    }

    @Test
    fun theManifestNamesEveryPackageAnArmAddsThatTheCapsuleDoesNot() {
        val alpine = build(
            capsule(
                "tool.node.version" to "22.12.0",
                "os.release.ID" to "alpine",
                "os.release.VERSION_ID" to "3.20.3",
                "env.TZ" to "UTC",
            ),
            "node a.js",
        )
        val alpinePackages = manifestPackages(alpine)
        assertEquals(listOf("gcompat", "libgcc", "tzdata"), alpinePackages.map { it.first })
        assertTrue(alpinePackages.all { it.second == "apk" })
        assertTrue("RUN apk add --no-cache gcompat libgcc tzdata\n" in alpine.dockerfile)
        val debian = build(
            capsule(
                "tool.node.version" to "22.12.0",
                "env.LANG" to "en_US.UTF-8",
                "env.TZ" to "Asia/Tokyo",
            ),
            "node a.js",
        )
        assertEquals(listOf("locales" to "apt"), manifestPackages(debian))
        val bare = build(capsule("tool.node.version" to "22.12.0"), "node a.js")
        assertEquals(emptyList(), manifestPackages(bare))
    }

    @Test
    fun anArchivedOrUnknownDebianReleaseIsPointedAtArchiveDebianOrgAtBuildTime() {
        val locale = "env.LANG" to "en_US.UTF-8"
        val gcc = listOf("tool.cc.family" to "gcc", "tool.cc.version" to "10.2.1")
        val buster = build(capsule(*(debian10 + gcc + locale).toTypedArray()), "make")
        assertTrue("FROM gcc:10.2\nRUN . /etc/os-release \\\n" in buster.dockerfile)
        assertTrue("case \"\$VERSION_ID\" in 9|10) sed -i" in buster.dockerfile)
        assertTrue("archive.debian.org/debian-security|' \\\n" in buster.dockerfile)
        assertTrue("-e 's|deb.debian.org|archive.debian.org|' \\\n" in buster.dockerfile)
        assertTrue("/etc/apt/sources.list ;; esac\nRUN apt-get update" in buster.dockerfile)
        assertEquals(
            listOf("archive.debian.org" to "apt-sources", "locales" to "apt"),
            manifestPackages(buster),
        )
        val unknown = build(capsule(*(gcc + locale).toTypedArray()), "make")
        assertEquals(
            buster.dockerfile.substringAfter("\n"),
            unknown.dockerfile.substringAfter("\n"),
        )
        val nodeAttrs = debian10 + locale + ("tool.node.version" to "16.13.0")
        val node = build(capsule(*nodeAttrs.toTypedArray()), "node a.js")
        assertTrue("case \"\$VERSION_ID\"" in node.dockerfile)
        val served = listOf(
            build(capsule(*(debian12 + gcc + locale).toTypedArray()), "make"),
            build(
                capsule(
                    "os.release.ID" to "debian",
                    "os.release.VERSION_ID" to "11",
                    "tool.node.version" to "18.0.0",
                    locale,
                ),
                "node a.js",
            ),
            build(capsule(*(debian10 + gcc).toTypedArray()), "make"),
        )
        for (r in served) {
            assertFalse("archive.debian.org" in r.dockerfile, r.dockerfile)
        }
        assertEquals(listOf("locales" to "apt"), manifestPackages(served[0]))
        assertEquals(listOf("locales" to "apt"), manifestPackages(served[1]))
    }

    private fun manifestPackages(r: Reproduction): List<Pair<String, String>> {
        val text = r.files().getValue("manifest.json")
        return CanonicalJson.parse(text).obj().require("packages").array().map {
            val p = it.obj()
            p.require("name").string() to p.require("manager").string()
        }
    }

    @Test
    fun theKernelHardwareAndHostedAttributesAreNeverMirrored() {
        val r = build(
            capsule(
                "kernel.release" to "6.8.0",
                "cpu.count" to "4",
                "hw.cpu.model" to "Xeon",
                "hw.memory.total" to "16",
                "hw.hypervisor" to "kvm",
                "ci.runner.image" to "ubuntu-24.04",
                "ci.provisioner.version" to "20260828.587",
                "deps.vitest.version" to "4.1.10",
                "drift.platform" to "jvm",
                "kotlin.version" to "2.4.0",
                "network.dns" to "x",
                "browser.chrome.version" to "1",
                "mystery.thing" to "1",
                "os.name" to "windows",
                "os.version" to "10.0",
            ),
        )
        assertTrue(r.entries.all { it.status == Status.NOT_MIRRORED })
        assertTrue("Docker host's kernel" in r.entry("kernel.release").detail)
        assertTrue("Docker host's CPU" in r.entry("cpu.count").detail)
        assertTrue("steal time" in r.entry("hw.cpu.model").detail)
        assertTrue("hypervisor" in r.entry("hw.hypervisor").detail)
        val hosted = r.entry("ci.runner.image").detail
        assertTrue("runner provider owns the image and provisioner" in hosted)
        assertTrue("lockfile" in r.entry("deps.vitest.version").detail)
        assertTrue("Drift binary" in r.entry("drift.platform").detail)
        assertTrue("no mapping for this attribute" in r.entry("mystery.thing").detail)
        assertTrue("the capsule is windows" in r.entry("os.name").detail)
    }

    @Test
    fun theOperatingSystemAttributesFollowTheImage() {
        val r = build(
            capsule(
                "tool.node.version" to "22.12.0",
                "os.name" to "linux",
                "os.release.ID" to "debian",
                "os.release.VERSION_ID" to "12",
                "os.release.PRETTY_NAME" to "Debian GNU/Linux 12 (bookworm)",
                "os.version" to "12.5",
            ),
            "node a.js",
        )
        assertEquals(Status.PARTIAL, r.status("os.name"))
        assertEquals(Status.MIRRORED, r.status("os.release.ID"))
        assertEquals(Status.MIRRORED, r.status("os.release.VERSION_ID"))
        assertEquals(Status.PARTIAL, r.status("os.release.PRETTY_NAME"))
        assertEquals(Status.PARTIAL, r.status("os.version"))
        val ubuntu = build(
            capsule(
                "tool.node.version" to "22.12.0",
                "os.release.ID" to "ubuntu",
                "os.release.VERSION_ID" to "24.04",
                "os.version" to "24.04.5",
            ),
            "node a.js",
        )
        assertEquals(Status.NOT_MIRRORED, ubuntu.status("os.release.ID"))
        assertEquals(Status.NOT_MIRRORED, ubuntu.status("os.release.VERSION_ID"))
        val ubuntuId = ubuntu.entry("os.release.ID").detail
        assertTrue("the image is debian, the capsule says ubuntu" in ubuntuId)
        val gcc = build(
            capsule(
                "os.release.name" to "Debian",
                "os.version" to "12.5",
                "tool.cc.family" to "gcc",
                "tool.cc.version" to "13.2.0",
            ),
            "make",
        )
        assertEquals(Status.MIRRORED, gcc.status("os.release.name"))
        assertEquals(Status.NOT_MIRRORED, gcc.status("os.version"))
        assertTrue("release not pinned by its tag" in gcc.entry("os.version").detail)
    }

    @Test
    fun theAccountingAddsUpForEveryCapsule() {
        val synthetic = listOf(
            capsule(),
            capsule("env.A" to "1", "env.A" to "2", "tool.node.version" to "22.12.0"),
        )
        for (c in fixtures.values + synthetic) {
            val r = build(c)
            assertEquals(c.attributes.size, r.total, c.label)
            assertEquals(r.total, r.mirrored + r.partial + r.notMirrored, c.label)
            assertEquals(c.attributes.map { it.path }.sorted(), r.entries.map { it.path }, c.label)
            val json = r.toJson()
            val summary = json.require("summary").obj()
            assertEquals(r.notMirrored.toLong(), (summary["notMirrored"] as JsonInt).value)
            assertTrue("${r.notMirrored} not mirrored" in r.summary, r.summary)
        }
    }

    @Test
    fun theDranglerCapsulesAreMostlyNotMirrorable() {
        val red = build(fixtures.getValue("drangler-red"), "npm test")
        assertEquals("node:24.21.0-bookworm-slim", red.image)
        assertEquals(30, red.total)
        val hosted = red.entries.filter { it.path.startsWith("ci.") }
        assertEquals(7, hosted.size)
        assertTrue(hosted.all { it.status == Status.NOT_MIRRORED })
        assertEquals(Status.MIRRORED, red.status("tool.node.version"))
        assertEquals(Status.MIRRORED, red.status("tool.npm.version"))
        assertEquals(Status.NOT_MIRRORED, red.status("deps.wrangler.version"))
        assertEquals(Status.NOT_MIRRORED, red.status("os.release.name"))
        assertTrue(red.notMirrored > red.mirrored + red.partial, red.summary)
        assertEquals(
            "30 attributes: 2 mirrored, 0 partially mirrored, 28 not mirrored; " +
                "base image node:24.21.0-bookworm-slim",
            red.summary,
        )
        val green = build(fixtures.getValue("drangler-green"), "npm test")
        assertEquals("ubuntu:24.04", green.image)
        assertTrue("distribution attributes pick the base" in green.basis)
        assertEquals(Status.MIRRORED, green.status("os.release.name"))
        assertEquals(Status.PARTIAL, green.status("os.version"))
    }

    @Test
    fun theGeneratedFilesAreIdenticalOnEveryTarget() {
        val actual = golden.keys.associateWith { name ->
            val files = build(fixtures.getValue(name), "npm test").files()
            listOf("Dockerfile", "run.sh", "manifest.json").map { Sha256.hex(files.getValue(it)) }
        }
        assertEquals(golden, actual)
    }

    @Test
    fun theOutputDoesNotDependOnAttributeOrder() {
        val c = fixtures.getValue("node")
        val shuffled = Capsule(c.label, c.attributes.reversed())
        assertEquals(build(c).files(), build(shuffled).files())
        assertEquals(build(c).files(), build(c).files())
    }

    @Test
    fun theManifestIsCanonicalJsonThatNamesEveryAttribute() {
        val r = build(fixtures.getValue("node"), "npm test")
        val text = r.files().getValue("manifest.json")
        assertTrue(text.endsWith("}\n"))
        val parsed = CanonicalJson.parse(text).obj()
        assertEquals(text.trimEnd('\n'), CanonicalJson.encode(parsed))
        assertEquals("1", CanonicalJson.encode(parsed.require("schema")))
        assertEquals("npm test", parsed.require("command").string())
        val image = parsed.require("image").obj()
        assertEquals("tag", image.require("pinned").string())
        assertEquals(r.image, image.require("ref").string())
        assertTrue("tool.node.version 22.12.0" in image.require("basis").string())
    }

    @Test
    fun theBundleListsWhatChangesWithTheBaseImage() {
        val r = build(fixtures.getValue("node"), "npm test")
        val attrs = r.bundleAttributes
        for (p in listOf(
            "os.release.ID",
            "tool.libc.family",
            "tool.libc.version",
            "tool.git.version",
            "tool.npm.version",
        )) {
            assertTrue(p in attrs, p)
        }
        assertFalse("tool.node.version" in attrs)
        assertTrue("tzdata" in r.bundleOthers && "ca-certificates" in r.bundleOthers)
        assertTrue("bundled npm" in r.bundleOthers && "dash as /bin/sh" in r.bundleOthers)
        val java = build(fixtures.getValue("jvm"), "mvn test")
        assertTrue("bundled cacerts" in java.bundleOthers)
        assertFalse("bundled npm" in java.bundleOthers)
        assertEquals(attrs.size, r.entries.count { it.bundle })
    }

    @Test
    fun digestsPinTheImageAndUnusedOnesAreListed() {
        val digest = "sha256:" + "ab".repeat(32)
        val other = "sha256:" + "cd".repeat(32)
        val c = capsule("tool.node.version" to "22.12.0")
        val tag = "node:22.12.0-bookworm-slim"
        val r = build(c, "node a.js", mapOf(tag to digest, "node:other" to other))
        assertEquals("$tag@$digest", r.image)
        assertTrue("FROM $tag@$digest\n" in r.dockerfile)
        assertEquals(listOf("node:other"), r.unusedDigests)
        val json = r.toJson().require("image").obj()
        assertEquals("digest", json.require("pinned").string())
        assertTrue("Digests not used: node:other" in ReproduceRender.render(r, Detail.DETAIL))
        assertTrue("Pinned by tag only" in ReproduceRender.render(build(c), Detail.DETAIL))
        assertFalse("Pinned by tag only" in ReproduceRender.render(r, Detail.DETAIL))
    }

    @Test
    fun aBadDigestOrAnEmptyCommandIsRefused() {
        val c = capsule("tool.node.version" to "22.12.0")
        val bads = listOf("sha256:abc", "md5:" + "a".repeat(32), "sha256:" + "AB".repeat(32), "")
        for (bad in bads) {
            val result = Reproduce.synthesize(c, "x", mapOf("node:1" to bad))
            val refused = assertIs<ReproduceResult.Refused>(result)
            assertEquals(ReproduceProblem.BadDigest("node:1", bad), refused.problem)
            assertTrue("sha256:<64 hex digits>" in refused.problem.message())
        }
        for (empty in listOf("", "   ", "\n\t")) {
            val refused = assertIs<ReproduceResult.Refused>(Reproduce.synthesize(c, empty))
            assertEquals(ReproduceProblem.EmptyCommand, refused.problem)
            assertEquals("the test command is empty", refused.problem.message())
        }
    }

    @Test
    fun theRunScriptBuildsTheImageAndRunsTheCommandInTheWorkspace() {
        val r = build(fixtures.getValue("jvm"), "./gradlew test")
        val tag = "drift-reproduce:" + Sha256.hex(r.dockerfile).take(12)
        val lines = r.runScript.lines()
        assertEquals("#!/bin/sh", lines[0])
        assertEquals("set -eu", lines[2])
        assertTrue("image=$tag" in lines)
        assertTrue(lines.any { it.startsWith("docker build --platform linux/amd64 --tag ") })
        assertTrue(lines.any { it == "\t--volume \"\$PWD:/work\" \\" })
        assertTrue(lines.any { it == "\t--user \"\$(id -u):\$(id -g)\" \\" })
        assertTrue(lines.any { it == "\t--env 'HOME=/tmp' \\" })
        assertTrue(lines.any { it == "\t--memory 2g \\" })
        assertTrue(lines.any { it == "\t--cpus 2 \\" })
        assertTrue(lines.any { it == "\t--ulimit nofile=1024:4096 \\" })
        assertTrue(lines.any { it == "\t\"\$@\" \\" })
        assertEquals("\t\"\$image\" sh -c './gradlew test'", lines[lines.size - 2])
        assertTrue(r.dockerfile.endsWith("WORKDIR /work\n"))
        assertTrue(
            r.dockerfile.lines()[0].startsWith(
                "# drift reproduce: capsule ${fixtures.getValue("jvm").hash()}",
            ),
        )
    }

    @Test
    fun theThreeRenderingsGrowAndStayWithinTheWidth() {
        val r = build(fixtures.getValue("drangler-red"), "npm test")
        val summary = ReproduceRender.render(r, Detail.SUMMARY)
        val detail = ReproduceRender.render(r, Detail.DETAIL)
        val full = ReproduceRender.render(r, Detail.FULL)
        assertTrue(summary.replace("\n  ", " ").startsWith("${r.summary}\n"))
        assertTrue("This is not the original environment" in summary.replace("\n  ", " "))
        assertTrue(detail.startsWith(summary))
        assertTrue(full.startsWith(detail.substringBefore("A different base image")))
        assertTrue(summary.length < detail.length && detail.length < full.length)
        assertTrue("Not mirrored (28), by reason:" in detail)
        assertTrue("ci.provisioner.build-date" in detail)
        assertTrue("Mirrored (2):" in full && "Conventions:" in full)
        for (text in listOf(summary, detail, full)) {
            assertTrue(text.lines().all { it.length <= 100 && it.all { c -> c.code < 128 } })
        }
        val clean = build(capsule("tool.node.version" to "22.12.0"))
        assertFalse("not the original environment" in ReproduceRender.render(clean, Detail.SUMMARY))
    }

    @Test
    fun theLabelOfAnOutputCarriesNoSecret() {
        val label = "run " + "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2"
        val r = build(Capsule(label, emptyList()))
        assertFalse("ghp_" in r.toJson().toString())
        assertNotNull(r.label)
        assertEquals("run <redacted>", r.label)
    }
}
