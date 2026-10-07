@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.serve

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

internal interface TlsSession {
	fun read(buffer: CPointer<ByteVar>, length: Int): Int

	fun write(buffer: CPointer<ByteVar>, length: Int): Int

	fun close()
}

internal interface TlsHandshake {
	fun accept(fd: Int): TlsSession?
}

private typealias Ptr = COpaquePointer?

private const val RTLD_NOW_ID = 2
private const val SET_MIN_PROTO = 123
private const val TLS_1_2 = 0x0303L
private const val PEM = 1

private val libraries = listOf(
	"/opt/homebrew/opt/openssl@3/lib/libssl.3.dylib",
	"/usr/local/opt/openssl@3/lib/libssl.3.dylib",
	"libssl.so.3",
	"libssl.so.1.1",
)

private var loaded: OpenSsl? = null

/** The system OpenSSL, opened at run time so the executable has no link-time dependency on it. */
internal fun openSsl(): OpenSsl {
	loaded?.let { return it }
	val handle = libraries.firstNotNullOfOrNull { dlopen(it, RTLD_NOW_ID) }
		?: throw ServeFailure(
			"no OpenSSL 3 or 1.1 library was found (tried ${libraries.joinToString()}); " +
				"install OpenSSL, for example brew install openssl@3 or your libssl package",
		)
	return OpenSsl(handle).also { loaded = it }
}

internal class OpenSsl(private val handle: COpaquePointer) {
	private fun <T : Function<*>> symbol(name: String): CPointer<CFunction<T>> {
		val found = dlsym(handle, name)
			?: throw ServeFailure("OpenSSL has no $name: ${dlerror()?.toKString()}")
		return found.reinterpret()
	}

	private val serverMethod = symbol<() -> Ptr>("TLS_server_method")
	private val contextNew = symbol<(Ptr) -> Ptr>("SSL_CTX_new")
	private val contextFree = symbol<(Ptr) -> Unit>("SSL_CTX_free")
	private val contextControl = symbol<(Ptr, Int, Long, Ptr) -> Long>("SSL_CTX_ctrl")
	private val useChain =
		symbol<(Ptr, CPointer<ByteVar>?) -> Int>("SSL_CTX_use_certificate_chain_file")
	private val useKey =
		symbol<(Ptr, CPointer<ByteVar>?, Int) -> Int>("SSL_CTX_use_PrivateKey_file")
	private val checkKey = symbol<(Ptr) -> Int>("SSL_CTX_check_private_key")
	private val sslNew = symbol<(Ptr) -> Ptr>("SSL_new")
	private val sslFree = symbol<(Ptr) -> Unit>("SSL_free")
	private val setFd = symbol<(Ptr, Int) -> Int>("SSL_set_fd")
	private val sslAccept = symbol<(Ptr) -> Int>("SSL_accept")
	private val sslRead = symbol<(Ptr, CPointer<ByteVar>?, Int) -> Int>("SSL_read")
	private val sslWrite = symbol<(Ptr, CPointer<ByteVar>?, Int) -> Int>("SSL_write")
	private val sslShutdown = symbol<(Ptr) -> Int>("SSL_shutdown")
	private val errorCode = symbol<() -> Long>("ERR_get_error")
	private val errorText = symbol<(Long, CPointer<ByteVar>?, ULong) -> Unit>("ERR_error_string_n")

	private fun lastError(): String = memScoped {
		val code = errorCode()
		val text = allocArray<ByteVar>(256)
		errorText(code, text, 256u)
		text.toKString()
	}

	fun context(files: TlsFiles): TlsHandshake {
		val context = contextNew(serverMethod())
			?: throw ServeFailure("cannot create an OpenSSL context: ${lastError()}")
		contextControl(context, SET_MIN_PROTO, TLS_1_2, null)
		memScoped {
			if (useChain(context, files.cert.cstr.ptr) != 1) {
				contextFree(context)
				throw ServeFailure("cannot read the certificate ${files.cert}: ${lastError()}")
			}
			if (useKey(context, files.key.cstr.ptr, PEM) != 1 || checkKey(context) != 1) {
				contextFree(context)
				val why = lastError()
				throw ServeFailure("the key ${files.key} is unusable or does not match: $why")
			}
		}
		return object : TlsHandshake {
			override fun accept(fd: Int): TlsSession? {
				val ssl = sslNew(context) ?: return null
				if (setFd(ssl, fd) != 1 || sslAccept(ssl) != 1) {
					sslFree(ssl)
					return null
				}
				return object : TlsSession {
					override fun read(buffer: CPointer<ByteVar>, length: Int): Int {
						val n = sslRead(ssl, buffer, length)
						return n
					}

					override fun write(buffer: CPointer<ByteVar>, length: Int): Int {
						val n = sslWrite(ssl, buffer, length)
						return n
					}

					override fun close() {
						sslShutdown(ssl)
						sslFree(ssl)
					}
				}
			}
		}
	}
}
