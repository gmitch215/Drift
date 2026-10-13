package dev.gmitch215.drift.ci

import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.redact.Redactor

/** How a wrapped command ended: [command] is redacted, [truncated] says the tail was cut. */
data class Execution(
    val command: String,
    val exitCode: Int,
    val durationMs: Long,
    val truncated: Boolean,
) {
    fun toJson(): JsonObject = obj(
        "schema" to JsonInt(SCHEMA),
        "command" to JsonString(command),
        "exitCode" to JsonInt(exitCode.toLong()),
        "durationMs" to JsonInt(durationMs),
        "truncated" to JsonBool(truncated),
    )

    companion object {
        const val SCHEMA = 1L
        const val MAX_TAIL_CHARS = 64 * 1024
        const val WINDOW_CHARS = 2 * MAX_TAIL_CHARS

        fun fromJson(json: JsonValue): Execution {
            val o = json.obj()
            val schema = o.require("schema").long()
            if (schema != SCHEMA) throw JsonException("unsupported result schema $schema")
            return Execution(
                o.require("command").string(),
                o.require("exitCode").long().toInt(),
                o.require("durationMs").long(),
                (o.require("truncated") as? JsonBool)?.value
                    ?: throw JsonException("expected boolean"),
            )
        }

        /**
         * The last [lines] lines of [output] with secrets masked, cut from the front to at most
         * [MAX_TAIL_CHARS]; the flag says whether anything was dropped.
         */
        fun tail(output: String, lines: Int): Pair<String, Boolean> {
            val all = output.replace("\r\n", "\n").trimEnd('\n').split('\n')
            val kept = all.takeLast(lines).joinToString("\n") { Redactor.redactText(it) }
            if (kept.length <= MAX_TAIL_CHARS) return kept to (all.size > lines)
            val cut = kept.takeLast(MAX_TAIL_CHARS)
            return cut.substringAfter('\n', cut) to true
        }
    }
}

/**
 * A directory holding one environment: `capsule.json`, and when known `run.json` (the [Run]
 * with its outcome, steps and symptoms) and `result.json` (an [Execution], written by
 * `drift run`). `tail.log` holds the bounded log tail and is never read back.
 */
data class CapsuleDirectory(
    val capsule: Capsule,
    val run: Run? = null,
    val execution: Execution? = null,
    val tail: String? = null,
)

sealed interface DirectoryRead {
    data class Loaded(val directory: CapsuleDirectory) : DirectoryRead

    data class Failed(val reason: String) : DirectoryRead
}

object CapsuleDirectories {
    const val CAPSULE = "capsule.json"
    const val RUN = "run.json"
    const val RESULT = "result.json"
    const val TAIL = "tail.log"

    /** The path that could not be written, or null when every file was. */
    fun write(files: CaseFiles, dir: String, value: CapsuleDirectory): String? {
        val base = dir.trimEnd('/')
        val parts = buildList {
            add(CAPSULE to value.capsule.canonical() + "\n")
            value.run?.let { add(RUN to it.canonical() + "\n") }
            value.execution?.let { add(RESULT to CanonicalJson.encode(it.toJson()) + "\n") }
            value.tail?.let { add(TAIL to it + "\n") }
        }
        return parts.firstOrNull { (name, text) -> !files.write("$base/$name", text) }
            ?.let { "$base/${it.first}" }
    }

    /** Null when [dir] holds no `capsule.json`, so the caller can try it as a capsule file. */
    fun read(read: (String) -> String?, dir: String): DirectoryRead? {
        val base = dir.trimEnd('/')
        val capsule = read("$base/$CAPSULE") ?: return null
        return try {
            DirectoryRead.Loaded(
                CapsuleDirectory(
                    Capsule.parse(capsule),
                    read("$base/$RUN")?.let { Run.parse(it) },
                    read("$base/$RESULT")?.let { Execution.fromJson(CanonicalJson.parse(it)) },
                ),
            )
        } catch (e: JsonException) {
            DirectoryRead.Failed("invalid capsule directory $dir: ${e.message}")
        }
    }
}
