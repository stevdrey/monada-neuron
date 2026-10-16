// Classpath host: the same host source as module-host, compiled without its descriptor, so the identical
// loop also runs from the unnamed module.
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
    mainClass.set("consumer.host.EmbeddedHostMain")
}

tasks.named<JavaExec>("run") {
    args("classpath")
}
