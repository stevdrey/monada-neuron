rootProject.name = "monada-neuron"
include("monada-neuron-evaluation")

// The Resonance Store is an unpublished sibling checkout. The adapter module is registered only when
// it is present, so core builds and `./gradlew test` work without it; see ADR 0018.
val resonanceStoreDir = file("../monada-resonance-store")
if (resonanceStoreDir.resolve("settings.gradle.kts").exists()) {
    include("monada-neuron-resonance-adapter")
    includeBuild(resonanceStoreDir)
}

// Fails on a Java 27 runtime without the CVE-2026-114938 fix; see docs/java-runtime.md.
apply(from = "gradle/java-runtime-floor.settings.gradle.kts")
