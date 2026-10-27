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
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.token.OpaqueTokens;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * The {@value AdviceReceipt#FORMAT} writer and its strict reader. Both are pure functions of their input, so
 * class-level.
 *
 * <p>Decoding runs in two phases. Phase 1 reads every field into raw values through one bounds checked reader and
 * defers each record to a {@link Supplier}, so no record constructor runs on structurally bad input. Phase 2 runs
 * those suppliers, then encodes the decision again and requires the input byte for byte. Phase 2 catches only
 * {@code IllegalArgumentException} and {@code NullPointerException}, the two exceptions the routing record
 * constructors throw for bad values; anything else is a codec bug and is left to surface.
 */
final class ReceiptCodec {

    private static final String TAG_PREFIX = "routing-advice-receipt/";
    private static final String SUPPORTED_MAJOR = "1";
    private static final int MAX_TAG_BYTES = 64;
    // a token is at most 128 code points of at most 4 UTF-8 bytes each
    private static final int MAX_TOKEN_BYTES = OpaqueTokens.MAX_CODE_POINTS * 4;

    private static final int SELECTED = 1;
    private static final int ABSTAIN = 2;
    private static final int NO_ELIGIBLE_ROUTE = 3;

    private static final int COMPATIBLE = 1;
    private static final int NOT_EVALUATED = 2;
    private static final int INCOMPATIBLE = 3;

    private static final StateMismatch[] MISMATCHES = StateMismatch.values();
    private static final ResourceObjective.Direction[] DIRECTIONS = ResourceObjective.Direction.values();
    private static final Basis[] BASES = Basis.values();
    private static final Placement[] PLACEMENTS = Placement.values();
    private static final RoutingRule[] RULES = RoutingRule.values();
    private static final AbstainReason[] ABSTAIN_REASONS = AbstainReason.values();
    private static final EligibilityReason[] ELIGIBILITY_REASONS = EligibilityReason.values();

    private ReceiptCodec() {
    }

    /** The canonical encoding of a decision; its length is not checked against the cap here. */
    static byte[] encode(RoutingDecision decision) {
        var out = new Writer();
        out.text(AdviceReceipt.FORMAT);
        out.u8(switch (decision) {
            case RoutingDecision.Selected _ -> SELECTED;
            case RoutingDecision.Abstain _ -> ABSTAIN;
            case RoutingDecision.NoEligibleRoute _ -> NO_ELIGIBLE_ROUTE;
        });
        out.provenance(decision.provenance());
        switch (decision) {
            case RoutingDecision.Selected selected -> {
                out.key(selected.route());
                out.text(selected.executionMode());
                out.name(selected.basis());
                out.bool(selected.overflowUsed());
                out.candidates(selected.candidates());
                out.bool(selected.candidatesTruncated());
                out.names(selected.rulesApplied());
                out.names(selected.rulesSkipped());
                out.exclusions(selected.exclusions());
                out.cohort(selected.cohortBinding());
            }
            case RoutingDecision.Abstain abstain -> {
                out.name(abstain.reason());
                out.i32(abstain.eligible().size());
                abstain.eligible().forEach(out::key);
                out.candidates(abstain.candidates());
                out.bool(abstain.candidatesTruncated());
                out.exclusions(abstain.exclusions());
                out.bool(abstain.cohortBinding().isPresent());
                abstain.cohortBinding().ifPresent(out::cohort);
            }
            case RoutingDecision.NoEligibleRoute none -> out.exclusions(none.exclusions());
        }
        return out.bytes();
    }

    /** Reads a receipt strictly; the caller has already rejected a null array. */
    static DecodeResult decode(byte[] input) {
        if (input.length > AdviceReceipt.MAX_ENCODED_BYTES) {
            return new DecodeResult.Rejected(RejectReason.OVERSIZE);
        }
        // parse a private copy, so a caller changing its array during the call cannot affect the result
        byte[] bytes = input.clone();
        Supplier<RoutingDecision> build;
        try {
            var in = new Reader(bytes);
            in.tag();
            build = in.decision();
            in.end();
        } catch (Fault fault) {
            return new DecodeResult.Rejected(fault.reason);
        }
        RoutingDecision decision;
        try {
            decision = build.get();
        } catch (IllegalArgumentException | NullPointerException e) {
            // the message may echo host tokens, so it is dropped
            return new DecodeResult.Rejected(RejectReason.INVALID_DECISION);
        }
        if (!Arrays.equals(encode(decision), bytes)) {
            return new DecodeResult.Rejected(RejectReason.NON_CANONICAL);
        }
        return new DecodeResult.Decoded(AdviceReceipt.decoded(decision, bytes));
    }

    /** Big endian, length prefixed byte writer. */
    private static final class Writer {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        private final ByteBuffer scratch = ByteBuffer.allocate(Long.BYTES);

        void u8(int value) {
            bytes.write(value);
        }

        void bool(boolean value) {
            u8(value ? 1 : 0);
        }

        void i32(int value) {
            bytes.write(scratch.clear().putInt(value).array(), 0, Integer.BYTES);
        }

        void i64(long value) {
            bytes.write(scratch.clear().putLong(value).array(), 0, Long.BYTES);
        }

        void text(String value) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            i32(utf8.length);
            bytes.writeBytes(utf8);
        }

        void name(Enum<?> value) {
            text(value.name());
        }

        void names(List<? extends Enum<?>> values) {
            i32(values.size());
            values.forEach(this::name);
        }

        void key(RouteKey key) {
            text(key.routeId());
            i64(key.routeVersion());
        }

        void provenance(Provenance provenance) {
            DecisionRef ref = provenance.decisionRef();
            text(ref.scopeId());
            text(ref.taskId());
            text(ref.executionId());
            text(ref.attemptId());
            text(ref.stageId());
            i64(ref.requestOrdinal());
            text(provenance.catalogVersion());
            text(provenance.policyId());
            text(provenance.policyVersion());
            i64(provenance.cutoff());
            StateBinding state = provenance.state();
            text(state.scopeId());
            text(state.featureSchemaVersion());
            text(state.evaluationPolicyId());
            text(state.evaluationPolicyVersion());
            text(state.mappingVersion());
            i64(state.processedCutoff());
            text(state.producedByPolicyId());
            text(state.producedByPolicyVersion());
            switch (provenance.validation()) {
                case StateValidation.Compatible _ -> u8(COMPATIBLE);
                case StateValidation.NotEvaluated _ -> u8(NOT_EVALUATED);
                case StateValidation.Incompatible incompatible -> {
                    u8(INCOMPATIBLE);
                    names(incompatible.reasons());
                }
            }
            PolicyParameters parameters = provenance.parameters();
            i32(parameters.minSupportingObservations());
            bool(parameters.maxAutoSelectTier().isPresent());
            parameters.maxAutoSelectTier().ifPresent(this::i32);
            bool(parameters.objective().isPresent());
            parameters.objective().ifPresent(objective -> {
                text(objective.dimension());
                name(objective.direction());
            });
            text(parameters.stateMappingVersion());
            text(parameters.stateFeatureSchemaVersion());
            bool(provenance.stageKind().isPresent());
            provenance.stageKind().ifPresent(this::text);
        }

        void candidates(List<RankedCandidate> candidates) {
            i32(candidates.size());
            for (RankedCandidate candidate : candidates) {
                i32(candidate.rank());
                key(candidate.key());
                name(candidate.placement());
            }
        }

        void exclusions(List<EligibilityReport.Exclusion> exclusions) {
            i32(exclusions.size());
            for (EligibilityReport.Exclusion exclusion : exclusions) {
                key(exclusion.key());
                name(exclusion.primaryReason());
                names(exclusion.additionalReasons());
            }
        }

        void cohort(CohortBinding binding) {
            text(binding.cohortBucket());
            text(binding.bucketMappingVersion());
            text(binding.mappingVersion());
            text(binding.featureSchemaVersion());
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    /**
     * Phase 1: a bounds checked reader that yields raw values and deferred records. Every length and count is checked
     * as an {@code int} against its bound and the bytes remaining before anything is allocated or the position moves.
     */
    private static final class Reader {

        private final byte[] bytes;
        private final ByteBuffer buffer;
        private final CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        private int position;

        Reader(byte[] bytes) {
            this.bytes = bytes;
            this.buffer = ByteBuffer.wrap(bytes);
        }

        void tag() {
            String tag = text(MAX_TAG_BYTES);
            if (!tag.startsWith(TAG_PREFIX)) {
                throw malformed();
            }
            String major = tag.substring(TAG_PREFIX.length());
            boolean digits = !major.isEmpty() && major.chars().allMatch(c -> c >= '0' && c <= '9');
            if (!digits || (major.length() > 1 && major.charAt(0) == '0')) {
                throw malformed();
            }
            // compared as a digit string, never parsed, so no major can overflow
            if (!major.equals(SUPPORTED_MAJOR)) {
                throw new Fault(RejectReason.UNSUPPORTED_VERSION);
            }
        }

        Supplier<RoutingDecision> decision() {
            int variant = u8();
            if (variant < SELECTED || variant > NO_ELIGIBLE_ROUTE) {
                throw malformed();
            }
            Supplier<Provenance> provenance = provenance();
            return switch (variant) {
                case SELECTED -> selected(provenance);
                case ABSTAIN -> abstain(provenance);
                default -> noEligibleRoute(provenance);
            };
        }

        void end() {
            if (position != bytes.length) {
                throw malformed();
            }
        }

        private Supplier<Provenance> provenance() {
            String scopeId = token();
            String taskId = token();
            String executionId = token();
            String attemptId = token();
            String stageId = token();
            long requestOrdinal = i64();
            String catalogVersion = token();
            String policyId = token();
            String policyVersion = token();
            long cutoff = i64();
            Supplier<StateBinding> state = state();
            Supplier<StateValidation> validation = validation();
            Supplier<PolicyParameters> parameters = parameters();
            Optional<String> stageKind = present() ? Optional.of(token()) : Optional.empty();
            return () -> new Provenance(
                    new DecisionRef(scopeId, taskId, executionId, attemptId, stageId, requestOrdinal),
                    catalogVersion, policyId, policyVersion, cutoff, state.get(), validation.get(), parameters.get(),
                    stageKind);
        }

        private Supplier<StateBinding> state() {
            String scopeId = token();
            String featureSchemaVersion = token();
            String evaluationPolicyId = token();
            String evaluationPolicyVersion = token();
            String mappingVersion = token();
            long processedCutoff = i64();
            String producedByPolicyId = token();
            String producedByPolicyVersion = token();
            return () -> new StateBinding(scopeId, featureSchemaVersion, evaluationPolicyId, evaluationPolicyVersion,
                    mappingVersion, processedCutoff, producedByPolicyId, producedByPolicyVersion);
        }

        private Supplier<StateValidation> validation() {
            return switch (u8()) {
                case COMPATIBLE -> StateValidation.Compatible::new;
                case NOT_EVALUATED -> StateValidation.NotEvaluated::new;
                case INCOMPATIBLE -> {
                    List<StateMismatch> reasons = constants(MISMATCHES.length, MISMATCHES);
                    yield () -> new StateValidation.Incompatible(reasons);
                }
                default -> throw malformed();
            };
        }

        private Supplier<PolicyParameters> parameters() {
            int minSupportingObservations = i32();
            OptionalInt maxAutoSelectTier = present() ? OptionalInt.of(i32()) : OptionalInt.empty();
            Supplier<Optional<ResourceObjective>> objective;
            if (present()) {
                String dimension = token();
                ResourceObjective.Direction direction = constant(DIRECTIONS);
                objective = () -> Optional.of(new ResourceObjective(dimension, direction));
            } else {
                objective = Optional::empty;
            }
            String stateMappingVersion = token();
            String stateFeatureSchemaVersion = token();
            return () -> new PolicyParameters(minSupportingObservations, maxAutoSelectTier, objective.get(),
                    stateMappingVersion, stateFeatureSchemaVersion);
        }

        private Supplier<RoutingDecision> selected(Supplier<Provenance> provenance) {
            Supplier<RouteKey> route = key();
            String executionMode = token();
            Basis basis = constant(BASES);
            boolean overflowUsed = bool();
            Supplier<List<RankedCandidate>> candidates = candidates();
            boolean candidatesTruncated = bool();
            List<RoutingRule> rulesApplied = constants(RULES.length, RULES);
            List<RoutingRule> rulesSkipped = constants(RULES.length, RULES);
            Supplier<List<EligibilityReport.Exclusion>> exclusions = exclusions();
            Supplier<CohortBinding> cohort = cohort();
            return () -> new RoutingDecision.Selected(provenance.get(), route.get(), executionMode, basis,
                    overflowUsed, candidates.get(), candidatesTruncated, rulesApplied, rulesSkipped,
                    exclusions.get(), cohort.get());
        }

        private Supplier<RoutingDecision> abstain(Supplier<Provenance> provenance) {
            AbstainReason reason = constant(ABSTAIN_REASONS);
            int count = count(RouteCatalog.MAX_ROUTES);
            List<Supplier<RouteKey>> eligible = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                eligible.add(key());
            }
            Supplier<List<RankedCandidate>> candidates = candidates();
            boolean candidatesTruncated = bool();
            Supplier<List<EligibilityReport.Exclusion>> exclusions = exclusions();
            Supplier<Optional<CohortBinding>> cohort;
            if (present()) {
                Supplier<CohortBinding> binding = cohort();
                cohort = () -> Optional.of(binding.get());
            } else {
                cohort = Optional::empty;
            }
            return () -> new RoutingDecision.Abstain(provenance.get(), reason, all(eligible).get(),
                    candidates.get(), candidatesTruncated, exclusions.get(), cohort.get());
        }

        private Supplier<RoutingDecision> noEligibleRoute(Supplier<Provenance> provenance) {
            Supplier<List<EligibilityReport.Exclusion>> exclusions = exclusions();
            return () -> new RoutingDecision.NoEligibleRoute(provenance.get(), exclusions.get());
        }

        private Supplier<List<RankedCandidate>> candidates() {
            int count = count(RoutingDecision.MAX_CANDIDATES);
            List<Supplier<RankedCandidate>> candidates = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int rank = i32();
                Supplier<RouteKey> key = key();
                Placement placement = constant(PLACEMENTS);
                candidates.add(() -> new RankedCandidate(rank, key.get(), placement));
            }
            return all(candidates);
        }

        private Supplier<List<EligibilityReport.Exclusion>> exclusions() {
            int count = count(RouteCatalog.MAX_ROUTES);
            List<Supplier<EligibilityReport.Exclusion>> exclusions = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Supplier<RouteKey> key = key();
                EligibilityReason primary = constant(ELIGIBILITY_REASONS);
                List<EligibilityReason> additional =
                        constants(EligibilityReport.Exclusion.MAX_ADDITIONAL, ELIGIBILITY_REASONS);
                exclusions.add(() -> new EligibilityReport.Exclusion(key.get(), primary, additional));
            }
            return all(exclusions);
        }

        private Supplier<CohortBinding> cohort() {
            String cohortBucket = token();
            String bucketMappingVersion = token();
            String mappingVersion = token();
            String featureSchemaVersion = token();
            return () -> new CohortBinding(cohortBucket, bucketMappingVersion, mappingVersion, featureSchemaVersion);
        }

        private Supplier<RouteKey> key() {
            String routeId = token();
            long routeVersion = i64();
            return () -> new RouteKey(routeId, routeVersion);
        }

        private <E extends Enum<E>> List<E> constants(int max, E[] constants) {
            int count = count(max);
            List<E> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(constant(constants));
            }
            return values;
        }

        // a name table, not valueOf: a miss here is a structural fault, never an IllegalArgumentException
        private <E extends Enum<E>> E constant(E[] constants) {
            String name = token();
            for (E constant : constants) {
                if (constant.name().equals(name)) {
                    return constant;
                }
            }
            throw malformed();
        }

        private int count(int max) {
            int count = i32();
            if (count < 0 || count > max) {
                throw malformed();
            }
            return count;
        }

        private String token() {
            return text(MAX_TOKEN_BYTES);
        }

        private String text(int maxBytes) {
            int length = i32();
            if (length < 1 || length > maxBytes || length > bytes.length - position) {
                throw malformed();
            }
            String value;
            try {
                value = utf8.decode(buffer.slice(position, length)).toString();
            } catch (CharacterCodingException e) {
                throw malformed();
            }
            position += length;
            return value;
        }

        private boolean present() {
            return bool();
        }

        private boolean bool() {
            int value = u8();
            if (value > 1) {
                throw malformed();
            }
            return value == 1;
        }

        private int u8() {
            need(1);
            return bytes[position++] & 0xFF;
        }

        private int i32() {
            need(Integer.BYTES);
            int value = buffer.getInt(position);
            position += Integer.BYTES;
            return value;
        }

        private long i64() {
            need(Long.BYTES);
            long value = buffer.getLong(position);
            position += Long.BYTES;
            return value;
        }

        private void need(int count) {
            if (count > bytes.length - position) {
                throw malformed();
            }
        }

        private static <T> Supplier<List<T>> all(List<Supplier<T>> parts) {
            return () -> parts.stream().map(Supplier::get).toList();
        }

        private static Fault malformed() {
            return new Fault(RejectReason.MALFORMED);
        }
    }

    /** A phase 1 fault; carries only the reason and records no stack trace. */
    private static final class Fault extends RuntimeException {

        private final RejectReason reason;

        Fault(RejectReason reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }
}
