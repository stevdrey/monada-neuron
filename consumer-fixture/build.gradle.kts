// Standalone external consumer of Monada Neuron (Issue #42). It is deliberately NOT part of the root
// build: it resolves the artifact only from the repository passed as -PneuronRepo, and it never
// enables jdk.incubator.vector.
plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(27))
    }
}

repositories {
    maven(url = uri(providers.gradleProperty("neuronRepo").get()))
    mavenCentral()
}

dependencies {
    implementation("monada.neuron:monada-neuron:0.1.0-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
