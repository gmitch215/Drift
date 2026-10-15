package dev.gmitch215.drift.cli

import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.upgrade.UpgradeReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpgradeCheckCommandTest {
    private fun capsule(label: String, vararg attrs: Pair<String, String>) =
        Capsule(label, attrs.map { Attribute(it.first, it.second, "test") }).canonical()

    private val composer = capsule(
        "composer-d10",
        "tool.php.version" to "8.1.27",
        "project.composer.drupal/core" to "10.3.6",
        "project.composer.symfony/http-kernel" to "v6.4.12",
        "project.composer.twig/twig" to "v3.14.0",
    )
    private val gradle = capsule(
        "gradle-8",
        "tool.java.version" to "11.0.22",
        "project.gradle.wrapper" to "8.10.2",
    )
    private val python = capsule(
        "python-313",
        "tool.python.version" to "3.13.1",
        "project.python.imports.tomllib" to "true",
    )
    private val jdk17 = capsule("jdk-17", "tool.java.version" to "17.0.9")
    private val jdk21 = capsule("jdk-21", "tool.java.version" to "21.0.5")

    private val locked = Capsule(
        "locked-php-74",
        listOf(
            Attribute("tool.php.version", "7.4.33", "tool"),
            Attribute("project.composer.phpspec/prophecy", "1.12.2", "project:composer.lock"),
            Attribute(
                "project.composer.phpspec/prophecy.requires.php",
                "^7.2 || ~8.0, <8.1",
                "project:composer.lock",
            ),
            Attribute("project.npm.left-pad", "1.3.0", "project:yarn.lock"),
        ),
    ).canonical()

    private val host = FakeHost(
        canRun = false,
        files = mapOf(
            "composer.json" to composer,
            "gradle.json" to gradle,
            "python.json" to python,
            "locked.json" to locked,
            "jdk17.json" to jdk17,
            "jdk21.json" to jdk21,
            "stack.yml" to "php: '8.3'\n# the rest\ndrupal: 11\n",
            "bad.json" to "{nope",
        ),
    )

    private fun drift(args: String) = DriftCommand(host).test(args)

    private fun groups(json: String): Map<String, List<String>> {
        val root = CanonicalJson.parse(json) as JsonObject
        val names = listOf("will-break", "may-break", "version-boundary", "unknown")
        return names.associateWith { group ->
            root.require(group).array().map { (it as JsonObject).require("rule").string() }
        }
    }

    @Test
    fun phpAndDrupalMoveListsTheBreaksAsText() {
        val result = drift("upgrade-check composer.json --to php=8.3,drupal=11,symfony=7.1")
        assertEquals(0, result.statusCode, result.stderr)
        val out = result.stdout
        assertTrue(
            out.startsWith(
                "upgrade-check: composer-d10\n" +
                    "  drupal   project.composer.drupal/core          10.3.6 -> 11\n" +
                    "  symfony  project.composer.symfony/http-kernel  v6.4.12 -> 7.1\n" +
                    "  php      tool.php.version                      8.1.27 -> 8.3\n",
            ),
            out,
        )
        val will = out.substringAfter("Will break (").substringBefore("May break (")
        assertFalse("rule: php-8-2-dynamic-properties-deprecated\n" in will, will)
        assertFalse("rule: drupal-10-to-11-symfony-6-2-to-7-1\n" in will, will)
        val crossed = out.substringAfter("Version boundary crossed (").substringBefore("Unknown (")
        assertTrue("rule: php-8-2-dynamic-properties-deprecated\n" in crossed, crossed)
        assertTrue(
            "     boundary: tool.php.version 8.1.27 -> 8.3 (rule: was <8.2.0, now >=8.2.0)\n" in
                crossed,
            crossed,
        )
        assertTrue("rule: drupal-10-to-11-symfony-6-2-to-7-1\n" in crossed, crossed)
        assertTrue(
            "source: https://www.php.net/manual/en/migration82.deprecated.php\n" in crossed,
        )
        assertTrue(UpgradeReport.VERSION_NOTE in crossed, crossed)
        assertTrue(out.contains("\nMay break ("), out)
        assertTrue(out.endsWith("\n"))
    }

    @Test
    fun jsonListsTheSameFindingsInGroups() {
        val result = drift("upgrade-check composer.json --to php=8.3,drupal=11,symfony=7.1 --json")
        assertEquals(0, result.statusCode, result.stderr)
        val g = groups(result.stdout)
        assertFalse("php-8-2-dynamic-properties-deprecated" in g.getValue("will-break"))
        assertTrue("php-8-2-dynamic-properties-deprecated" in g.getValue("version-boundary"))
        assertTrue("drupal-10-to-11-symfony-6-2-to-7-1" in g.getValue("version-boundary"))
        assertTrue("php-8-2-string-case-functions-ascii-only" in g.getValue("may-break"))
        assertEquals(emptyList(), g.getValue("unknown"))
        assertEquals(result.stdout, CanonicalJson.encode(CanonicalJson.parse(result.stdout)) + "\n")
    }

    @Test
    fun aRuleThatReadsAProjectFactIsWillBreakWithTheFactNamed() {
        val hit = drift("upgrade-check python.json --to python=3.10 --json")
        assertEquals(0, hit.statusCode, hit.stderr)
        val rule = "python-3-11-tomllib-added"
        assertTrue(rule in groups(hit.stdout).getValue("will-break"))
        val text = drift("upgrade-check python.json --to python=3.10").stdout
        val block = text.substringAfter("rule: $rule\n").substringBefore("mechanism:")
        assertTrue("     evidence: project.python.imports.tomllib\n" in block, block)
        val bare = capsule("python-313-bare", "tool.python.version" to "3.13.1")
        val files = FakeHost(canRun = false, files = mapOf("bare.json" to bare))
        val miss = DriftCommand(files).test("upgrade-check bare.json --to python=3.10 --json")
        assertFalse(rule in groups(miss.stdout).getValue("will-break"))
    }

    @Test
    fun theSameCaptureGivesTheSameBytes() {
        val args = "upgrade-check composer.json --to drupal=11,php=8.3,symfony=7.1 --json"
        assertEquals(drift(args).stdout, drift(args).stdout)
        val spaced = "upgrade-check composer.json --to symfony=7.1 --to php=8.3,drupal=11 --json"
        assertEquals(drift(args).stdout, drift(spaced).stdout)
    }

    @Test
    fun gradleNineOnAnOldJdkBreaksOnTheJvmFloor() {
        val result = drift("upgrade-check gradle.json --to gradle=9.0 --json")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("gradle-9-0-requires-jvm-17" in groups(result.stdout).getValue("will-break"))
        val moved = drift("upgrade-check gradle.json --to gradle=9.0,java=21 --json")
        assertFalse("gradle-9-0-requires-jvm-17" in groups(moved.stdout).getValue("will-break"))
    }

    private fun hits(json: String) =
        groups(json).getValue("will-break") + groups(json).getValue("version-boundary")

    @Test
    fun jdkMovesBreakInTheirOwnDirectionOnly() {
        val up = hits(drift("upgrade-check jdk17.json --to java=21 --json").stdout)
        val down = hits(drift("upgrade-check jdk21.json --to java=17 --json").stdout)
        assertFalse("jdk-20-cldr-42-space-before-ampm" in up)
        assertTrue("jdk-20-cldr-42-space-before-ampm" in down)
        val to17 = hits(drift("upgrade-check jdk17.json --to jdk=11 --json").stdout)
        assertFalse("jdk-17-strong-encapsulation" in to17)
        val from11 = capsule("jdk-11", "tool.java.version" to "11.0.22")
        val files = FakeHost(canRun = false, files = mapOf("jdk11.json" to from11))
        val result = DriftCommand(files).test("upgrade-check jdk11.json --to jdk=17 --json")
        assertTrue("jdk-17-strong-encapsulation" in hits(result.stdout))
    }

    @Test
    fun aTargetFileAndTheOptionMerge() {
        val file = drift("upgrade-check composer.json --to-file stack.yml --json")
        assertEquals(0, file.statusCode, file.stderr)
        val options = drift("upgrade-check composer.json --to php=8.3,drupal=11 --json")
        assertEquals(options.stdout, file.stdout)
        val over = drift("upgrade-check composer.json --to-file stack.yml --to php=8.4 --json")
        assertTrue("\"to\":\"8.4\"" in over.stdout)
        assertFalse("\"to\":\"8.3\"" in over.stdout)
    }

    @Test
    fun aMissingCurrentVersionIsListedAsUnknown() {
        val result = drift("upgrade-check jdk17.json --to php=8.3 --json")
        assertEquals(0, result.statusCode, result.stderr)
        val g = groups(result.stdout)
        assertEquals(emptyList(), g.getValue("will-break"))
        assertTrue("php-8-2-dynamic-properties-deprecated" in g.getValue("unknown"))
        assertTrue(drift("upgrade-check jdk17.json --to php=8.3").stdout.contains("unknown -> 8.3"))
    }

    @Test
    fun badInputIsRefused() {
        val cases = mapOf(
            "upgrade-check composer.json" to "give the versions to move to",
            "upgrade-check composer.json --to php" to "expected name=version",
            "upgrade-check composer.json --to rust=1.80" to "unknown target 'rust'",
            "upgrade-check composer.json --to php=latest" to "php=latest is not a version",
            "upgrade-check composer.json --to java=21,jdk=17" to "both set tool.java.version",
            "upgrade-check composer.json --to-file nope.yml" to "cannot read target file: nope.yml",
            "upgrade-check missing.json --to php=8.3" to "cannot read capsule file: missing.json",
            "upgrade-check bad.json --to php=8.3" to "invalid capsule file bad.json",
        )
        for ((args, message) in cases) {
            val result = drift(args)
            assertTrue(result.statusCode != 0, args)
            assertTrue(message in result.stderr, "$args: ${result.stderr}")
        }
    }

    @Test
    fun aLockedPackageThatExcludesTheTargetIsListedWithItsConstraint() {
        val text = drift("upgrade-check locked.json --to php=8.3,node=22").stdout
        val block = text.substringAfter("Will break: locked packages (1)\n")
            .substringBefore("\nUnchecked")
        assertTrue("1. phpspec/prophecy 1.12.2 (composer, composer.lock)\n" in block, block)
        assertTrue("requires php ^7.2 || ~8.0, <8.1; php 8.3 is outside it\n" in block, block)
        assertTrue("Unchecked package constraints (1)\n" in text, text)
        assertTrue("1. yarn.lock, node: " in text, text)
        val out = drift("upgrade-check locked.json --to php=8.3 --json").stdout
        val root = CanonicalJson.parse(out) as JsonObject
        val row = root.require("will-break-packages").array().single() as JsonObject
        assertEquals("phpspec/prophecy", row.require("package").string())
        assertEquals("^7.2 || ~8.0, <8.1", row.require("constraint").string())
        assertEquals(emptyList(), root.require("unchecked-packages").array())
    }

    @Test
    fun aTargetInsideTheConstraintListsNoPackage() {
        val out = drift("upgrade-check locked.json --to php=8.0 --json").stdout
        val root = CanonicalJson.parse(out) as JsonObject
        assertEquals(emptyList(), root.require("will-break-packages").array())
    }
}
