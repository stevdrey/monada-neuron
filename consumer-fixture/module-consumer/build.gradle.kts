// Module-path consumer: `requires monada.neuron` (an automatic module named by the jar manifest). It runs
// without jdk.incubator.vector and without any Store, evaluation or provider artifact.
plugins {
    application
}

val neuronVersion = providers.gradleProperty("neuronVersion").orNull
    ?: error("Missing -PneuronVersion=<published Neuron version>")
val neuronCoordinate = "monada.neuron:monada-neuron:$neuronVersion"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(27))
    }
}

dependencies {
    implementation(neuronCoordinate)
}

application {
    mainModule.set("monada.neuron.consumer.jpms")
    mainClass.set("consumer.jpms.ModuleConsumer")
}

val printResolution = tasks.register("printResolution") {
    group = "verification"
    description = "Prints and verifies the resolved Neuron dependency graph"
    val runtimeArtifacts = configurations.runtimeClasspath.get().incoming.artifacts.resolvedArtifacts
    val expectedVersion = neuronVersion
    doLast {
        println("[runtimeClasspath] declared: $neuronCoordinate")
        val resolved = runtimeArtifacts.get().map { artifact ->
            val id = artifact.id.componentIdentifier
            println("[runtimeClasspath] $id -> ${artifact.file.name}")
            check(id is ModuleComponentIdentifier) { "Unexpected non-repository component: $id" }
            "${id.group}:${id.module}:${id.version}"
        }.toSet()
        val expected = setOf("monada.neuron:monada-neuron:$expectedVersion")
        check(resolved == expected) { "expected exactly $expected (no Store, evaluation or provider) but resolved $resolved" }
    }
}

tasks.named("run") {
    mustRunAfter(printResolution)
}

tasks.register("verifyConsumer") {
    group = "verification"
    description = "Verifies dependency resolution and runs the module-path consumer"
    dependsOn(printResolution, tasks.named("run"))
}
