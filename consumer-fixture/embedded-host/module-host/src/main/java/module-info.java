/** JPMS host that embeds the published Neuron adapter and records into the published Resonance Store. */
module monada.neuron.consumer.host {
    requires monada.neuron;
    requires monada.neuron.resonance.adapter;
    requires com.monada.api;
    requires com.monada.core;
}
