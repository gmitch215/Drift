package dev.gmitch215.drift

import dev.gmitch215.drift.ci.JUnitCheck
import dev.gmitch215.drift.ci.JUnitStatus
import dev.gmitch215.drift.ci.JUnitXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JUnitXmlTest {
    @Test
    fun rendersOneTestcasePerCheckWithFailureAndSkipElements() {
        val xml = JUnitXml.render(
            "drift verify",
            listOf(
                JUnitCheck("tar", JUnitStatus.PASS, "PASS tar: 3 entries"),
                JUnitCheck("manifest", JUnitStatus.FAIL, "bad"),
                JUnitCheck("posteriors", JUnitStatus.SKIP, "ingested"),
            ),
        )
        val expected = listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<testsuites>",
            "  <testsuite name=\"drift verify\" tests=\"3\" failures=\"1\" errors=\"0\" " +
                "skipped=\"1\">",
            "    <testcase name=\"tar\" classname=\"drift verify\"/>",
            "    <testcase name=\"manifest\" classname=\"drift verify\">" +
                "<failure message=\"bad\"/></testcase>",
            "    <testcase name=\"posteriors\" classname=\"drift verify\">" +
                "<skipped message=\"ingested\"/></testcase>",
            "  </testsuite>",
            "</testsuites>",
        ).joinToString("\n", postfix = "\n")
        assertEquals(expected, xml)
    }

    @Test
    fun escapesMarkupAndReplacesControlCharactersInEveryValue() {
        val message = "a & b < c > d \" e\tf\ng\rh\u0001i"
        val xml = JUnitXml.render("s&t", listOf(JUnitCheck("x<y", JUnitStatus.FAIL, message)))
        assertTrue("<testsuite name=\"s&amp;t\"" in xml, xml)
        assertTrue("name=\"x&lt;y\"" in xml, xml)
        val replaced = "h${Char(0xFFFD)}i"
        assertTrue(
            "message=\"a &amp; b &lt; c &gt; d &quot; e&#9;f&#10;g&#13;$replaced\"" in xml,
            xml,
        )
        assertFalse("\u0001" in xml)
    }

    @Test
    fun anEmptyCheckListIsAValidEmptySuite() {
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n" +
                "  <testsuite name=\"drift contract verify\" tests=\"0\" failures=\"0\" " +
                "errors=\"0\" skipped=\"0\">\n  </testsuite>\n</testsuites>\n",
            JUnitXml.render("drift contract verify", emptyList()),
        )
    }
}
