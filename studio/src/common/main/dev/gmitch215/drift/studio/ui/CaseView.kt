package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.lab.Archive
import dev.gmitch215.drift.lab.ArchiveOpen
import dev.gmitch215.drift.lab.Check
import dev.gmitch215.drift.lab.CheckStatus
import dev.gmitch215.drift.lab.Verify

/** A `.driftcase` as Studio shows it: what `drift verify` finds, or why the file cannot be read. */
class CaseView(
    val name: String,
    val lines: List<CaseLine>,
    val verdict: String?,
    val story: CaseStory? = null,
) {
    val failure: CaseLine? get() = lines.firstOrNull { it.failed }

    val ok: Boolean get() = failure == null

    companion object {
        fun of(name: String, bytes: ByteArray): CaseView = try {
            val report = Verify.open(bytes)
            CaseView(name, report.checks.map(::line), report.verdict, story(bytes))
        } catch (e: Exception) {
            unreadable(name, e.message ?: e::class.simpleName ?: "unknown error")
        }

        fun unreadable(name: String, reason: String): CaseView =
            CaseView(name, listOf(CaseLine("cannot read $name: $reason", true)), null)

        private fun story(bytes: ByteArray): CaseStory? = try {
            (Archive.open(bytes) as? ArchiveOpen.Opened)?.let { CaseStory.of(it.case) }
        } catch (e: Exception) {
            null
        }

        private fun line(check: Check) = CaseLine(check.line(), check.status == CheckStatus.FAIL)
    }
}

class CaseLine(val text: String, val failed: Boolean)
