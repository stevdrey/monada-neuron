package monada.neuron.routing.features;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Explicit, versioned, finite policy that maps {@link TaskFeatures} to signal frequencies.
 *
 * <p>Vocabulary <em>order is part of the policy</em>: a token's index selects its frequency, so any change
 * to a vocabulary, ceiling or the encoder arithmetic requires a new {@code version}. The policy performs no
 * hashing; a token outside its vocabulary maps to a shared, documented {@code OTHER} position.
 *
 * @param id opaque policy token
 * @param version positive policy version
 * @param stageKinds ordered stage kind vocabulary, at most {@value #MAX_VOCABULARY} tokens
 * @param categories ordered category vocabulary
 * @param languages ordered language vocabulary
 * @param domains ordered domain vocabulary
 * @param changeSizeCeiling positive saturation ceiling for change size
 * @param contextSizeCeiling positive saturation ceiling for context size
 */
public record EncodingPolicy(
        String id,
        long version,
        List<String> stageKinds,
        List<String> categories,
        List<String> languages,
        List<String> domains,
        long changeSizeCeiling,
        long contextSizeCeiling) {

    /** Maximum tokens in one vocabulary. */
    public static final int MAX_VOCABULARY = 32;

    /** Validates tokens, vocabulary bounds and ceilings, and copies the vocabularies. */
    public EncodingPolicy {
        FeatureTokens.require(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive, got: " + version);
        }
        stageKinds = vocabulary(stageKinds, "stageKinds");
        categories = vocabulary(categories, "categories");
        languages = vocabulary(languages, "languages");
        domains = vocabulary(domains, "domains");
        if (changeSizeCeiling < 1) {
            throw new IllegalArgumentException("changeSizeCeiling must be positive, got: " + changeSizeCeiling);
        }
        if (contextSizeCeiling < 1) {
            throw new IllegalArgumentException("contextSizeCeiling must be positive, got: " + contextSizeCeiling);
        }
    }

    /**
     * The illustrative v1 policy. Its vocabularies are host-replaceable defaults, not a claim about which
     * categories matter; ceilings are one less than a power of two so the saturation point is exact.
     */
    public static EncodingPolicy defaultV1() {
        return new EncodingPolicy(
                "task-encoding-default", 1,
                List.of("plan", "implement", "review", "qa"),
                List.of("bugfix", "feature", "refactor", "docs", "test", "chore"),
                List.of("java", "kotlin", "python", "typescript", "javascript", "go", "rust", "csharp"),
                List.of("backend", "frontend", "data", "infrastructure", "security", "documentation"),
                65_535L,
                1_048_575L);
    }

    private static List<String> vocabulary(List<String> tokens, String name) {
        Objects.requireNonNull(tokens, name + " must not be null");
        if (tokens.size() > MAX_VOCABULARY) {
            throw new IllegalArgumentException(
                    name + " allows at most " + MAX_VOCABULARY + " tokens, got: " + tokens.size());
        }
        List<String> copy = List.copyOf(tokens);
        HashSet<String> seen = HashSet.newHashSet(copy.size());
        for (String token : copy) {
            FeatureTokens.require(token, name + " token");
            if (!seen.add(token)) {
                throw new IllegalArgumentException(name + " contains a duplicate token: " + token);
            }
        }
        return copy;
    }
}
