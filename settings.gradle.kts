rootProject.name = "monada-neuron"
include("monada-neuron-evaluation")

// The Resonance Store is an unpublished sibling checkout. The adapter module is registered only when
// it is present, so core builds and `./gradlew test` work without it; see ADR 0018.
val resonanceStoreDir = file("../monada-resonance-store")
if (resonanceStoreDir.resolve("settings.gradle.kts").exists()) {
    include("monada-neuron-resonance-adapter")
    includeBuild(resonanceStoreDir)
}
