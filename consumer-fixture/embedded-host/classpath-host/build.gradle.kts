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

application {
    mainClass.set("consumer.host.EmbeddedHostMain")
}

tasks.named<JavaExec>("run") {
    args("classpath")
}
