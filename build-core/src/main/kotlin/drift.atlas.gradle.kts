import dev.gmitch215.drift.build.AtlasSite
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

val runtime = configurations.create("atlasCli") {
	isCanBeConsumed = false
	isCanBeResolved = true
	attributes {
		attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
		attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
	}
}

dependencies {
	runtime(project(":cli"))
}

val fixtures = layout.projectDirectory.dir("fixtures/atlas")

val datasetFile = layout.buildDirectory.file("atlas-work/dataset.json")

tasks.register<Sync>("atlasRuntime") {
	from(runtime)
	into(layout.buildDirectory.dir("atlas-runtime"))
}

val atlasDataset = tasks.register<JavaExec>("atlasDataset") {
	classpath = runtime
	mainClass = "dev.gmitch215.drift.cli.MainKt"
	val files = fixtures.asFileTree.matching { include("**/*.txt") }
	val out = datasetFile
	inputs.files(files)
	outputs.file(out)
	doFirst {
		val names = files.files.map { it.absolutePath }.sorted()
		args = listOf("atlas", "build") + names + listOf("--out", out.get().asFile.absolutePath)
	}
}

tasks.register<AtlasSite>("atlasSite") {
	dependsOn(atlasDataset, ":studio:wasmJsBrowserDistribution")
	dataset = datasetFile
	classification = layout.projectDirectory.file("atlas/classification.yml")
	columns = layout.projectDirectory.file("atlas/columns.yml")
	repros = layout.projectDirectory.dir("atlas/repros")
	transcripts = fixtures
	studio = project(":studio").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
	outputDir = layout.buildDirectory.dir("atlas-site")
}
