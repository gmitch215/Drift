package dev.gmitch215.drift

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.fixtures.LabFixtures
import dev.gmitch215.drift.fixtures.RunFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReproduceTest {
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
			"c9c54887153e0fb5ef59fd10e251ad04ed716bc5b6c2745174b725affb986d57",
		),
		"node" to listOf(
			"aca2de505d34df6e337a224db4bccc19fc2d825dc84444fc0ede08026f07e786",
			"0cc24a1ca2fab3c9f36e884bcd862b9b12a133916067c089ae5e2456b327dfe9",
			"d8582885191a353a7afc98003ae49672c258cea94fd4d3eeb31dfb70400568e7",
		),
		"node-alpine" to listOf(
			"67b596a58c76f708d69be46e3a99d00a84e4007142697cae8cc0fb76dfbf1d9c",
			"14e096545016d3c998c10ff787a0ec456f1dab2fbeba38516b090f22d9abd280",
			"33592f2de94cedfb387f9311810f65a72939566ee7183a8dee0da32f3cf7b878",
		),
		"drangler-red" to listOf(
			"31046eebbeec44898217b9f3db0492b6a8d68356eb37d489c9c743914b26b932",
			"890e214f6b39badd45cb7f623448b7c52e1facf0a2cec14f354caa8af0d6ad65",
			"f0d8ce95a4cccf16081a92bdb28dbe5a210b98d3569ce9edd2979964d90785ee",
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
			"node without a distribution",
			listOf("tool.node.version" to "18.0.0"),
			"npm test",
			"node:18.0.0-bookworm-slim",
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
			"php with a distribution suffix",
			listOf("tool.php.version" to "8.1.2-1ubuntu2.14"),
			"phpunit",
			"php:8.1.2-cli-bookworm",
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
			"gcc:13.2.0",
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
		Base("only a distribution: ubuntu", ubuntu2204, "make", "ubuntu:22.04", "distribution"),
		Base("only a distribution: debian", debian12, "make", "debian:12-slim", "distribution"),
		Base("only a distribution: alpine", alpine320, "make", "alpine:3.20", "distribution"),
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
			"published for linux/amd64 only" in alpine.entry("tool.java.version").detail,
		)
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
		)
		for ((name, value, flag) in cases) {
			val r = build(capsule("limits.$name" to value))
			assertEquals(flag, r.flags.firstOrNull { it.startsWith("--ulimit") }, "$name=$value")
			val status = if (flag == null) Status.NOT_MIRRORED else Status.MIRRORED
			assertEquals(status, r.status("limits.$name"), "$name=$value")
		}
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
		assertTrue("RUN apk add --no-cache tzdata\n" in alpine.dockerfile)
		assertFalse("locale" in alpine.dockerfile)
		assertEquals(Status.PARTIAL, alpine.status("env.LANG"))
		assertTrue("musl" in alpine.entry("env.LANG").detail)
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
