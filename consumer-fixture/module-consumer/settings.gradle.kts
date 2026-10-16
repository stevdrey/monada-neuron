// Standalone JPMS consumer of the published Monada Neuron core artifact (Issue #68, ADR 0032). It is NOT
// part of the root build: no includeBuild, no project(...) substitution and no manually supplied jars.
// The artifact resolves only from the repository passed as -PneuronRepo, and nothing else is declared,
// so any undeclared transitive dependency in the published metadata fails resolution.

val neuronRepo = providers.gradleProperty("neuronRepo").orNull
    ?: error("Missing -PneuronRepo=<absolute path to the local repository>")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "neuronLocal"
            url = uri(file(neuronRepo))
        }
    }
}

rootProject.name = "monada-neuron-module-consumer"
