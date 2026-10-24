package dev.gmitch215.drift.ci

enum class JUnitStatus { PASS, FAIL, SKIP }

/** One testcase: [name] is the check id; [message] is the failure or skip reason. */
data class JUnitCheck(val name: String, val status: JUnitStatus, val message: String)

object JUnitXml {
    fun render(suite: String, checks: List<JUnitCheck>): String = buildString {
        val failures = checks.count { it.status == JUnitStatus.FAIL }
        val skipped = checks.count { it.status == JUnitStatus.SKIP }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<testsuites>\n")
        append("  <testsuite name=\"${escape(suite)}\" tests=\"${checks.size}\" ")
        append("failures=\"$failures\" errors=\"0\" skipped=\"$skipped\">\n")
        for (check in checks) {
            val attributes = "name=\"${escape(check.name)}\" classname=\"${escape(suite)}\""
            when (check.status) {
                JUnitStatus.PASS -> append("    <testcase $attributes/>\n")

                JUnitStatus.FAIL -> append(
                    "    <testcase $attributes><failure message=\"${escape(check.message)}\"/>" +
                        "</testcase>\n",
                )

                JUnitStatus.SKIP -> append(
                    "    <testcase $attributes><skipped message=\"${escape(check.message)}\"/>" +
                        "</testcase>\n",
                )
            }
        }
        append("  </testsuite>\n")
        append("</testsuites>\n")
    }

    // xml 1.0 cannot reference control characters, so they become U+FFFD
    // tab, LF and CR stay references (raw ones normalise in attributes)
    private fun escape(text: String): String = buildString {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' -> append("&#9;")
                c == '\n' -> append("&#10;")
                c == '\r' -> append("&#13;")
                c < ' ' || c.code == 0xFFFE || c.code == 0xFFFF -> append(Char(0xFFFD))
                else -> append(c)
            }
        }
    }
}
