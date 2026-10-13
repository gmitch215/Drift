package dev.gmitch215.drift.scan

import dev.gmitch215.drift.scan.atlas.Classifications
import dev.gmitch215.drift.triage.AtlasRef
import dev.gmitch215.drift.triage.Claims
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TriageClaimsTest {
    @Test
    fun everyAtlasClaimNamesTwoColumnsThatPrintDifferentResults() {
        val refs = Claims.all.map { it.source }.filterIsInstance<AtlasRef>()
        assertTrue(refs.size >= 3)
        for (ref in refs) {
            val entry = assertNotNull(Classifications.embedded[ref.probe], ref.probe)
            val groups = entry.columns.getValue("2.4.20")
            val left = groups.indexOfFirst { ref.left in it }
            val right = groups.indexOfFirst { ref.right in it }
            assertTrue(left >= 0 && right >= 0, "${ref.label} names a column the probe lacks")
            assertTrue(left != right, "${ref.label}: the two columns print the same result")
        }
    }
}
