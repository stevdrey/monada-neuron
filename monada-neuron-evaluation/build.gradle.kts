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

/**
 * The `benchmarkArgs` property split into arguments, quote-aware so `--output-dir "/tmp/benchmark reports"` stays one
 * argument; empty when the property is absent. One definition for every evaluation runner task.
 */
fun quotedBenchmarkArgs(): List<String> =
    if (!project.hasProperty("benchmarkArgs")) emptyList()
    else Regex("\"([^\"]*)\"|(\\S+)").findAll(project.property("benchmarkArgs").toString())
        .map { it.groups[1]?.value ?: it.groups[2]!!.value }
        .toList()

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

tasks.register<JavaExec>("runFeedbackLoop") {
    group = "benchmark"
    description = "Runs the repeated-cycle cross-cycle outcome-to-feedback evaluation (Issue #34)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("monada.neuron.evaluation.feedback.FeedbackLoopRunner")
    jvmArgs("--add-modules", "jdk.incubator.vector")
    args(quotedBenchmarkArgs())
}

tasks.register<JavaExec>("runForgeRoutingEvaluation") {
    group = "benchmark"
    description = "Runs the Forge routing temporal replay evaluation over synthetic fixtures (Issue #67)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("monada.neuron.evaluation.routing.RoutingReplayRunner")
    args(quotedBenchmarkArgs())
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
        // Store evaluation tooling (CostToAcceptedOutcome, Store #99), used only by the routing replay's Store arm.
        "integrationImplementation"("com.monada:monada-evaluation")
        "integrationAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
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
        args(quotedBenchmarkArgs())
    }

    // Routing replay over a real Store export (Issue #67). Registered only with the optional adapter; the store-free
    // `runForgeRoutingEvaluation --store-arm` reports this arm as unavailable instead of silently passing.
    tasks.register<JavaExec>("runForgeRoutingStoreReplay") {
        group = "benchmark"
        description = "Runs the Forge routing temporal replay over a real, temporary Resonance Store export (Issue #67)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.routing.RoutingStoreReplayRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    // HTML knowledge corpus ingestion (Issue #105, docs/experiments/html-knowledge-v1.md). Imports the authored
    // `html-kc-v1` corpus into a fresh embedded Store; an MDN snapshot is ingested only when the caller supplies it.
    tasks.register<JavaExec>("runHtmlKnowledgeIngestion") {
        group = "benchmark"
        description = "Imports the html-kc-v1 HTML knowledge corpus into a temporary Resonance Store and writes its manifest (Issue #105)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestionRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    // HTML knowledge persistence and reopen (Issue #106, docs/benchmarks/html-knowledge-persistence.md). Seeds a
    // temporary Store in one child JVM, observes it from a second child JVM, then audits failure cases on copies.
    tasks.register<JavaExec>("runHtmlKnowledgePersistence") {
        group = "benchmark"
        description = "Verifies html-kc-v1 persistence across separate JVMs and traces Neuron recall to Store atoms (Issue #106)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    // Judged HTML recall (Issue #107, docs/benchmarks/html-knowledge-recall.md). Runs the html-kq-v1 queries through
    // B0/B1/B2/B2c in a seed JVM and again in a separate reopen JVM, then scores them against the gold labels.
    tasks.register<JavaExec>("runHtmlKnowledgeRecall") {
        group = "benchmark"
        description = "Measures judged html-kq-v1 recall through the Store and Neuron's memory port across separate JVMs (Issue #107)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    // HTML evidence trace (Issue #108, docs/benchmarks/html-knowledge-evidence-trace.md). Seeds two roots in one JVM,
    // reopens them in a second JVM that runs the html-kq-v1 texts through a bounded Neuron cycle and its controls, then
    // scores the trace against the gold labels.
    tasks.register<JavaExec>("runHtmlKnowledgeEvidenceTrace") {
        group = "benchmark"
        description = "Traces recalled html-kc-v1 evidence through a bounded Neuron cognitive cycle across separate JVMs (Issue #108)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlKnowledgeEvidenceTraceRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    // HTML grounded answers (Issue #109, docs/benchmarks/html-knowledge-answers.md). Seeds two roots in one JVM, answers
    // every html-kq-v1 query from a second JVM with the extractive baselines and the generator test double, then scores
    // the answers against the gold labels and the frozen human judgments. -PbenchmarkArgs='--ask "<question>"' answers
    // one ad-hoc question instead, unscored.
    tasks.register<JavaExec>("runHtmlKnowledgeAnswers") {
        group = "benchmark"
        description = "Answers html-kq-v1 with cited extractive baselines and abstention, scored at L4 (Issue #109)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlKnowledgeAnswerRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    tasks.register<JavaExec>("runHtmlKnowledgeFeedback") {
        group = "benchmark"
        description = "Compares Store relevance feedback with a no-feedback control on html-kq-v1 (Issue #110)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlKnowledgeFeedbackRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    tasks.register<JavaExec>("runHtmlKnowledgeScaling") {
        group = "benchmark"
        description = "Profiles the HTML knowledge pipeline over the html-kc-v1 scaling family N = 25, 100, 500 (Issue #111)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlKnowledgeScalingRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    tasks.register<JavaExec>("runHtmlKnowledgeRecallDiagnosis") {
        group = "benchmark"
        description = "Diagnoses where B2 loses relevant evidence over a controlled corpus ladder, research-only (Issue #135)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallDiagnosisRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    // HTML design decisions (Issue #112, docs/benchmarks/html-decision.md). Opt-in: imports html-kc-v1 with the
    // html-kd-v1 supplement into a temporary Store, runs html-decision-v1 through the Store-only, Neuron and host-rule
    // arms and scores them against the frozen gold labels. -PbenchmarkArgs='--generator test-double' adds arm C.
    tasks.register<JavaExec>("runHtmlDecisionBenchmark") {
        group = "benchmark"
        description = "Benchmarks evidence-grounded HTML design decisions under explicit constraints (Issue #112)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlDecisionRunner")
        systemProperty("monada.neuron.version", project.version.toString())
        systemProperty("monada.resonance.store.dir", rootProject.file("../monada-resonance-store").absolutePath)
        args(quotedBenchmarkArgs())
    }

    // Independent adjudication hand-off of the html-decision-v1 gold labels (Issue #152): writes the reviewer
    // worksheet and the fill-in template, and gold-v2.tsv once a bundled adjudication changes a label.
    tasks.register<JavaExec>("htmlDecisionAdjudicationWorksheet") {
        group = "benchmark"
        description = "Writes the independent adjudication worksheet of the html-decision-v1 gold labels (Issue #152)"
        classpath = integration.runtimeClasspath
        mainClass.set("monada.neuron.evaluation.knowledge.HtmlDecisionAdjudicationWorksheet")
        args("--output-dir", layout.buildDirectory.dir("reports/html-decision-adjudication").get().asFile.absolutePath)
    }

    // Adapter-boundary JMH (Issue #49). Lives with the integration source set because it needs the
    // optional adapter; the generic `jmh` task only sees `main`.
    tasks.register<JavaExec>("jmhResonanceStoreAdapter") {
        group = "benchmark"
        description = "Runs JMH for the Neuron-side Resonance Store adapter translation and merge overhead (Issue #49)"
        classpath = integration.runtimeClasspath
        mainClass.set("org.openjdk.jmh.Main")
        if (project.hasProperty("jmhArgs")) {
            args(project.property("jmhArgs").toString().split(" "))
        } else {
            args("-bm", "avgt", "-f", "3", "-wi", "3", "-i", "5", "-r", "1s", "-prof", "gc", "ResonanceStoreAdapter")
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
