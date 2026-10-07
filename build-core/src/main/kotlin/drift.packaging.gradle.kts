import dev.gmitch215.drift.build.RenderPackaging

tasks.register<RenderPackaging>("renderPackaging") {
	version = project.version.toString()
	sums = layout.file(providers.gradleProperty("drift.sums").map { file(it) })
	license = layout.projectDirectory.file("LICENSE")
	templates = layout.projectDirectory.dir("packaging")
	outputDir = layout.buildDirectory.dir("packaging")
}
