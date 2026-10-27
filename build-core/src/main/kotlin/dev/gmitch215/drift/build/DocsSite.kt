package dev.gmitch215.drift.build

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URLDecoder

class DocsSiteException(message: String) : RuntimeException(message)

/** A Markdown file in the repository and the page it becomes. */
class DocsPage(
    val source: String,
    val file: String,
    val title: String,
    val description: String,
    val front: Boolean = false,
    val guide: Boolean = false,
)

abstract class DocsSite : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pages: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val template: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val icons: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val atlas: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val engine: DirectoryProperty

    @get:Input
    abstract val domain: Property<String>

    @get:Internal
    abstract val repository: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        try {
            DocsSiteBuilder.assemble(
                out = outputDir.get().asFile,
                repo = repository.get().asFile,
                template = template.get().asFile.readText(),
                icons = icons.get().asFile,
                atlas = atlas.get().asFile,
                engine = engine.get().asFile,
                domain = domain.get(),
            )
        } catch (e: DocsSiteException) {
            throw GradleException(e.message ?: "cannot render the documentation site", e)
        }
    }
}

object DocsSiteBuilder {
    const val REPO = "https://github.com/gmitch215/Drift"
    const val BRANCH = "master"
    const val PLACEHOLDER = "<!-- readme -->"

    private val SLOTS =
        listOf("title", "description", "nav", "guides", "hero", "pager").map { "<!-- $it -->" } +
            PLACEHOLDER

    private val ASSETS = listOf("drift.png", "drift_128.png", "drift.ico", "docs-render.js")

    val PAGES = listOf(
        DocsPage(
            "README.md",
            "index.html",
            "Drift",
            "Why it passes here and fails there",
            front = true,
        ),
        DocsPage(
            "docs/README.md",
            "docs.html",
            "Documentation",
            "The guides, the manual and the reference documents.",
            guide = true,
        ),
        DocsPage(
            "docs/why.md",
            "why.html",
            "Why",
            "Every flow of drift why, with real output.",
            guide = true,
        ),
        DocsPage(
            "docs/install.md",
            "install.html",
            "Install",
            "Downloads, package managers, the install script and building from source.",
            guide = true,
        ),
        DocsPage(
            "docs/try-it.md",
            "try-it.html",
            "Try It",
            "Commands to run against the recorded fixtures, with their output.",
            guide = true,
        ),
        DocsPage(
            "docs/portability-atlas.md",
            "portability-atlas.html",
            "Kotlin Portability Atlas",
            "The probes, the columns and how to read them.",
            guide = true,
        ),
        DocsPage(
            "docs/architecture.md",
            "architecture.html",
            "Architecture",
            "The modules, their dependencies and the investigation chain.",
            guide = true,
        ),
        DocsPage(
            "docs/shared-code.md",
            "shared-code.html",
            "Shared Code",
            "How much of the code every target shares.",
            guide = true,
        ),
        DocsPage(
            "docs/limits.md",
            "limits.html",
            "Limits",
            "What Drift cannot do, by platform.",
            guide = true,
        ),
        DocsPage(
            "MANUAL.md",
            "manual.html",
            "Studio Manual",
            "What each Studio screen shows and how to use it, with screenshots.",
        ),
        DocsPage(
            "ADVANCED_USAGE.md",
            "advanced-usage.html",
            "Advanced Usage",
            "Every drift command and flag, with output from real runs.",
        ),
        DocsPage(
            "TECHNICAL_REPORT.md",
            "technical-report.html",
            "Technical Report",
            "The design of Drift, the Atlas findings and the evaluation.",
        ),
    )

    private val NAV = listOf(
        Triple("Guides", "docs.html", "Install, try it, the Atlas, the design and the limits."),
        Triple("Manual", "manual.html", "What each Studio screen shows, with pictures."),
        Triple("Usage", "advanced-usage.html", "Every command and flag, with real output."),
        Triple("Report", "technical-report.html", "The design, the evidence and the numbers."),
        Triple("Atlas", "atlas/#atlas", "Kotlin behavior recorded per target, with Studio."),
        Triple("Engine", "engine/", "KDoc for the engine modules, from Dokka."),
        Triple("GitHub", REPO, "Source, releases and issues."),
    )

    private val STEPS = listOf(
        Triple("Learn", "index.html#what-it-looks-at", "What Drift reads and compares."),
        Triple("Install", "install.html", "A release archive, a package or source."),
        Triple("Diagnose", "try-it.html", "Run a first diagnosis on a recorded failure."),
        Triple("Use", "advanced-usage.html", "Every command and flag, with real output."),
        Triple("Read", "technical-report.html", "The design, the evidence and the numbers."),
    )

    private val HEADER = Regex("^<div[\\s\\S]*?\\n</div>\\n+(---\\n+)?")
    private val TAGLINE = Regex("<p[^>]*>([^<]+)</p>")
    private val ID = Regex("\\sid=\"([^\"]+)\"")
    private val SCHEME = Regex("^([a-z][a-z0-9+.-]*:|//)", RegexOption.IGNORE_CASE)

    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        AutolinkExtension.create(),
        HeadingAnchorExtension.create(),
    )

    private class Fragment(val from: String, val target: DocsPage, val id: String)

    private class Rendered(val page: DocsPage, val html: String)

    fun assemble(
        out: File,
        repo: File,
        template: String,
        icons: File,
        atlas: File,
        engine: File,
        domain: String,
    ) {
        val missing = SLOTS.filter { it !in template }
        if (missing.isNotEmpty()) {
            throw DocsSiteException("the site template has no ${missing.joinToString(", ")} line")
        }
        val fragments = mutableListOf<Fragment>()
        val images = sortedSetOf<String>()
        val rendered = PAGES.map { render(it, repo, fragments, images) }
        val ids = rendered.associate {
            it.page to
                ID.findAll(it.html).map { m -> m.groupValues[1] }.toSet()
        }
        for (f in fragments) {
            if (f.id !in ids.getValue(f.target)) {
                throw DocsSiteException(
                    "${f.from} links to ${f.target.source}#${f.id}, which has no such heading",
                )
            }
        }
        val tagline = tagline(repo)
        for ((label, href) in STEPS.map { it.first to it.second }) {
            val target = PAGES.first { it.file == href.substringBefore('#') }
            val id = href.substringAfter('#', "")
            if (id.isNotEmpty() && id !in ids.getValue(target)) {
                throw DocsSiteException("the $label step links to $href, which has no such heading")
            }
        }
        out.deleteRecursively()
        out.mkdirs()
        for (r in rendered) {
            val hero = if (r.page.front) hero(tagline) else ""
            val description = if (r.page.front) tagline else r.page.description
            val title = if (r.page.front) r.page.title else "${r.page.title} - Drift"
            val html = template
                .replace("<!-- title -->", SiteRender.escape(title))
                .replace("<!-- description -->", SiteRender.escape(description))
                .replace("<!-- nav -->", nav(r.page))
                .replace("<!-- guides -->", guides(r.page))
                .replace("<!-- hero -->", hero)
                .replace("<!-- pager -->", pager(r.page))
                .replace(PLACEHOLDER, r.html.trimEnd())
            write(out, r.page.file, html)
        }
        for (asset in ASSETS) {
            val file = icons.resolve(asset)
            if (!file.isFile) throw DocsSiteException("the assets directory has no $asset")
            file.copyTo(out.resolve(asset))
        }
        for (image in images) {
            val target = out.resolve(image)
            target.parentFile.mkdirs()
            repo.resolve(image).copyTo(target, overwrite = true)
        }
        atlas.copyRecursively(out.resolve("atlas"))
        engine.copyRecursively(out.resolve("engine"))
        write(out, ".nojekyll", "")
        write(out, "CNAME", "$domain\n")
    }

    private fun write(out: File, path: String, text: String) {
        val file = out.resolve(path)
        file.parentFile.mkdirs()
        file.writeBytes(text.toByteArray(Charsets.UTF_8))
    }

    private fun readme(repo: File): String = repo.resolve("README.md").readText()

    private fun tagline(repo: File): String {
        val header = HEADER.find(readme(repo))?.value
            ?: throw DocsSiteException("README.md has no header block to take the tagline from")
        return TAGLINE.find(header)?.groupValues?.get(1)?.trim()
            ?: throw DocsSiteException("the README.md header block has no tagline paragraph")
    }

    private fun render(
        page: DocsPage,
        repo: File,
        fragments: MutableList<Fragment>,
        images: MutableSet<String>,
    ): Rendered {
        val file = repo.resolve(page.source)
        if (!file.isFile) throw DocsSiteException("${page.source} does not exist")
        var markdown = file.readText()
        if (page.front) {
            val header = HEADER.find(markdown)
                ?: throw DocsSiteException("${page.source} has no header block to replace")
            markdown = markdown.removePrefix(header.value)
        }
        val document = Parser.builder().extensions(extensions).build().parse(markdown)
        document.accept(
            object : AbstractVisitor() {
                override fun visit(link: Link) {
                    link.destination = resolve(page, link.destination, repo, fragments)
                    super.visit(link)
                }

                override fun visit(image: Image) {
                    image.destination = image(page, image.destination, repo, images)
                    super.visit(image)
                }
            },
        )
        val html = HtmlRenderer.builder().extensions(extensions).build().render(document)
            .replace("<table>", "<div class=\"table\"><table>")
            .replace("</table>", "</table></div>")
        return Rendered(page, html)
    }

    private fun normalize(page: DocsPage, path: String): String {
        val parts = ArrayDeque<String>()
        val dir = page.source.substringBeforeLast('/', "")
        for (part in "$dir/$path".split('/')) {
            when (part) {
                "", "." -> Unit

                ".." -> if (parts.isEmpty()) {
                    throw DocsSiteException("${page.source} links to $path outside the repository")
                } else {
                    parts.removeLast()
                }

                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun image(page: DocsPage, src: String, repo: File, images: MutableSet<String>): String {
        if (SCHEME.containsMatchIn(src) || src.startsWith("/")) return src
        val path = normalize(page, src)
        if (!repo.resolve(path).isFile) {
            throw DocsSiteException("${page.source} shows $src, which is not a repository file")
        }
        images += path
        return path
    }

    private fun resolve(
        page: DocsPage,
        href: String,
        repo: File,
        fragments: MutableList<Fragment>,
    ): String {
        if (SCHEME.containsMatchIn(href) || href.startsWith("/")) return href
        val path = href.substringBefore('#')
        val id = if ('#' in href) href.substringAfter('#') else ""
        if (path.isEmpty()) {
            if (id.isNotEmpty()) fragments += Fragment(page.source, page, id)
            return href
        }
        val target = normalize(page, path)
        val site = PAGES.firstOrNull { it.source == target }
        if (site != null) {
            if (id.isNotEmpty()) fragments += Fragment(page.source, site, id)
            return site.file + if (id.isEmpty()) "" else "#$id"
        }
        val file = repo.resolve(target)
        val kind = when {
            file.isDirectory -> "tree"

            file.isFile -> "blob"

            else -> throw DocsSiteException(
                "${page.source} links to $href, which is not a file or directory in the repository",
            )
        }
        return "$REPO/$kind/$BRANCH/$target" + if (id.isEmpty()) "" else "#$id"
    }

    private fun card(label: String, href: String, text: String, extra: String = "") =
        "<a class=\"card$extra\" href=\"$href\"><strong>$label</strong>" +
            "<span>${SiteRender.escape(text)}</span></a>"

    private fun hero(tagline: String): String {
        val steps = STEPS.joinToString("\n") { (label, href, text) ->
            "<li>${card(label, href, text)}</li>"
        }
        val more = NAV.takeLast(3).joinToString("\n") { (label, href, text) ->
            card(label, href, text)
        }
        val logo = "<img src=\"drift.png\" alt=\"\" width=\"96\" height=\"96\">"
        return "<div class=\"hero\">\n$logo\n" +
            "<h1>Drift</h1>\n<p class=\"lead\">${SiteRender.escape(tagline)}</p>\n</div>\n" +
            "<ol class=\"steps\">\n$steps\n</ol>\n<div class=\"links\">\n$more\n</div>"
    }

    private fun pager(current: DocsPage): String {
        val at = PAGES.indexOf(current)
        fun link(page: DocsPage?, label: String, extra: String = "") = page?.let {
            "<a class=\"card$extra\" href=\"${it.file}\" rel=\"${label.lowercase()}\">" +
                "<span>$label</span><strong>${SiteRender.escape(it.title)}</strong></a>"
        }
        val links = listOfNotNull(
            link(PAGES.getOrNull(at - 1), "Prev"),
            link(PAGES.getOrNull(at + 1), "Next", " next"),
        )
        if (links.isEmpty()) return ""
        return "<div class=\"pager\">\n${links.joinToString("\n")}\n</div>"
    }

    private fun nav(current: DocsPage): String {
        fun link(href: String, label: String, page: DocsPage? = null) =
            "<a href=\"$href\"${if (page == current) " aria-current=\"page\"" else ""}>$label</a>"
        val brand = "<a class=\"brand\" href=\"index.html\"" +
            "${if (current.front) " aria-current=\"page\"" else ""}>" +
            "<img src=\"drift_128.png\" alt=\"\" width=\"28\" height=\"28\">Drift</a>"
        val items = NAV.map { (label, href) ->
            val page = PAGES.firstOrNull { it.file == href }
            link(href, label, if (current.guide && href == "docs.html") current else page)
        }
        return (listOf(brand) + items).joinToString("\n")
    }

    private fun guides(current: DocsPage): String {
        if (!current.guide) return ""
        val items = PAGES.filter { it.guide }.joinToString("\n") {
            val here = if (it == current) " aria-current=\"page\"" else ""
            "<a href=\"${it.file}\"$here>${SiteRender.escape(it.title)}</a>"
        }
        return "<div class=\"guides\">\n$items\n</div>"
    }
}

class BrokenLink(val page: String, val href: String)

class LinkScan(val checked: Int, val broken: List<BrokenLink>)

object SiteLinks {
    private val ATTRIBUTE = Regex("\\s(?:href|src)=(?:\"([^\"]*)\"|'([^']*)')")
    private val EXTERNAL = Regex("^([a-z][a-z0-9+.-]*:|//)", RegexOption.IGNORE_CASE)

    /** Every relative href and src in the html pages under root, and those that do not resolve. */
    fun scan(root: File): LinkScan {
        val base = root.canonicalFile
        var checked = 0
        val broken = mutableListOf<BrokenLink>()
        val pages = base.walkTopDown().filter { it.isFile && it.extension == "html" }
            .sortedBy { it.relativeTo(base).invariantSeparatorsPath }
        for (page in pages) {
            val name = page.relativeTo(base).invariantSeparatorsPath
            for (m in ATTRIBUTE.findAll(page.readText())) {
                val href = (m.groups[1] ?: m.groups[2])!!.value.replace("&amp;", "&")
                val path = href.substringBefore('#').substringBefore('?')
                if (path.isEmpty() || EXTERNAL.containsMatchIn(path)) continue
                checked++
                if (!resolves(base, page.parentFile, path)) broken += BrokenLink(name, href)
            }
        }
        return LinkScan(checked, broken)
    }

    private fun resolves(base: File, dir: File, path: String): Boolean {
        val decoded = try {
            URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val target = (
            if (decoded.startsWith(
                    "/",
                )
            ) {
                base.resolve(decoded.trimStart('/'))
            } else {
                dir.resolve(decoded)
            }
            )
            .normalize()
        if (target != base && !target.path.startsWith(base.path + File.separator)) return false
        return target.isFile || (target.isDirectory && target.resolve("index.html").isFile)
    }
}

abstract class CheckSiteLinks : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val siteDir: DirectoryProperty

    @get:Input
    abstract val unchecked: ListProperty<String>

    @TaskAction
    fun check() {
        val scan = SiteLinks.scan(siteDir.get().asFile)
        val (skipped, failed) = scan.broken.partition { b ->
            unchecked.get().any { b.page.startsWith("$it/") }
        }
        for (prefix in unchecked.get()) {
            val count = skipped.count { it.page.startsWith("$prefix/") }
            logger.lifecycle("$prefix/: $count unresolved relative links, not counted as failures")
        }
        logger.lifecycle("${scan.checked} relative links scanned, ${failed.size} unresolved")
        if (failed.isNotEmpty()) {
            val shown = failed.take(20).joinToString("\n") { "  ${it.page}: ${it.href}" }
            throw GradleException("${failed.size} unresolved links in the site:\n$shown")
        }
    }
}
