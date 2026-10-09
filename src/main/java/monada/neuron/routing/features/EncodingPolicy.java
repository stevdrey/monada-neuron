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
 * @param changeSizeCeiling saturation ceiling for change size, of the form {@code 2^k - 1}
 * @param contextSizeCeiling saturation ceiling for context size, of the form {@code 2^k - 1}
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
        requireCeiling(changeSizeCeiling, "changeSizeCeiling");
        requireCeiling(contextSizeCeiling, "contextSizeCeiling");
    }

    /**
     * The illustrative v1 policy. Its vocabularies are host-replaceable defaults, not a claim about which
     * categories matter; ceilings are one less than a power of two, as every policy requires.
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

    private static void requireCeiling(long ceiling, String name) {
        // 2^k - 1 makes "value > ceiling" identical to "bitLength(value) > bitLength(ceiling)".
        if (ceiling < 1 || (ceiling & (ceiling + 1)) != 0) {
            throw new IllegalArgumentException(name + " must be of the form 2^k - 1, got: " + ceiling);
        }
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
