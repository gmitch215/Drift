package dev.gmitch215.drift.cli

import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.lab.ArmBase
import dev.gmitch215.drift.lab.Arms
import dev.gmitch215.drift.lab.ConfigResult
import dev.gmitch215.drift.lab.RunConfig
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Environment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockerExecutorTest {
	private val node = LabCapsules.node
	private val unlimited = Capsule(
		"unlimited",
		node.attributes.filter { !it.path.startsWith("cgroup.") && !it.path.startsWith("limits.") },
	)

	private fun arm(c: Capsule): RunConfig = (
		Arms.config(
			ArmBase(c, "npm test", Environment.LOCAL),
			emptyList(),
			"arm",
		) as ConfigResult.Built
		).config

	private fun executor(
		commands: Map<List<String>, CommandResult>,
		binary: String? = null,
		memory: String? = null,
	): Pair<DockerExecutor, FakeHost> {
		val host = FakeHost(commands = commands)
		return DockerExecutor(host, "/work space", binary, 120, memory, "8") to host
	}

	private fun script(e: DockerExecutor, a: RunConfig, i: Int) = e.trialScript(a.reproduction!!, i)

	@Test
	fun theTrialScriptCarriesTheCapsuleFlagsTheTrialIndexAndTheBounds() {
		val a = arm(node)
		val text = script(executor(emptyMap()).first, a, 3)
		assertTrue("cd '/work space' && " in text)
		assertTrue("docker run --rm " in text)
		assertTrue("--env DRIFT_TRIAL=3" in text)
		assertTrue("--hostname drift" in text)
		assertTrue("timeout -k 5 120" in text)
		assertTrue("tail -c 4096" in text)
		assertTrue("--name drift-solve-" in text && "-3-\$\$" in text)
		assertTrue(text.contains("--memory 512m"), "the capsule's own limit is kept")
		assertFalse("--memory '" in text, "the cap does not override the capsule's limit")
		assertTrue(a.reproduction!!.tag in text)
	}

	@Test
	fun theCapsAreAddedOnlyWhereTheCapsuleSetsNoLimit() {
		val e = executor(emptyMap(), memory = "10g").first
		val text = script(e, arm(unlimited), 0)
		assertTrue("--memory '10g'" in text, text)
		assertTrue("--cpus '8'" in text, text)
		assertFalse("--memory '" in script(e, arm(node), 0))
	}

	@Test
	fun aTrialIsSplitIntoItsExitCodeAndItsBoundedOutput() {
		val a = arm(node)
		val (e0, _) = executor(emptyMap())
		val s = script(e0, a, 0)
		val key = listOf("sh", "-c", s)
		val (e, _) = executor(mapOf(key to CommandResult(0, "boom: wrong day\nDRIFT_EXIT=1\n")))
		val raw = e.trial(a, 0)
		assertEquals(1, raw.exit)
		assertEquals("boom: wrong day\n", raw.output)
		assertNull(raw.infra)
		assertTrue(raw.seconds >= 0)
	}

	@Test
	fun aTimeoutAndADockerErrorAreInfraNotATestResult() {
		val a = arm(node)
		val key = listOf("sh", "-c", script(executor(emptyMap()).first, a, 0))
		val timeout = executor(mapOf(key to CommandResult(0, "DRIFT_EXIT=124\n"))).first.trial(a, 0)
		assertEquals("timed out after 120 seconds", timeout.infra)
		val daemon = executor(
			mapOf(key to CommandResult(0, "docker: Cannot connect to daemon\nDRIFT_EXIT=125\n")),
		).first.trial(a, 0)
		assertTrue(daemon.infra!!.startsWith("docker could not run the container: docker: Cannot"))
		val notFound = CommandResult(0, "sh: 1: foo: not found\nDRIFT_EXIT=127\n")
		val program = executor(mapOf(key to notFound)).first.trial(a, 0)
		assertNull(program.infra, "127 is the contained command's own exit, not docker's")
		assertEquals(127, program.exit)
	}

	@Test
	fun aMissingOrUnreadableExitStatusIsInfra() {
		val a = arm(node)
		val key = listOf("sh", "-c", script(executor(emptyMap()).first, a, 0))
		val none = executor(mapOf(key to CommandResult(0, "no marker here"))).first.trial(a, 0)
		assertEquals("no exit status came back from docker", none.infra)
		val bad = executor(mapOf(key to CommandResult(0, "DRIFT_EXIT=oops\n"))).first.trial(a, 0)
		assertEquals("unreadable exit status from docker", bad.infra)
		val unrunnable = executor(emptyMap()).first.trial(a, 0)
		assertNotNull(unrunnable.infra, "an unscripted command is exit 127 with no marker")
	}

	@Test
	fun buildsOnceAndReportsTheTailOfAFailedBuild() {
		val a = arm(node)
		val build = listOf("sh", "-c", "${a.reproduction!!.buildLine("arms/x")} 2>&1")
		val (e, host) = executor(mapOf(build to CommandResult(0, "built")))
		assertNull(e.prepare(a, "arms/x"))
		assertNull(e.prepare(a, "arms/y"))
		assertEquals(1, host.ran.size, "the same image is built once")
		val (f, _) = executor(mapOf(build to CommandResult(1, "x".repeat(900) + "no such image")))
		val problem = f.prepare(a, "arms/x")!!
		assertTrue(problem.startsWith("docker build failed (exit 1): "))
		assertTrue(problem.endsWith("no such image"))
		assertTrue(problem.length < 480)
		assertNotNull(f.prepare(a, "arms/x"), "a failed build is retried, not remembered")
	}

	@Test
	fun captureNeedsABinaryAndReturnsTheCapsuleTheContainerPrinted() {
		val a = arm(node)
		assertNull(executor(emptyMap()).first.capture(a))
		val probe = unlimited.canonical()
		val (e0, _) = executor(emptyMap(), binary = "/opt/bin/drift")
		val line = a.reproduction!!.runLine(
			listOf("--hostname drift", "--volume '/opt/bin/drift':/opt/drift:ro"),
			"/opt/drift capture --label 'arm' --tools --probes",
		)
		val key = listOf("sh", "-c", "cd '/work space' && $line 2>/dev/null")
		val printed = CommandResult(0, probe + "\n")
		val (e, _) = executor(mapOf(key to printed), binary = "/opt/bin/drift")
		assertEquals(unlimited.hash(), e.capture(a)!!.hash())
		val (garbled, _) = executor(
			mapOf(key to CommandResult(0, "not json")),
			binary = "/opt/bin/drift",
		)
		assertNull(garbled.capture(a))
		val (failed, _) = executor(mapOf(key to CommandResult(3, probe)), binary = "/opt/bin/drift")
		assertNull(failed.capture(a))
		assertNull(e0.capture(a), "an unscripted capture command fails")
	}
}
