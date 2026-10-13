package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktError
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.know.Learned
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.RuleException
import dev.gmitch215.drift.know.Rules

/** The shipped rules plus those of the learned store in [dir]; a missing store adds none. */
internal fun rulesWithLearned(files: CaseFiles, dir: String?): List<Rule> {
    if (dir == null) return Rules.all
    val text = files.read(Learned.path(dir)) ?: return Rules.all
    return try {
        Rules.all + Learned.parse(text)
    } catch (e: RuleException) {
        throw CliktError("cannot read the learned store ${Learned.path(dir)}: ${e.message}")
    }
}
