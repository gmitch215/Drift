import dev.gmitch215.drift.build.CheckSiteLinks
import dev.gmitch215.drift.build.DocsSite
import dev.gmitch215.drift.build.DocsSiteBuilder

plugins {
	id("org.jetbrains.dokka")
}

dokka {
	moduleName = "Drift Engine Documentation"

	dokkaPublications.html {
		includes.from("Module.md")
	}

	pluginsConfiguration.html {
		footerMessage = "Copyright (c) Gregory Mitchell"
	}
}

dependencies {
	rootProject.subprojects.forEach { dokka(project(it.path)) }
}

val docsSite = tasks.register<DocsSite>("docsSite") {
	dependsOn("dokkaGenerate", "atlasSite")
	pages.from(DocsSiteBuilder.PAGES.map { layout.projectDirectory.file(it.source) })
	template = layout.projectDirectory.file("assets/site-template.html")
	icons = layout.projectDirectory.dir("assets")
	atlas = layout.buildDirectory.dir("atlas-site")
	engine = layout.buildDirectory.dir("dokka/html")
	domain = "drift.gmitch215.dev"
	repository = layout.projectDirectory
	outputDir = layout.buildDirectory.dir("site")
	doNotTrackState("links are checked against the repository, which is not an input")
}

val checkSiteLinks = tasks.register<CheckSiteLinks>("checkSiteLinks") {
	siteDir = docsSite.flatMap { it.outputDir }
	unchecked.add("engine")
}

docsSite {
	finalizedBy(checkSiteLinks)
}
