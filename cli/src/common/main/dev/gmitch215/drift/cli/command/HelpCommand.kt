package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import dev.gmitch215.drift.help.Glossary

class HelpCommand : CliktCommand(name = "help") {
    private val term by argument("term", help = "a word Drift uses, such as capsule or verify")
        .optional()

    override fun help(context: Context) =
        "Explain a term Drift uses; without a term, explain all of them"

    override fun helpEpilog(context: Context) = examples("help")

    override fun run() {
        val name = term
        if (name == null) {
            Glossary.terms.forEach { echo("${it.name}: ${it.meaning}") }
            return
        }
        val found = Glossary.find(name)
            ?: throw CliktError(
                "unknown term \"$name\"; the terms are " +
                    Glossary.terms.joinToString(", ") { it.name.lowercase() },
            )
        echo("${found.name}: ${found.meaning}")
    }
}

internal const val LINE = "\u0085"

internal object Examples {
    private const val DIAGNOSE_PAIR = "fixtures/drangler/capsules/36702683742.json " +
        "fixtures/drangler/capsules/36995781138.json"
    private const val LAB_CASE = "fixtures/lab/cases/locale-tz-date-2.driftcase"
    private const val BISECT_PLAN = "bisect plan fixtures/lab/node.json fixtures/lab/jvm.json " +
        "--out plan.json"
    private const val CONTRACT_CREATE = "contract create --from fixtures/lab/node.json " +
        "-o node.contract.json"
    private const val CASE_SHOW = "case show fixtures/cases/a/case"
    private const val PROBE_CLOUDFLARE = "probe cloudflare " +
        "--worker tools/cloudflare/wasm/build/worker"

    val byCommand: Map<String, List<String>> = mapOf(
        "capture" to listOf("capture --label demo", "capture --label demo --project"),
        "run" to listOf(
            "run --out green-run --label green -- make test",
            "run --repeat 10 --out flaky -- make test",
        ),
        "import" to listOf(
            "import fixtures/drangler/logs/36702683742.log --format github " +
                "--job \"Docker E2E\" --out green-import",
        ),
        "ci" to listOf("ci", "ci --commit c775d8f --detail summary"),
        "handsoff" to listOf("handsoff plan"),
        "handsoff plan" to listOf(
            "handsoff plan",
            "handsoff plan . --job build --json",
            "handsoff plan --from-ci .drift/ci/c775d8f",
        ),
        "bisect" to listOf(BISECT_PLAN),
        "bisect plan" to listOf(BISECT_PLAN),
        "bisect apply" to listOf("bisect apply plan.json --step 1 --green"),
        "diagnose" to listOf(
            "diagnose --detail summary $DIAGNOSE_PAIR",
            "diagnose --detail detail --case out $DIAGNOSE_PAIR",
        ),
        "compare" to listOf("compare fast-run slow-run", "compare fast-run slow-run --json"),
        "next" to listOf("next --detail summary fixtures/cases/a/case"),
        "case" to listOf(CASE_SHOW),
        "case show" to listOf(CASE_SHOW),
        "reproduce" to listOf(
            "reproduce fixtures/lab/node-alpine.json --run \"npm test\" --out repro",
        ),
        "atlas" to listOf("atlas here", "atlas show fixtures/atlas/jvm.txt"),
        "atlas record" to listOf("atlas record --out tr"),
        "atlas here" to listOf("atlas here", "atlas here --out device.txt"),
        "atlas build" to listOf(
            "atlas build fixtures/atlas/jvm.txt fixtures/atlas/macos.txt " +
                "fixtures/atlas/wasm.txt --out atlas.json",
        ),
        "atlas compare" to listOf("atlas compare atlas.json tr/macos.txt"),
        "atlas show" to listOf("atlas show atlas.json", "atlas show atlas.json --oracle"),
        "atlas diff" to listOf("atlas diff ladder.json --from 2.4.21 --to 2.5.0-Beta1"),
        "probe" to listOf("$PROBE_CLOUDFLARE --target cloudflare-wasm --out tr"),
        "probe cloudflare" to listOf(
            "$PROBE_CLOUDFLARE --target cloudflare-wasm --out tr",
            "$PROBE_CLOUDFLARE --deploy --dry-run",
        ),
        "solve" to listOf(
            "solve fixtures/lab/node.json fixtures/lab/node-alpine.json --run \"npm test\" " +
                "--fail-when exit --budget trials=20,minutes=5",
        ),
        "ingest" to listOf("ingest case results --template"),
        "pack" to listOf("pack case --out solved.driftcase"),
        "verify" to listOf("verify $LAB_CASE", "verify --json $LAB_CASE"),
        "report" to listOf("report $LAB_CASE --out report.html"),
        "open" to listOf("open $LAB_CASE"),
        "install" to listOf("install --dry-run", "install --dry-run --no-modify-path"),
        "uninstall" to listOf("uninstall --dry-run"),
        "serve" to listOf("serve --dir cli/build/serve/studio --port 0"),
        "contract" to listOf(CONTRACT_CREATE),
        "contract create" to listOf(CONTRACT_CREATE),
        "contract verify" to listOf(
            "contract verify node.contract.json fixtures/lab/node-alpine.json",
        ),
        "triage" to listOf("triage fixtures/triage/b01-form-no-response.md"),
        "upgrade-check" to listOf(
            "upgrade-check fixtures/upgrade-check/drupal-10-php-8-1.json --to php=8.3,drupal=11",
        ),
        "doctor" to listOf("doctor"),
        "help" to listOf("help capsule", "help"),
        "completion" to listOf("completion bash", "completion zsh", "completion fish"),
    )

    fun epilog(path: String): String =
        "Examples:$LINE" + byCommand.getValue(path).joinToString(LINE) { "drift $it" }
}

internal fun examples(path: String) = Examples.epilog(path)
