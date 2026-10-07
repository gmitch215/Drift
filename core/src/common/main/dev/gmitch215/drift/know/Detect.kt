package dev.gmitch215.drift.know

import dev.gmitch215.drift.diff.Version
import dev.gmitch215.drift.diff.VersionScheme
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.rank.Evidence

/** Three-valued result: a missing fact or an unparsed version is [UNKNOWN], never [NO_MATCH]. */
enum class Verdict { MATCH, NO_MATCH, UNKNOWN }

enum class Which { A, B }

enum class Op(val symbol: String) {
	LE("<="),
	GE(">="),
	EQ("=="),
	NE("!="),
	LT("<"),
	GT(">"),
	;

	fun holds(c: Int) = when (this) {
		LE -> c <= 0
		GE -> c >= 0
		EQ -> c == 0
		NE -> c != 0
		LT -> c < 0
		GT -> c > 0
	}
}

data class Bound(val op: Op, val version: Version)

sealed interface Pred {
	val key: String

	fun toJson(): JsonValue

	/** `null` when the verdict cannot be known from [value]. */
	fun test(value: String?): Boolean?

	data class Eq(val value: String) : Pred {
		override val key get() = "eq"

		override fun toJson() = JsonString(value)

		override fun test(value: String?) = value?.let { it == this.value }
	}

	data class Ne(val value: String) : Pred {
		override val key get() = "ne"

		override fun toJson() = JsonString(value)

		override fun test(value: String?) = value?.let { it != this.value }
	}

	data class In(val values: List<String>) : Pred {
		override val key get() = "in"

		override fun toJson() = JsonArray(values.map(::JsonString))

		override fun test(value: String?) = value?.let { it in values }
	}

	/** Plain substring test; there is deliberately no regex. */
	data class Has(val value: String) : Pred {
		override val key get() = "has"

		override fun toJson() = JsonString(value)

		override fun test(value: String?) = value?.let { this.value in it }
	}

	data class Starts(val value: String) : Pred {
		override val key get() = "starts"

		override fun toJson() = JsonString(value)

		override fun test(value: String?) = value?.startsWith(this.value)
	}

	/**
	 * [Pred.Ver.ranges] are OR-ed; the bounds inside one range (comma separated in text) are
	 * AND-ed.
	 */
	data class Ver(
		val ranges: List<String>,
		val groups: List<List<Bound>>,
		val scheme: VersionScheme,
	) : Pred {
		override val key get() = "ver"

		override fun toJson() =
			if (ranges.size == 1) JsonString(ranges[0]) else JsonArray(ranges.map(::JsonString))

		override fun test(value: String?): Boolean? {
			val v = Version.parse(value ?: return null, scheme) ?: return null
			return groups.any { g -> g.all { it.op.holds(v.compareCore(it.version)) } }
		}
	}

	data class Present(val present: Boolean) : Pred {
		override val key get() = "present"

		override fun toJson() = JsonBool(present)

		override fun test(value: String?) = (value != null) == present
	}
}

sealed interface Node {
	data class AllOf(val nodes: List<Node>) : Node

	data class AnyOf(val nodes: List<Node>) : Node

	data class Not(val node: Node) : Node

	/** [Node.On.pred] on one side's fact at [Node.On.path]. */
	data class On(val which: Which, val path: String, val pred: Pred) : Node

	/** [Node.Cross.pred] on both sides: matches when the two results differ. */
	data class Cross(val path: String, val pred: Pred) : Node

	/** The raw value differs between the sides; a trailing `*` ranges over a prefix. */
	data class Changed(val path: String) : Node
}

fun Node.paths(): List<String> = when (this) {
	is Node.AllOf -> nodes.flatMap { it.paths() }
	is Node.AnyOf -> nodes.flatMap { it.paths() }
	is Node.Not -> node.paths()
	is Node.On -> listOf(path)
	is Node.Cross -> listOf(path)
	is Node.Changed -> listOf(path)
}

/** A rule match with everything a renderer may want; verbosity is a presentation choice. */
data class RuleMatch(
	val ruleId: String,
	val title: String,
	val tier: RuleTier,
	val severity: Severity,
	val evidence: List<Evidence>,
	val mechanism: String,
	val basis: Basis,
) {
	fun toJson(): JsonObject = obj(
		"rule" to JsonString(ruleId),
		"title" to JsonString(title),
		"tier" to JsonString(tier.id),
		"severity" to JsonString(severity.id),
		"evidence" to JsonArray(evidence.map { it.toJson() }),
		"mechanism" to JsonString(mechanism),
		"basis" to JsonString(basis.id),
	)
}

/** `known` is a rule match with its fixture-backed mechanism; `confirmed` needs an experiment. */
enum class RuleTier(val id: String) {
	KNOWN("known"),
}

data class RuleOutcome(val verdict: Verdict, val match: RuleMatch?)

object Detect {
	const val MAX_DEPTH = 8
	private val OPERATORS = setOf("all", "any", "not", "a", "b", "changed", "cross")
	private val PREDICATES = setOf("eq", "ne", "in", "has", "starts", "ver", "present")

	/** The `run:<field>` names a rule may read; they are the keys of [Run.facts]. */
	val RUN_FIELDS = setOf("signature", "exit", "signal", "step", "duration-ms", "transport")

	/** Evaluates [rule] with [green] as side `a` (passing) and [red] as side `b` (failing). */
	fun match(rule: Rule, green: Env, red: Env): RuleOutcome {
		val evidence = mutableSetOf<String>()
		val verdict = walk(rule.detect, green, red, evidence)
		if (verdict != Verdict.MATCH) return RuleOutcome(verdict, null)
		val basis = if (rule.symptoms.all { it.basis == Basis.VERIFIED }) {
			Basis.VERIFIED
		} else {
			Basis.INFERRED
		}
		return RuleOutcome(
			verdict,
			RuleMatch(
				rule.id,
				rule.title,
				RuleTier.KNOWN,
				rule.severity,
				evidence.sorted().map(::Evidence),
				rule.mechanism,
				basis,
			),
		)
	}

	fun match(rule: Rule, green: Capsule, red: Capsule, run: Map<String, String> = emptyMap()) =
		match(rule, env(green), env(red, run))

	fun match(rule: Rule, green: Capsule, red: Capsule, greenRun: Run?, redRun: Run?) =
		match(rule, env(green, greenRun), env(red, redRun))

	fun env(capsule: Capsule, run: Run?) = env(capsule, run?.facts().orEmpty())

	/** Static attributes and probes with status `ok`; other facts are absent. */
	fun env(capsule: Capsule, run: Map<String, String> = emptyMap()) = Env(
		capsule.attributes.filter { it.stability == Stability.STATIC }
			.associate { it.path to it.value },
		capsule.probes.filter { it.status == ProbeStatus.OK }.associate { it.id to it.transcript },
		run,
	)

	internal fun walk(node: Node, a: Env, b: Env, ev: MutableSet<String>): Verdict = when (node) {
		is Node.AllOf -> all(node.nodes.map { walk(it, a, b, ev) })

		is Node.AnyOf -> any(node.nodes.map { walk(it, a, b, ev) })

		is Node.Not -> when (walk(node.node, a, b, mutableSetOf())) {
			Verdict.MATCH -> Verdict.NO_MATCH
			Verdict.NO_MATCH -> Verdict.MATCH
			Verdict.UNKNOWN -> Verdict.UNKNOWN
		}

		is Node.On -> {
			val side = if (node.which == Which.A) a else b
			verdict(node.pred.test(side.lookup(node.path))).also {
				if (it == Verdict.MATCH) ev += node.path
			}
		}

		is Node.Cross -> {
			val x = node.pred.test(a.lookup(node.path))
			val y = node.pred.test(b.lookup(node.path))
			when {
				x == null || y == null -> Verdict.UNKNOWN
				x != y -> Verdict.MATCH.also { ev += node.path }
				else -> Verdict.NO_MATCH
			}
		}

		is Node.Changed -> changed(node.path, a, b, ev)
	}

	private fun verdict(b: Boolean?) = when (b) {
		true -> Verdict.MATCH
		false -> Verdict.NO_MATCH
		null -> Verdict.UNKNOWN
	}

	private fun all(vs: List<Verdict>) = when {
		Verdict.NO_MATCH in vs -> Verdict.NO_MATCH
		Verdict.UNKNOWN in vs -> Verdict.UNKNOWN
		else -> Verdict.MATCH
	}

	private fun any(vs: List<Verdict>) = when {
		Verdict.MATCH in vs -> Verdict.MATCH
		Verdict.UNKNOWN in vs -> Verdict.UNKNOWN
		else -> Verdict.NO_MATCH
	}

	private fun changed(path: String, a: Env, b: Env, ev: MutableSet<String>): Verdict {
		val paths = if (path.endsWith("*")) {
			val prefix = path.dropLast(1)
			(a.attrs.keys + b.attrs.keys).filter { it.startsWith(prefix) }.toSet()
		} else {
			setOf(path)
		}
		val both = paths.filter { a.lookup(it) != null && b.lookup(it) != null }
		val hits = both.filter { a.lookup(it) != b.lookup(it) }
		ev += hits
		return when {
			hits.isNotEmpty() -> Verdict.MATCH
			both.isNotEmpty() -> Verdict.NO_MATCH
			else -> Verdict.UNKNOWN
		}
	}

	// #region decode
	internal fun parse(json: JsonValue, path: String, depth: Int = 0): Node {
		if (depth > MAX_DEPTH) fail(path, "deeper than $MAX_DEPTH levels")
		val f = Fields(path, json)
		val ops = f.keys.filter { it in OPERATORS }
		if (ops.size != 1) fail(path, "needs exactly one operator, found $ops")
		val op = ops.single()
		val node = when (op) {
			"all", "any" -> {
				val items = (f.raw(op) as? JsonArray)?.items ?: fail("$path.$op", "expected array")
				if (items.isEmpty()) fail("$path.$op", "empty $op")
				val children = items.mapIndexed { i, c -> parse(c, "$path.$op[$i]", depth + 1) }
				if (op == "all") Node.AllOf(children) else Node.AnyOf(children)
			}

			"not" -> Node.Not(parse(f.raw("not"), "$path.not", depth + 1))

			else -> leaf(f, op, path)
		}
		f.close()
		return node
	}

	private fun leaf(f: Fields, op: String, at: String): Node {
		val target = f.str(op)
		checkPath("$at.$op", target, op == "changed")
		val preds = f.keys.filter { it in PREDICATES }
		if (op == "changed") {
			if (preds.isNotEmpty()) fail(at, "changed takes no predicate, found $preds")
			return Node.Changed(target)
		}
		if (preds.size != 1) fail(at, "needs exactly one predicate, found $preds")
		val pred = pred(f, preds.single(), target, at)
		return when (op) {
			"a" -> Node.On(Which.A, target, pred)
			"b" -> Node.On(Which.B, target, pred)
			else -> Node.Cross(target, pred)
		}
	}

	private fun pred(f: Fields, key: String, target: String, at: String): Pred = when (key) {
		"eq" -> Pred.Eq(f.str(key))
		"ne" -> Pred.Ne(f.str(key))
		"has" -> Pred.Has(f.str(key))
		"starts" -> Pred.Starts(f.str(key))
		"present" -> Pred.Present(f.bool(key))
		"in" -> Pred.In(f.strings(key).also { if (it.isEmpty()) fail("$at.in", "empty list") })
		else -> ver(f.raw(key), VersionScheme.of(target), "$at.ver")
	}

	private fun ver(json: JsonValue, scheme: VersionScheme, at: String): Pred.Ver {
		val ranges = when (json) {
			is JsonString -> listOf(json.value)

			is JsonArray -> json.items.map {
				(it as? JsonString)?.value ?: fail(at, "expected string")
			}

			else -> fail(at, "expected string or array")
		}
		if (ranges.isEmpty()) fail(at, "empty list")
		return Pred.Ver(ranges, ranges.map { range(it, scheme, at) }, scheme)
	}

	private fun range(text: String, scheme: VersionScheme, at: String): List<Bound> =
		text.split(",").map { part ->
			val atom = part.trim()
			val op = Op.entries.sortedByDescending { it.symbol.length }
				.firstOrNull { atom.startsWith(it.symbol) }
				?: fail(at, "bad bound '$atom' in '$text'")
			val bound = atom.substring(op.symbol.length).trim()
			val spaced = bound.any { it.isWhitespace() }
			val version = if (spaced) null else Version.parse(bound, scheme)
			Bound(op, version ?: fail(at, "unparsable version '$bound' in '$text'"))
		}

	private fun checkPath(at: String, path: String, allowStar: Boolean) {
		if (path.isEmpty() || !path.all(::pathChar)) fail(at, "bad path '$path'")
		if (path.dropLast(1).contains('*') || (path.endsWith("*") && !allowStar)) {
			fail(at, "'*' is only allowed at the end of a changed path")
		}
		if (path.endsWith("*") && (path.startsWith("probe:") || path.startsWith("run:"))) {
			fail(at, "'*' does not apply to probe or run paths")
		}
		if (path == "probe:" || path == "run:") fail(at, "empty name in '$path'")
		if (path.startsWith("run:") && path.substring(4) !in RUN_FIELDS) {
			fail(at, "unknown run field '${path.substring(4)}'")
		}
	}

	private fun pathChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_.:*-"
	// #endregion

	fun toJson(node: Node): JsonValue = when (node) {
		is Node.AllOf -> obj("all" to JsonArray(node.nodes.map(::toJson)))
		is Node.AnyOf -> obj("any" to JsonArray(node.nodes.map(::toJson)))
		is Node.Not -> obj("not" to toJson(node.node))
		is Node.On -> obj(node.which.name.lowercase() to JsonString(node.path), pair(node.pred))
		is Node.Cross -> obj("cross" to JsonString(node.path), pair(node.pred))
		is Node.Changed -> obj("changed" to JsonString(node.path))
	}

	private fun pair(pred: Pred) = pred.key to pred.toJson()
}
