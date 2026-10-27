package monada.neuron.routing.receipt;

import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.Provenance;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.StateValidation;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteKey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A second writer for {@code routing-advice-receipt/1}, built from the layout table of
 * {@code docs/specs/routing-advice-receipt-v1.md} with a {@link ByteBuffer} and without the production codec, so a
 * golden vector checks the codec against the spec rather than against itself.
 */
final class ReferenceReceiptWriter {

    private final ByteBuffer out = ByteBuffer.allocate(AdviceReceipt.MAX_ENCODED_BYTES);

    private ReferenceReceiptWriter() {
    }

    static byte[] write(RoutingDecision decision) {
        var writer = new ReferenceReceiptWriter();
        writer.str("routing-advice-receipt/1");
        writer.out.put((byte) switch (decision) {
            case RoutingDecision.Selected _ -> 1;
            case RoutingDecision.Abstain _ -> 2;
            case RoutingDecision.NoEligibleRoute _ -> 3;
        });
        writer.provenance(decision.provenance());
        switch (decision) {
            case RoutingDecision.Selected s -> {
                writer.key(s.route());
                writer.str(s.executionMode());
                writer.str(s.basis().name());
                writer.flag(s.overflowUsed());
                writer.candidates(s.candidates());
                writer.flag(s.candidatesTruncated());
                writer.enums(s.rulesApplied());
                writer.enums(s.rulesSkipped());
                writer.exclusions(s.exclusions());
                writer.cohort(s.cohortBinding());
            }
            case RoutingDecision.Abstain a -> {
                writer.str(a.reason().name());
                writer.out.putInt(a.eligible().size());
                for (RouteKey key : a.eligible()) {
                    writer.key(key);
                }
                writer.candidates(a.candidates());
                writer.flag(a.candidatesTruncated());
                writer.exclusions(a.exclusions());
                writer.flag(a.cohortBinding().isPresent());
                a.cohortBinding().ifPresent(writer::cohort);
            }
            case RoutingDecision.NoEligibleRoute n -> writer.exclusions(n.exclusions());
        }
        return Arrays.copyOf(writer.out.array(), writer.out.position());
    }

    // rows 3 to 8
    private void provenance(Provenance p) {
        var ref = p.decisionRef();
        str(ref.scopeId());
        str(ref.taskId());
        str(ref.executionId());
        str(ref.attemptId());
        str(ref.stageId());
        out.putLong(ref.requestOrdinal());
        str(p.catalogVersion());
        str(p.policyId());
        str(p.policyVersion());
        out.putLong(p.cutoff());
        var state = p.state();
        str(state.scopeId());
        str(state.featureSchemaVersion());
        str(state.evaluationPolicyId());
        str(state.evaluationPolicyVersion());
        str(state.mappingVersion());
        out.putLong(state.processedCutoff());
        str(state.producedByPolicyId());
        str(state.producedByPolicyVersion());
        if (p.validation() instanceof StateValidation.Incompatible incompatible) {
            out.put((byte) 3);
            enums(incompatible.reasons());
        } else {
            out.put((byte) (p.validation() instanceof StateValidation.Compatible ? 1 : 2));
        }
        var parameters = p.parameters();
        out.putInt(parameters.minSupportingObservations());
        flag(parameters.maxAutoSelectTier().isPresent());
        if (parameters.maxAutoSelectTier().isPresent()) {
            out.putInt(parameters.maxAutoSelectTier().getAsInt());
        }
        flag(parameters.objective().isPresent());
        parameters.objective().ifPresent(objective -> {
            str(objective.dimension());
            str(objective.direction().name());
        });
        str(parameters.stateMappingVersion());
        str(parameters.stateFeatureSchemaVersion());
        flag(p.stageKind().isPresent());
        p.stageKind().ifPresent(this::str);
    }

    private void candidates(List<RankedCandidate> candidates) {
        out.putInt(candidates.size());
        for (RankedCandidate c : candidates) {
            out.putInt(c.rank());
            key(c.key());
            str(c.placement().name());
        }
    }

    private void exclusions(List<EligibilityReport.Exclusion> exclusions) {
        out.putInt(exclusions.size());
        for (EligibilityReport.Exclusion e : exclusions) {
            key(e.key());
            str(e.primaryReason().name());
            enums(e.additionalReasons());
        }
    }

    private void cohort(CohortBinding c) {
        str(c.cohortBucket());
        str(c.bucketMappingVersion());
        str(c.mappingVersion());
        str(c.featureSchemaVersion());
    }

    private void key(RouteKey key) {
        str(key.routeId());
        out.putLong(key.routeVersion());
    }

    private void enums(List<? extends Enum<?>> values) {
        out.putInt(values.size());
        values.forEach(value -> str(value.name()));
    }

    private void flag(boolean value) {
        out.put((byte) (value ? 1 : 0));
    }

    private void str(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.putInt(utf8.length).put(utf8);
    }
}
