plugins {
	id("org.jetbrains.dokka")
}

dokka {
	moduleName = project.name

	dokkaSourceSets.configureEach {
		includes.from("Module.md")
		sourceLink {
			localDirectory = rootDir
			remoteUrl("https://github.com/gmitch215/Drift/blob/master")
			remoteLineSuffix = "#L"
		}
	}

	pluginsConfiguration.html {
		footerMessage = "Copyright (c) Gregory Mitchell"
	}
}

afterEvaluate {
	dokka.dokkaSourceSets.matching { it.name == "linuxMain" }.configureEach {
		val roots = project.files(sourceRoots.from.toList())
		sourceRoots.setFrom(roots.filter { "/src/posix/main" !in it.invariantSeparatorsPath })
	}
}
