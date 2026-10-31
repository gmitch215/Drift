package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import dev.gmitch215.drift.cli.why.WhyView
import dev.gmitch215.drift.fixtures.DemoFixtures
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.scan.why.WhyFlow
import dev.gmitch215.drift.why.Explain
import dev.gmitch215.drift.why.Session
import dev.gmitch215.drift.why.Sides

private const val ERROR = "TypeError: Object.groupBy is not a function"
private const val SAVED = "there.json"

private const val INTRO = "A script calls Object.groupBy. It passes on Node 22 and fails on " +
    "Node 20 with\n$ERROR\n\n" +
    "Every input below is a recording of real runs in the node:20-slim and node:22-slim\n" +
    "Docker images on 2026-10-31. Drift reads the recordings the way it reads your own\n" +
    "machines, so this demo needs no repository, no Docker and no network."

private const val STEP2 = "Step 2: the machine where it fails\n" +
    "Step 1 asked for a capture of the failing machine. This is that check, with the\n" +
    "recording of the failing machine as its output:\n"

private const val STEP3 = "Step 3: the machine where it works\n" +
    "Now compare. The recording of the working machine stands in for this machine, and\n" +
    "$SAVED is the file from the failing one:\n" +
    "\$ drift why --save $SAVED\n"

private const val CLOSE = "Next: drift why with your own error text, or drift why issue.md " +
    "with a bug report.\nIn the browser: https://drift.gmitch215.dev/atlas\n" +
    "The guide is docs/why.md."

class DemoCommand : CliktCommand(name = "demo") {
    override fun help(context: Context) =
        "Replay one recorded failure through drift why, offline, to see what Drift asks for " +
            "and how it answers"

    override fun helpEpilog(context: Context) = examples("demo")

    override fun run() {
        echo(INTRO + "\n")
        echo("Step 1: only the error text\n\$ drift why --error \"$ERROR\"\n")
        val first = WhyFlow.report(WhyView.ERROR_TEXT_FLOW, ERROR, true, WhyView.ERROR_ONLY)
        echo(first.view.text(), trailingNewline = false)
        val session = Session.parse(checkNotNull(first.session).text())!!
        val ask = session.open.first().ask

        echo("\n" + STEP2 + "\$ ${ask.piped()}\n")
        val fails = DemoFixtures.text("fails.json")
        val reply = WhyFlow.reply(session, fails)
        echo((reply as WhyFlow.Turn.Shown).view.text(), trailingNewline = false)

        echo("\n" + STEP3 + "\$ drift why --there $SAVED --error \"$ERROR\"\n")
        val works = Capsule.parse(DemoFixtures.text("works.json")).copy(label = "here")
        val there = Capsule.parse(fails).copy(label = SAVED)
        val failure = WhyFlow.symptoms(ERROR)
        val answer = Explain.run(
            WhyView.compareInputs(
                works,
                there,
                Sides.HERE_THERE,
                failure,
                { null },
                WhyView.errorHint(SAVED),
                emptyList(),
                Explain.LOOKED_AT,
            ),
        )
        val checks = WhyView.openChecks(answer)
        val flow = WhyView.savedCaptureFlow(SAVED)
        echo(WhyView.view(flow, answer, checks, null, null).text(), trailingNewline = false)
        echo("\n" + CLOSE)
    }
}
