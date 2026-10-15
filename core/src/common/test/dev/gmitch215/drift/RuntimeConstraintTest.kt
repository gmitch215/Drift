package dev.gmitch215.drift

import dev.gmitch215.drift.diff.Version
import dev.gmitch215.drift.upgrade.Dialect
import dev.gmitch215.drift.upgrade.RuntimeConstraint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuntimeConstraintTest {
    private fun allows(dialect: Dialect, text: String, version: String): Boolean {
        val constraint = assertNotNull(RuntimeConstraint.parse(text, dialect), text)
        return constraint.allows(assertNotNull(Version.parse(version)))
    }

    private fun check(dialect: Dialect, text: String, yes: List<String>, no: List<String>) {
        for (v in yes) assertEquals(true, allows(dialect, text, v), "$text allows $v")
        for (v in no) assertEquals(false, allows(dialect, text, v), "$text excludes $v")
    }

    @Test
    fun composerReadsTheShapesRealLocksCarry() {
        val php = Dialect.COMPOSER
        check(
            php,
            "^7.2 || ~8.0, <8.1",
            listOf("7.4.33", "8.0.30"),
            listOf("8.1.0", "8.3.35", "7.1.0"),
        )
        check(php, "^7.4 || ^8.0", listOf("7.4.0", "8.3.1"), listOf("7.3.9", "9.0.0"))
        check(php, "^7.1|^8.0", listOf("8.2.0"), listOf("7.0.9"))
        check(php, ">=7.2.5", listOf("7.2.5", "8.3"), listOf("7.2.4"))
        check(php, ">=7.3 <8.2", listOf("8.1.9"), listOf("8.2.0"))
        check(php, "~7.4.0 || ~8.0.0", listOf("7.4.9", "8.0.1"), listOf("7.3.0", "8.1.0"))
        check(php, "~7.2", listOf("7.9.0"), listOf("8.0.0", "7.1.0"))
        check(php, "7.4.*", listOf("7.4.33"), listOf("7.5.0", "8.0.0"))
        check(php, "^8.0@dev", listOf("8.3"), listOf("7.4"))
        check(php, "^0.3", listOf("0.3.9"), listOf("0.4.0"))
        check(php, ">=7.0 <7.4 || >=8.1", listOf("8.3"), listOf("7.4.1"))
    }

    @Test
    fun npmReadsEnginesRanges() {
        val node = Dialect.NPM
        check(node, ">=6.9.0", listOf("22.1.0"), listOf("6.8.0"))
        check(
            node,
            "^12.20.0 || ^14.13.1 || >=16.0.0",
            listOf("12.22.0", "20.0.0"),
            listOf("13.0.0", "14.0.0"),
        )
        check(node, ">=16 <21", listOf("18.1.0"), listOf("21.0.0"))
        check(node, "20", listOf("20.5.0"), listOf("21.0.0"))
        check(node, "~18.12", listOf("18.12.9"), listOf("18.13.0"))
        check(node, "<=20", listOf("20.9.0"), listOf("21.0.0"))
        check(node, ">20", listOf("21.0.0"), listOf("20.9.0"))
        check(node, "10 - 14", listOf("12.0.0", "14.9.9"), listOf("15.0.0"))
        check(node, "*", listOf("1.0.0"), emptyList())
        check(node, ">= 18.0.0", listOf("18.0.0"), listOf("17.9.0"))
    }

    @Test
    fun pythonReadsPoetryAndPepRanges() {
        val py = Dialect.PYTHON
        check(py, ">=3.8,<4.0", listOf("3.12.1"), listOf("3.7.9", "4.0.0"))
        check(py, "^3.7", listOf("3.13"), listOf("3.6.9", "4.0"))
        check(py, ">=3.6, !=3.7.*", listOf("3.8.0", "3.6.1"), listOf("3.7.4"))
        check(py, "~=3.8", listOf("3.12"), listOf("4.0"))
        check(py, "~3.9", listOf("3.9.7"), listOf("3.10.0"))
        check(py, "==3.11.*", listOf("3.11.4"), listOf("3.12.0"))
        check(py, ">=3.7 || <3.0", listOf("3.12", "2.7"), listOf("3.1"))
    }

    @Test
    fun exactAndOneSidedBoundsFollowTheDialect() {
        val py = Dialect.PYTHON
        check(py, ">3.7", listOf("3.7.1"), listOf("3.7.0"))
        check(py, "<=3.8", listOf("3.8.0", "3.7.9"), listOf("3.8.1"))
        check(py, "==3.8", listOf("3.8.0"), listOf("3.8.1"))
        check(py, "!=3.7", listOf("3.7.1", "3.8"), listOf("3.7.0"))
        check(Dialect.COMPOSER, "7.4", listOf("7.4.0"), listOf("7.4.1"))
        check(Dialect.NPM, ">=1.2 <=1.3", listOf("1.3.9"), listOf("1.4.0", "1.1.9"))
        check(Dialect.NPM, "<1.2", listOf("1.1.9"), listOf("1.2.0"))
        check(Dialect.NPM, "==20", listOf("20.0.0"), listOf("20.1.0"))
    }

    @Test
    fun aPreReleaseTagIsReadAfterAComparisonOperatorOnly() {
        val php = Dialect.COMPOSER
        check(php, ">=1.0.0-alpha4", listOf("1.0.0"), listOf("0.9.0"))
        check(php, "<2.0.0-rc1", listOf("1.9.0"), listOf("2.0.0"))
        check(php, ">1.0.0-beta1", listOf("1.0.0"), listOf("0.9.0"))
        check(php, "<=1.0.0-dev", listOf("0.9.0"), listOf("1.0.0"))
        check(php, "!=1.0.0-dev", listOf("1.0.0"), emptyList())
        check(php, "1.0.0-dev", listOf("1.0.0-dev"), listOf("1.0.0"))
        assertNull(RuntimeConstraint.parse("^1.0.0-alpha", php))
    }

    @Test
    fun aConstraintItDoesNotReadGivesNull() {
        for (text in listOf("", "dev-master", "^8.0 || banana", ">=", "~")) {
            assertNull(RuntimeConstraint.parse(text, Dialect.COMPOSER), text)
        }
    }
}
