package atlaslibs

import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.indexOf
import kotlinx.io.readByteArray
import kotlinx.io.readDecimalLong
import kotlinx.io.readCodePointValue
import kotlinx.io.readHexadecimalUnsignedLong
import kotlinx.io.readShortLe
import kotlinx.io.readUShort
import kotlinx.io.readString
import kotlinx.io.writeCodePointValue
import kotlinx.io.writeDecimalLong
import kotlinx.io.writeHexadecimalUnsignedLong
import kotlinx.io.writeString

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

private fun bufferOf(vararg v: Int) = Buffer().also { it.write(bytes(*v)) }

val ioProbes = listOf(
    Probe("io.utf8-malformed") {
        val inputs = mapOf(
            "overlong-C0AF" to bytes(0xC0, 0xAF),
            "overlong-E08080" to bytes(0xE0, 0x80, 0x80),
            "surrogate-EDA080" to bytes(0xED, 0xA0, 0x80),
            "above-10ffff-F4908080" to bytes(0xF4, 0x90, 0x80, 0x80),
            "truncated-E282" to bytes(0xE2, 0x82),
            "lone-continuation-80" to bytes(0x80),
            "valid-4byte-F09F9880" to bytes(0xF0, 0x9F, 0x98, 0x80),
            "valid-then-bad" to bytes(0x41, 0xFF, 0x42),
            "F8-lead" to bytes(0xF8, 0x88, 0x80, 0x80, 0x80),
            "lead-then-ascii-E241" to bytes(0xE2, 0x41),
            "nul" to bytes(0x00),
            "bom-EFBBBF" to bytes(0xEF, 0xBB, 0xBF),
            "noncharacter-EFBFBF" to bytes(0xEF, 0xBF, 0xBF),
        )
        for ((name, b) in inputs) {
            attempt("readString[$name]") {
                Buffer().also { it.write(b) }.readString().codes()
            }
            attempt("readString-limited[$name]") {
                Buffer().also { it.write(b) }.readString(b.size.toLong()).codes()
            }
            attempt("decodeToString[$name]") { b.decodeToString().codes() }
            attempt("decodeToString-throw[$name]") {
                b.decodeToString(throwOnInvalidSequence = true).codes()
            }
            attempt("readCodePoint[$name]") {
                val buf = Buffer().also { it.write(b) }
                val out = mutableListOf<String>()
                while (!buf.exhausted()) {
                    out += try {
                        buf.readCodePointValue().toString(16)
                    } catch (e: Exception) {
                        "err:" + e::class.simpleName
                    }
                    if (out.size > 8) break
                }
                out.joinToString(" ")
            }
        }
        attempt("writeString-lone-surrogate") {
            Buffer().also { it.writeString("a\ud800b") }.readByteArray()
                .joinToString(" ") { (it.toInt() and 0xff).toString(16) }
        }
        attempt("writeString-pair") {
            Buffer().also { it.writeString("\ud83d\ude00") }.readByteArray()
                .joinToString(" ") { (it.toInt() and 0xff).toString(16) }
        }
        attempt("writeString-reversed-pair") {
            Buffer().also { it.writeString("\ude00\ud83d") }.readByteArray()
                .joinToString(" ") { (it.toInt() and 0xff).toString(16) }
        }
        attempt("encodeToByteArray-lone-surrogate") {
            "a\ud800b".encodeToByteArray().joinToString(" ") { (it.toInt() and 0xff).toString(16) }
        }
        attempt("writeCodePoint-surrogate") {
            Buffer().also { it.writeCodePointValue(0xD800) }.readByteArray()
                .joinToString(" ") { (it.toInt() and 0xff).toString(16) }
        }
        attempt("writeCodePoint-above-max") {
            Buffer().also { it.writeCodePointValue(0x110000) }.readByteArray()
                .joinToString(" ") { (it.toInt() and 0xff).toString(16) }
        }
        attempt("writeCodePoint-negative") {
            Buffer().also { it.writeCodePointValue(-1) }.readByteArray()
                .joinToString(" ") { (it.toInt() and 0xff).toString(16) }
        }
    },
    Probe("io.decimal-long") {
        val decimals = listOf(
            "9223372036854775807", "9223372036854775808", "-9223372036854775808",
            "-9223372036854775809", "-", "", "12abc", "+5", "00012", "-0", " 1", "1 ", "1\n2",
            "99999999999999999999", "-0012", "--1", "1_000",
        )
        for (s in decimals) {
            attempt("readDecimalLong[$s]") {
                val b = Buffer().also { it.writeString(s) }
                val v = b.readDecimalLong()
                "$v ${v.toString()} max=${v == Long.MAX_VALUE} min=${v == Long.MIN_VALUE} " +
                    "hi=${v ushr 32} lo=${v and 0xffffffffL} rest=${b.size}"
            }
        }
        val max = Long.MAX_VALUE
        line("template-const", "$max")
        line("concat-const", "" + max)
        line("tostring-const", max.toString())
        val opaque = "9223372036854775807".toLong()
        line("template-parsed", "$opaque")
        line("tostring-parsed", opaque.toString())
        line("template-parsed-suffix", "$opaque|")
        line("template-parsed-prefix", "|$opaque")
        line("template-parsed-twice", "$opaque $opaque")
        line("template-parsed-expr", "${opaque + 0}|")
        line("template-parsed-mixed", "$opaque ${opaque.toString()}")
        line("template-parsed-mixed-2", "${opaque.toString()} $opaque")
        line("template-parsed-mixed-3", "x ${opaque.toString()} $opaque")
        line("template-parsed-three", "$opaque $opaque $opaque")
        line("template-parsed-int-between", "$opaque ${1} $opaque")
        line("listof-parsed", listOf(opaque).toString())
        class Holder(val l: Long)
        val holder = Holder(opaque)
        line("template-property", "${holder.l} ${holder.l.toString()}")
        line("template-property-single", "${holder.l}")
        attempt("attempt-template-parsed") {
            val v = "9223372036854775807".toLong()
            "$v ${v.toString()} max=${v == Long.MAX_VALUE} hi=${v ushr 32}"
        }
        attempt("attempt-template-read-single") {
            val v = Buffer().also { it.writeString("9223372036854775807") }.readDecimalLong()
            "$v"
        }
        attempt("attempt-template-read-pair") {
            val v = Buffer().also { it.writeString("9223372036854775807") }.readDecimalLong()
            "$v ${v.toString()}"
        }
        line(
            "read-template-outside",
            Buffer().also { it.writeString("9223372036854775807") }
                .readDecimalLong().let { "$it" },
        )
        line(
            "read-tostring-outside",
            Buffer().also { it.writeString("9223372036854775807") }
                .readDecimalLong().toString(),
        )
        line(
            "read-eq-outside",
            Buffer().also { it.writeString("9223372036854775807") }
                .readDecimalLong() == opaque,
        )
        val hexes = listOf(
            "ffffffffffffffff", "10000000000000000", "", "g", "FF", "ffg", "-1", "0x1",
            "0000000000000000f",
        )
        for (s in hexes) {
            attempt("readHex[$s]") {
                val b = Buffer().also { it.writeString(s) }
                val v = b.readHexadecimalUnsignedLong()
                "$v rest=${b.size}"
            }
        }
        attempt("writeDecimal[min]") {
            Buffer().also { it.writeDecimalLong(Long.MIN_VALUE) }.readString()
        }
        attempt("writeDecimal[-1]") { Buffer().also { it.writeDecimalLong(-1) }.readString() }
        attempt("writeDecimal[0]") { Buffer().also { it.writeDecimalLong(0) }.readString() }
        attempt("writeHex[-1]") {
            Buffer().also { it.writeHexadecimalUnsignedLong(-1) }.readString()
        }
        attempt("writeHex[0]") { Buffer().also { it.writeHexadecimalUnsignedLong(0) }.readString() }
        attempt("writeHex[min]") {
            Buffer().also { it.writeHexadecimalUnsignedLong(Long.MIN_VALUE) }.readString()
        }
    },
    Probe("io.buffer-edges") {
        attempt("skip-beyond") { bufferOf(1, 2, 3).skip(4) }
        attempt("skip-negative") { bufferOf(1, 2, 3).skip(-1) }
        attempt("require-beyond") { bufferOf(1, 2, 3).require(4) }
        attempt("request-beyond") { bufferOf(1, 2, 3).request(4) }
        attempt("request-negative") { bufferOf(1, 2, 3).request(-1) }
        attempt("readShort-1byte") { bufferOf(1).readShort() }
        attempt("readInt-3bytes") { bufferOf(1, 2, 3).readInt() }
        attempt("readLong-negative") {
            bufferOf(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff).readLong()
        }
        attempt("readUShort") { bufferOf(0xff, 0xfe).readUShort() }
        attempt("readShortLe") { bufferOf(0x01, 0x02).readShortLe() }
        attempt("get-oob") { bufferOf(1, 2, 3)[3] }
        attempt("get-negative") { bufferOf(1, 2, 3)[-1] }
        attempt("copyTo-start-beyond") { bufferOf(1, 2, 3).copyTo(Buffer(), 4, 5) }
        attempt("copyTo-end-beyond") { bufferOf(1, 2, 3).copyTo(Buffer(), 1, 5) }
        attempt("copyTo-reversed") { bufferOf(1, 2, 3).copyTo(Buffer(), 2, 1) }
        attempt("copyTo-empty-range") { Buffer().also { bufferOf(1, 2, 3).copyTo(it, 1, 1) }.size }
        attempt("readByteArray-neg") { bufferOf(1, 2, 3).readByteArray(-1) }
        attempt("readByteArray-beyond") { bufferOf(1, 2, 3).readByteArray(4) }
        attempt("readString-beyond") { bufferOf(0x41).readString(2) }
        attempt("peek-then-read") {
            val b = bufferOf(1, 2, 3)
            val p = b.peek()
            p.readByte()
            "${b.size} ${p.readByte()}"
        }
        attempt("indexOf-found") { bufferOf(1, 2, 3).indexOf(3) }
        attempt("indexOf-missing") { bufferOf(1, 2, 3).indexOf(9) }
        attempt("indexOf-startIndex-beyond") { bufferOf(1, 2, 3).indexOf(3, 4) }
        attempt("indexOf-endIndex-beyond") { bufferOf(1, 2, 3).indexOf(3, 0, 9) }
        attempt("indexOf-negative-start") { bufferOf(1, 2, 3).indexOf(3, -1) }
        attempt("clear-then-size") { bufferOf(1, 2, 3).also { it.clear() }.size }
        attempt("write-range-oob") { Buffer().write(bytes(1, 2, 3), 2, 5) }
        attempt("write-range-neg") { Buffer().write(bytes(1, 2, 3), -1, 2) }
        attempt("readAtMostTo-empty") { bufferOf().readAtMostTo(ByteArray(3)) }
        attempt("readAtMostTo-zero-len") { bufferOf(1).readAtMostTo(ByteArray(3), 0, 0) }
        attempt("transferTo-size") { Buffer().let { out -> bufferOf(1, 2, 3).transferTo(out) } }
        attempt("exhausted-fresh") { Buffer().exhausted() }
        attempt("large-size") {
            val b = Buffer()
            repeat(10) { b.write(ByteArray(10_000)) }
            "${b.size} ${b.skip(65_536).let { b.size }}"
        }
        attempt("eof-type") {
            try {
                bufferOf().readByte()
            } catch (e: EOFException) {
                "EOFException"
            }
        }
    },
)
