package dev.gmitch215.drift.scan.atlas

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.scan.probe.kotlin.Classification

/**
 * A page and the sentence read from it. [decides] names the transcript lines whose expected value
 * the sentence states; a sentence that only allows variation leaves it empty.
 */
class Reference(val url: String, val quote: String, val decides: List<String> = emptyList())

/**
 * A tracker issue with the state it had when the research read it. A Kotlin issue has no [url];
 * an issue in another tracker names its page there.
 */
class IssueRef(
    val id: String,
    val state: String,
    val resolved: String?,
    val summary: String,
    val url: String? = null,
)

/** One differing line of a probe, with the cause class and the columns that differ. */
class LineNote(val line: String, val cause: String, val columns: String, val issue: String?)

/**
 * What is known about one probe, read from `atlas/classification.yml`. [columns] holds, per
 * Kotlin version where the probe diverges, the groups of targets with identical output.
 */
class ClassificationEntry(
    val id: String,
    val classification: Classification,
    val basis: String,
    val causes: List<String>,
    val downgradedFrom: Classification?,
    val references: List<Reference>,
    val issues: List<IssueRef>,
    val searched: String?,
    val repro: String?,
    val columns: Map<String, List<List<String>>>,
    val lines: List<LineNote>,
    val note: String,
)

class ClassificationData(val issuesRead: List<String>, val entries: List<ClassificationEntry>) {
    private val byId = entries.associateBy { it.id }

    operator fun get(id: String): ClassificationEntry? = byId[id]
}

object Classifications {
    const val SCHEMA = 1L

    /** The data embedded from `atlas/classification.yml` at build time. */
    val embedded: ClassificationData by lazy { parse(ClassificationJson.text) }

    fun fromJson(o: JsonObject): ClassificationEntry = try {
        entry(o)
    } catch (e: JsonException) {
        throw AtlasException(BadClassification(e.message ?: "unreadable"))
    }

    fun toJson(e: ClassificationEntry): JsonObject {
        val fields = linkedMapOf<String, JsonValue>(
            "id" to JsonString(e.id),
            "classification" to JsonString(e.classification.label),
            "basis" to JsonString(e.basis),
            "causes" to strings(e.causes),
            "references" to JsonArray(
                e.references.map {
                    val reference = linkedMapOf<String, JsonValue>(
                        "url" to JsonString(it.url),
                        "quote" to JsonString(it.quote),
                    )
                    if (it.decides.isNotEmpty()) reference["decides"] = strings(it.decides)
                    JsonObject(reference)
                },
            ),
            "issues" to JsonArray(
                e.issues.map {
                    val issue = linkedMapOf<String, JsonValue>(
                        "id" to JsonString(it.id),
                        "state" to JsonString(it.state),
                        "summary" to JsonString(it.summary),
                    )
                    it.resolved?.let { r -> issue["resolved"] = JsonString(r) }
                    it.url?.let { u -> issue["url"] = JsonString(u) }
                    JsonObject(issue)
                },
            ),
            "lines" to JsonArray(
                e.lines.map {
                    val line = linkedMapOf<String, JsonValue>(
                        "line" to JsonString(it.line),
                        "cause" to JsonString(it.cause),
                        "columns" to JsonString(it.columns),
                    )
                    it.issue?.let { i -> line["issue"] = JsonString(i) }
                    JsonObject(line)
                },
            ),
            "note" to JsonString(e.note),
        )
        e.downgradedFrom?.let { fields["downgradedFrom"] = JsonString(it.label) }
        e.searched?.let { fields["searched"] = JsonString(it) }
        e.repro?.let { fields["repro"] = JsonString(it) }
        return JsonObject(fields)
    }

    fun parse(text: String): ClassificationData = try {
        val root = CanonicalJson.parse(text).obj()
        val schema = root.require("schema").long()
        if (schema != SCHEMA) throw JsonException("schema $schema, expected $SCHEMA")
        ClassificationData(
            root.require("measured").obj().require("issuesRead").array().map { it.string() },
            root.require("probes").array().map { entry(it.obj()) },
        )
    } catch (e: JsonException) {
        throw AtlasException(BadClassification(e.message ?: "unreadable"))
    }

    private fun entry(o: JsonObject): ClassificationEntry {
        val id = o.require("id").string()
        return ClassificationEntry(
            id = id,
            classification = classification(o.require("classification").string()),
            basis = o.require("basis").string(),
            causes = o.require("causes").array().map { it.string() },
            downgradedFrom = optional(o, "downgradedFrom")?.let { classification(it.string()) },
            references = list(o, "references") {
                val r = it.obj()
                Reference(
                    r.require("url").string(),
                    r.require("quote").string(),
                    list(r, "decides") { d -> d.string() },
                )
            },
            issues = list(o, "issues") {
                val i = it.obj()
                IssueRef(
                    i.require("id").string(),
                    i.require("state").string(),
                    optional(i, "resolved")?.string(),
                    i.require("summary").string(),
                    optional(i, "url")?.string(),
                )
            },
            searched = optional(o, "searched")?.string(),
            repro = optional(o, "repro")?.string(),
            columns = optional(o, "columns")?.obj()?.fields.orEmpty().mapValues { (_, groups) ->
                groups.array().map { g -> g.array().map { it.string() } }
            },
            lines = list(o, "lines") {
                val l = it.obj()
                LineNote(
                    l.require("line").string(),
                    l.require("cause").string(),
                    l.require("columns").string(),
                    optional(l, "issue")?.string(),
                )
            },
            note = o.require("note").string(),
        )
    }

    private fun optional(o: JsonObject, key: String): JsonValue? = o[key]

    private fun <T> list(o: JsonObject, key: String, read: (JsonValue) -> T): List<T> =
        optional(o, key)?.array()?.map(read).orEmpty()

    private fun classification(label: String): Classification =
        Classification.entries.firstOrNull { it.label == label }
            ?: throw JsonException("unknown classification $label")
}

private fun strings(items: List<String>): JsonArray = JsonArray(items.map { JsonString(it) })
