rootProject.name = "monada-neuron"
include("monada-neuron-evaluation")
include("monada-neuron-resonance-adapter")

// The Resonance Store is an optional sibling checkout consumed only by the resonance adapter module.
// Core builds and tests do not require it; see ADR 0018.
val resonanceStoreDir = file("../monada-resonance-store")
if (resonanceStoreDir.resolve("settings.gradle.kts").exists()) {
    includeBuild(resonanceStoreDir)
}
