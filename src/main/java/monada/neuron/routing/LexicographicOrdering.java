package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;

/**
 * The ordering checks {@code route-lex/1} verification shares between its trusted and self-reported modes (Forge Routing
 * Contract v1, sections 3.3 and 17.5): the exact lexicographic ranking, its bounded explanation, truncation and the tier
 * cap, recomputed with {@link LexicographicRanking} from the parameters and cohort bucket the caller supplies. Instances
 * are immutable and stateless.
 */
final class LexicographicOrdering {

    private final String parameterSource;

    /**
     * @param parameterSource whose parameters bound the check ("trusted" or "recorded"), used in diagnostics only
     */
    LexicographicOrdering(String parameterSource) {
        this.parameterSource = parameterSource;
    }

    /** The ranking reads {@code report.eligible()}, so a report for another catalog would compare the wrong routes. */
    void requireReportFor(EligibilityReport report, RoutingInput input) {
        if (!report.catalogVersion().equals(input.catalog().catalogVersion())) {
            throw new IllegalArgumentException("eligibility report was computed for catalog " + report.catalogVersion()
                    + ", not " + input.catalog().catalogVersion());
        }
    }

    /** Verifies the ordering of every decision variant that ran one, with the given parameters and bucket source. */
    void verify(RoutingDecision decision, EligibilityReport report, RoutingInput input, PolicyParameters parameters,
            BucketSource buckets) {
        switch (decision) {
            case RoutingDecision.Selected selected ->
                    verifySelected(selected, report, input, parameters, buckets.bucketOf(selected.cohortBinding()));
            case RoutingDecision.Abstain abstain when abstain.reason() == AbstainReason.POLICY_TRADEOFF_UNRESOLVED ->
                    verifyTradeoff(abstain, report, input, parameters,
                            buckets.bucketOf(abstain.cohortBinding().orElseThrow()));
            case RoutingDecision.Abstain ignored -> { }
            case RoutingDecision.NoEligibleRoute ignored -> { }
        }
    }

    /** Supplies the cohort bucket the ranking runs with for a recorded binding. */
    interface BucketSource {
        String bucketOf(CohortBinding recorded);
    }

    /**
     * A {@code Selected} decision must equal the contract ordering recomputed from the parameters and the cohort bucket,
     * and its winner must not exceed the tier cap of those parameters.
     */
    private void verifySelected(RoutingDecision.Selected selected, EligibilityReport report, RoutingInput input,
            PolicyParameters parameters, String bucket) {
        LexicographicRanking.Result expected = new LexicographicRanking(
                parameters.minSupportingObservations(), parameters.objective())
                .rank(input.request(), input.catalog(), report.eligible(), input.preference(), bucket);
        boolean sameRanking = expected.order().getFirst().route().key().equals(selected.route())
                && expected.candidates().equals(selected.candidates())
                && expected.truncated() == selected.candidatesTruncated()
                && expected.applied().equals(selected.rulesApplied())
                && expected.skipped().equals(selected.rulesSkipped())
                && expected.candidates().getFirst().placement().basis() == selected.basis();
        if (!sameRanking) {
            throw new IllegalStateException("policy ranking disagrees with the recomputed ranking: expected "
                    + expected.candidates() + " (applied " + expected.applied() + ", skipped " + expected.skipped()
                    + "), got " + selected.candidates() + " (applied " + selected.rulesApplied() + ", skipped "
                    + selected.rulesSkipped() + ", basis " + selected.basis() + ")");
        }
        if (exceedsCap(expected, parameters)) {
            throw new IllegalStateException("policy selected " + selected.route() + " (tier "
                    + expected.order().getFirst().tier() + ") above the " + parameterSource + " tier cap "
                    + parameters.maxAutoSelectTier());
        }
    }

    /**
     * A tier-cap abstention is the only tradeoff trigger. The ranking is recomputed with the cohort bucket and must be
     * reproduced, and the recomputed winner must exceed the cap.
     */
    private void verifyTradeoff(RoutingDecision.Abstain abstain, EligibilityReport report, RoutingInput input,
            PolicyParameters parameters, String bucket) {
        LexicographicRanking.Result expected = new LexicographicRanking(
                parameters.minSupportingObservations(), parameters.objective())
                .rank(input.request(), input.catalog(), report.eligible(), input.preference(), bucket);
        if (!expected.candidates().equals(abstain.candidates())
                || expected.truncated() != abstain.candidatesTruncated()) {
            throw new IllegalStateException("policy ranking disagrees with the recomputed ranking: expected "
                    + expected.candidates() + ", got " + abstain.candidates());
        }
        if (!exceedsCap(expected, parameters)) {
            throw new IllegalStateException("policy abstained on the tier cap but the winner does not exceed the "
                    + parameterSource + " cap " + parameters.maxAutoSelectTier());
        }
    }

    private boolean exceedsCap(LexicographicRanking.Result ranking, PolicyParameters parameters) {
        return parameters.maxAutoSelectTier().isPresent()
                && ranking.order().getFirst().tier() > parameters.maxAutoSelectTier().getAsInt();
    }
}
