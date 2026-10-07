package dev.gmitch215.drift.cli.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReceiptTest {
	private val receipt = Receipt(
		"1.0.0",
		Scope.USER,
		"/home/u/.local/bin",
		"/home/u/.local/bin/drift",
		listOf("/home/u/.local", "/home/u/.local/bin"),
		listOf("/home/u/.local/bin/drift.old"),
		listOf(
			ProfileEdit("/home/u/.profile", "# >>> drift >>>\n# <<< drift <<<\n", true, false),
			RegistryEdit(false, "C:\\Drift", ";", true, REG_EXPAND_SZ),
		),
	)

	@Test
	fun theCanonicalFormIsFixed() {
		val expected = "{\"binary\":\"/home/u/.local/bin/drift\"," +
			"\"createdDirectories\":[\"/home/u/.local\",\"/home/u/.local/bin\"]," +
			"\"directory\":\"/home/u/.local/bin\"," +
			"\"leftovers\":[\"/home/u/.local/bin/drift.old\"]," +
			"\"pathEdits\":[{\"block\":\"# >>> drift >>>\\n# <<< drift <<<\\n\",\"created\":true," +
			"\"kind\":\"profile\",\"leadingNewline\":false,\"path\":\"/home/u/.profile\"}," +
			"{\"created\":true,\"entry\":\"C:\\\\Drift\",\"key\":\"HKCU\\\\Environment\"," +
			"\"kind\":\"registry\",\"name\":\"Path\",\"separator\":\";\",\"type\":2}]," +
			"\"schema\":1,\"scope\":\"user\",\"version\":\"1.0.0\"}"
		assertEquals(expected, receipt.canonical())
	}

	@Test
	fun itRoundTrips() {
		assertEquals(receipt, Receipt.parse(receipt.canonical()))
		val edits = listOf<PathEdit>(RegistryEdit(true, "D", "", false, 1))
		val machine = receipt.copy(scope = Scope.GLOBAL, edits = edits)
		assertEquals(machine, Receipt.parse(machine.canonical()))
	}

	@Test
	fun theMachineKeyIsNamedForTheHive() {
		assertEquals("HKCU\\Environment", RegistryEdit(false, "D", "", false, 2).key)
		assertEquals(true, RegistryEdit(true, "D", "", false, 2).key.startsWith("HKLM\\SYSTEM"))
	}

	@Test
	fun unknownSchemasKindsAndFieldTypesAreRejected() {
		val text = receipt.canonical()
		val schema = text.replace("\"schema\":1", "\"schema\":2")
		assertFailsWith<IllegalStateException> { Receipt.parse(schema) }
		val kind = text.replace("\"profile\"", "\"other\"")
		assertFailsWith<IllegalStateException> { Receipt.parse(kind) }
		assertFailsWith<IllegalStateException> {
			Receipt.parse(text.replace("\"leadingNewline\":false", "\"leadingNewline\":0"))
		}
		assertFailsWith<Exception> { Receipt.parse("{") }
	}
}
