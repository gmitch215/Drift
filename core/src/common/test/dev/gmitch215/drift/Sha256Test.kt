package dev.gmitch215.drift

import dev.gmitch215.drift.hash.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
	@Test
	fun knownVectors() {
		assertEquals(
			"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
			Sha256.hex(""),
		)
		assertEquals(
			"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
			Sha256.hex("abc"),
		)
		assertEquals(
			"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
			Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
		)
	}

	@Test
	fun blockBoundaries() {
		val expected = mapOf(
			55 to "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
			56 to "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
			63 to "7d3e74a05d7db15bce4ad9ec0658ea98e3f06eeecf16b4c6fff2da457ddc2f34",
			64 to "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
			65 to "635361c48bb9eab14198e76ea8ab7f1a41685d6ad62aa9146d301d4f17eb0ae0",
			119 to "31eba51c313a5c08226adf18d4a359cfdfd8d2e816b13f4af952f7ea6584dcfb",
			120 to "2f3d335432c70b580af0e8e1b3674a7c020d683aa5f73aaaedfdc55af904c21c",
		)
		for ((n, hash) in expected) assertEquals(hash, Sha256.hex("a".repeat(n)), "length $n")
	}

	@Test
	fun millionAs() {
		assertEquals(
			"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
			Sha256.hex("a".repeat(1_000_000)),
		)
	}
}
