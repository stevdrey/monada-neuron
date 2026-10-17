val neuronVersion = providers.gradleProperty("neuronVersion").orNull
    ?: error("Missing -PneuronVersion=<published Neuron version>")
val storeVersion = providers.gradleProperty("resonanceStoreVersion").orNull
    ?: error("Missing -PresonanceStoreVersion=<pinned Store version>")

// The single declared coordinate: everything else must arrive through the adapter's published metadata.
val adapterCoordinate = "monada.neuron:monada-neuron-resonance-adapter:$neuronVersion"

// Both scopes must hold exactly these Neuron coordinates and these Store modules (the adapter's `api` dependencies).
val requiredNeuron = setOf(
    "monada.neuron:monada-neuron:$neuronVersion",
    "monada.neuron:monada-neuron-resonance-adapter:$neuronVersion"
)
val requiredStore = setOf(
    "com.monada:monada-api:$storeVersion",
    "com.monada:monada-core:$storeVersion"
)
// Store's internal modules come from its own published metadata and may change with Store `main`: any other
// `com.monada` module is accepted only at the pinned version, and any other group (third-party, provider) fails.
val storeGroup = "com.monada"

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

    val printResolution = tasks.register("printResolution") {
        group = "verification"
        description = "Prints and verifies the resolved Neuron, adapter and Store dependency graph"
        val consumerName = project.name
        val compileArtifacts = configurations.getByName("compileClasspath").incoming.artifacts.resolvedArtifacts
        val runtimeArtifacts = configurations.getByName("runtimeClasspath").incoming.artifacts.resolvedArtifacts
        doLast {
            fun verify(label: String, artifacts: Set<ResolvedArtifactResult>) {
                println("[$label] declared: $adapterCoordinate")
                val resolved = artifacts.map { artifact ->
                    val id = artifact.id.componentIdentifier
                    println("[$label] $id -> ${artifact.file.name}")
                    check(id is ModuleComponentIdentifier) { "Unexpected non-repository component in $label: $id" }
                    "${id.group}:${id.module}:${id.version}"
                }.toSet()
                check(resolved.containsAll(requiredNeuron + requiredStore)) {
                    "[$label] expected ${requiredNeuron + requiredStore} but resolved $resolved"
                }
                val others = resolved - requiredNeuron - requiredStore
                check(others.all { it.startsWith("$storeGroup:") && it.endsWith(":$storeVersion") }) {
                    "[$label] only $storeGroup modules at $storeVersion may be added, but resolved $others"
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
    description = "Verifies dependency resolution and runs the classpath and JPMS embedded-host loops"
    subprojects.forEach { consumer ->
        dependsOn(consumer.tasks.named("printResolution"), consumer.tasks.named("run"))
    }
}
