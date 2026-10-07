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
