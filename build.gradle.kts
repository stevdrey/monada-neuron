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

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Standalone/demo entry point. It is a plain task, not the `application` plugin, so it adds no
// distribution or start-script semantics to the library artifact (ADR 0025).
tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Runs the standalone Monada Neuron demo entry point"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("monada.neuron.Main")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "monada-neuron"
            pom {
                name.set("Monada Neuron")
                description.set("Experimental resonance-oriented cognitive core: signals, Aeons, nodes, and bounded cognitive cycles.")
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
        // Repository-local target used by `consumerSmokeTest`; never a remote repository.
        maven {
            name = "consumerSmoke"
            url = uri(layout.buildDirectory.dir("consumer-repo"))
        }
    }
}

// Builds a standalone consumer project against the published artifact, with no incubator flags.
tasks.register<Delete>("cleanConsumerRepo") {
    delete(layout.buildDirectory.dir("consumer-repo"))
}

tasks.named("publishMavenJavaPublicationToConsumerSmokeRepository") {
    dependsOn("cleanConsumerRepo")
}

tasks.register<Exec>("consumerSmokeTest") {
    group = "verification"
    description = "Compiles and runs an external Gradle consumer against the locally published artifact"
    dependsOn("publishMavenJavaPublicationToConsumerSmokeRepository")
    val fixtureDir = layout.projectDirectory.dir("consumer-fixture")
    val repoUri = layout.buildDirectory.dir("consumer-repo").get().asFile.toURI()
    inputs.dir(fixtureDir.dir("src"))
    inputs.file(fixtureDir.file("build.gradle.kts"))
    // Run the project wrapper from the project directory with the host OS launcher. The relative name
    // avoids quoting an absolute path (which may contain spaces) through `cmd /c` on Windows.
    workingDir = layout.projectDirectory.asFile
    val windows = System.getProperty("os.name").lowercase().contains("win")
    val launcher = if (windows) listOf("cmd", "/c", "gradlew.bat") else listOf("./gradlew")
    commandLine(launcher + listOf(
        "-p", fixtureDir.asFile.absolutePath,
        "test", "--rerun-tasks", "--refresh-dependencies",
        "-PneuronRepo=$repoUri"
    ))
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

tasks.withType<JavaExec> {
    // Demo/dev runs opt into the optional SIMD backend; library consumers are not required to.
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("-add-modules", "jdk.incubator.vector")
}
