package consumer.forge;

import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.FeatureReadinessAssessment;
import monada.neuron.routing.features.Requirement;
import monada.neuron.routing.features.TaskFeatureReadiness;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.receipt.AdviceReceipt;
import monada.neuron.routing.review.DecisionReview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The transformation boundary of the readiness contract, Section 4: the only place where a {@link TaskDraft} and a
 * Neuron type meet. Only metadata crosses. The task text, the scope entries and the workspace path stay in the draft;
 * the fingerprints that reach the request are digests the host compares by equality and Neuron never computes, parses
 * or resolves.
 *
 * <p>Every value the adapter sends is either trusted configuration (a human pick from a fixed list, a host minted id,
 * the preview requirements, the policy) or an untrusted observation (what the context collection measured), and no
 * value is ever permission. Every {@code Unknown} stays {@code Unknown}: nothing is defaulted or inferred.
 */
public final class ForgeRoutingAdapter {

    /** Written as literals on purpose: the version probe compares the loaded constants against these. */
    static final String CONTRACT_VERSION = "forge-routing/1";
    static final String SCHEMA_VERSION = "task-features/1";

    /** The partition key Forge would choose once; the example value of the readiness contract, Section 3.3. */
    public static final String EVALUATION_POLICY_ID = "forge-eval-preview";
    public static final String EVALUATION_POLICY_VERSION = "1";

    /** The host's fixed stage pick list; every catalog route must declare every entry (Section 3.1). */
    public static final List<String> STAGE_KINDS = List.of("plan", "implement", "review");

    /**
     * The policy in force as the constraints fingerprint covers it (readiness contract, Section 4.2): the routing
     * policy identity plus the preview requirements derived from the static catalog configuration.
     */
    public record PolicyInForce(String policyId, String policyVersion, HardRequirements preview) {
        public PolicyInForce {
            Objects.requireNonNull(policyId, "policyId");
            Objects.requireNonNull(policyVersion, "policyVersion");
            Objects.requireNonNull(preview, "preview");
        }

        /** The policy as digest input, every field length prefixed like the rest of the constraints digest. */
        String canonical() {
            return Fingerprints.canonical(List.of(policyId, policyVersion,
                    Fingerprints.list(Optional.of(preview.requiredCapabilities())),
                    Fingerprints.list(Optional.of(preview.requiredTools())),
                    Fingerprints.list(Optional.of(preview.allowedLocalities())),
                    Fingerprints.list(Optional.of(preview.permittedModes())),
                    Long.toString(preview.requiredContextSize())));
        }
    }

    /** The three fingerprints of a request, host digests compared by string equality. */
    public record Fingerprints(String source, String context, String constraints) {

        public Fingerprints {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(constraints, "constraints");
        }

        /**
         * Recomputes the three digests from the draft as it is now. The source fingerprint is the workspace's own
         * revision token. The context fingerprint covers what the context collection measured. The constraints
         * fingerprint covers the confirmed specification, which here includes the characteristics the person
         * confirmed, the scope selection, and the policy in force, so that any of them changing makes earlier advice
         * stale. Free text and scope entries enter the digest and never the request.
         */
        public static Fingerprints of(TaskDraft draft, PolicyInForce policyInForce) {
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(policyInForce, "policyInForce");
            String source = draft.workspace().map(TaskDraft.Workspace::sourceRevision).orElse("");
            var context = draft.context();
            String contextDigest = sha256(canonical(List.of(
                    list(context.languages()),
                    list(context.domains()),
                    context.changeSize().isPresent() ? Long.toString(context.changeSize().getAsLong()) : "",
                    context.contextSize().isPresent() ? Long.toString(context.contextSize().getAsLong()) : "")));
            var specification = draft.specification();
            var review = draft.review();
            String constraintsDigest = sha256(canonical(List.of(
                    specification.map(TaskDraft.Specification::title).orElse(""),
                    specification.map(TaskDraft.Specification::description).orElse(""),
                    switch (draft.scope()) {
                        case TaskDraft.Scope.Unset ignored -> "unset";
                        case TaskDraft.Scope.WholeWorkspace ignored -> "whole-workspace";
                        case TaskDraft.Scope.Directories directories ->
                                "directories" + list(Optional.of(directories.relativeDirectories()));
                    },
                    review.stageKind().orElse(""),
                    review.category().orElse(""),
                    review.tests().map(Enum::name).orElse(""),
                    review.security().map(Enum::name).orElse(""),
                    policyInForce.canonical())));
            return new Fingerprints(source, contextDigest, constraintsDigest);
        }

        /**
         * The digest input as length prefixed fields. A separator alone is not injective: a single scope entry
         * {@code "a, b"} would read like the two entries {@code a} and {@code b}, and a reshaped scope would pass the
         * guard as fresh. The length prefix makes a field's content unable to imitate a field boundary.
         */
        static String canonical(List<String> fields) {
            var out = new StringBuilder();
            for (String field : fields) {
                out.append(field.length()).append(':').append(field).append(';');
            }
            return out.toString();
        }

        /** An absent list is empty text; a present list is its size followed by its length prefixed entries. */
        static String list(Optional<List<String>> values) {
            return values.map(entries -> entries.size() + ":" + canonical(entries)).orElse("");
        }

        private static String sha256(String canonical) {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is a required algorithm", e);
            }
        }
    }

    /** The host ids of one request; real ids from the first preview, never placeholders (Section 3.3). */
    public record Ids(String taskId, String executionId, String attemptId, String stageId) {
        public Ids {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(stageId, "stageId");
        }
    }

    private final LexicographicRoutingPolicy policy;
    private final HardRequirements preview;
    private final Set<FeatureDimension> required;
    private final TaskFeatureReadiness readiness = new TaskFeatureReadiness();

    /**
     * @param policy the routing policy, over {@code RoutingStateDefinition.reference()} through Phase 4
     * @param preview the fixed preview requirements derived from the static catalog configuration
     * @param required the feature dimensions this host's preview needs, a display preference of the host
     */
    public ForgeRoutingAdapter(LexicographicRoutingPolicy policy, HardRequirements preview,
            Set<FeatureDimension> required) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.preview = Objects.requireNonNull(preview, "preview");
        this.required = Set.copyOf(required);
    }

    public PolicyInForce policyInForce() {
        return new PolicyInForce(policy.policyId(), policy.policyVersion(), preview);
    }

    /** The three fingerprints of the draft as it is now, under the policy this adapter has in force. */
    public Fingerprints fingerprints(TaskDraft draft) {
        return Fingerprints.of(draft, policyInForce());
    }

    /**
     * Builds the features from the draft. A human pick becomes {@code Known}; an absent pick stays {@code Unknown}. A
     * measured tag list that exceeds {@link TaskFeatures#MAX_TAGS}, repeats a tag or holds a tag that breaks the
     * opaque token rules, and a size the collection measured as negative, are reported as {@code Unknown}: never
     * truncated, never substituted, never allowed to throw from the constructor (Section 3.1). The schema version is
     * set explicitly from this host's literal, never left to the builder default, so the core's schema backstop stays
     * reachable (Section 5.2).
     */
    public TaskFeatures features(TaskDraft draft) {
        Objects.requireNonNull(draft, "draft");
        var builder = TaskFeatures.builder().schemaVersion(SCHEMA_VERSION);
        var review = draft.review();
        review.stageKind().ifPresent(builder::stageKind);
        review.category().ifPresent(builder::category);
        review.tests().map(this::requirement).ifPresent(builder::tests);
        review.security().map(this::requirement).ifPresent(builder::security);
        var context = draft.context();
        context.languages().filter(this::validTags).ifPresent(builder::languages);
        context.domains().filter(this::validTags).ifPresent(builder::domains);
        context.changeSize().ifPresent(size -> {
            if (size >= 0) {
                builder.changeSize(size);
            }
        });
        context.contextSize().ifPresent(size -> {
            if (size >= 0) {
                builder.contextSize(size);
            }
        });
        return builder.build();
    }

    /** Which fields the host supplied, judged against the stage kind of the request it is about to build. */
    public FeatureReadinessAssessment assess(TaskFeatures features, String stageKind) {
        return readiness.assess(features, stageKind, required);
    }

    /** The request over the draft's metadata: ids, fingerprints, the features and the fixed preview requirements. */
    public RoutingRequest request(TaskDraft draft, Ids ids, long ordinal, Fingerprints fingerprints,
            TaskFeatures features, String stageKind) {
        var workspace = draft.workspace().orElseThrow(() -> new IllegalStateException("a request needs a workspace"));
        return new RoutingRequest(CONTRACT_VERSION, workspace.scopeId(), ids.taskId(), ids.executionId(),
                ids.attemptId(), ids.stageId(), stageKind, ordinal, fingerprints.source(), fingerprints.context(),
                fingerprints.constraints(), EVALUATION_POLICY_ID, EVALUATION_POLICY_VERSION, features, preview, false,
                0L);
    }

    /**
     * One pure call of {@code decide} over cold state built fresh from the request and the policy instance's runtime
     * values (Section 3.1), then the review and the receipt projected from the decision.
     */
    public Advice advise(RoutingRequest request, RouteCatalog catalog, FeatureReadinessAssessment.Assessed assessment,
            int epoch) {
        var preference = RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion());
        var decision = policy.decide(request, catalog, preference);
        return new Advice(request, decision, assessment, DecisionReview.from(decision), AdviceReceipt.of(decision),
                epoch);
    }

    private Requirement requirement(TaskDraft.Toggle toggle) {
        return switch (toggle) {
            case REQUIRED -> Requirement.REQUIRED;
            case NOT_REQUIRED -> Requirement.NOT_REQUIRED;
        };
    }

    /** The opaque token rules a tag must meet, checked here so the constructor never throws on an observation. */
    private boolean validTags(List<String> tags) {
        if (tags.size() > TaskFeatures.MAX_TAGS || tags.size() != Set.copyOf(tags).size()) {
            return false;
        }
        for (String tag : tags) {
            int codePoints = tag.codePointCount(0, tag.length());
            if (codePoints == 0 || codePoints > 128 || !tag.strip().equals(tag)
                    || tag.chars().anyMatch(Character::isISOControl)) {
                return false;
            }
        }
        return true;
    }
}
