plugins {
    id("java")
    application
}

group = "monada.neuron"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(27))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(rootProject)

    // JMH microbenchmarking framework
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("monada.neuron.evaluation.baseline.CognitiveBaselineRunner")
    applicationDefaultJvmArgs = listOf("--add-modules", "jdk.incubator.vector")
}

tasks.register<JavaExec>("runCognitiveBaseline") {
    group = "benchmark"
    description = "Runs the deterministic Monada Neuron end-to-end cognitive baseline suite"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("monada.neuron.evaluation.baseline.CognitiveBaselineRunner")
    jvmArgs("--add-modules", "jdk.incubator.vector")
    if (project.hasProperty("benchmarkArgs")) {
        args(project.property("benchmarkArgs").toString().split(" "))
    }
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH microbenchmarks"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    jvmArgs("--add-modules", "jdk.incubator.vector")
    if (project.hasProperty("jmhArgs")) {
        args(project.property("jmhArgs").toString().split(" "))
    } else {
        args("-f", "1", "-wi", "2", "-i", "3", "-r", "1s")
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// Real Resonance Store integration evaluation (Issue #31). The adapter module, and with it the
// unpublished sibling store, is optional (ADR 0018), so this code lives in its own source set and the
// baseline `main` code and `./gradlew test` never require the store.
if (findProject(":monada-neuron-resonance-adapter") != null) {
    val integration = sourceSets.create("integration") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
    val integrationTestSet = sourceSets.create("integrationTest") {
        compileClasspath += integration.output + sourceSets["main"].output
        runtimeClasspath += integration.output + sourceSets["main"].output
    }

    configurations["integrationImplementation"].extendsFrom(configurations["implementation"])
    configurations["integrationRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])
    configurations["integrationTestImplementation"].extendsFrom(configurations["integrationImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["integrationRuntimeOnly"])

    dependencies {
        "integrationImplementation"(project(":monada-neuron-resonance-adapter"))
        "integrationImplementation"("com.monada:monada-api")
        "integrationImplementation"("com.monada:monada-core")
        "integrationTestImplementation"(platform("org.junit:junit-bom:5.10.0"))
        "integrationTestImplementation"("org.junit.jupiter:junit-jupiter")
        "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.register<JavaExec>("runResonanceStoreIntegration") {
        group = "benchmark"
        description = "Runs the end-to-end Neuron + Resonance Store cognitive evaluation over a temporary store"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.integration.ResonanceStoreIntegrationRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        if (project.hasProperty("benchmarkArgs")) {
            // Quote-aware so `--output-dir "/tmp/benchmark reports"` stays one argument.
            args(Regex("\"([^\"]*)\"|(\\S+)").findAll(project.property("benchmarkArgs").toString())
                .map { it.groups[1]?.value ?: it.groups[2]!!.value }
                .toList())
        }
    }

    val integrationTest = tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Runs correctness tests against a real, isolated temporary Resonance Store"
        testClassesDirs = integrationTestSet.output.classesDirs
        classpath = integrationTestSet.runtimeClasspath
        useJUnitPlatform()
    }

    tasks.test {
        dependsOn(integrationTest)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

tasks.withType<Test> {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

tasks.withType<JavaExec> {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("-add-modules", "jdk.incubator.vector")
}
