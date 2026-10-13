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
}
