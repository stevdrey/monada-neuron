/**
 * Embedding boundary for external Java hosts.
 *
 * <p>{@link monada.neuron.host.NeuronRuntime} composes existing Neuron contracts once, including the
 * optional perception, memory, and action capabilities, and executes bounded cognitive cycles without
 * requiring the host to wire stages itself. The low-level cycle, stage, memory, and action APIs remain
 * public for experiments and focused tests.
 */
package monada.neuron.host;
