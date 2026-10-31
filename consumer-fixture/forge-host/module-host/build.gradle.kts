// Module-path host: `requires monada.neuron` (the automatic module named by the jar manifest) and nothing else.
application {
    mainModule.set("monada.neuron.consumer.forge")
    mainClass.set("consumer.forge.ForgeHostMain")
}

tasks.named<JavaExec>("run") {
    args("module")
}
