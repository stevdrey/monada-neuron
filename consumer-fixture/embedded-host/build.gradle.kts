val neuronVersion = providers.gradleProperty("neuronVersion").orNull
    ?: error("Missing -PneuronVersion=<published Neuron version>")
val storeVersion = providers.gradleProperty("resonanceStoreVersion").orNull
    ?: error("Missing -PresonanceStoreVersion=<pinned Store version>")

// The single declared coordinate: everything else must arrive through the adapter's published metadata.
val adapterCoordinate = "monada.neuron:monada-neuron-resonance-adapter:$neuronVersion"

// Compile scope: the adapter's `api` dependencies and Store's own `api` graph behind monada-api.
val expectedCompile = setOf(
    "monada.neuron:monada-neuron:$neuronVersion",
    "monada.neuron:monada-neuron-resonance-adapter:$neuronVersion",
    "com.monada:monada-api:$storeVersion",
    "com.monada:monada-core:$storeVersion",
    "com.monada:monada-encoder:$storeVersion",
    "com.monada:monada-storage:$storeVersion"
)
// Runtime adds Store's `implementation` modules; no evaluation, benchmark or provider artifact.
val expectedRuntime = expectedCompile + setOf(
    "com.monada:monada-index:$storeVersion",
    "com.monada:monada-learning:$storeVersion"
)

subprojects {
    apply(plugin = "application")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(27))
        }
    }

    dependencies {
        "implementation"(adapterCoordinate)
    }

    // Always re-read the freshly published SNAPSHOTs instead of a cached copy.
    configurations.configureEach {
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
    }

    val printResolution = tasks.register("printResolution") {
        group = "verification"
        description = "Prints and verifies the resolved Neuron, adapter and Store dependency graph"
        val consumerName = project.name
        val compileArtifacts = configurations.getByName("compileClasspath").incoming.artifacts.resolvedArtifacts
        val runtimeArtifacts = configurations.getByName("runtimeClasspath").incoming.artifacts.resolvedArtifacts
        doLast {
            fun verify(label: String, artifacts: Set<ResolvedArtifactResult>, expected: Set<String>) {
                println("[$label] declared: $adapterCoordinate")
                val resolved = artifacts.map { artifact ->
                    val id = artifact.id.componentIdentifier
                    println("[$label] $id -> ${artifact.file.name}")
                    check(id is ModuleComponentIdentifier) { "Unexpected non-repository component in $label: $id" }
                    "${id.group}:${id.module}:${id.version}"
                }.toSet()
                check(resolved == expected) { "[$label] expected $expected but resolved $resolved" }
            }
            verify("$consumerName compileClasspath", compileArtifacts.get(), expectedCompile)
            verify("$consumerName runtimeClasspath", runtimeArtifacts.get(), expectedRuntime)
        }
    }

    tasks.named("run") {
        mustRunAfter(printResolution)
    }
}

tasks.register("verifyConsumer") {
    group = "verification"
    description = "Verifies dependency resolution and runs the classpath and JPMS embedded-host loops"
    subprojects.forEach { consumer ->
        dependsOn(consumer.tasks.named("printResolution"), consumer.tasks.named("run"))
    }
}
