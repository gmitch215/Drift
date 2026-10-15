package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.upgrade.Crossing
import dev.gmitch215.drift.upgrade.Group
import dev.gmitch215.drift.upgrade.Target
import dev.gmitch215.drift.upgrade.UpgradeReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpgradeStoryTest {
    private val sample = upgradeSamples().first()
    private val move = "php=8.3,drupal=11,symfony=7.1"

    private val done: UpgradeView
        get() = (UpgradeStory.check(sample.value, move) as UpgradeCheckResult.Done).view

    private fun report(vararg groups: Pair<Group, Int>): UpgradeReport {
        val base = done.report
        val any = base.findings.first()
        return base.copy(findings = groups.flatMap { (g, n) -> List(n) { any.copy(group = g) } })
    }

    @Test
    fun theSampleIsTheRecordedDrupalCapture() {
        assertEquals("drupal-10-php-8-1.json", sample.name)
        assertEquals("drupal-10-site", sample.value.label)
    }

    @Test
    fun theSentenceNamesTheMoveAndCountsTheGroupsAndNothingElse() {
        val head = "moving to Drupal 11, PHP 8.3 and Symfony 7.1."
        assertEquals(
            "1 change will likely break this environment when $head",
            UpgradeStory.headline(report(Group.WILL_BREAK to 1)),
        )
        assertEquals(
            "2 changes will likely break this environment when $head 1 more change may break. " +
                "2 more changes cannot be judged.",
            UpgradeStory.headline(
                report(Group.WILL_BREAK to 2, Group.MAY_BREAK to 1, Group.UNKNOWN to 2),
            ),
        )
        assertEquals(
            "No documented change is known to break this environment when $head " +
                "11 changes apply to these versions; nothing in the capsule shows they are used.",
            UpgradeStory.headline(report(Group.VERSION_BOUNDARY to 11)),
        )
        assertEquals(
            "No documented change is known to break this environment when $head " +
                "2 changes may break.",
            UpgradeStory.headline(report(Group.MAY_BREAK to 2)),
        )
        assertEquals(
            "No documented change is known to break this environment when $head " +
                "3 changes leave a documented compatible range.",
            UpgradeStory.headline(report(Group.OUTSIDE_RANGE to 3)),
        )
        assertEquals(
            "No documented change is known to break this environment when $head",
            UpgradeStory.headline(report()),
        )
    }

    @Test
    fun theRealRunLeadsWithTheGroupCounts() {
        val view = done
        val will = view.report.of(Group.WILL_BREAK).size
        val crossed = view.report.of(Group.VERSION_BOUNDARY).size
        assertTrue(will + crossed >= 1, "the sample move should have a finding")
        assertTrue(will == 0 || view.headline.startsWith("${changesOf(will)} will likely break"))
        assertTrue(crossed == 0 || view.headline.contains("${changesOf(crossed)} apply"))
        assertTrue(view.headline.contains("Drupal 11, PHP 8.3 and Symfony 7.1"))
    }

    private fun changesOf(n: Int) = if (n == 1) "1 change" else "$n changes"

    @Test
    fun targetsReadAsProductNamesAndValues() {
        fun t(name: String, key: String, value: String) = Target(name, key, value)
        assertEquals("PHP 8.3", UpgradeStory.moveTo(listOf(t("php", "tool.php.version", "8.3"))))
        assertEquals(
            "Java 21 and Node.js 22",
            UpgradeStory.moveTo(
                listOf(
                    t("jdk", "tool.java.version", "21"),
                    t("node", "tool.node.version", "22"),
                ),
            ),
        )
        assertEquals(
            "Setting x.y 2",
            UpgradeStory.moveTo(listOf(t("x.y", "x.y", "2"))),
        )
    }

    @Test
    fun keysReadAsTheSettingTheyHold() {
        assertEquals("PHP version", UpgradeStory.keyName("tool.php.version"))
        assertEquals("Drupal version", UpgradeStory.keyName("project.composer.drupal/core"))
        assertEquals("Java version", UpgradeStory.keyName("tool.java.version"))
        assertEquals("Setting x.y", UpgradeStory.keyName("x.y"))
    }

    @Test
    fun everyGroupHasItsOwnFixedWords() {
        val words = Group.entries.map { UpgradeStory.words(it) }
        assertEquals(
            listOf(
                "Will Break",
                "May Break",
                "Version Boundary Crossed",
                "Outside the Tested Range",
                "Unknown",
            ),
            words.map { it.title },
        )
        assertEquals(5, words.map { it.about }.toSet().size)
        assertEquals(5, words.map { it.none }.toSet().size)
    }

    @Test
    fun aBoundaryStatesTheVersionsAndTheRuleCondition() {
        assertEquals(
            "PHP version: 8.1.34 to 8.3. Rule condition after the move: >=8.2. Before: <8.2.",
            UpgradeStory.boundary(
                Crossing("tool.php.version", "8.1.34", "8.3", listOf("<8.2"), listOf(">=8.2")),
            ),
        )
        assertEquals(
            "PHP version: not in the capsule to 8.3. Rule condition after the move: >=8.2.",
            UpgradeStory.boundary(
                Crossing("tool.php.version", null, "8.3", emptyList(), listOf(">=8.2")),
            ),
        )
    }

    @Test
    fun everyFindingCarriesASummarySourceAndSeverityLine() {
        val findings = done.report.findings
        assertTrue(findings.isNotEmpty())
        findings.forEach {
            assertTrue(it.rule.title.isNotBlank())
            assertTrue(it.rule.mechanism.isNotBlank())
            assertTrue(it.rule.provenance.isNotEmpty(), it.rule.id)
            assertTrue(UpgradeStory.severity(it).startsWith("Severity: "))
            assertTrue(it.crossings.isNotEmpty() || it.group != Group.WILL_BREAK)
            assertEquals(it.group == Group.WILL_BREAK, UpgradeStory.evidence(it) != null)
        }
    }

    @Test
    fun missingFactsAreListedAndPendingScannerRulesSayNoScannerCollectsThem() {
        val report = done.report
        report.findings.filter { it.missing.isEmpty() }.forEach {
            assertNull(UpgradeStory.needs(it))
        }
        val needing = report.findings.filter { it.missing.isNotEmpty() }
        assertTrue(needing.isNotEmpty(), "the sample move should leave facts missing")
        needing.forEach {
            val text = assertNotNull(UpgradeStory.needs(it))
            assertTrue(text.startsWith("Drift needs: ${it.missing.joinToString(", ")}."))
            assertEquals(
                it.rule.status == Status.PENDING_SCANNER,
                text.contains("no scanner collects"),
            )
        }
    }

    @Test
    fun badInputGivesASentenceNotAnEmptyResult() {
        val capsule = sample.value
        fun bad(text: String) =
            (UpgradeStory.check(capsule, text) as UpgradeCheckResult.Bad).message
        assertEquals(UpgradeStory.NO_TARGETS, bad(""))
        assertEquals(UpgradeStory.NO_TARGETS, bad(" , "))
        assertEquals("expected name=version, found 'php'", bad("php"))
        assertTrue(bad("cobol=1").startsWith("unknown target 'cobol'"))
        assertEquals("php=banana is not a version", bad("php=banana"))
    }

    @Test
    fun twoNamesForOneSettingAreRefused() {
        val bad = UpgradeStory.check(sample.value, "java=17,jdk=21") as UpgradeCheckResult.Bad
        assertEquals("java and jdk both set tool.java.version", bad.message)
    }

    @Test
    fun presetsAreOfferedOnlyWhenTheCapsuleHoldsTheirSettings() {
        val offered = UpgradeStory.presetsFor(sample.value).map { it.label }
        assertTrue("PHP 8.3" in offered)
        assertTrue("PHP 8.3 and Drupal 11" in offered)
        assertFalse("Gradle 9" in offered)
        assertEquals(emptyList(), UpgradeStory.presetsFor(Capsule("empty", emptyList())))
    }

    @Test
    fun everyPresetIsAValidMove() {
        UpgradeStory.presets.forEach {
            val result = UpgradeStory.check(sample.value, it.text)
            assertTrue(result is UpgradeCheckResult.Done, it.label)
        }
    }

    @Test
    fun theSameInputGivesTheSameReport() {
        assertEquals(done.report.text(), done.report.text())
        assertEquals(done.headline, done.headline)
    }
}
