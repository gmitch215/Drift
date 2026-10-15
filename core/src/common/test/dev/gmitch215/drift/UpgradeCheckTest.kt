package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.know.paths
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.upgrade.Group
import dev.gmitch215.drift.upgrade.Target
import dev.gmitch215.drift.upgrade.TargetException
import dev.gmitch215.drift.upgrade.Targets
import dev.gmitch215.drift.upgrade.UpgradeCheck
import dev.gmitch215.drift.upgrade.UpgradeReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpgradeCheckTest {
    private fun capsule(vararg attrs: Pair<String, String>) =
        Capsule("today", attrs.map { Attribute(it.first, it.second, "test") })

    private fun targets(text: String) = Targets.resolve(Targets.parseList(text))

    private val php82 =
        """{"all":[{"a":"tool.php.version","ver":"<8.2"},{"b":"tool.php.version","ver":">=8.2"}]}"""
    private val phpFact = """{"all":[{"a":"tool.php.version","ver":"<8.2"},""" +
        """{"b":"tool.php.version","ver":">=8.2"},{"b":"os.arch","present":"true"}]}"""
    private val phpExt = """{"all":[{"a":"tool.php.version","ver":"<8.2"},""" +
        """{"b":"tool.php.version","ver":">=8.2"},{"b":"tool.php.extensions","has":"mcrypt"}]}"""
    private val drupal =
        """{"all":[{"a":"project.composer.drupal/core","ver":"<11"},""" +
            """{"b":"project.composer.drupal/core","ver":">=11"}]}"""
    private val convention = """{"all":[{"a":"project.gradle.wrapper","ver":"<9.0.0"},""" +
        """{"b":"project.gradle.wrapper","ver":">=9.0.0"},""" +
        """{"b":"project.gradle.uses-convention-api","present":"true"}]}"""

    private fun rule(id: String, detect: String, vararg edits: Pair<String, String>) =
        demoRule(detect, "id" to "\"$id\"", "title" to "\"Title of $id\"", *edits)

    private val verified = """[{"text":"t","basis":"verified"}]"""
    private val pending = "\"pending-scanner\""

    private val rules: List<Rule> = listOf(
        rule("b-verified", phpFact, "symptoms" to verified, "severity" to "\"medium\""),
        rule("a-high", phpFact, "severity" to "\"high\""),
        rule("k-version-only", php82, "severity" to "\"high\""),
        rule(
            "l-calls",
            """{"all":[{"a":"tool.php.version","ver":"<8.2"},""" +
                """{"b":"tool.php.version","ver":">=8.2"},""" +
                """{"b":"project.php.calls.utf8_encode","present":"true"}]}""",
            "severity" to "\"low\"",
        ),
        rule("c-may", phpExt, "status" to pending),
        rule("d-unknown", drupal),
        rule(
            "e-same",
            """{"all":[{"a":"tool.node.version","ver":">=18"},""" +
                """{"b":"tool.node.version","ver":">=18"}]}""",
        ),
        rule("f-other", """{"b":"tool.go.version","ver":">=1.20"}"""),
        rule(
            "g-no",
            """{"all":[{"a":"tool.php.version","ver":"<8.2"},""" +
                """{"b":"tool.php.version","ver":">=8.4"}]}""",
        ),
        rule("h-a-only", """{"a":"tool.php.version","ver":"<8.2"}"""),
        rule(
            "i-preexisting",
            """{"any":[{"b":"tool.php.version","ver":">=8.2"},""" +
                """{"b":"tool.node.version","ver":">=18"}]}""",
        ),
    )

    private val today = capsule(
        "tool.php.version" to "8.1.27",
        "tool.node.version" to "18.19.0",
        "os.arch" to "x86_64",
        "project.php.calls.utf8_encode" to "3",
    )
    private val report = UpgradeCheck.run(today, targets("php=8.3,node=22,drupal=11"), rules)

    private fun ids(group: Group) = report.of(group).map { it.rule.id }

    @Test
    fun theGroupsFollowTheVerdictsAndRankBySeverityThenBasis() {
        assertEquals(listOf("a-high", "b-verified", "l-calls"), ids(Group.WILL_BREAK))
        assertEquals(listOf("k-version-only"), ids(Group.VERSION_BOUNDARY))
        assertEquals(listOf("c-may"), ids(Group.MAY_BREAK))
        assertEquals(listOf("d-unknown"), ids(Group.UNKNOWN))
        assertEquals(11, report.rules)
        assertEquals(9, report.touched)
    }

    @Test
    fun aRuleThatReadsOnlyTheTargetVersionNeverLandsInWillBreak() {
        val only = report.of(Group.VERSION_BOUNDARY).single()
        assertEquals(emptyList(), only.evidence)
        assertTrue(report.of(Group.WILL_BREAK).none { it.rule.id == "k-version-only" })
    }

    @Test
    fun aRuleWithAProjectFactOrAnotherEnvironmentKeyIsWillBreakAndNamesIt() {
        val will = report.of(Group.WILL_BREAK).associate { it.rule.id to it.evidence }
        assertEquals(listOf("os.arch"), will["a-high"])
        assertEquals(listOf("project.php.calls.utf8_encode"), will["l-calls"])
    }

    @Test
    fun onlyTheClausesThatMatchedAreEvidence() {
        val any = rule(
            "m-any",
            """{"all":[{"a":"tool.php.version","ver":"<8.2"},{"b":"tool.php.version",""" +
                """"ver":">=8.2"},{"any":[{"b":"tool.node.version","ver":">=99"},""" +
                """{"b":"project.php.calls.utf8_encode","present":"true"}]}]}""",
        )
        val out = UpgradeCheck.run(today, targets("php=8.3"), listOf(any))
        assertEquals(
            listOf("project.php.calls.utf8_encode"),
            out.of(Group.WILL_BREAK).single().evidence,
        )
        val not = rule(
            "n-not",
            """{"all":[{"a":"tool.php.version","ver":"<8.2"},{"b":"tool.php.version",""" +
                """"ver":">=8.2"},{"not":{"b":"project.php.calls.nope","present":"true"}}]}""",
        )
        val negated = UpgradeCheck.run(today, targets("php=8.3"), listOf(not))
        assertEquals(
            listOf("project.php.calls.nope"),
            negated.of(Group.WILL_BREAK).single().evidence,
        )
    }

    @Test
    fun aRuleThatTheTargetsDoNotChangeIsLeftOut() {
        val all = report.findings.map { it.rule.id }
        for (id in listOf("e-same", "f-other", "g-no", "h-a-only", "i-preexisting")) {
            assertTrue(id !in all, id)
        }
    }

    @Test
    fun aFindingNamesTheBoundaryAndTheMissingKeys() {
        val high = report.of(Group.WILL_BREAK).first()
        val crossing = high.crossings.single()
        assertEquals("tool.php.version", crossing.key)
        assertEquals("8.1.27", crossing.from)
        assertEquals("8.3", crossing.to)
        assertEquals(listOf("<8.2"), crossing.was)
        assertEquals(listOf(">=8.2"), crossing.now)
        assertEquals(listOf("tool.php.extensions"), report.of(Group.MAY_BREAK).single().missing)
        val unknown = report.of(Group.UNKNOWN).single()
        assertEquals(listOf("project.composer.drupal/core"), unknown.missing)
        assertEquals(null, unknown.crossings.single().from)
    }

    @Test
    fun aKeyNoScannerEmitsIsUnknownInAPendingRuleAndAbsentInAnActiveOne() {
        val capture = capsule("project.gradle.wrapper" to "8.10.2")
        val goal = targets("gradle=9.0")
        val active = UpgradeCheck.run(capture, goal, listOf(rule("gradle-active", convention)))
        assertEquals(emptyList(), active.findings)
        val held = UpgradeCheck.run(
            capture,
            goal,
            listOf(rule("gradle-pending", convention, "status" to pending)),
        )
        assertEquals(listOf("gradle-pending"), held.of(Group.MAY_BREAK).map { it.rule.id })
        assertEquals(
            listOf("project.gradle.uses-convention-api"),
            held.findings.single().missing,
        )
    }

    @Test
    fun movingBackwardsDoesNotMatchAnUpgradeRule() {
        val newer = capsule("tool.php.version" to "8.3.1")
        val back = UpgradeCheck.run(newer, targets("php=8.1"), rules)
        assertEquals(emptyList(), back.of(Group.WILL_BREAK))
    }

    @Test
    fun theResultDoesNotDependOnRuleOrder() {
        val reversed = UpgradeCheck.run(
            today,
            targets("drupal=11,node=22,php=8.3"),
            rules.reversed(),
        )
        assertEquals(report.json(), reversed.json())
    }

    @Test
    fun jsonCarriesTheGroupsTargetsAndSources() {
        val json = CanonicalJson.parse(report.json()) as JsonObject
        assertEquals(JsonInt(2), json.require("schema"))
        val rows = json.require("targets").array().map { it as JsonObject }
        assertEquals(listOf("drupal", "node", "php"), rows.map { it.require("name").string() })
        assertEquals(3, json.require("will-break").array().size)
        assertEquals(1, json.require("version-boundary").array().size)
        val first = json.require("will-break").array().first() as JsonObject
        assertEquals(listOf(JsonString("os.arch")), first.require("evidence").array())
        assertEquals(1, json.require("may-break").array().size)
        val unknown = json.require("unknown").array().single() as JsonObject
        assertEquals("d-unknown", unknown.require("rule").string())
        val crossing = unknown.require("crossings").array().single() as JsonObject
        assertEquals(JsonNull, crossing.require("from"))
        val source = unknown.require("sources").array().single() as JsonObject
        assertEquals(JsonString("https://example.com/x"), source.require("url"))
    }

    @Test
    fun textGolden() {
        val text = UpgradeCheck.run(
            today,
            targets("php=8.3"),
            listOf(rules[1], rules[4]),
        ).text()
        assertEquals(
            """
            upgrade-check: today
              php  tool.php.version  8.1.27 -> 8.3
            2 of 2 rules read a target key

            Will break (1)
              1. [high, inferred] Title of a-high
                 rule: a-high
                 boundary: tool.php.version 8.1.27 -> 8.3 (rule: was <8.2, now >=8.2)
                 evidence: os.arch
                 mechanism: M.
                 source: https://example.com/x

            May break (1)
              1. [low, inferred] Title of c-may
                 rule: c-may
                 boundary: tool.php.version 8.1.27 -> 8.3 (rule: was <8.2, now >=8.2)
                 needs: tool.php.extensions; the rule is pending-scanner
                 mechanism: M.
                 source: https://example.com/x

            Version boundary crossed (0)
              none

            Unknown (0)
              none

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun theVersionBoundaryGroupSaysWhatItHasAndWhatItLacks() {
        val text = UpgradeCheck.run(today, targets("php=8.3"), listOf(rules[2])).text()
        assertTrue("Version boundary crossed (1)\n${UpgradeReport.VERSION_NOTE}\n" in text, text)
        assertTrue("evidence:" !in text, text)
    }

    @Test
    fun aRuleWhoseSymptomsAreAllRangeLandsInOutsideRangeAndKeepsItsEvidence() {
        val range = rule(
            "r-range",
            phpFact,
            "symptoms" to """[{"text":"t","basis":"range"}]""",
        )
        val result = UpgradeCheck.run(today, targets("php=8.3"), listOf(range, rules[2]))
        assertEquals(listOf("r-range"), result.of(Group.OUTSIDE_RANGE).map { it.rule.id })
        assertEquals(emptyList(), result.of(Group.WILL_BREAK))
        val finding = result.of(Group.OUTSIDE_RANGE).single()
        assertEquals("range", finding.basis.id)
        assertEquals(listOf("os.arch"), finding.evidence)
        val text = result.text()
        assertTrue("Outside the tested range (1)\n${UpgradeReport.RANGE_NOTE}\n" in text, text)
        assertTrue("evidence: os.arch" in text, text)
        val json = CanonicalJson.parse(result.json()) as JsonObject
        val entry = json.require("outside-range").array().single() as JsonObject
        assertEquals(JsonString("range"), entry.require("basis"))
        assertEquals(JsonInt(2), json.require("schema"))
        val plain = UpgradeCheck.run(today, targets("php=8.3"), rules).text()
        assertTrue("Outside the tested range" !in plain, plain)
    }

    @Test
    fun aMoveOutOfTheKotlinCompatibilityTableIsOutsideTheTestedRangeNotWillBreak() {
        val capture = capsule(
            "project.gradle.kotlin.version" to "2.4.20",
            "project.gradle.agp.version" to "9.3.1",
            "project.gradle.wrapper" to "9.6.0",
            "project.gradle.android.compile-sdk" to "37",
        )
        val result = UpgradeCheck.run(capture, targets("agp=9.4.0,gradle=9.5.0"))
        val range = result.of(Group.OUTSIDE_RANGE).map { it.rule.id }
        val agp = "kgp-2-4-20-to-2-4-21-agp-outside-compatibility-table"
        assertTrue(agp in range, range.toString())
        assertTrue(
            result.of(Group.WILL_BREAK).none { it.rule.id.startsWith("kgp-") },
            result.of(Group.WILL_BREAK).toString(),
        )
        assertTrue(result.of(Group.OUTSIDE_RANGE).all { it.basis.id == "range" })
        val inside = UpgradeCheck.run(capture, targets("agp=9.2.0,gradle=9.7.0"))
        assertEquals(emptyList(), inside.of(Group.OUTSIDE_RANGE).map { it.rule.id })
        val sdk = UpgradeCheck.run(
            capsule(
                "project.gradle.agp.version" to "8.6.0",
                "project.gradle.android.compile-sdk" to "35",
            ),
            targets("agp=8.5.0"),
        )
        assertEquals(
            listOf("agp-8-2-to-8-5-compile-sdk-above-34"),
            sdk.of(Group.OUTSIDE_RANGE).map { it.rule.id },
        )
    }

    @Test
    fun theCatalogFlagsWhatAPhpAndDrupalMoveBreaks() {
        val capture = capsule(
            "tool.php.version" to "8.1.27",
            "project.composer.drupal/core" to "10.3.6",
            "project.composer.symfony/http-kernel" to "v6.4.12",
        )
        val result = UpgradeCheck.run(capture, targets("php=8.3,drupal=11,symfony=7.1"))
        assertEquals(Rules.all.size, result.rules)
        val will = result.of(Group.WILL_BREAK).map { it.rule.id }
        val crossed = result.of(Group.VERSION_BOUNDARY).map { it.rule.id }
        assertTrue("php-8-2-dynamic-properties-deprecated" in crossed, crossed.toString())
        assertTrue("drupal-10-to-11-symfony-6-2-to-7-1" in crossed, crossed.toString())
        assertTrue("php-8-2-dynamic-properties-deprecated" !in will, will.toString())
        val may = result.of(Group.MAY_BREAK).associate { it.rule.id to it.missing }
        assertEquals(listOf("project.composer.twig/twig"), may["symfony-7-0-drops-twig-2"])
        assertTrue(result.of(Group.UNKNOWN).isEmpty())
    }

    @Test
    fun aCaptureWithoutTheCurrentVersionIsUnknownNotBroken() {
        val result = UpgradeCheck.run(capsule("os.arch" to "x86_64"), targets("php=8.3"))
        assertTrue(result.of(Group.WILL_BREAK).isEmpty())
        val unknown = result.of(Group.UNKNOWN).map { it.rule.id }
        assertTrue("php-8-2-dynamic-properties-deprecated" in unknown, unknown.toString())
    }

    @Test
    fun aRuleThatMatchesOnlyBecauseTheCurrentValueIsMissingIsUnknown() {
        val bOnly = rule("j-b-only", """{"b":"tool.php.version","ver":">=8.2"}""")
        val bare = capsule("os.arch" to "x86_64")
        val result = UpgradeCheck.run(bare, targets("php=8.3"), listOf(bOnly))
        assertEquals(emptyList(), result.of(Group.WILL_BREAK))
        val unknown = result.of(Group.UNKNOWN).single()
        assertEquals(listOf("tool.php.version"), unknown.missing)
        val known = UpgradeCheck.run(today, targets("php=8.3"), listOf(bOnly))
        assertEquals(emptyList(), known.of(Group.UNKNOWN))
        assertEquals(listOf("j-b-only"), known.of(Group.VERSION_BOUNDARY).map { it.rule.id })
    }

    @Test
    fun everyAliasIsReadByARule() {
        assertEquals(Targets.aliases.keys.sorted(), Targets.aliases.keys.toList())
        for ((name, key) in Targets.aliases) {
            assertTrue(Rules.all.any { key in it.detect.paths() }, "$name -> $key")
        }
    }

    @Test
    fun aliasesAndAttributePathsResolveToKeys() {
        val flag = "project.gradle.properties.android.builtInKotlin"
        val resolved = targets("php=8.3, Drupal=11,jdk=21,$flag=true")
        assertEquals(
            listOf(
                Target("Drupal", "project.composer.drupal/core", "11"),
                Target(flag, flag, "true"),
                Target("jdk", "tool.java.version", "21"),
                Target("php", "tool.php.version", "8.3"),
            ),
            resolved,
        )
    }

    @Test
    fun aFileAndTheListMerge() {
        val file = Targets.parseFile(
            """
            # target stack
            php: '8.2'
            drupal: "11"   # next major
            node: 22

            """.trimIndent(),
        )
        assertEquals(
            listOf("drupal" to "11", "node" to "22", "php" to "8.2"),
            file.sortedBy {
                it.first
            },
        )
        val merged = Targets.resolve(file + Targets.parseList("php=8.3"))
        assertEquals("8.3", merged.single { it.name == "php" }.value)
        assertEquals(3, merged.size)
    }

    @Test
    fun badTargetsAreRefused() {
        val cases = listOf(
            { Targets.parseList("php") },
            { Targets.parseList("=8.3") },
            { Targets.parseList("php=") },
            { Targets.resolve(Targets.parseList("php=latest")) },
            { Targets.resolve(Targets.parseList("rust=1.80")) },
            { Targets.resolve(Targets.parseList("java=21,jdk=17")) },
            { Targets.resolve(listOf("tool.x.y" to "")) },
            { Targets.parseFile("php:\n  - 8.3\n") },
            { Targets.parseFile("- php: 8.3") },
        )
        for (case in cases) assertFailsWith<TargetException> { case() }
        assertEquals(1, Targets.resolve(Targets.parseList("java=21,jdk=21")).size)
        assertEquals(emptyList(), Targets.parseList(""))
    }

    // #region package constraints
    private fun locked(vararg attrs: Triple<String, String, String>) = Capsule(
        "locked",
        attrs.map { Attribute(it.first, it.second, "project:${it.third}") },
    )

    private val prophecy = "^7.2 || ~8.0, <8.1"

    private val composerLock = locked(
        Triple("tool.php.version", "7.4.33", "tool"),
        Triple("project.composer.phpspec/prophecy", "1.12.2", "composer.lock"),
        Triple("project.composer.phpspec/prophecy.requires.php", prophecy, "composer.lock"),
        Triple("project.composer.sebastian/diff", "4.0.4", "composer.lock"),
        Triple("project.composer.sebastian/diff.requires.php", ">=7.3", "composer.lock"),
        Triple("project.composer.old/pkg", "1.0.0", "composer.lock"),
        Triple("project.composer.old/pkg.requires.php", "^5.6", "composer.lock"),
        Triple("project.composer.odd/pkg", "1.0.0", "composer.lock"),
        Triple("project.composer.odd/pkg.requires.php", "dev-master", "composer.lock"),
    )

    @Test
    fun aLockedPackageWhoseConstraintExcludesTheTargetWillBreak() {
        val result = UpgradeCheck.run(composerLock, targets("php=8.3"), emptyList())
        val broken = result.packages.single()
        assertEquals("phpspec/prophecy", broken.name)
        assertEquals("1.12.2", broken.version)
        assertEquals(prophecy, broken.constraint)
        assertEquals("composer", broken.ecosystem)
        assertEquals("composer.lock", broken.file)
        assertEquals("7.4.33", broken.from)
        assertEquals("8.3", broken.to)
    }

    @Test
    fun aPackageThatAlreadyExcludedTheCurrentRuntimeIsNotReported() {
        val names = UpgradeCheck.run(composerLock, targets("php=8.3"), emptyList())
            .packages.map { it.name }
        assertTrue("old/pkg" !in names, names.toString())
    }

    @Test
    fun aConstraintTheParserDoesNotReadIsUncheckedNotBroken() {
        val result = UpgradeCheck.run(composerLock, targets("php=8.3"), emptyList())
        val unread = result.unchecked.single()
        assertEquals("composer.lock", unread.file)
        assertEquals(listOf("odd/pkg dev-master"), unread.packages)
    }

    @Test
    fun noPackageIsCheckedWithoutATargetOnItsRuntime() {
        val result = UpgradeCheck.run(composerLock, targets("node=22"), emptyList())
        assertEquals(emptyList(), result.packages)
        assertEquals(emptyList(), result.unchecked)
    }

    @Test
    fun aCaptureWithoutTheCurrentRuntimeCannotTellBrokenFromAlreadyBroken() {
        val kept = composerLock.attributes.filter { it.path != "tool.php.version" }
        val noPhp = Capsule("no-php", kept)
        val result = UpgradeCheck.run(noPhp, targets("php=8.3"), emptyList())
        assertEquals(emptyList(), result.packages)
        val names = result.unchecked.flatMap { it.packages }
        assertTrue("phpspec/prophecy" in names && "old/pkg" in names, names.toString())
    }

    @Test
    fun npmAndPythonLocksAreCheckedAndRegistryOnlyLocksAreListedUnchecked() {
        val node = UpgradeCheck.run(
            locked(
                Triple("tool.node.version", "18.19.0", "tool"),
                Triple("project.npm.left-pad", "1.3.0", "package-lock.json"),
                Triple("project.npm.left-pad.requires.node", ">=14 <20", "package-lock.json"),
                Triple("project.npm.yarned", "1.0.0", "yarn.lock"),
            ),
            targets("node=22"),
            emptyList(),
        )
        assertEquals(listOf("left-pad"), node.packages.map { it.name })
        assertEquals(listOf("yarn.lock"), node.unchecked.map { it.file })
        val python = UpgradeCheck.run(
            locked(
                Triple("tool.python.version", "3.11.4", "tool"),
                Triple("project.python.numpy", "1.24.0", "poetry.lock"),
                Triple("project.python.numpy.requires.python", ">=3.8,<3.12", "poetry.lock"),
                Triple("project.python.flask", "3.0.3", "requirements.txt"),
            ),
            targets("python=3.12"),
            emptyList(),
        )
        assertEquals(listOf("numpy"), python.packages.map { it.name })
        assertEquals(listOf("requirements.txt"), python.unchecked.map { it.file })
        val jvm = UpgradeCheck.run(
            locked(
                Triple("tool.java.version", "17.0.9", "tool"),
                Triple("project.gradle.org.jetbrains:annotations", "24.0.0", "build.gradle.kts"),
            ),
            targets("java=21"),
            emptyList(),
        )
        assertEquals(listOf("build.gradle.kts"), jvm.unchecked.map { it.file })
    }

    @Test
    fun packagesAppearInTextAndJson() {
        val result = UpgradeCheck.run(composerLock, targets("php=8.3"), emptyList())
        val text = result.text()
        assertTrue("Will break: locked packages (1)" in text, text)
        assertTrue("phpspec/prophecy 1.12.2 (composer, composer.lock)" in text, text)
        assertTrue("requires php $prophecy; php 8.3 is outside it" in text, text)
        assertTrue("Unchecked package constraints (1)" in text, text)
        val json = CanonicalJson.parse(result.json()) as JsonObject
        val row = json.require("will-break-packages").array().single() as JsonObject
        assertEquals("phpspec/prophecy", row.require("package").string())
        assertEquals(prophecy, row.require("constraint").string())
        assertEquals(1, json.require("unchecked-packages").array().size)
    }
    // #endregion
}
