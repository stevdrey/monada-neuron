package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.FeedbackArm.KeyStrategy;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Surface variants of the calibration queries ({@value #VERSION}), which probe whether a feedback key strategy carries
 * an event recorded for one text to a re-asked query that differs only in letter case, spacing or final punctuation.
 * Variants are functions of the text alone, so this class reads no gold label; they are scored with their source
 * query's labels and are always reported as in-sample, since their source received events.
 */
final class FeedbackKeyVariants {

    /** Version of the variant rule, part of {@link #variantHash(List)}. */
    static final String VERSION = "feedback-variants-v1";

    /** One surface transformation. */
    enum Transform {
        /** Upper-cases the text ({@link Locale#ROOT}). */
        CASE("case"),
        /** Doubles every space. */
        SPACING("spacing"),
        /** Removes a final {@code ?}, or appends one when there is none. */
        PUNCTUATION("punctuation");

        private final String label;

        Transform(String label) {
            this.label = label;
        }

        /** Returns the report label. */
        String label() {
            return label;
        }

        /** Applies the transformation. */
        String apply(String text) {
            return switch (this) {
                case CASE -> text.toUpperCase(Locale.ROOT);
                case SPACING -> text.replace(" ", "  ");
                case PUNCTUATION -> text.endsWith("?") ? text.substring(0, text.length() - 1) : text + "?";
            };
        }

        /** Parses a report label. */
        static Transform ofLabel(String label) {
            return Arrays.stream(values()).filter(value -> value.label.equals(label)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown transform " + label));
        }
    }

    /**
     * One variant.
     *
     * @param variantId {@code v:<sourceId>/<transform>}
     * @param sourceId the calibration query it varies
     * @param transform the transformation applied
     * @param text the transformed text
     */
    record Variant(String variantId, String sourceId, Transform transform, String text) {

        Variant {
            Objects.requireNonNull(variantId, "variantId must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(transform, "transform must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }

        /** Returns the workload entry of this variant. */
        QueryText queryText() {
            return new QueryText(variantId, text);
        }
    }

    private FeedbackKeyVariants() {
    }

    /** Returns the variant id of {@code sourceId} under {@code transform}. */
    static String variantId(String sourceId, Transform transform) {
        return "v:" + sourceId + "/" + transform.label();
    }

    /** Returns every transformation of every non-blank source, in source order, then transformation order. */
    static List<Variant> of(List<QueryText> sources) {
        var variants = new ArrayList<Variant>();
        for (var source : sources) {
            if (source.text().isBlank()) {
                continue;
            }
            for (var transform : Transform.values()) {
                variants.add(new Variant(variantId(source.queryId(), transform), source.queryId(), transform,
                        transform.apply(source.text())));
            }
        }
        return List.copyOf(variants);
    }

    /**
     * {@code variants.keyPreserving}: every variant differs from its source and from every other workload text, keeps
     * its source's normalized and lexically enriched keys, and changes its exact key. A failure means a probe would not
     * test what it is labeled as testing.
     */
    static Check keyPreserving(List<Variant> variants, List<QueryText> workload) {
        var textBySource = new HashMap<String, String>();
        workload.forEach(query -> textBySource.put(query.queryId(), query.text()));
        var texts = new HashSet<String>();
        var duplicates = new ArrayList<String>();
        workload.forEach(query -> {
            if (!texts.add(query.text())) {
                duplicates.add(query.queryId());
            }
        });
        var problems = new ArrayList<String>(duplicates.stream().map(id -> id + " repeats another workload text")
                .toList());
        for (var variant : variants) {
            var source = textBySource.get(variant.sourceId());
            if (source == null) {
                problems.add(variant.variantId() + " has no source in the workload");
                continue;
            }
            if (KeyStrategy.EXACT.effectiveKey(variant.text()).equals(KeyStrategy.EXACT.effectiveKey(source))) {
                problems.add(variant.variantId() + " keeps its exact key");
            }
            for (var strategy : List.of(KeyStrategy.NORMALIZED, KeyStrategy.ENRICHED)) {
                if (!strategy.effectiveKey(variant.text()).equals(strategy.effectiveKey(source))) {
                    problems.add(variant.variantId() + " changes its " + strategy.label() + " key");
                }
            }
        }
        return new Check("variants.keyPreserving", problems.isEmpty(), problems.isEmpty()
                ? variants.size() + " variants: every workload text is distinct, and each variant changes its exact"
                        + " key and keeps its normalized and lexically enriched keys"
                : problems.size() + " problems: " + String.join("; ", problems.subList(0, Math.min(5,
                        problems.size()))));
    }

    /**
     * Returns the {@link CanonicalRecordDigest} of {@value #VERSION}, the variant count, then each variant in order as
     * variant id, source id, transformation label and text.
     */
    static String variantHash(List<Variant> variants) {
        var digest = new CanonicalRecordDigest().field(VERSION).field(variants.size());
        variants.forEach(variant -> digest.field(variant.variantId()).field(variant.sourceId())
                .field(variant.transform().label()).field(variant.text()));
        return digest.hex();
    }
}
