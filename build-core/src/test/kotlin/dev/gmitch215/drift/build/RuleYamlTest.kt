package dev.gmitch215.drift.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleYamlTest {
	private fun json(body: String) = RuleYaml.toJson("schema: 1\nid: x\n$body", "x.yml")

	@Test
	fun trickyScalarsStayStrings() {
		val values = listOf("1.20", "1.10", "no", "on", "0o17", "1e3", "~", "true", "null", "0x1F")
		val yaml = values.joinToString("\n") { "k$it: $it" }
		val out = json(yaml)
		for (v in values) assertTrue(out.contains("\t\"k$v\": \"$v\""), "$v in $out")
	}

	@Test
	fun quotedAndBlockScalarsDecodeToTheSameString() {
		val out = json("a: '1.20'\nb: \"1.20\"\nc: >-\n  long\n  folded text\nd: \"line\\n\"\n")
		assertTrue(out.contains("\"a\": \"1.20\""))
		assertTrue(out.contains("\"b\": \"1.20\""))
		assertTrue(out.contains("\"c\": \"long folded text\""))
		assertTrue(out.contains("\"d\": \"line\\n\""))
	}

	@Test
	fun writesTheStoredPrettyFormat() {
		val out = json("list:\n  - a\n  - b: \"q\\\"\"\nempty: {}\nnone: []\n")
		val expected = "{\n\t\"schema\": 1,\n\t\"id\": \"x\",\n\t\"list\": [\n\t\t\"a\",\n" +
			"\t\t{\n\t\t\t\"b\": \"q\\\"\"\n\t\t}\n\t],\n\t\"empty\": {},\n\t\"none\": []\n}\n"
		assertEquals(expected, out)
	}

	@Test
	fun onlyTheTopLevelSchemaIsANumber() {
		assertTrue(json("nested:\n  schema: 2\n").contains("\"schema\": \"2\""))
	}

	@Test
	fun explicitTagsAndDuplicateKeysAreRejected() {
		assertFailsWith<RuleYamlException> { json("n: !!int 5\n") }
		assertFailsWith<RuleYamlException> { json("o: !!java.lang.Object {}\n") }
		assertFailsWith<RuleYamlException> { json("a: 1\na: 2\n") }
	}

	@Test
	fun shapeErrorsNameTheFile() {
		val bad = listOf(
			"- a\n",
			"schema: 1\n",
			"id: x\n",
			"schema: one\nid: x\n",
			"schema:\n  - 1\nid: x\n",
			"id: [a\n",
		)
		for (yaml in bad) {
			val e = assertFailsWith<RuleYamlException> { RuleYaml.toJson(yaml, "bad.yml") }
			assertTrue(e.message!!.startsWith("bad.yml: "), e.message)
		}
	}
}
