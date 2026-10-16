// Standalone embedded-host consumer of the published Neuron adapter (Issue #68, ADR 0032). It is NOT part
// of the root build: no includeBuild, no project(...) substitution, no source copying and no manually
// supplied jars. Neuron, the adapter and the Store resolve only from the isolated repository produced by
// `./gradlew publishIntegrationRepository`, which holds the actual Store publications copied verbatim.

val neuronRepo = providers.gradleProperty("neuronRepo").orNull
    ?: error("Missing -PneuronRepo=<absolute path to build/integration-repo>")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The only repository: an undeclared or third-party dependency in any published metadata fails.
        maven {
            name = "neuronIntegration"
            url = uri(file(neuronRepo))
        }
    }
}

rootProject.name = "monada-neuron-embedded-host"

include("classpath-host", "module-host")
