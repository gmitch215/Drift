package dev.gmitch215.drift.tools.corpus

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists

/** Downloads the frozen corpus; a file that differs from the manifest hash or size is refused. */
object CorpusDownload {
    fun fetch(asset: CorpusAsset, out: Path, open: (String) -> InputStream = ::http): Path {
        if (out.exists() && CorpusBuild.sha256(out) == asset.sha256) return out
        Files.createDirectories(out.toAbsolutePath().parent)
        val tmp = out.resolveSibling(out.fileName.toString() + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        open(asset.url).use { input ->
            Files.newOutputStream(tmp).use { sink ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    sink.write(buffer, 0, n)
                    bytes += n
                }
            }
        }
        val sha = CorpusBuild.hex(digest.digest())
        if (sha != asset.sha256 || bytes != asset.bytes) {
            Files.deleteIfExists(tmp)
            throw IOException(
                "corpus download does not match the manifest: got $bytes bytes sha256 $sha, " +
                    "want ${asset.bytes} bytes sha256 ${asset.sha256}",
            )
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
        return out
    }

    private fun http(url: String): InputStream {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != HTTP_OK) {
            response.body().close()
            throw IOException("$url answered HTTP ${response.statusCode()}; is the asset uploaded?")
        }
        return response.body()
    }

    private const val HTTP_OK = 200
}
