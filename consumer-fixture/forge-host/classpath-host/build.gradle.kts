// Classpath host: the same host source as module-host, compiled without its descriptor, so the identical
// review-to-advice flow also runs from the unnamed module.
sourceSets {
    main {
        java {
            setSrcDirs(listOf("../module-host/src/main/java"))
            exclude("module-info.java")
        }
    }
}

// The shared source directory holds module-host's descriptor; without this, Gradle would still infer a module
// path from it. This consumer must compile and run on the plain classpath.
java {
    modularity.inferModulePath.set(false)
}

application {
    mainClass.set("consumer.forge.ForgeHostMain")
}

tasks.named<JavaExec>("run") {
    args("classpath")
}

// The host unit tests live beside this consumer only: they run on the plain classpath over the same shared sources.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
