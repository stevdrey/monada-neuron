// Standalone Forge-like host consumer of the published Neuron core artifact (Issue #102). It is NOT part of
// the root build: no includeBuild, no project(...) substitution, no source copying and no manually supplied
// jars. The core resolves only from the repository passed as -PneuronRepo (build/consumer-repo, the same
// repository consumerSmokeTest and jpmsConsumerTest use), so the fixture is core-only: no Store, no adapter,
// no evaluation module and no provider artifact can resolve.

val neuronRepo = providers.gradleProperty("neuronRepo").orNull
    ?: error("Missing -PneuronRepo=<absolute path to build/consumer-repo>")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The only repository for the core: an undeclared or third-party dependency in the published metadata fails.
        maven {
            name = "neuronLocal"
            url = uri(file(neuronRepo))
        }
        // Serves the JUnit test runtime of classpath-host and nothing else: the content filter keeps a leaked
        // dependency of the core from resolving here, and printResolution still asserts the main classpaths.
        mavenCentral {
            content {
                includeGroupAndSubgroups("org.junit")
                includeGroup("org.opentest4j")
                includeGroup("org.apiguardian")
            }
        }
    }
}

rootProject.name = "monada-neuron-forge-host"

include("classpath-host", "module-host")
