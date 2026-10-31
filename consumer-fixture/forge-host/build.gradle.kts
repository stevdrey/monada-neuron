val neuronVersion = providers.gradleProperty("neuronVersion").orNull
    ?: error("Missing -PneuronVersion=<published Neuron version>")

// The single declared coordinate. The host is core-only (readiness contract, Section 8.1): the resolved graph
// must hold exactly this artifact, so a Store, adapter, evaluation or provider dependency leaking into the
// published metadata fails the build.
val neuronCoordinate = "monada.neuron:monada-neuron:$neuronVersion"

subprojects {
    apply(plugin = "application")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(27))
        }
    }

    dependencies {
        "implementation"(neuronCoordinate)
    }

    val printResolution = tasks.register("printResolution") {
        group = "verification"
        description = "Prints and verifies that the resolved dependency graph is exactly the Neuron core"
        val consumerName = project.name
        val compileArtifacts = configurations.getByName("compileClasspath").incoming.artifacts.resolvedArtifacts
        val runtimeArtifacts = configurations.getByName("runtimeClasspath").incoming.artifacts.resolvedArtifacts
        doLast {
            fun verify(label: String, artifacts: Set<ResolvedArtifactResult>) {
                println("[$label] declared: $neuronCoordinate")
                val resolved = artifacts.map { artifact ->
                    val id = artifact.id.componentIdentifier
                    println("[$label] $id -> ${artifact.file.name}")
                    check(id is ModuleComponentIdentifier) { "Unexpected non-repository component in $label: $id" }
                    "${id.group}:${id.module}:${id.version}"
                }.toSet()
                check(resolved == setOf(neuronCoordinate)) {
                    "[$label] expected exactly $neuronCoordinate (no Store, adapter, evaluation or provider) but resolved $resolved"
                }
            }
            verify("$consumerName compileClasspath", compileArtifacts.get())
            verify("$consumerName runtimeClasspath", runtimeArtifacts.get())
        }
    }

    tasks.named("run") {
        mustRunAfter(printResolution)
    }
}

tasks.register("verifyConsumer") {
    group = "verification"
    description = "Verifies dependency resolution, runs the classpath and JPMS Forge-like hosts and the host unit tests"
    subprojects.forEach { consumer ->
        dependsOn(consumer.tasks.named("printResolution"), consumer.tasks.named("run"))
    }
    dependsOn(project(":classpath-host").tasks.named("test"))
}
