package dev.gmitch215.drift.tools.corpus

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.triage.Via
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private const val USAGE = "usage: corpus build <manifest> <work-dir> <out-dir> | " +
    "corpus fetch <manifest> <out.zst> | " +
    "corpus eval <manifest> <corpus.json[.zst]> <report> [claims-tsv [misses-tsv]]"

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "build" -> if (args.size == 4) build(args) else usage()
        "fetch" -> if (args.size == 3) fetch(args) else usage()
        "eval" -> if (args.size in 4..6) eval(args) else usage()
        else -> usage()
    }
}

private fun build(args: Array<String>) {
    val manifest = CorpusManifest.parse(Path.of(args[1]).readText())
    val work = Path.of(args[2])
    val out = Files.createDirectories(Path.of(args[3]))
    val build = CorpusBuild(manifest, HostSource(GithubSource(), GitlabSource()), work, ::println)
    build.fetchAll()
    val json = out.resolve("corpus.json")
    val zst = out.resolve("corpus.json.zst")
    val summary = CorpusBuild.compress(build.assemble(json), json, zst)
    out.resolve("summary.json").writeText(
        CanonicalJson.encode(summary.toJson()) + "\n",
    )
    println("json ${summary.jsonBytes} bytes sha256 ${summary.jsonSha256}")
    println("zst ${summary.zstBytes} bytes sha256 ${summary.zstSha256}")
    println("issues ${summary.counts.values.sum()}")
}

private fun fetch(args: Array<String>) {
    val manifest = CorpusManifest.parse(Path.of(args[1]).readText())
    val asset = manifest.asset ?: error("triage/corpus.yml has no asset entry yet")
    println(CorpusDownload.fetch(asset, Path.of(args[2])))
}

private fun eval(args: Array<String>) {
    val manifest = CorpusManifest.parse(Path.of(args[1]).readText())
    val claims = args.getOrNull(4)?.let(::writer)
    val misses = args.getOrNull(5)?.let(::writer)
    val eval = EvalTriage(
        manifest,
        onClaim = { issue, id, permille, excerpt ->
            claims?.write("${issue.repo}\t${issue.number}\t$id\t$permille\t$excerpt\n")
        },
        onMiss = { issue, f, wanted ->
            val line = "${issue.repo}\t${issue.number}\t${f.key}\t${f.value}\t${f.via.id}\t$wanted"
            misses?.write(line + "\n")
        },
    )
    var n = 0
    CorpusReader.use(Path.of(args[2])) { issues ->
        for (issue in issues) {
            eval.add(issue)
            if (++n % PROGRESS == 0) System.err.println("$n issues")
        }
    }
    claims?.close()
    misses?.close()
    val result = eval.result()
    val text = buildString {
        append(EvalTriage.render(result))
        append("proposed bases from the dev split\n")
        for (via in Via.entries) {
            val s = result.dev.byVia[via.id]
            val next = s?.let { EvalTriage.proposed(via, it) } ?: "keep"
            append("  ${via.id}: ${via.base} -> $next\n")
        }
    }
    Path.of(args[3]).also { it.toAbsolutePath().parent?.let(Files::createDirectories) }
        .writeText(text)
    print(text)
}

private fun writer(file: String): BufferedWriter {
    val path = Path.of(file).toAbsolutePath()
    path.parent?.let(Files::createDirectories)
    return Files.newBufferedWriter(path)
}

private fun usage(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}

private const val PROGRESS = 10_000
