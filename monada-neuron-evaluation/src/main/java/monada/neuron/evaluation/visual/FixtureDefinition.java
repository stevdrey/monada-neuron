package monada.neuron.evaluation.visual;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * One fixture id of the catalogue with its gold values; it gives two manifest rows, one per size.
 *
 * @param id the fixture id of contract Section 8
 * @param group the group, which also fixes the kind
 * @param formula the short ASCII key of the pixel formula; a label, never parsed
 * @param pose the pose number {@code 1} to {@code 8} of Section 8.4, for positive and composite ids only
 * @param shape the gold geometry, when the id has one
 * @param transform the gold pose, when the id has one
 * @param accept the acceptable top hypothesis kinds of a mask id
 * @param abstain the acceptable abstain reasons of a mask id
 * @param layers the layers of a shape render id, in draw order; empty otherwise
 */
record FixtureDefinition(String id, FixtureGroup group, String formula, OptionalInt pose, Optional<GoldShape> shape,
                         Optional<GoldTransform> transform, SortedSet<String> accept, SortedSet<String> abstain,
                         List<GoldLayer> layers) {

    static final String NONE = "NONE";

    FixtureDefinition {
        accept = new TreeSet<>(accept);
        abstain = new TreeSet<>(abstain);
        layers = List.copyOf(layers);
    }

    FixtureKind kind() {
        return group.kind();
    }

    String goldGeometry() {
        return shape.map(GoldShape::print).orElse(NONE);
    }

    String goldTransform() {
        return transform.map(GoldTransform::print).orElse(NONE);
    }

    /** An RGB id carries only {@code gold_layers}, so its accept column is {@code NONE}, not an empty set. */
    String goldAccept() {
        return kind() == FixtureKind.RGB ? NONE : printedSet(accept);
    }

    String goldAbstain() {
        return kind() == FixtureKind.RGB ? NONE : printedSet(abstain);
    }

    String goldLayers() {
        return layers.isEmpty() ? NONE : layers.stream().map(GoldLayer::print).collect(Collectors.joining(" | "));
    }

    private String printedSet(SortedSet<String> values) {
        return "{" + String.join(",", values) + "}";
    }
}
