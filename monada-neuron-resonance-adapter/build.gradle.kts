plugins {
    `java-library`
    `maven-publish`
}

group = "monada.neuron"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(27))
    }
    withSourcesJar()
    withJavadocJar()
}

val resonanceStoreVersion = providers.gradleProperty("resonanceStoreVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    // Root and Store types appear in the adapter's public signatures (ExecutionMemory, MonadaMemory,
    // ResonanceResult, RoutingObservation), so consumers need them at compile time: `api`, not
    // `implementation`. Inside this build the Store coordinates are substituted from the sibling checkout;
    // the published metadata carries the pinned version (ADR 0032).
    api(rootProject)
    api("com.monada:monada-api:$resonanceStoreVersion")
    api("com.monada:monada-core:$resonanceStoreVersion")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "monada.neuron.resonance.adapter",
            "Implementation-Title" to "Monada Neuron Resonance Store Adapter",
            "Implementation-Version" to project.version
        )
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "monada-neuron-resonance-adapter"
            pom {
                name.set("Monada Neuron Resonance Store Adapter")
                description.set("Optional adapter from Monada Resonance Store recall and execution exports to Monada Neuron ports.")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
    repositories {
        // The root build's isolated integration repository only; never a remote repository.
        maven {
            name = "integration"
            url = uri(rootProject.layout.buildDirectory.dir("integration-repo"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

tasks.withType<Test> {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("-add-modules", "jdk.incubator.vector")
}
