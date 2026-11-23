rootProject.name = "monada-neuron-consumer-fixture"

// Fails on a Java 27 runtime without the CVE-2026-114938 fix; see docs/java-runtime.md.
apply(from = "../gradle/java-runtime-floor.settings.gradle.kts")
