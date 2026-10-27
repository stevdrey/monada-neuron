package monada.neuron.routing.receipt;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Basis;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.Placement;
import monada.neuron.routing.PolicyParameters;
import monada.neuron.routing.Provenance;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.ResourceObjective;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingRule;
import monada.neuron.routing.StateBinding;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.StateValidation;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteKey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Fixtures: decisions built from one set of leaf values (all identifiers are invented). Every token is distinct, so a
 * test can find a field in an encoding by its token.
 */
final class ReceiptFixtures {

    static final RouteKey SUB_A = new RouteKey("sub-a", 1);
    static final RouteKey SUB_B = new RouteKey("sub-b", 1);
    static final RouteKey API_X = new RouteKey("api-x", 1);

    static final EligibilityReport.Exclusion GONE = new EligibilityReport.Exclusion(new RouteKey("ex-gone", 3),
            EligibilityReason.MISSING_CAPABILITY, List.of(EligibilityReason.REQUIRED_LIMIT_UNKNOWN));

    private ReceiptFixtures() {
    }

    /** One base decision per shape: the variants, both abstain reasons, and a selection with a full ranking. */
    enum Base {
        SELECTED(Parts::selected),
        SELECTED_FULL(Parts::selected),
        ABSTAIN_TRADEOFF(Parts::tradeoff),
        ABSTAIN_INCOMPATIBLE(Parts::incompatible),
        NO_ELIGIBLE_ROUTE(Parts::noEligibleRoute);

        private final Function<Parts, RoutingDecision> build;

        Base(Function<Parts, RoutingDecision> build) {
            this.build = build;
        }

        /** The decision of these parts in this shape; {@link #parts()} for defaults. */
        RoutingDecision build(Parts parts) {
            return build.apply(parts);
        }

        RoutingDecision decision() {
            return build(parts());
        }

        Parts parts() {
            return this == SELECTED_FULL ? new Parts().full() : new Parts();
        }
    }

    /** Every leaf of a decision as a mutable field, so a test can change exactly one value and rebuild. */
    static final class Parts {
        String scopeId = "scope-demo";
        String taskId = "task-17";
        String executionId = "exec-1";
        String attemptId = "att-1";
        String stageId = "s-impl";
        long requestOrdinal = 101;
        String catalogVersion = "cat-demo-7";
        String policyId = "route-lex";
        String policyVersion = "pv-1";
        long cutoff = 50;
        String stateScopeId = "state-scope";
        String stateSchema = "task-features/1";
        String evaluationPolicyId = "forge-gates";
        String evaluationPolicyVersion = "ev-1";
        String stateMapping = "routing-state/1";
        long processedCutoff = 40;
        String producedById = "producer";
        String producedByVersion = "ppv-1";
        List<StateMismatch> mismatches = List.of(StateMismatch.MAPPING_VERSION_MISMATCH);
        int minSupport = 3;
        OptionalInt maxTier = OptionalInt.empty();
        Optional<ResourceObjective> objective = Optional.empty();
        String parameterMapping = "routing-state/1";
        String parameterSchema = "task-features/1";
        Optional<String> stageKind = Optional.of("implement");
        RouteKey route = SUB_A;
        String executionMode = "sandboxed";
        Basis basis = Basis.COLD_START;
        boolean overflowUsed = false;
        List<RankedCandidate> candidates = List.of(new RankedCandidate(0, SUB_A, Placement.ROUTE_ORDER),
                new RankedCandidate(1, SUB_B, Placement.ROUTE_ORDER), new RankedCandidate(2, API_X, Placement.TIER));
        boolean truncated = false;
        List<RoutingRule> applied = List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.ROUTE_ORDER);
        List<RoutingRule> skipped = List.of(RoutingRule.LEARNED_PREFERENCE, RoutingRule.RESOURCE_OBJECTIVE);
        List<EligibilityReport.Exclusion> exclusions = List.of(GONE);
        String cohortBucket = "UNKNOWN";
        String bucketMapping = "b/1";
        String cohortMapping = "routing-state/1";
        String cohortSchema = "task-features/1";
        List<RouteKey> eligible = List.of(SUB_A, SUB_B);
        List<RankedCandidate> tradeoffCandidates = List.of(new RankedCandidate(0, SUB_A, Placement.ROUTE_ORDER),
                new RankedCandidate(1, SUB_B, Placement.ROUTE_ORDER));

        /** Eight ranked routes, a tier cap and a resource objective. */
        Parts full() {
            List<RankedCandidate> ranked = new ArrayList<>();
            ranked.add(new RankedCandidate(0, SUB_A, Placement.ROUTE_ORDER));
            for (int i = 1; i < RoutingDecision.MAX_CANDIDATES; i++) {
                ranked.add(new RankedCandidate(i, new RouteKey("r-" + i, 1), Placement.ROUTE_ORDER));
            }
            candidates = List.copyOf(ranked);
            maxTier = OptionalInt.of(2);
            objective = Optional.of(new ResourceObjective("cost", ResourceObjective.Direction.MINIMIZE));
            return this;
        }

        Provenance provenance(StateValidation validation) {
            return new Provenance(new DecisionRef(scopeId, taskId, executionId, attemptId, stageId, requestOrdinal),
                    catalogVersion, policyId, policyVersion, cutoff,
                    new StateBinding(stateScopeId, stateSchema, evaluationPolicyId, evaluationPolicyVersion,
                            stateMapping, processedCutoff, producedById, producedByVersion),
                    validation, new PolicyParameters(minSupport, maxTier, objective, parameterMapping, parameterSchema),
                    stageKind);
        }

        CohortBinding cohort() {
            return new CohortBinding(cohortBucket, bucketMapping, cohortMapping, cohortSchema);
        }

        RoutingDecision selected() {
            return new RoutingDecision.Selected(provenance(new StateValidation.Compatible()), route, executionMode,
                    basis, overflowUsed, candidates, truncated, applied, skipped, exclusions, cohort());
        }

        RoutingDecision tradeoff() {
            return new RoutingDecision.Abstain(provenance(new StateValidation.Compatible()),
                    AbstainReason.POLICY_TRADEOFF_UNRESOLVED, eligible, tradeoffCandidates, false, exclusions,
                    Optional.of(cohort()));
        }

        RoutingDecision incompatible() {
            return new RoutingDecision.Abstain(provenance(new StateValidation.Incompatible(mismatches)),
                    AbstainReason.STATE_INCOMPATIBLE, eligible, List.of(), false, exclusions, Optional.empty());
        }

        RoutingDecision noEligibleRoute() {
            return new RoutingDecision.NoEligibleRoute(provenance(new StateValidation.NotEvaluated()), exclusions);
        }
    }

    /** The {@code str} encoding of a token: its 4 byte big endian UTF-8 length, then its UTF-8. */
    static byte[] str(String token) {
        byte[] utf8 = token.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + utf8.length).putInt(utf8.length).put(utf8).array();
    }

    /** Index of the first {@code str} encoding of a token in an encoding; fails if absent. */
    static int indexOf(byte[] encoding, String token) {
        byte[] needle = str(token);
        outer:
        for (int i = 0; i + needle.length <= encoding.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (encoding[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("token not encoded: " + token);
    }

    /** Index just after the first {@code str} encoding of a token. */
    static int after(byte[] encoding, String token) {
        return indexOf(encoding, token) + str(token).length;
    }

    /** A copy of an encoding with the first encoded token's text replaced by text of the same UTF-8 length. */
    static byte[] replaceText(byte[] encoding, String token, byte[] text) {
        if (text.length != token.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException("replacement must keep the length");
        }
        byte[] copy = encoding.clone();
        System.arraycopy(text, 0, copy, indexOf(encoding, token) + Integer.BYTES, text.length);
        return copy;
    }

    static byte[] replaceText(byte[] encoding, String token, String text) {
        return replaceText(encoding, token, text.getBytes(StandardCharsets.UTF_8));
    }

    /** A copy of an encoding with the bytes at {@code [from, to)} replaced. */
    static byte[] splice(byte[] encoding, int from, int to, byte[] replacement) {
        return ByteBuffer.allocate(encoding.length - (to - from) + replacement.length)
                .put(encoding, 0, from).put(replacement).put(encoding, to, encoding.length - to).array();
    }

    static byte[] i32(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    static byte[] i64(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
}
