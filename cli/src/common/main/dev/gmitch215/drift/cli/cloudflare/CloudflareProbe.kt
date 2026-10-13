package dev.gmitch215.drift.cli.cloudflare

import kotlin.io.encoding.Base64

class CloudflareOptions(
    val worker: String,
    val wrangler: String?,
    val port: Int,
    val timeoutSeconds: Int,
    val deploy: Boolean,
    val dryRun: Boolean,
)

object CloudflareProbe {
    const val PRICING_URL = "https://developers.cloudflare.com/workers/platform/pricing/"
    const val PRICING_READ = "2026-10-12"

    /** The `node` command that runs the driver; the script travels base64 encoded. */
    fun argv(options: CloudflareOptions): List<String> {
        val script = Base64.encode(CLOUDFLARE_DRIVER.encodeToByteArray())
        return listOf(
            "node",
            "-e",
            "eval(Buffer.from('$script','base64').toString())",
            if (options.deploy) "deploy" else "dev",
            options.worker,
            options.wrangler.orEmpty(),
            options.port.toString(),
            options.timeoutSeconds.toString(),
            options.dryRun.toString(),
        )
    }

    /** What one `--deploy` costs, from the Workers pricing page read on [PRICING_READ]. */
    fun estimate(): String = listOf(
        "Deploying sends one request to a new Worker on your Cloudflare account, and up to " +
            "$CLOUDFLARE_RETRIES more while it propagates.",
        "Free plan: 100,000 requests per day and 10 ms of CPU per invocation at no charge; " +
            "a run that needs more CPU than that fails there.",
        "Paid plan (5 USD a month minimum): 10 million requests and 30 million CPU ms a month " +
            "included, then 0.30 USD per million requests and 0.02 USD per million CPU ms.",
        "The Worker stays deployed until you delete it with wrangler delete.",
        "Source: $PRICING_URL, read $PRICING_READ.",
    ).joinToString("\n")

    /** The text between the driver's markers, or null when the driver printed none. */
    fun transcript(output: String): String? {
        val lines = output.lines()
        val begin = lines.indexOfFirst { it.trimEnd() == CLOUDFLARE_BEGIN }
        val end = lines.indexOfLast { it.trimEnd() == CLOUDFLARE_END }
        if (begin < 0 || end <= begin) return null
        return lines.subList(begin + 1, end).joinToString("\n", postfix = "\n")
    }

    /** Output the driver printed before the markers, such as the wrangler address. */
    fun log(output: String): String = output.substringBefore(CLOUDFLARE_BEGIN).trim()

    fun retarget(transcript: String, target: String): String =
        transcript.replace(Regex("^(# kotlin\\.target = ).*\$", RegexOption.MULTILINE)) {
            "${it.groupValues[1]}$target"
        }
}
