package dev.gmitch215.drift.json

object CanonicalJson {
	fun encode(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

	fun parse(text: String): JsonValue {
		val parser = Parser(text)
		val value = parser.value()
		parser.skipSpace()
		if (!parser.done()) throw JsonException("trailing characters at ${parser.pos}")
		return value
	}

	private fun write(value: JsonValue, out: StringBuilder) {
		when (value) {
			JsonNull -> out.append("null")

			is JsonBool -> out.append(if (value.value) "true" else "false")

			is JsonInt -> out.append(value.value)

			is JsonString -> writeString(value.value, out)

			is JsonArray -> {
				out.append('[')
				value.items.forEachIndexed { i, item ->
					if (i > 0) out.append(',')
					write(item, out)
				}
				out.append(']')
			}

			is JsonObject -> {
				out.append('{')
				value.fields.keys.sorted().forEachIndexed { i, key ->
					if (i > 0) out.append(',')
					writeString(key, out)
					out.append(':')
					write(value.fields.getValue(key), out)
				}
				out.append('}')
			}
		}
	}

	private fun writeString(s: String, out: StringBuilder) {
		out.append('"')
		for ((i, c) in s.withIndex()) {
			if (c.isSurrogate() &&
				!wellFormed(s, i)
			) {
				throw JsonException("lone surrogate at index $i")
			}
			when {
				c == '"' -> out.append("\\\"")

				c == '\\' -> out.append("\\\\")

				c == '\n' -> out.append("\\n")

				c == '\r' -> out.append("\\r")

				c == '\t' -> out.append("\\t")

				c.code < 0x20 -> out.append("\\u00").append(HEX[c.code shr 4]).append(
					HEX[
						c.code and
							15,
					],
				)

				else -> out.append(c)
			}
		}
		out.append('"')
	}

	private fun wellFormed(s: String, i: Int): Boolean = if (s[i].isHighSurrogate()) {
		s.getOrNull(i + 1)?.isLowSurrogate() == true
	} else {
		i > 0 && s[i - 1].isHighSurrogate()
	}

	private const val HEX = "0123456789abcdef"

	private const val MAX_DEPTH = 256

	private class Parser(val text: String) {
		var pos = 0
		var depth = 0

		fun nested(read: () -> JsonValue): JsonValue {
			if (++depth > MAX_DEPTH) throw JsonException("nesting deeper than $MAX_DEPTH")
			try {
				return read()
			} finally {
				depth--
			}
		}

		fun done() = pos >= text.length

		fun skipSpace() {
			while (pos < text.length &&
				text[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }
			) {
				pos++
			}
		}

		fun value(): JsonValue {
			skipSpace()
			if (done()) throw JsonException("unexpected end")
			return when (val c = text[pos]) {
				'{' -> nested(::objectValue)

				'[' -> nested(::arrayValue)

				'"' -> JsonString(string())

				't' -> literal("true", JsonBool(true))

				'f' -> literal("false", JsonBool(false))

				'n' -> literal("null", JsonNull)

				else -> if (c == '-' ||
					c in '0'..'9'
				) {
					number()
				} else {
					throw JsonException("unexpected '$c' at $pos")
				}
			}
		}

		fun literal(word: String, result: JsonValue): JsonValue {
			if (!text.startsWith(word, pos)) throw JsonException("bad literal at $pos")
			pos += word.length
			return result
		}

		fun number(): JsonValue {
			val start = pos
			if (text[pos] == '-') pos++
			while (pos < text.length && text[pos] in '0'..'9') pos++
			if (pos < text.length && text[pos].let { it == '.' || it == 'e' || it == 'E' }) {
				throw JsonException("only integers are supported at $pos")
			}
			return JsonInt(
				text.substring(start, pos).toLongOrNull()
					?: throw JsonException("bad number at $start"),
			)
		}

		fun string(): String {
			pos++
			val sb = StringBuilder()
			while (true) {
				if (done()) throw JsonException("unterminated string")
				val c = text[pos++]
				when (c) {
					'"' -> return sb.toString()
					'\\' -> sb.append(escape())
					else -> sb.append(c)
				}
			}
		}

		fun escape(): Char {
			if (done()) throw JsonException("unterminated escape")
			return when (val e = text[pos++]) {
				'"' -> '"'

				'\\' -> '\\'

				'/' -> '/'

				'n' -> '\n'

				'r' -> '\r'

				't' -> '\t'

				'b' -> '\b'

				'f' -> '\u000c'

				'u' -> {
					if (pos + 4 > text.length) throw JsonException("bad unicode escape")
					val code =
						text.substring(pos, pos + 4).toIntOrNull(16)
							?: throw JsonException("bad unicode escape")
					pos += 4
					code.toChar()
				}

				else -> throw JsonException("bad escape '$e'")
			}
		}

		fun arrayValue(): JsonValue {
			pos++
			val items = mutableListOf<JsonValue>()
			skipSpace()
			if (!done() && text[pos] == ']') {
				pos++
				return JsonArray(items)
			}
			while (true) {
				items += value()
				skipSpace()
				if (done()) throw JsonException("unterminated array")
				when (text[pos++]) {
					',' -> continue
					']' -> return JsonArray(items)
					else -> throw JsonException("expected , or ] at ${pos - 1}")
				}
			}
		}

		fun objectValue(): JsonValue {
			pos++
			val fields = mutableMapOf<String, JsonValue>()
			skipSpace()
			if (!done() && text[pos] == '}') {
				pos++
				return JsonObject(fields)
			}
			while (true) {
				skipSpace()
				if (done() || text[pos] != '"') throw JsonException("expected key at $pos")
				val key = string()
				skipSpace()
				if (done() || text[pos++] != ':') throw JsonException("expected : at ${pos - 1}")
				if (key in fields) throw JsonException("duplicate key $key")
				fields[key] = value()
				skipSpace()
				if (done()) throw JsonException("unterminated object")
				when (text[pos++]) {
					',' -> continue
					'}' -> return JsonObject(fields)
					else -> throw JsonException("expected , or } at ${pos - 1}")
				}
			}
		}
	}
}
