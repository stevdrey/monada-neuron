import java.security.MessageDigest
import java.util.HexFormat

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
        // Repository-local target that holds Neuron, the adapter and the copied Store artifacts for
        // `embeddedHostConsumerTest` (ADR 0032); never a remote repository.
        maven {
            name = "integration"
            url = uri(layout.buildDirectory.dir("integration-repo"))
        }
    }
}

// A stable JPMS name lets module-path consumers `requires monada.neuron` without a descriptor. As an
// automatic module it declares no `requires`, so jdk.incubator.vector is never resolved for consumers
// and the reflective SIMD loader keeps its scalar fallback (ADR 0032).
tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "monada.neuron",
            "Implementation-Title" to "Monada Neuron",
            "Implementation-Version" to project.version
        )
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Builds a standalone consumer project against the published artifact, with no incubator flags.
tasks.register<Delete>("cleanConsumerRepo") {
    delete(layout.buildDirectory.dir("consumer-repo"))
}

tasks.named("publishMavenJavaPublicationToConsumerSmokeRepository") {
    dependsOn("cleanConsumerRepo")
}

// Runs a standalone consumer build with the project wrapper and the host OS launcher. The relative
// launcher name avoids quoting an absolute path (which may contain spaces) through `cmd /c` on Windows.
fun Exec.runConsumerBuild(fixture: Directory, arguments: List<String>) {
    inputs.files(fileTree(fixture) {
        exclude("**/build/**", "**/.gradle/**")
    })
    workingDir = layout.projectDirectory.asFile
    val windows = System.getProperty("os.name").lowercase().contains("win")
    val launcher = if (windows) listOf("cmd", "/c", "gradlew.bat") else listOf("./gradlew")
    commandLine(launcher + listOf("-p", fixture.asFile.absolutePath) + arguments)
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

// Builds a standalone JPMS consumer (`requires monada.neuron`) against the same published core
// artifact. Core-only: it never needs the Resonance Store checkout (ADR 0032).
tasks.register<Exec>("jpmsConsumerTest") {
    group = "verification"
    description = "Compiles and runs an external module-path consumer against the locally published artifact"
    dependsOn("publishMavenJavaPublicationToConsumerSmokeRepository")
    val repo = layout.buildDirectory.dir("consumer-repo").get().asFile.absolutePath
    runConsumerBuild(layout.projectDirectory.dir("consumer-fixture/module-consumer"), listOf(
        "verifyConsumer", "--rerun-tasks", "--refresh-dependencies",
        "-PneuronRepo=$repo", "-PneuronVersion=${project.version}"
    ))
}

// The embedded host loop needs the published adapter and the actual Store publications, so these tasks
// exist only when the optional adapter module is included (ADR 0018, ADR 0032).
if (findProject(":monada-neuron-resonance-adapter") != null) {
    val storeVersion = providers.gradleProperty("resonanceStoreVersion").get()
    val storeDir = file("../monada-resonance-store")
    val integrationRepo = layout.buildDirectory.dir("integration-repo")

    val cleanIntegrationRepo = tasks.register<Delete>("cleanIntegrationRepo") {
        delete(integrationRepo)
    }
    val neuronPublications = listOf(
        ":publishMavenJavaPublicationToIntegrationRepository",
        ":monada-neuron-resonance-adapter:publishMavenJavaPublicationToIntegrationRepository"
    )
    allprojects {
        tasks.withType<PublishToMavenRepository>()
            .matching { it.name.endsWith("ToIntegrationRepository") }
            .configureEach { mustRunAfter(cleanIntegrationRepo) }
    }

    // Store publishes only into its own, wiped-on-publish build directory. Its actual publication is
    // produced by its own task and then copied into Neuron's isolated repository; nothing is rebuilt
    // from Store sources here and nothing in the Store checkout is edited.
    val copyStorePublications = tasks.register<Copy>("copyStorePublications") {
        dependsOn(gradle.includedBuild("monada-resonance-store").task(":publishLibrariesToVerificationRepository"))
        mustRunAfter(cleanIntegrationRepo)
        from(storeDir.resolve("build/verification-repo")) {
            include("com/monada/**")
        }
        into(integrationRepo)
    }

    val storeCommit = providers.exec {
        commandLine("git", "-C", storeDir.absolutePath, "rev-parse", "HEAD")
    }.standardOutput.asText.map { it.trim() }
    val storeStatus = providers.exec {
        commandLine("git", "-C", storeDir.absolutePath, "status", "--porcelain")
    }.standardOutput.asText

    tasks.register("publishIntegrationRepository") {
        group = "publishing"
        description = "Publishes Neuron, the adapter and the pinned Store artifacts into build/integration-repo"
        dependsOn(cleanIntegrationRepo, copyStorePublications)
        dependsOn(neuronPublications)
        val repoDir = integrationRepo.get().asFile
        val pinned = storeVersion
        inputs.property("storeCommit", storeCommit)
        inputs.property("storeStatus", storeStatus)
        doLast {
            val storeModules = repoDir.resolve("com/monada").listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name } ?: emptyList()
            check(storeModules.isNotEmpty()) { "no Store publication was copied into $repoDir" }
            val checksumExtensions = setOf("md5", "sha1", "sha256", "sha512")
            val provenance = StringBuilder()
            provenance.appendLine("store.version=$pinned")
            provenance.appendLine("store.commit=${storeCommit.get()}")
            provenance.appendLine("store.dirty=${storeStatus.get().isNotBlank()}")
            for (module in storeModules) {
                val versions = module.listFiles { f -> f.isDirectory }?.map { it.name }?.toSet() ?: emptySet()
                check(versions == setOf(pinned)) {
                    "Store module ${module.name} was published as $versions, but resonanceStoreVersion pins $pinned"
                }
                // Every file that can change what a consumer resolves: jars, POMs, Gradle module metadata and both
                // maven-metadata.xml levels (which pick the timestamped SNAPSHOT). Checksum sidecars are derived.
                module.walkTopDown()
                    .filter { it.isFile && it.extension !in checksumExtensions }
                    .sortedBy { it.relativeTo(repoDir).invariantSeparatorsPath }
                    .forEach { file ->
                        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                        provenance.appendLine("sha256 ${HexFormat.of().formatHex(digest)}  " +
                            file.relativeTo(repoDir).invariantSeparatorsPath)
                    }
            }
            repoDir.resolve("STORE-PROVENANCE.txt").writeText(provenance.toString())
            print(provenance)
        }
    }

    tasks.register<Exec>("embeddedHostConsumerTest") {
        group = "verification"
        description = "Runs the classpath and JPMS embedded-host consumers against build/integration-repo"
        dependsOn("publishIntegrationRepository")
        val repo = integrationRepo.get().asFile.absolutePath
        runConsumerBuild(layout.projectDirectory.dir("consumer-fixture/embedded-host"), listOf(
            "verifyConsumer", "--rerun-tasks", "--refresh-dependencies",
            "-PneuronRepo=$repo", "-PneuronVersion=${project.version}", "-PresonanceStoreVersion=$storeVersion"
        ))
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

tasks.withType<JavaExec> {
    // Demo/dev runs opt into the optional SIMD backend; library consumers are not required to.
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("-add-modules", "jdk.incubator.vector")
}
