package dev.gmitch215.drift.cli.serve

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenersTest {
    private val opened = mutableListOf<Triple<Boolean, Boolean, Int>>()

    private fun open(failV6: Boolean = false, failV4: Boolean = false) =
        { v6: Boolean, all: Boolean, port: Int ->
            opened += Triple(v6, all, port)
            if (v6 && failV6) throw ServeFailure("no ipv6")
            if (!v6 && failV4) throw ServeFailure("in use")
            if (port == 0) 4000 else port
        }

    @Test
    fun loopbackOpensIpv4ThenIpv6OnTheSamePort() {
        val result = openListeners(Bind.LOOPBACK, 0, open(), { it })
        assertEquals(listOf(4000, 4000), result.sockets)
        assertEquals(listOf("127.0.0.1:4000", "[::1]:4000"), result.addresses)
        assertEquals(4000, result.port)
        assertEquals(listOf(Triple(false, false, 0), Triple(true, false, 4000)), opened)
    }

    @Test
    fun allInterfacesUseTheWildcardAddresses() {
        val result = openListeners(Bind.ALL, 80, open(), { it })
        assertEquals(listOf("0.0.0.0:80", "[::]:80"), result.addresses)
    }

    @Test
    fun noIpv6LeavesIpv4Alone() {
        val result = openListeners(Bind.LOOPBACK, 0, open(failV6 = true), { it })
        assertEquals(listOf(4000), result.sockets)
        assertEquals(listOf("127.0.0.1:4000"), result.addresses)
    }

    @Test
    fun anIpv4FailureNamesThePortAndTheFix() {
        val failure = assertFailsWith<ServeFailure> {
            openListeners(Bind.LOOPBACK, 8080, open(failV4 = true), { it })
        }
        assertEquals(
            "cannot listen on port 8080: in use; pass --port 0 to take a free port",
            failure.message,
        )
        assertEquals(1, opened.size)
    }

    @Test
    fun sendAllRepeatsUntilEveryByteWentOut() {
        val calls = mutableListOf<Pair<Int, Int>>()
        assertTrue(
            sendAll(10) { at, left ->
                calls += at to left
                4
            },
        )
        assertEquals(listOf(0 to 10, 4 to 6, 8 to 2), calls)
        assertTrue(sendAll(0) { _, _ -> error("nothing to send") })
    }

    @Test
    fun sendAllFailsOnAZeroOrNegativeCount() {
        assertFalse(sendAll(5) { _, _ -> 0 })
        assertFalse(sendAll(5) { at, _ -> if (at == 0) 3 else -1 })
    }

    @Test
    fun pemBlockDecodesTheNamedBlockAndSkipsOthers() {
        val der = ByteArray(40) { it.toByte() }
        val body = Base64.encode(der).chunked(16).joinToString("\n")
        val text = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n" +
            "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
        assertContentEquals(der, pemBlock(text, "PRIVATE KEY"))
        assertNull(pemBlock(text, "RSA PRIVATE KEY"))
        assertNull(pemBlock("-----BEGIN PRIVATE KEY-----\nAAAA", "PRIVATE KEY"))
        assertNull(pemBlock("not pem", "PRIVATE KEY"))
    }

    @Test
    fun backgroundLineQuotesEveryArgumentForSh() {
        assertEquals(
            "'dns-sd' '-R' 'it'\\''s' >/dev/null 2>&1 & echo \$!",
            backgroundLine(listOf("dns-sd", "-R", "it's")),
        )
    }

    @Test
    fun isZombieReadsTheStateAfterTheCommandName() {
        assertTrue(isZombie("12 (sh) Z 1 12"))
        assertTrue(isZombie("12 (a) b) Z 1"))
        assertFalse(isZombie("12 (sh) S 1 12"))
        assertFalse(isZombie("garbage"))
    }

    @Test
    fun swapExchangesTheTwoBytesOfAPort() {
        assertEquals(0x5000, swap(80))
        assertEquals(0x901f, swap(8080))
        assertEquals(8080, swap(swap(8080)))
    }

    @Test
    fun addressTextJoinsIpv4AndFoldsTheLongestIpv6ZeroRun() {
        assertEquals("127.0.0.1", addressText(listOf(127, 0, 0, 1)))
        assertEquals("::1", addressText(ipv6(7 to 1)))
        assertEquals("::", addressText(ipv6()))
        assertEquals("fe80::1", addressText(ipv6(0 to 0xfe80, 7 to 1)))
        assertEquals("1:0:0:2::3", addressText(ipv6(0 to 1, 3 to 2, 7 to 3)))
        assertEquals("1:2:3:4:5:6:7:8", addressText(ipv6(*Array(8) { it to it + 1 })))
        val single = ipv6(0 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 6, 7 to 7)
        assertEquals("1:0:2:3:4:5:6:7", addressText(single))
    }

    private fun ipv6(vararg groups: Pair<Int, Int>): List<Int> {
        val bytes = MutableList(16) { 0 }
        for ((at, value) in groups) {
            bytes[at * 2] = value shr 8
            bytes[at * 2 + 1] = value and 0xff
        }
        return bytes
    }
}
