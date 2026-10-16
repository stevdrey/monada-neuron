// Module-path host: `requires monada.neuron.resonance.adapter` plus the Store automatic modules it uses.
application {
    mainModule.set("monada.neuron.consumer.host")
    mainClass.set("consumer.host.EmbeddedHostMain")
}

tasks.named<JavaExec>("run") {
    args("module")
}
