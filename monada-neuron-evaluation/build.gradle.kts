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
