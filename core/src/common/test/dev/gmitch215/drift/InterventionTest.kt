package dev.gmitch215.drift

import dev.gmitch215.drift.fixtures.LabFixtures
import dev.gmitch215.drift.fixtures.RunFixtures
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.lab.ArmBase
import dev.gmitch215.drift.lab.ArmDiff
import dev.gmitch215.drift.lab.ArmSet
import dev.gmitch215.drift.lab.ArmSetResult
import dev.gmitch215.drift.lab.Arms
import dev.gmitch215.drift.lab.BundleVerdict
import dev.gmitch215.drift.lab.ConfigResult
import dev.gmitch215.drift.lab.Controllability
import dev.gmitch215.drift.lab.Intervention
import dev.gmitch215.drift.lab.InterventionProblem
import dev.gmitch215.drift.lab.Pairing
import dev.gmitch215.drift.lab.Reproduce
import dev.gmitch215.drift.lab.ReproduceResult
import dev.gmitch215.drift.lab.RunConfig
import dev.gmitch215.drift.lab.SplitResult
import dev.gmitch215.drift.lab.Splits
import dev.gmitch215.drift.lab.Status
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.plan.Environment
import dev.gmitch215.drift.plan.InterventionClass
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterventionTest {
	private val node = Capsule.parse(LabFixtures.text("node.json"))
	private val red = Capsule.parse(RunFixtures.text("capsules/36995781138.json"))
	private val local = ArmBase(node, "npm test", Environment.LOCAL)
	private val ci = ArmBase(red, "npm test", Environment.CI)

	private fun config(base: ArmBase, vararg i: Intervention): RunConfig =
		assertIs<ConfigResult.Built>(Arms.config(base, i.toList(), "t")).config

	private fun refusal(base: ArmBase, vararg i: Intervention): InterventionProblem =
		assertIs<ConfigResult.Refused>(Arms.config(base, i.toList(), "t")).problem

	private fun pair(base: ArmBase, left: List<Intervention>, right: List<Intervention>): ArmSet =
		assertIs<ArmSetResult.Built>(Arms.pair(base, left, right)).arms

	private fun manifest(c: Capsule, command: String = "npm test") =
		assertIs<ReproduceResult.Built>(Reproduce.synthesize(c, command)).reproduction

	// #region interventions

	private class Case(
		val name: String,
		val intervention: Intervention,
		val edited: List<String>,
		val check: (RunConfig) -> Unit,
	)

	private val flags = { c: RunConfig -> c.reproduction!!.flags }
	private val dockerfile = { c: RunConfig -> c.reproduction!!.dockerfile }

	private val cases = listOf(
		Case("set-env", Intervention.SetEnv("FOO", "bar"), listOf("env.FOO")) {
			assertTrue("--env 'FOO=bar'" in flags(it))
			assertEquals(
				Status.MIRRORED,
				it.reproduction!!.entries.single { e ->
				e.path == "env.FOO"
			}.status,
			)
		},
		Case("unset-env", Intervention.UnsetEnv("LANG"), listOf("env.LANG")) {
			assertFalse(flags(it).any { f -> f.startsWith("--env 'LANG=") })
		},
		Case("set-locale", Intervention.SetLocale("LC_ALL", "fr_FR.UTF-8"), listOf("env.LC_ALL")) {
			assertTrue("--env 'LC_ALL=fr_FR.UTF-8'" in flags(it))
			assertTrue("echo 'fr_FR.UTF-8 UTF-8' >> /etc/locale.gen" in dockerfile(it))
		},
		Case("set-timezone", Intervention.SetTimezone("Europe/Paris"), listOf("env.TZ")) {
			assertTrue("--env 'TZ=Europe/Paris'" in flags(it))
			assertFalse("--env 'TZ=Asia/Tokyo'" in flags(it))
		},
		Case("set-cpu-limit", Intervention.SetCpuLimit.ofMillis(2500), listOf("cgroup.cpu.max")) {
			assertTrue("--cpus 2.5" in flags(it))
			assertEquals(
				"250000 100000",
				it.capsule.attributes.single { a ->
				a.path == "cgroup.cpu.max"
			}.value,
			)
		},
		Case(
			"set-memory-limit",
			Intervention.SetMemoryLimit.ofBytes(1L shl 30),
			listOf("cgroup.memory.max"),
		) {
			assertTrue("--memory 1g" in flags(it))
			assertFalse("--memory 512m" in flags(it))
		},
		Case("set-ulimit", Intervention.SetUlimit("nofile", "4096:4096"), listOf("limits.nofile")) {
			assertTrue("--ulimit nofile=4096:4096" in flags(it))
		},
		Case(
			"set-runtime",
			Intervention.SetRuntime("node", "20.11.1"),
			listOf("tool.node.version"),
		) {
			assertTrue("FROM node:20.11.1-bookworm-slim" in dockerfile(it))
		},
		Case("set-os", Intervention.SetOs("debian", "11"), listOf("os.release.VERSION_ID")) {
			assertTrue("FROM node:22.12.0-bullseye-slim" in dockerfile(it))
		},
		Case("add-flag", Intervention.SetFlag("--bail", true), listOf("arm.flag:--bail")) {
			assertEquals("npm test --bail", it.command)
			assertTrue(it.reproduction!!.runScript.contains("sh -c 'npm test --bail'"))
		},
		Case(
			"set-file",
			Intervention.SetFile("conf/ci.json", "{}"),
			listOf("arm.file:conf/ci.json"),
		) {
			assertEquals(mapOf<String, String?>("conf/ci.json" to "{}"), it.files)
			assertTrue(it.extras.getValue("arm.file:conf/ci.json").startsWith("sha256:"))
		},
		Case(
			"pin-dependency",
			Intervention.PinDependency("vitest", "3.0.0"),
			listOf("deps.vitest.version"),
		) {
			assertEquals(
				"3.0.0",
				it.capsule.attributes.single { a ->
				a.path == "deps.vitest.version"
			}.value,
			)
		},
	)

	@Test
	fun everyInterventionTypeReachesTheGeneratedContainerAndRecordsWhatItChanged() {
		for (c in cases) {
			val config = config(local, c.intervention)
			assertEquals(mapOf(c.intervention.id to c.edited), config.edited, c.name)
			c.check(config)
			val again = config(local, c.intervention)
			assertEquals(config.reproduction!!.files(), again.reproduction!!.files(), c.name)
		}
		assertEquals(
			setOf(
				"set-env", "unset-env", "set-locale", "set-timezone", "set-cpu-limit",
				"set-memory-limit", "set-ulimit", "set-runtime", "set-os", "set-flag", "set-file",
				"pin-dependency",
			),
			cases.map { it.intervention.type }.toSet(),
		)
	}

	@Test
	fun removingAFlagAndDeletingAFileAreInterventionsToo() {
		val flagged = ArmBase(node, "npm test --bail --silent", Environment.LOCAL)
		val removed = config(flagged, Intervention.SetFlag("--bail", false))
		assertEquals("npm test --silent", removed.command)
		assertEquals(listOf("arm.flag:--bail"), removed.edited.values.single())
		val deleted = config(local, Intervention.SetFile("old.lock", null))
		assertEquals(mapOf<String, String?>("old.lock" to null), deleted.files)
		assertEquals("deleted", deleted.extras.getValue("arm.file:old.lock"))
	}

	@Test
	fun aStepExistsOnlyInAWorkflow() {
		val step = Intervention.ToggleStep("Set up Node", true)
		val problem = assertIs<InterventionProblem.NotApplicable>(refusal(local, step))
		assertEquals(Environment.LOCAL, problem.environment)
		val config = config(ci, step)
		assertNull(config.reproduction)
		assertEquals(mapOf("step:Set up Node" to "present"), config.extras)
		assertEquals(listOf("add the workflow step Set up Node"), config.edits)
	}

	@Test
	fun anInterventionTheContainerCannotCarryIsRefusedWithTheManifestReason() {
		val cases = mapOf(
			Intervention.SetEnv("HOME", "/x") to "host identity",
			Intervention.SetEnv("TOKEN", "x") to "redacted",
			Intervention.SetRuntime("python", "3.12.1") to "second toolchain",
			Intervention.SetRuntime("bun", "1.2.0") to "no official image",
			Intervention.SetOs("ubuntu", "24.04") to "the image is debian",
			Intervention.SetUlimit("bogus", "1") to "docker has no --ulimit named bogus",
			Intervention.SetMemoryLimit.ofBytes(1024) to "6 MiB",
		)
		for ((i, why) in cases) {
			val problem = assertIs<InterventionProblem.NotMirrored>(refusal(local, i), i.id)
			assertTrue(why in problem.detail, "${i.id}: ${problem.detail}")
			assertTrue(problem.message().startsWith(i.id))
		}
	}

	@Test
	fun anInterventionThatChangesNothingIsRefused() {
		val same = refusal(local, Intervention.SetTimezone("Asia/Tokyo"))
		assertIs<InterventionProblem.NoEffect>(same)
		val absent = refusal(local, Intervention.SetFlag("--bail", false))
		assertIs<InterventionProblem.NoEffect>(absent)
		assertIs<InterventionProblem.NoEffect>(refusal(local, Intervention.UnsetEnv("NOPE")))
		val partialTag = Capsule.parse(LabFixtures.text("jvm.json"))
		val java = ArmBase(partialTag, "mvn test", Environment.LOCAL)
		val tag = assertIs<InterventionProblem.NoEffect>(
			refusal(java, Intervention.SetRuntime("java", "17.0.12")),
		)
		assertTrue("equals the control" in tag.why)
		assertTrue(tag.message().contains("changes nothing"))
	}

	@Test
	fun anEnvVariableOutsideTheAllowlistReachesTheControlOnlyWhenTheBaseNamesIt() {
		val unset = Intervention.UnsetEnv("NODE_ENV")
		val unnamed = assertIs<InterventionProblem.NotMirrored>(refusal(local, unset))
		assertEquals("not allowlisted", unnamed.detail)
		val named = local.copy(envNames = setOf("NODE_ENV"))
		val control = config(named)
		assertTrue("--env 'NODE_ENV=production'" in control.reproduction!!.flags)
		assertFalse("--env 'NODE_ENV=production'" in config(named, unset).reproduction!!.flags)
	}

	@Test
	fun invalidInterventionsAreRefusedBeforeAnythingIsBuilt() {
		val bad = listOf(
			Intervention.SetEnv("1BAD", "x"),
			Intervention.UnsetEnv("a b"),
			Intervention.SetLocale("TZ", "UTC"),
			Intervention.SetTimezone(" "),
			Intervention.SetCpuLimit("fast"),
			Intervention.SetMemoryLimit("lots"),
			Intervention.SetUlimit("No File", "1"),
			Intervention.SetUlimit("nofile", " "),
			Intervention.SetRuntime("Node", "1"),
			Intervention.SetRuntime("node", ""),
			Intervention.SetOs("", "11"),
			Intervention.SetFlag("bail", true),
			Intervention.SetFile("/etc/passwd", "x"),
			Intervention.SetFile("a/../b", "x"),
			Intervention.SetFile("a//b", "x"),
			Intervention.ToggleStep("", true),
			Intervention.PinDependency("", "1"),
		)
		for (i in bad) {
			val problem = refusal(local.copy(environment = Environment.CI), i)
			assertIs<InterventionProblem.Invalid>(problem, i.id)
		}
		val onlyFlag = ArmBase(node, "--bail", Environment.LOCAL)
		val empty = assertIs<InterventionProblem.Invalid>(
			refusal(onlyFlag, Intervention.SetFlag("--bail", false)),
		)
		assertTrue("empty" in empty.why)
		val twice = refusal(local, Intervention.SetTimezone("UTC"), Intervention.SetTimezone("UTC"))
		assertTrue("applied twice" in assertIs<InterventionProblem.Invalid>(twice).why)
	}

	@Test
	fun interventionsSerializeWithTheirParameters() {
		for (c in cases) {
			val json = c.intervention.toJson()
			assertEquals(c.intervention.type, (json["type"] as JsonString).value)
			assertEquals(c.intervention.id, (json["id"] as JsonString).value)
			assertTrue(c.intervention.describe().isNotBlank())
		}
		val file = Intervention.SetFile("a.txt", "hello").toJson()
		assertEquals("hello", (file["content"] as JsonString).value)
		assertNull(Intervention.SetFile("a.txt", null).toJson()["content"])
	}

	// #endregion

	// #region controllability

	@Test
	fun onTheDranglerCapsuleEveryRunnerAttributeIsManualAndTheMirroredOnesAreAutomatic() {
		val manifest = manifest(red)
		val mirrored = manifest.entries.filter { it.status == Status.MIRRORED }.map { it.path }
		assertEquals(listOf("tool.node.version", "tool.npm.version"), mirrored.sorted())
		for (a in red.attributes) {
			val v = Controllability.classify(a.path, Environment.LOCAL, manifest)
			assertTrue(v.reason.isNotBlank() && '\n' !in v.reason, a.path)
			if (a.path.startsWith("ci.")) {
				assertEquals(InterventionClass.MANUAL, v.kind, a.path)
				assertNull(v.via)
			}
			if (a.path in mirrored) {
				assertEquals(InterventionClass.AUTOMATIC_LOCAL, v.kind, a.path)
				assertNotNull(v.via)
			}
		}
		assertEquals(7, red.attributes.count { it.path.startsWith("ci.") })
	}

	@Test
	fun theManifestDemotesWhatAContainerCannotMirror() {
		val manifest = manifest(red)
		fun kind(path: String, env: Environment = Environment.LOCAL) =
			Controllability.classify(path, env, manifest)

		val bun = kind("tool.bun.version")
		assertEquals(InterventionClass.MANUAL, bun.kind)
		assertTrue("no official image is mapped for bun" in bun.reason)
		assertEquals(InterventionClass.AUTOMATIC_CI, kind("tool.bun.version", Environment.CI).kind)
		assertEquals(InterventionClass.AUTOMATIC_LOCAL, kind("deps.wrangler.version").kind)
		assertEquals("pin-dependency", kind("deps.wrangler.version").via)
		val pinned = kind("deps.wrangler.version", Environment.CI)
		assertEquals(InterventionClass.AUTOMATIC_CI, pinned.kind)
		assertEquals(
			InterventionClass.AUTOMATIC_CI,
			kind("step:Docker E2E/Set up Node", Environment.CI).kind,
		)
		assertEquals(InterventionClass.MANUAL, kind("step:Docker E2E/Set up Node").kind)
		assertEquals(InterventionClass.AUTOMATIC_LOCAL, kind("env.NODE_ENV").kind)
	}

	@Test
	fun membersThatOnlyTravelWithAnImageAreManualEverywhere() {
		for (path in listOf("tool.libc.version", "tool.coreutils.flavor", "tool.git.version")) {
			for (env in Environment.entries) {
				val v = Controllability.classify(path, env)
				assertEquals(InterventionClass.MANUAL, v.kind, "$path ${env.id}")
				assertTrue(v.reason.isNotBlank())
			}
		}
		val hostBound = listOf("kernel.release", "cpu.count", "os.name", "hw.cpu.model")
		for (path in hostBound) {
			val v = Controllability.classify(path, Environment.LOCAL)
			assertEquals(InterventionClass.MANUAL, v.kind)
		}
		val os = Controllability.classify("os.release.VERSION_ID", Environment.LOCAL)
		assertEquals(InterventionClass.AUTOMATIC_LOCAL, os.kind)
		assertEquals("set-os", os.via)
	}

	@Test
	fun aHypothesisIsAsControllableAsItsWorstMember() {
		val manifest = manifest(red)
		val mixed = Controllability.hypothesis(
			listOf("tool.node.version", "ci.runner.image.version"),
			Environment.LOCAL,
			manifest,
		)
		assertEquals(InterventionClass.MANUAL, mixed.kind)
		assertEquals("ci.runner.image.version", mixed.path)
		val all = Controllability.hypothesis(
			listOf("tool.node.version", "env.REQUIRE_DOCKER"),
			Environment.LOCAL,
			manifest,
		)
		assertEquals(InterventionClass.AUTOMATIC_LOCAL, all.kind)
		assertTrue("run.sh" in all.reason)
		val ci = Controllability.hypothesis(listOf("deps.husky.version"), Environment.CI)
		assertEquals(InterventionClass.AUTOMATIC_CI, ci.kind)
		assertTrue(Controllability.hypothesis(emptyList(), Environment.CI).controllable)
	}

	@Test
	fun theRunConfigurationItselfIsAlwaysControllable() {
		for (env in Environment.entries) {
			val flag = Controllability.classify("arm.flag:--bail", env)
			assertTrue(flag.controllable)
			assertEquals("set-flag", flag.via)
			assertEquals("set-file", Controllability.classify("arm.file:a", env).via)
		}
		assertFalse(Controllability.classify("component:tzdata", Environment.LOCAL).controllable)
	}

	// #endregion

	// #region bundle rule

	@Test
	fun oneEnvVariableIsIsolatedAndTheMechanismNeedsARuleForThatAttribute() {
		val arms = pair(local, emptyList(), listOf(Intervention.SetTimezone("Europe/Paris")))
		val verdict = assertIs<BundleVerdict.Isolated>(arms.verdict)
		assertEquals("env.TZ", verdict.path)
		assertNull(verdict.mechanism)
		assertEquals(listOf("env.TZ"), arms.diff.changed)
		assertTrue(arms.executable)
		assertEquals(Pairing.CONTROL_TREATMENT, arms.pairing)
		assertEquals(InterventionClass.AUTOMATIC_LOCAL, arms.kind)
		val explained = Arms.verdict(
			arms.diff,
			arms.primaries,
			mapOf("env.TZ" to "a zone change moves date arithmetic", "env.LANG" to "unrelated"),
		)
		assertEquals(
			"a zone change moves date arithmetic",
			(explained as BundleVerdict.Isolated).mechanism,
		)
	}

	@Test
	fun anImageSwapBringsItsCoChangingMembersAndIsABundle() {
		val arms = pair(local, emptyList(), listOf(Intervention.SetRuntime("node", "20.11.1")))
		val verdict = assertIs<BundleVerdict.Bundle>(arms.verdict)
		val components = listOf(
			"component:GNU coreutils", "component:bundled OpenSSL", "component:bundled npm",
			"component:ca-certificates", "component:corepack", "component:dash as /bin/sh",
			"component:glibc", "component:openssl", "component:tzdata",
		)
		val attributes = listOf("os.release.PRETTY_NAME", "tool.git.version", "tool.libc.version")
		assertEquals(components + attributes + "tool.node.version", verdict.members)
		assertEquals(components + attributes, verdict.notIsolated)
		assertEquals(components + attributes, arms.diff.cochanging)
		assertNull(verdict.mechanism)
		assertEquals(listOf("tool.node.version"), arms.primaries)
		val entries = manifest(node).entries
		val mirrored = entries.filter { it.status == Status.MIRRORED }.map { it.path }
		assertTrue(arms.diff.changed.none { it in mirrored && it != "tool.node.version" })
	}

	@Test
	fun aDistributionSwapIsABundleWithTheSameMembers() {
		val arms = pair(local, emptyList(), listOf(Intervention.SetOs("debian", "11")))
		val verdict = assertIs<BundleVerdict.Bundle>(arms.verdict)
		assertTrue("os.release.VERSION_ID" in verdict.members)
		assertTrue("component:tzdata" in verdict.members && "tool.libc.version" in verdict.members)
		assertFalse("os.release.VERSION_ID" in verdict.notIsolated)
		assertTrue(verdict.reason.contains("${verdict.members.size} non-volatile"))
	}

	@Test
	fun aPinOfNpmDoesNotSwapTheImageAndStaysIsolated() {
		val arms = pair(local, emptyList(), listOf(Intervention.SetRuntime("npm", "11.0.0")))
		assertEquals(listOf("tool.npm.version"), arms.diff.changed)
		assertIs<BundleVerdict.Isolated>(arms.verdict)
		val dockerfile = arms.right.reproduction!!.dockerfile
		assertTrue("RUN npm install -g npm@11.0.0" in dockerfile)
	}

	@Test
	fun severalSettingsAreABundleThatListsEveryMember() {
		val arms = pair(
			local,
			emptyList(),
			listOf(Intervention.SetTimezone("UTC"), Intervention.SetEnv("FOO", "1")),
		)
		val verdict = assertIs<BundleVerdict.Bundle>(arms.verdict)
		assertEquals(listOf("env.FOO", "env.TZ"), verdict.members)
		assertEquals(verdict.members, verdict.notIsolated)
	}

	@Test
	fun theSameSettingOnBothArmsLeavesNothingToAttribute() {
		val same = listOf(Intervention.SetFlag("--bail", true))
		val arms = pair(local, same, same)
		assertEquals(Pairing.TREATMENT_AB, arms.pairing)
		assertTrue(arms.diff.changed.isEmpty())
		val verdict = assertIs<BundleVerdict.Empty>(arms.verdict)
		assertTrue("no effect can be attributed" in verdict.reason)
	}

	@Test
	fun twoTreatmentsCompareAgainstEachOther() {
		val arms = pair(
			local,
			listOf(Intervention.SetTimezone("UTC")),
			listOf(Intervention.SetTimezone("Europe/Paris")),
		)
		assertEquals(listOf("env.TZ"), arms.diff.changed)
		assertEquals("env.TZ", assertIs<BundleVerdict.Isolated>(arms.verdict).path)
		assertEquals(listOf("env.TZ"), arms.primaries)
	}

	@Test
	fun volatileAndNoisyAttributesNeverCountTowardTheDiff() {
		val capsule = Capsule(
			"v",
			listOf(
				Attribute("env.A", "1", "t"),
				Attribute("env.B", "1", "t", Stability.VOLATILE),
				Attribute("env.C", "1", "t", Stability.NOISY),
			),
		)
		val other = Capsule(
			"v",
			listOf(
				Attribute("env.A", "2", "t"),
				Attribute("env.B", "2", "t", Stability.VOLATILE),
				Attribute("env.C", "2", "t", Stability.NOISY),
			),
		)
		val measured = Arms.diff(capsule, other)
		assertEquals(listOf("env.A"), measured.changed)
		assertEquals(listOf("env.B", "env.C"), measured.ignored)
		val verdict = Arms.verdict(measured, emptyList())
		assertEquals("env.A", assertIs<BundleVerdict.Isolated>(verdict).path)
		assertTrue("2 volatile or noisy ignored" in verdict.reason)
		val quiet = ArmDiff(emptyList(), listOf("env.B"), emptyList())
		val onlyVolatile = Arms.verdict(quiet, emptyList())
		assertTrue("1 volatile or noisy ignored" in onlyVolatile.reason)
		val setVolatile = ArmBase(capsule, "x", Environment.CI)
		val arms = pair(setVolatile, emptyList(), listOf(Intervention.SetEnv("B", "9")))
		assertEquals(listOf("env.B"), arms.diff.ignored)
		assertIs<BundleVerdict.Empty>(arms.verdict)
	}

	@Test
	fun aSingleChangedAttributeThatIsNotTheTargetIsABundleNotAnIsolation() {
		val diff = ArmDiff(listOf("tool.libc.version"), emptyList(), emptyList())
		val verdict = Arms.verdict(diff, listOf("tool.node.version"))
		assertIs<BundleVerdict.Bundle>(verdict)
		assertEquals(listOf("tool.libc.version"), verdict.members)
		assertEquals(listOf("tool.libc.version"), verdict.notIsolated)
		assertTrue("not the one the experiment set" in verdict.reason)
		assertTrue(Arms.verdict(diff, emptyList()) is BundleVerdict.Isolated)
	}

	@Test
	fun theBundleVerdictSerializesWithItsMembersAndReason() {
		val diff = ArmDiff(listOf("a", "b"), emptyList(), emptyList())
		val json = Arms.verdict(diff, listOf("a"), mapOf("a" to "why")).toJson()
		assertEquals("bundle", (json["kind"] as JsonString).value)
		assertEquals("why", (json["mechanism"] as JsonString).value)
		assertEquals(
			listOf("b"),
			(json["notIsolated"] as JsonArray).items
				.map { (it as JsonString).value },
		)
		assertEquals(
			"empty",
			(
				Arms.verdict(
				ArmDiff(emptyList(), emptyList(), emptyList()),
				emptyList(),
			).toJson()["kind"] as JsonString
			).value,
		)
	}

	@Test
	fun aBundleVerdictNeverListsFewerMembersThanTheDiffHasStaticAttributes() {
		val pool = listOf(
			Intervention.SetEnv("A", "1"),
			Intervention.SetEnv("B", "2"),
			Intervention.SetTimezone("UTC"),
			Intervention.SetLocale("LANG", "C.UTF-8"),
			Intervention.SetCpuLimit.ofMillis(500),
			Intervention.SetMemoryLimit.ofBytes(1L shl 28),
			Intervention.SetUlimit("nofile", "512:512"),
			Intervention.SetRuntime("node", "20.11.1"),
			Intervention.SetOs("debian", "11"),
			Intervention.SetFlag("--bail", true),
			Intervention.SetFile("a.txt", "x"),
			Intervention.PinDependency("vitest", "3.0.0"),
		)
		var built = 0
		for (seed in 1..40) {
			val random = Random(seed)
			val right = pool.filter { random.nextInt(3) == 0 }
			val left = pool.filter { random.nextInt(6) == 0 && it !in right }
			val arms = (Arms.pair(local, left, right) as? ArmSetResult.Built)?.arms ?: continue
			built++
			val distinct = arms.diff.changed.distinct().size
			when (val verdict = arms.verdict) {
				is BundleVerdict.Bundle -> {
					assertTrue(verdict.members.size >= distinct, "seed $seed")
					assertTrue(verdict.notIsolated.all { it in verdict.members }, "seed $seed")
					val expected = arms.diff.changed.distinct().sorted()
					assertEquals(expected, verdict.members, "seed $seed")
				}

				is BundleVerdict.Isolated -> assertEquals(1, distinct, "seed $seed")

				is BundleVerdict.Empty -> assertEquals(0, distinct, "seed $seed")
			}
			assertTrue(arms.diff.cochanging.all { it in arms.diff.changed }, "seed $seed")
			assertEquals(arms.diff.changed, arms.diff.changed.sorted().distinct(), "seed $seed")
		}
		assertTrue(built >= 30, "built $built")
	}

	@Test
	fun theRuleOverRandomDiffsAgreesWithTheCountOfStaticAttributes() {
		for (seed in 1..100) {
			val random = Random(seed)
			val all = (0 until 6).map { "attr.$it" }
			val changed = all.filter { random.nextBoolean() }
			val primaries = all.filter { random.nextInt(4) == 0 }
			val diff = ArmDiff(changed, listOf("noise"), emptyList())
			when (val v = Arms.verdict(diff, primaries)) {
				is BundleVerdict.Empty -> assertEquals(0, changed.size)

				is BundleVerdict.Isolated -> {
					assertEquals(1, changed.size)
					assertTrue(primaries.isEmpty() || v.path in primaries)
				}

				is BundleVerdict.Bundle -> {
					assertEquals(changed, v.members)
					assertTrue(v.members.size >= changed.size)
					val stray = primaries.isNotEmpty() && changed[0] !in primaries
					assertTrue(changed.size > 1 || stray)
				}
			}
		}
	}

	// #endregion

	// #region splits

	@Test
	fun aBundleOfIndependentSettingsIsSplitByDdmin() {
		val arms = pair(
			local,
			emptyList(),
			listOf(
				Intervention.SetTimezone("UTC"),
				Intervention.SetLocale("LANG", "C.UTF-8"),
				Intervention.SetEnv("FOO", "1"),
			),
		)
		val result = assertIs<SplitResult.Proposed>(Splits.propose(local, arms))
		val sets = result.proposals.map { it.members }
		assertEquals(
			listOf(
				listOf("env.FOO"),
				listOf("env.LANG"),
				listOf("env.TZ"),
				listOf("env.LANG", "env.TZ"),
			),
			sets,
		)
		for (p in result.proposals.filter { it.members.size == 1 }) {
			assertIs<BundleVerdict.Isolated>(p.verdict)
		}
		assertIs<BundleVerdict.Bundle>(result.proposals.last().verdict)
		assertTrue(result.fixed.isEmpty())
	}

	@Test
	fun anImageSwapCannotBeSplitBecauseNothingSetsItsMembersAlone() {
		val arms = pair(local, emptyList(), listOf(Intervention.SetRuntime("node", "20.11.1")))
		val result = assertIs<SplitResult.NoSplit>(Splits.propose(local, arms))
		assertTrue(
			result.reason.startsWith("only tool.node.version can be set on its own"),
			result.reason,
		)
		assertTrue("tool.libc.version" in result.reason)
	}

	@Test
	fun theSplitSaysPlainlyWhenNoMemberIsControllableOrThereIsNoBundle() {
		val flagged = pair(local, emptyList(), listOf(Intervention.SetFlag("--bail", true)))
		val none = ArmSet(
			Pairing.CONTROL_TREATMENT,
			InterventionClass.AUTOMATIC_LOCAL,
			flagged.left,
			flagged.right,
			flagged.diff,
			BundleVerdict.Bundle(
				listOf("tool.git.version", "tool.libc.version"),
				listOf("tool.git.version", "tool.libc.version"),
				null,
				"two members",
			),
			listOf("arm.flag:--bail"),
			emptyList(),
		)
		val result = assertIs<SplitResult.NoSplit>(Splits.propose(local, none))
		val head = "none of the 2 members can be set on its own"
		assertTrue(result.reason.startsWith(head), result.reason)
		val isolated = pair(local, emptyList(), listOf(Intervention.SetTimezone("UTC")))
		val noBundle = assertIs<SplitResult.NoSplit>(Splits.propose(local, isolated))
		assertEquals("the verdict is isolated, so there is no bundle", noBundle.reason)
		val ab = pair(
			local,
			listOf(Intervention.SetTimezone("UTC")),
			listOf(Intervention.SetEnv("FOO", "1")),
		)
		assertIs<SplitResult.NoSplit>(Splits.propose(local, ab))
	}

	// #endregion
}
