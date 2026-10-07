package dev.gmitch215.drift.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaticSiteTest {
	private val files = FakeFiles().apply {
		put("/dist/index.html", "<title>Drift Studio</title>")
		put("/dist/studio.js", "js")
		put("/dist/bfa5198fb2fe683c613a.wasm", "wasm")
		put("/dist/icon.png", "png")
		put("/dist/data.unknown", "x")
		put("/dist/sub/index.html", "sub index")
		put("/dist/empty/keep.txt", "keep")
		put("/dist/.env", "SECRET")
		put("/dist/.git/config", "SECRET")
		put("/dist/a b.txt", "spaced")
		put("/dist/caf\u00e9.txt", "accent")
		put("/secret/passwd", "root:x")
		put("/dist-other/file", "sibling")
		links["/dist/escape"] = "/secret"
		links["/dist/inside"] = "/dist/sub"
		links["/dist/leak.txt"] = "/secret/passwd"
	}
	private val hosts = HostPolicy(listOf("drift.studio", "*.drift.local"))
	private val site = StaticSite("/dist", files, hosts)

	private fun get(
		target: String,
		host: String = "localhost:8080",
		vararg headers: Pair<String, String>,
		method: String = "GET",
	) = site.respond(Request(method, target, mapOf("host" to host) + headers))

	private fun header(response: Response, name: String) =
		response.headers.firstOrNull { it.first == name }?.second

	@Test
	fun theRootServesIndexHtml() {
		val response = get("/")
		assertEquals(200, response.status)
		assertEquals("<title>Drift Studio</title>", response.body.decodeToString())
		assertEquals("text/html; charset=utf-8", header(response, "Content-Type"))
		assertEquals("no-cache", header(response, "Cache-Control"))
	}

	@Test
	fun mimeTypesCoverTheBuildOutputs() {
		assertEquals("application/wasm", header(get("/bfa5198fb2fe683c613a.wasm"), "Content-Type"))
		assertEquals("text/javascript; charset=utf-8", header(get("/studio.js"), "Content-Type"))
		assertEquals("image/png", header(get("/icon.png"), "Content-Type"))
		assertEquals("application/octet-stream", header(get("/data.unknown"), "Content-Type"))
		assertEquals("application/manifest+json", Mime.of("site.webmanifest"))
		assertEquals("image/svg+xml", Mime.of("A.SVG"))
		assertEquals("application/octet-stream", Mime.of("noextension"))
	}

	@Test
	fun contentHashedFilesAreImmutableAndTheRestRevalidate() {
		val hashed = get("/bfa5198fb2fe683c613a.wasm")
		assertEquals("public, max-age=31536000, immutable", header(hashed, "Cache-Control"))
		assertEquals("no-cache", header(get("/studio.js"), "Cache-Control"))
	}

	@Test
	fun aDirectoryServesItsIndexAndNeverAListing() {
		assertEquals("sub index", get("/sub").body.decodeToString())
		assertEquals("sub index", get("/sub/").body.decodeToString())
		assertEquals(404, get("/empty").status)
		assertEquals(404, get("/empty/").status)
	}

	@Test
	fun aMissingFileIsA404WithNoSpaFallbackForAssets() {
		assertEquals(404, get("/missing.js").status)
		assertEquals(404, get("/missing.js", "localhost", "accept" to "text/html").status)
		assertEquals(404, get("/missing").status)
		assertEquals(404, get("/missing", "localhost", "accept" to "*/*").status)
	}

	@Test
	fun aNavigationToAnUnknownRouteFallsBackToIndexHtml() {
		val page = get("/some/route", "localhost", "accept" to "text/html,application/xhtml+xml")
		assertEquals(200, page.status)
		assertEquals("<title>Drift Studio</title>", page.body.decodeToString())
		val html = "accept" to "text/html"
		val navigate = get("/route", "localhost", html, "sec-fetch-mode" to "navigate")
		assertEquals(200, navigate.status)
		val fetch = get("/route", "localhost", "accept" to "text/html", "sec-fetch-mode" to "cors")
		assertEquals(404, fetch.status)
	}

	@Test
	fun headCarriesTheSameStatusAndLengthAsGet() {
		val head = get("/studio.js", method = "HEAD")
		assertEquals(200, head.status)
		assertEquals(2, head.body.size)
	}

	@Test
	fun traversalVectorsNeverReachOutsideTheRoot() {
		val bad400 = listOf(
			"/../secret/passwd",
			"/a/../../secret/passwd",
			"/%2e%2e/secret/passwd",
			"/%2E%2E%2Fsecret/passwd",
			"/..%2fsecret/passwd",
			"/%2e%2e%5csecret%5cpasswd",
			"/..\\secret\\passwd",
			"/sub/..\\..\\secret",
			"/%00",
			"/index.html%00.png",
			"/%c0%ae%c0%ae/secret",
			"/%ff",
			"/%zz",
			"/%",
			"/%2",
			"/%+1",
			"/a:b",
			"/C:/Windows/win.ini",
			"/index.html.",
			"/index.html%20",
			"/..%00/",
			"/\u00e9",
			"/%0d%0a",
			"/a%09b",
			"/%7f",
			"/...",
			"/%2e%2e%2e/x",
			"/sub/.. /x",
		)
		for (target in bad400) assertEquals(400, get(target).status, target)
	}

	@Test
	fun anEncodedSlashOrDotThatIsOnlyLiteralJustMisses() {
		assertEquals(404, get("/%252e%252e/secret/passwd").status)
		assertEquals(404, get("//secret/passwd").status)
		assertEquals(404, get("/dist/index.html").status)
		assertEquals(404, get("/dist-other/file").status)
	}

	@Test
	fun hiddenFilesAreNotServed() {
		assertEquals(404, get("/.env").status)
		assertEquals(404, get("/.git/config").status)
		assertEquals(404, get("/sub/.hidden").status)
		assertEquals(404, get("/%2eenv").status)
	}

	@Test
	fun symlinksInsideTheRootWorkAndOnesOutsideAreRefused() {
		assertEquals("sub index", get("/inside").body.decodeToString())
		assertEquals(404, get("/escape/passwd").status)
		assertEquals(404, get("/leak.txt").status)
		assertEquals(404, get("/escape").status)
	}

	@Test
	fun theQueryStringAndFragmentAreIgnoredAndEncodedNamesDecode() {
		assertEquals(200, get("/studio.js?v=3#x").status)
		assertEquals("spaced", get("/a%20b.txt").body.decodeToString())
		assertEquals("accent", get("/caf%C3%A9.txt").body.decodeToString())
		assertEquals("js", get("/%73tudio.js").body.decodeToString())
	}

	@Test
	fun theHostHeaderIsCheckedBeforeAnythingElse() {
		val allowed = listOf(
			"localhost", "localhost:8080", "LOCALHOST:1", "127.0.0.1:8080", "[::1]:8080", "[::1]",
			"drift.studio:8443", "Drift.Studio", "drift.studio.", "a.drift.local",
			"x.y.drift.local:9",
		)
		for (host in allowed) assertEquals(200, get("/", host).status, host)
		val refused = listOf(
			"evil.example", "evil.example:8080", "localhost.evil.example", "127.0.0.2",
			"127.0.0.1.evil.example", "drift.local", "drift.studio.evil.example", "xdrift.studio",
			"[::2]", "0.0.0.0", "localhost@evil.example", "evil.example:80@localhost", "",
		)
		for (host in refused) assertEquals(403, get("/", host).status, host)
		assertEquals(403, get("/../x", "evil.example").status)
		assertEquals("forbidden host\n", get("/", "evil.example").body.decodeToString())
	}

	@Test
	fun bindAllAcceptsAnyIpLiteralAndNothingElse() {
		val open = StaticSite("/dist", files, HostPolicy(emptyList(), anyAddress = true))
		fun status(host: String) = open.respond(Request("GET", "/", mapOf("host" to host))).status
		assertEquals(200, status("192.168.1.20:8080"))
		assertEquals(200, status("[fe80::1]:8080"))
		assertEquals(403, status("evil.example"))
		assertEquals(403, status("1.2.3.evil"))
	}

	@Test
	fun theRootMustExist() {
		val failure = assertFailsWith<ServeFailure> { StaticSite("/nope", files, HostPolicy()) }
		assertEquals("/nope does not exist", failure.message)
	}

	@Test
	fun segmentsDecodeOnceAndDropEmptyAndDotParts() {
		assertEquals(listOf("a", "b"), StaticSite.segments("/a//./b/"))
		assertEquals(emptyList(), StaticSite.segments("/"))
		assertEquals(listOf("a%2eb"), StaticSite.segments("/a%252eb"))
		assertNull(StaticSite.segments("/a/../b"))
		assertNotNull(StaticSite.segments("/%e2%82%ac"))
		assertTrue(StaticSite.text(404, "x").body.isNotEmpty())
	}

	@Test
	fun aWindowsRootIsComparedWithoutCase() {
		val disk = FakeFiles().apply { put("/Dist/index.html", "ok") }
		fun respond(ignoreCase: Boolean): Int {
			val shouting = object : SiteFiles by disk {
				override val ignoreCase = ignoreCase

				override fun canonical(path: String) = disk.canonical(path)?.let {
					if (it.endsWith("index.html")) it.lowercase() else it
				}

				override fun read(path: String) =
					disk.tree.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value
			}
			val site = StaticSite("/Dist", shouting, HostPolicy())
			return site.respond(Request("GET", "/", mapOf("host" to "localhost"))).status
		}
		assertEquals(200, respond(ignoreCase = true))
		assertEquals(404, respond(ignoreCase = false))
	}
}
