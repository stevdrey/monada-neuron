package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.ClusterBootstrap.Interval;
import monada.neuron.evaluation.bilingual.GridSelection.Selection;
import monada.neuron.evaluation.bilingual.NeuronSenseArm.Scored;
import monada.neuron.evaluation.bilingual.SenseArm.Prediction;
import monada.neuron.evaluation.bilingual.SenseArm.TrainingExample;
import monada.neuron.evaluation.bilingual.SenseDataset.DatasetItem;
import monada.neuron.evaluation.bilingual.SenseMetrics.Labels;
import monada.neuron.evaluation.bilingual.SenseMetrics.Outputs;
import monada.neuron.evaluation.bilingual.SenseMetrics.Sense;
import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Step 8 of {@code bilingual-sense/2}: fits arms A, B, C and N on training, selects each arm's grid point per direction
 * on validation, answers the sealed held-out split once, then opens its gold and scores it with the registered metrics,
 * paired cluster bootstrap, hypotheses, checks and per-direction verdict.
 *
 * <p>The optional wave arm W is not run; its omission is reported (protocol "Arm W"), so checks {@code w.*} do not
 * apply. Every verdict carries the dataset's label status; under v2 a GO is an {@code EXPLORATORY GO}.
 *
 * <p>The run executes the whole pipeline twice from fresh datasets for check {@code determinism}.
 */
final class BilingualSenseEvaluation {

    /** Protocol id. */
    static final String PROTOCOL_ID = "bilingual-sense/2";
    /** H1 margin on {@code Macro(N) - Macro(X)}. */
    static final double H1_MARGIN = 0.05;
    /** H2a margin on {@code AURC(X) - AURC(N)}. */
    static final double H2A_MARGIN = 0.02;
    /** H2b bound on the abstention AUROC of N. */
    static final double H2B_BOUND = 0.70;
    /** One-sided level of {@code nc.label-permutation}. */
    static final double PERMUTATION_LEVEL = 0.995;
    /** Budget: fitting plus selection per direction and arm. */
    static final long FIT_SELECT_BUDGET_NANOS = 15L * 60 * 1_000_000_000L;
    /** Budget: median held-out inference per item. */
    static final long INFERENCE_BUDGET_NANOS = 10_000_000L;
    /** Budget: retained model state. */
    static final long STATE_BUDGET_BYTES = 64L << 20;
    /** Budget: whole-harness heap. */
    static final long HEAP_BUDGET_BYTES = 2L << 30;
    /** Memorization probe size per {@code (direction, lemma)}. */
    static final int PROBE_SIZE = 30;
    /** Warm-up and measured iterations of the cost measurement. */
    static final int WARMUP = 3;
    static final int MEASURED = 10;

    /** A semantic check. */
    record Check(String name, boolean passed, String detail) implements EvaluationCheck {
    }

    /**
     * Point value and paired 95% interval.
     *
     * @param point value on the held-out items, empty when undefined
     * @param interval bootstrap interval
     */
    record Estimate(OptionalDouble point, Interval interval) {
    }

    /**
     * Scored results of one arm in one direction.
     *
     * @param arm arm name
     * @param selection selected grid point
     * @param selectionDescription readable configuration and threshold
     * @param macro sense-macro forced accuracy
     * @param aurc AURC
     * @param auroc abstention AUROC
     * @param values every other point metric, by name, empty when {@code n/a}
     * @param perSense per scored sense, its accuracy (reported, never gated)
     * @param strata per stratum, its summary
     * @param cost cost measurements
     */
    record ArmResult(String arm, Selection selection, String selectionDescription, Estimate macro, Estimate aurc,
                     Estimate auroc, Map<String, OptionalDouble> values, Map<String, OptionalDouble> perSense,
                     Map<String, String> strata, Cost cost) {
    }

    /**
     * Cost of one arm in one direction.
     *
     * @param fitNanos fitting time (all training, shared by both directions)
     * @param selectNanos selection time in this direction
     * @param medianNanos median held-out inference time per item, -1 when not measured
     * @param p95Nanos 95th percentile held-out inference time per item, -1 when not measured
     * @param bytesPerItem allocated bytes per held-out item
     * @param retainedBytes estimated retained model state
     */
    record Cost(long fitNanos, long selectNanos, long medianNanos, long p95Nanos, double bytesPerItem,
                long retainedBytes) {

        /** Returns whether inference was measured; {@code medianNanos} and {@code p95Nanos} are -1 otherwise. */
        boolean measured() {
            return medianNanos >= 0;
        }

        /** Returns whether every limit is met; an unmeasured inference cost never meets its limit. */
        boolean withinBudget() {
            return measured() && fitNanos + selectNanos <= FIT_SELECT_BUDGET_NANOS
                    && medianNanos <= INFERENCE_BUDGET_NANOS && retainedBytes <= STATE_BUDGET_BYTES;
        }
    }

    /**
     * Results of one direction.
     *
     * @param direction the direction
     * @param verdict the verdict with its label status
     * @param reason why the verdict was reached
     * @param arms per-arm results; empty when the direction is not testable
     * @param h1 {@code Macro(N) - Macro(X)} for {@code X = A, B, C}
     * @param h2a {@code AURC(X) - AURC(N)} for {@code X = A, B, C}
     * @param macroConstant {@code Macro_const}
     * @param notes descriptive notes (low-frequency testability, item counts)
     */
    record DirectionResult(Direction direction, String verdict, String reason, List<ArmResult> arms,
                           Map<String, Estimate> h1, Map<String, Estimate> h2a, OptionalDouble macroConstant,
                           List<String> notes) {
    }

    /**
     * Outcome of a run.
     *
     * @param checks every check
     * @param directions per-direction results
     * @param fingerprint SHA-256 of the outputs, selections and intervals of the first pass
     * @param resources cost measurements, one per arm and direction
     * @param metadata protocol, provenance and run metadata
     */
    record Outcome(List<Check> checks, List<DirectionResult> directions, String fingerprint,
                   List<BenchmarkRunResult> resources, Map<String, String> metadata) {

        boolean allPassed() {
            return checks.stream().allMatch(Check::passed);
        }
    }

    /**
     * Fitted arms, selections and outputs of one pass, computed before the held-out gold is opened. {@code selections}
     * and {@code heldOut} hold only the directions that passed step 7; each {@code heldOut} array is indexed by held-out
     * item, and an item of another direction was never given to the arm ({@code k* = -1}, not answered).
     */
    record Pipeline(SenseWindows windows, List<SenseArm> arms, long[] fitNanos, Map<Direction, Selection[]> selections,
                            Map<Direction, long[]> selectNanos, Map<Direction, Outputs[]> heldOut) {
    }

    private final int resamples;
    private final boolean measureCost;
    private final SenseMetrics metrics = new SenseMetrics();
    private final GridSelection selection = new GridSelection();
    private final KeyedHash hash = new KeyedHash();

    /**
     * @param resamples bootstrap resamples ({@link ClusterBootstrap#RESAMPLES} for the registered run)
     * @param measureCost whether to run the warm-up and measured inference passes
     */
    BilingualSenseEvaluation(int resamples, boolean measureCost) {
        this.resamples = resamples;
        this.measureCost = measureCost;
    }

    /**
     * Runs the evaluation twice from fresh datasets and returns the first pass with the determinism check. The check
     * covers the fingerprints and every check outcome, so a check that fails only in the second pass is not lost, and
     * a failure suppresses the verdicts as any other failed check does.
     */
    Outcome run(Supplier<SenseDataset> datasets) {
        Outcome first = once(datasets.get());
        Outcome second = once(datasets.get());
        var checks = new ArrayList<>(first.checks());
        boolean sameFingerprint = first.fingerprint().equals(second.fingerprint());
        List<String> differing = differingChecks(first.checks(), second.checks());
        boolean same = sameFingerprint && differing.isEmpty();
        checks.add(new Check("determinism", same, same
                ? "a second full run is bit-identical in outputs, selections and intervals (" + first.fingerprint() + ")"
                : (sameFingerprint ? "fingerprints match" : "fingerprints differ: " + first.fingerprint() + " vs "
                + second.fingerprint()) + "; check outcomes that differ: " + differing));
        return new Outcome(checks, withheld(first.directions(), first.metadata().get("labelStatus"), checks),
                first.fingerprint(), first.resources(), first.metadata());
    }

    /** Returns the names of the checks whose outcome differs between two passes of the same run. */
    private List<String> differingChecks(List<Check> first, List<Check> second) {
        var names = new ArrayList<String>();
        for (int c = 0; c < first.size(); c++) {
            if (first.get(c).passed() != second.get(c).passed()) {
                names.add(first.get(c).name());
            }
        }
        return names;
    }

    /**
     * Returns the directions with their verdicts withheld when any check failed: {@code UNTRUSTWORTHY}, except that
     * {@code NOT TESTABLE} (a data gate, or H2 not testable) takes precedence.
     */
    private List<DirectionResult> withheld(List<DirectionResult> directions, String labelStatus, List<Check> checks) {
        boolean anyFailed = checks.stream().anyMatch(check -> !check.passed());
        var results = new ArrayList<DirectionResult>();
        for (DirectionResult result : directions) {
            results.add(anyFailed && !result.verdict().startsWith("NOT TESTABLE")
                    ? new DirectionResult(result.direction(), "UNTRUSTWORTHY (" + labelStatus + ")",
                    "a check failed; no verdict is recorded", result.arms(), result.h1(), result.h2a(),
                    result.macroConstant(), result.notes())
                    : result);
        }
        return results;
    }

    /** One full pass. */
    Outcome once(SenseDataset dataset) {
        var checks = new ArrayList<Check>();
        checks.add(inputSurface());
        checks.add(familyDisjoint(dataset));
        checks.add(nearDuplicate(dataset));
        checks.add(inventoryHash(dataset));
        if (dataset.scored().testableDirections().isEmpty()) {
            return globalStop(dataset, checks);
        }

        Pipeline pipeline = pipeline(dataset, List.of("A", "B", "C", "N"));
        Pipeline counterfactual = pipeline(dataset.withHeldOutGold(dataset.heldOutGold().permutedWithin(
                dataset.heldOut().stream().map(item -> item.input().lemmaKey()).toList(), hash)), List.of("A", "B", "C", "N"));
        checks.add(heldOutCounterfactual(pipeline, counterfactual));
        checks.add(unseenLemma(dataset, pipeline));
        NeuronSenseArm neuron = (NeuronSenseArm) pipeline.arms().get(3);
        checks.add(contextMask(dataset, neuron));
        checks.add(policyEquivalence(dataset, neuron));

        // Every system output, the permutation control's included, exists before the held-out gold is opened.
        Pipeline permuted = pipeline(dataset.withTrainingGold(permutedTraining(dataset)), List.of("C", "N"));
        int earlyReads = dataset.heldOutGold().earlyReads();
        checks.add(new Check("leakage.test-labels", earlyReads == 0, earlyReads == 0
                ? "no held-out gold was read before every arm answered"
                : earlyReads + " held-out gold reads happened before scoring"));
        dataset.heldOutGold().openForScoring();
        String goldHash = SenseDataset.sha256(dataset.heldOutGoldText());
        boolean sealed = goldHash.equals(dataset.hashes().heldOutGoldSha256());
        checks.add(new Check("leakage.sealed-hash", sealed, sealed
                ? "held-out gold SHA-256 equals the step-7 commitment " + goldHash
                : "held-out gold SHA-256 " + goldHash + " differs from " + dataset.hashes().heldOutGoldSha256()));
        checks.add(new MetricsFixture().check());

        var fingerprint = new StringBuilder();
        var directions = new ArrayList<DirectionResult>();
        var resources = new ArrayList<BenchmarkRunResult>();
        var permutation = new ArrayList<String>();
        boolean permutationPassed = true;
        for (Direction direction : Direction.values()) {
            if (!dataset.scored().testableDirections().contains(direction)) {
                directions.add(new DirectionResult(direction, "NOT TESTABLE (" + dataset.labelStatus() + ")",
                        "the direction stopped at a step-7 data gate", List.of(), Map.of(), Map.of(),
                        OptionalDouble.empty(), List.of()));
                continue;
            }
            DirectionResult result = score(dataset, direction, pipeline, resources, fingerprint);
            directions.add(result);
            // nc.label-permutation: N and C refitted on training labels permuted within each lemma.
            Scope scope = scope(dataset, direction);
            var bootstrap = new ClusterBootstrap(scope.families(), resamples, ClusterBootstrap.SEED);
            for (int a = 0; a < 2; a++) {
                Outputs outputs = restrict(permuted.heldOut().get(direction)[a], scope.items());
                double[] values = bootstrap.values(items -> difference(
                        metrics.macro(scope.labels(), outputs, items, scope.senses()),
                        metrics.macroConstant(scope.labels(), items, scope.senses(), scope.clusters())));
                double lower = bootstrap.lowerBound(values, PERMUTATION_LEVEL);
                boolean fails = permutationFails(lower);
                permutationPassed &= !fails;
                permutation.add(direction.label() + " " + permuted.arms().get(a).name() + ": lower "
                        + format(lower) + (fails ? " not <= 0 (FAIL)" : " <= 0"));
                fingerprint.append(direction).append(permuted.arms().get(a).name())
                        .append(permuted.selections().get(direction)[a])
                        .append(Arrays.toString(outputs.choice())).append(Arrays.toString(outputs.confidence()))
                        .append(Arrays.toString(outputs.answered())).append(Double.toHexString(lower));
            }
        }
        checks.add(new Check("nc.label-permutation", permutationPassed,
                "Macro(arm, permuted) - Macro_const, one-sided lower bound at 0.995: " + String.join("; ", permutation)));
        checks.add(budget(directions));

        return new Outcome(checks, withheld(directions, dataset.labelStatus(), checks),
                SenseDataset.sha256(fingerprint.toString()), resources, metadata(dataset));
    }

    /**
     * The global stop of the protocol: no direction met its minimum, so no system is run on this data, the held-out
     * gold stays sealed and the epic records a data no-go. Only the checks that read no system output and no gold run.
     */
    private Outcome globalStop(SenseDataset dataset, List<Check> checks) {
        checks.add(new MetricsFixture().check());
        var directions = new ArrayList<DirectionResult>();
        for (Direction direction : Direction.values()) {
            directions.add(new DirectionResult(direction, "NOT TESTABLE (" + dataset.labelStatus() + ")",
                    "global stop: no direction met its step-7 minimum, so no system ran and the held-out gold stayed "
                            + "sealed", List.of(), Map.of(), Map.of(), OptionalDouble.empty(), List.of()));
        }
        var metadata = metadata(dataset);
        metadata.put("globalStop", "no system was run; data no-go");
        return new Outcome(checks, withheld(directions, dataset.labelStatus(), checks),
                SenseDataset.sha256("global stop"), List.of(), metadata);
    }

    private Map<String, String> metadata(SenseDataset dataset) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("protocol", PROTOCOL_ID);
        metadata.put("provenance", dataset.provenance());
        metadata.put("labelStatus", dataset.labelStatus());
        metadata.put("bootstrap", resamples + " resamples, seed 0x" + Long.toHexString(ClusterBootstrap.SEED));
        metadata.put("armW", "omitted: optional under the protocol; no wave-token result is produced");
        metadata.put("items", "training " + dataset.training().size() + ", validation " + dataset.validation().size()
                + ", held-out " + dataset.heldOut().size());
        return metadata;
    }

    /**
     * Returns whether a {@code nc.label-permutation} one-sided lower bound fails the check: it exceeds 0, or it is
     * undefined ({@code NaN}), which cannot show that the control is harmless.
     */
    boolean permutationFails(double lower) {
        return !(lower <= 0.0);
    }

    /**
     * Fits the named arms and, for each direction that passed step 7, selects on validation and answers that
     * direction's held-out items; a stopped direction is neither fitted, selected nor answered. No held-out gold is read.
     */
    Pipeline pipeline(SenseDataset dataset, List<String> names) {
        var windows = new SenseWindows(new SenseTokenizer());
        var arms = new ArrayList<SenseArm>();
        for (String name : names) {
            arms.add(switch (name) {
                case "A" -> new MajoritySenseArm();
                case "B" -> new NearestExampleArm(windows);
                case "C" -> new ContextualLexicalArm(windows);
                case "N" -> new NeuronSenseArm(windows);
                default -> throw new IllegalArgumentException("unknown arm " + name);
            });
        }
        Set<Direction> testable = dataset.scored().testableDirections();
        List<TrainingExample> training = trainingExamples(dataset.training(), dataset.trainingGold(), testable);
        var fitNanos = new long[arms.size()];
        for (int a = 0; a < arms.size(); a++) {
            long start = System.nanoTime();
            arms.get(a).fit(training);
            fitNanos[a] = System.nanoTime() - start;
        }
        var selections = new LinkedHashMap<Direction, Selection[]>();
        var selectNanos = new LinkedHashMap<Direction, long[]>();
        var heldOut = new LinkedHashMap<Direction, Outputs[]>();
        for (Direction direction : testable.stream().sorted().toList()) {
            var validationItems = new ArrayList<SenseItem>();
            var validationGold = new ArrayList<SenseGold>();
            for (int i = 0; i < dataset.validation().size(); i++) {
                SenseItem input = dataset.validation().get(i).input();
                if (input.direction() == direction && dataset.scored().scoredLemma(input.lemmaKey())) {
                    validationItems.add(input);
                    validationGold.add(dataset.validationGold().get(i));
                }
            }
            Labels labels = labels(validationItems, validationGold, new HashMap<>());
            var chosen = new Selection[arms.size()];
            var nanos = new long[arms.size()];
            var outputs = new Outputs[arms.size()];
            List<SenseItem> heldOutItems = dataset.heldOut().stream().map(DatasetItem::input).toList();
            for (int a = 0; a < arms.size(); a++) {
                long start = System.nanoTime();
                chosen[a] = validationItems.isEmpty() ? new Selection(0, 0, 0.0, 0.0, 0.0, false)
                        : selection.select(arms.get(a), validationItems, labels);
                nanos[a] = System.nanoTime() - start;
                outputs[a] = answerDirection(arms.get(a), chosen[a], heldOutItems, direction);
            }
            selections.put(direction, chosen);
            selectNanos.put(direction, nanos);
            heldOut.put(direction, outputs);
        }
        return new Pipeline(windows, List.copyOf(arms), fitNanos, selections, selectNanos, heldOut);
    }

    /** Answers the items of one direction; every other item stays unanswered and is never given to the arm. */
    private Outputs answerDirection(SenseArm arm, Selection point, List<SenseItem> items, Direction direction) {
        var choice = new int[items.size()];
        var confidence = new double[items.size()];
        var answered = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).direction() != direction) {
                choice[i] = -1;
                continue;
            }
            Prediction prediction = arm.predict(items.get(i), point.configuration());
            choice[i] = prediction.choice();
            confidence[i] = prediction.confidence();
            answered[i] = prediction.answers(point.tau());
        }
        return new Outputs(choice, confidence, answered);
    }

    /** Answers every item at a selected grid point. */
    private Outputs predict(SenseArm arm, Selection point, List<SenseItem> items) {
        var choice = new int[items.size()];
        var confidence = new double[items.size()];
        var answered = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Prediction prediction = arm.predict(items.get(i), point.configuration());
            choice[i] = prediction.choice();
            confidence[i] = prediction.confidence();
            answered[i] = prediction.answers(point.tau());
        }
        return new Outputs(choice, confidence, answered);
    }

    /** The held-out items of a direction's scored lemmas, with their gold, families and senses. */
    private record Scope(int[] items, Labels labels, List<String> families, List<Sense> senses, int[] clusters,
                         List<String> lemmas) {
    }

    private Scope scope(SenseDataset dataset, Direction direction) {
        var lemmaIndex = new LinkedHashMap<String, Integer>();
        var items = new ArrayList<Integer>();
        var inputs = new ArrayList<SenseItem>();
        var gold = new ArrayList<SenseGold>();
        var families = new ArrayList<String>();
        for (int i = 0; i < dataset.heldOut().size(); i++) {
            DatasetItem item = dataset.heldOut().get(i);
            if (item.input().direction() == direction && dataset.scored().scoredLemma(item.input().lemmaKey())) {
                items.add(i);
                inputs.add(item.input());
                gold.add(dataset.heldOutGold().get(i));
                families.add(item.familyId());
            }
        }
        Labels labels = labels(inputs, gold, lemmaIndex);
        var senses = new ArrayList<Sense>();
        var clusters = new int[lemmaIndex.size()];
        var lemmas = new ArrayList<>(lemmaIndex.keySet());
        for (var entry : lemmaIndex.entrySet()) {
            for (int cluster : dataset.scored().clusters().get(entry.getKey())) {
                senses.add(new Sense(entry.getValue(), cluster));
            }
        }
        for (SenseItem input : inputs) {
            clusters[lemmaIndex.get(input.lemmaKey())] = input.inventory().size();
        }
        return new Scope(items.stream().mapToInt(Integer::intValue).toArray(), labels, families, senses, clusters,
                lemmas);
    }

    private DirectionResult score(SenseDataset dataset, Direction direction, Pipeline pipeline,
                                  List<BenchmarkRunResult> resources, StringBuilder fingerprint) {
        Scope scope = scope(dataset, direction);
        int[] all = metrics.all(scope.items().length);
        var bootstrap = new ClusterBootstrap(scope.families(), resamples, ClusterBootstrap.SEED);
        Outputs[] outputs = new Outputs[pipeline.arms().size()];
        for (int a = 0; a < outputs.length; a++) {
            outputs[a] = restrict(pipeline.heldOut().get(direction)[a], scope.items());
        }
        var notes = new ArrayList<String>();
        notes.add(scope.items().length + " held-out items of " + scope.lemmas().size() + " scored lemmas, "
                + new HashSet<>(scope.families()).size() + " families, " + scope.senses().size() + " scored senses");
        notes.add(lowFrequency(scope));
        var strata = strata(dataset, direction, scope, pipeline);
        var arms = new ArrayList<ArmResult>();
        for (int a = 0; a < outputs.length; a++) {
            SenseArm arm = pipeline.arms().get(a);
            Outputs o = outputs[a];
            Selection selected = pipeline.selections().get(direction)[a];
            var values = new LinkedHashMap<String, OptionalDouble>();
            values.put("forcedAccuracy", metrics.forcedAccuracy(scope.labels(), o, all));
            values.put("coverage", metrics.coverage(scope.labels(), o, all));
            values.put("selectiveAccuracy", metrics.selectiveAccuracy(scope.labels(), o, all));
            int una = 1 << SenseMetrics.UNA;
            int ooi = 1 << SenseMetrics.OOI;
            values.put("abstentionPrecision", metrics.abstentionPrecision(scope.labels(), o, all, una | ooi));
            values.put("abstentionRecall", metrics.abstentionRecall(scope.labels(), o, all, una | ooi));
            values.put("abstentionPrecisionUNA", metrics.abstentionPrecision(scope.labels(), o, all, una));
            values.put("abstentionRecallUNA", metrics.abstentionRecall(scope.labels(), o, all, una));
            values.put("abstentionPrecisionOOI", metrics.abstentionPrecision(scope.labels(), o, all, ooi));
            values.put("abstentionRecallOOI", metrics.abstentionRecall(scope.labels(), o, all, ooi));
            values.put("ece", metrics.ece(scope.labels(), o, all));
            values.put("brier", metrics.brier(scope.labels(), o, all));
            values.put("memorizationProbe", probeAccuracy(dataset, direction, arm, selected));
            double gap = difference(values.get("memorizationProbe"), values.get("forcedAccuracy"));
            values.put("generalizationGap", Double.isNaN(gap) ? OptionalDouble.empty() : OptionalDouble.of(gap));
            var perSense = new LinkedHashMap<String, OptionalDouble>();
            for (Sense sense : scope.senses()) {
                perSense.put(scope.lemmas().get(sense.lemma()) + "#" + sense.cluster(),
                        metrics.macro(scope.labels(), o, all, List.of(sense)));
            }
            Estimate macro = estimate(bootstrap, items -> value(metrics.macro(scope.labels(), o, items,
                    scope.senses())), all);
            Estimate aurc = estimate(bootstrap, items -> value(metrics.aurc(scope.labels(), o, items)), all);
            Estimate auroc = estimate(bootstrap, items -> value(metrics.auroc(scope.labels(), o, items)), all);
            Cost cost = cost(dataset, direction, pipeline, a, resources);
            String description = arm.describe(selected.configuration()) + ", tau=" + selected.tau()
                    + (selected.reachedCoverage() ? "" : " (coverage 0.70 not reached)");
            arms.add(new ArmResult(arm.name(), selected, description, macro, aurc, auroc, values, perSense,
                    strata.get(a), cost));
            fingerprint.append(direction).append(arm.name()).append(selected);
            for (int i = 0; i < o.choice().length; i++) {
                fingerprint.append(o.choice()[i]).append(Double.toHexString(o.confidence()[i]))
                        .append(o.answered()[i] ? 'a' : 'x');
            }
            fingerprint.append(macro).append(aurc).append(auroc);
        }

        Outputs n = outputs[3];
        var h1 = new LinkedHashMap<String, Estimate>();
        var h2a = new LinkedHashMap<String, Estimate>();
        boolean h1Pass = true;
        boolean h2aPass = true;
        for (int x = 0; x < 3; x++) {
            Outputs control = outputs[x];
            String name = pipeline.arms().get(x).name();
            Estimate macroDifference = estimate(bootstrap, items -> difference(
                    metrics.macro(scope.labels(), n, items, scope.senses()),
                    metrics.macro(scope.labels(), control, items, scope.senses())), all);
            Estimate aurcDifference = estimate(bootstrap, items -> difference(
                    metrics.aurc(scope.labels(), control, items), metrics.aurc(scope.labels(), n, items)), all);
            h1.put(name, macroDifference);
            h2a.put(name, aurcDifference);
            h1Pass &= macroDifference.interval().lower() > H1_MARGIN;
            h2aPass &= aurcDifference.interval().lower() > H2A_MARGIN;
            fingerprint.append(macroDifference).append(aurcDifference);
        }
        boolean h2bPass = arms.get(3).auroc().interval().lower() > H2B_BOUND;
        OptionalDouble macroConstant = metrics.macroConstant(scope.labels(), all, scope.senses(), scope.clusters());
        boolean budget = arms.get(3).cost().withinBudget();
        String status = dataset.labelStatus();
        String verdict;
        String reason;
        if (!dataset.scored().abstentionTestable().contains(direction)) {
            verdict = "NOT TESTABLE (" + status + ")";
            reason = "H2 is not testable: fewer than " + SenseGates.MIN_ABSTENTION_ITEMS
                    + " held-out UNA/OOI families of scored lemmas";
        } else if (h1Pass && h2aPass && h2bPass && budget) {
            verdict = "EXPLORATORY GO (" + status + ")";
            reason = "H1, H2a and H2b pass and N is within budget";
        } else {
            verdict = "NO-GO (" + status + ")";
            reason = "H1 " + (h1Pass ? "pass" : "fail") + ", H2a " + (h2aPass ? "pass" : "fail") + ", H2b "
                    + (h2bPass ? "pass" : "fail") + ", N budget " + (budget ? "met" : "exceeded");
        }
        return new DirectionResult(direction, verdict, reason, arms, h1, h2a, macroConstant, notes);
    }

    /** Summaries of every registered stratum, per arm (index aligned with the pipeline's arms). */
    private List<Map<String, String>> strata(SenseDataset dataset, Direction direction, Scope scope,
                                             Pipeline pipeline) {
        var groups = new LinkedHashMap<String, List<Integer>>();
        var majority = (MajoritySenseArm) pipeline.arms().getFirst();
        var vocabulary = new HashSet<String>();
        var windows = new SenseWindows(new SenseTokenizer());
        for (DatasetItem item : dataset.training()) {
            if (item.input().direction() == direction) {
                vocabulary.addAll(Arrays.asList(windows.window(item.input(), SenseTokenizer.SENTENCE).tokens()));
            }
        }
        for (int s = 0; s < scope.items().length; s++) {
            DatasetItem item = dataset.heldOut().get(scope.items()[s]);
            byte kind = scope.labels().kind()[s];
            if (kind == SenseMetrics.ANS) {
                add(groups, "new context", s);
                int choice = majority.majorityCluster(item.input().lemmaKey());
                if (choice >= 0 && (scope.labels().mask()[s] & (1 << choice)) == 0) {
                    add(groups, "against-majority", s);
                }
            } else {
                add(groups, kind == SenseMetrics.UNA ? "UNA" : "OOI", s);
            }
            if (item.translationDivergent()) {
                add(groups, "translation-divergent", s);
            }
            add(groups, "source " + item.source(), s);
            if (item.originalSentence() != null) {
                add(groups, item.originalSentence() ? "original source sentence" : "translated source sentence", s);
            }
            String[] tokens = windows.window(item.input(), SenseTokenizer.SENTENCE).tokens();
            long unknown = Arrays.stream(tokens).filter(token -> !vocabulary.contains(token)).count();
            double rate = tokens.length == 0 ? 0.0 : (double) unknown / tokens.length;
            add(groups, rate == 0.0 ? "context OOV 0" : rate <= 0.3 ? "context OOV (0, 0.3]" : "context OOV > 0.3", s);
        }
        var result = new ArrayList<Map<String, String>>();
        for (int a = 0; a < pipeline.arms().size(); a++) {
            Outputs o = restrict(pipeline.heldOut().get(direction)[a], scope.items());
            var summaries = new LinkedHashMap<String, String>();
            for (var group : groups.entrySet()) {
                int[] items = group.getValue().stream().mapToInt(Integer::intValue).toArray();
                summaries.put(group.getKey(), "n=" + items.length + ", forced "
                        + format(metrics.forcedAccuracy(scope.labels(), o, items)) + ", coverage "
                        + format(metrics.coverage(scope.labels(), o, items)) + ", abstention recall "
                        + format(metrics.abstentionRecall(scope.labels(), o, items,
                        (1 << SenseMetrics.UNA) | (1 << SenseMetrics.OOI))));
            }
            result.add(summaries);
        }
        return result;
    }

    /** Memorization probe: up to 30 {@code ANS} training items per scored lemma, in training-file order. */
    private OptionalDouble probeAccuracy(SenseDataset dataset, Direction direction, SenseArm arm, Selection point) {
        var taken = new HashMap<String, Integer>();
        int n = 0;
        int correct = 0;
        for (int i = 0; i < dataset.training().size(); i++) {
            SenseItem input = dataset.training().get(i).input();
            if (input.direction() != direction || !dataset.scored().scoredLemma(input.lemmaKey())
                    || !(dataset.trainingGold().get(i) instanceof SenseGold.Answerable gold)
                    || taken.merge(input.lemmaKey(), 1, Integer::sum) > PROBE_SIZE) {
                continue;
            }
            n++;
            if (gold.contains(arm.predict(input, point.configuration()).choice())) {
                correct++;
            }
        }
        return n == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) correct / n);
    }

    private String lowFrequency(Scope scope) {
        var lemmaUnits = new long[scope.clusters().length];
        var senseUnits = new HashMap<Sense, Long>();
        for (int s = 0; s < scope.items().length; s++) {
            if (scope.labels().kind()[s] != SenseMetrics.ANS) {
                continue;
            }
            int lemma = scope.labels().lemma()[s];
            int mask = scope.labels().mask()[s];
            long perItem = LemmaTraining.unitsPerItem(scope.clusters()[lemma]);
            lemmaUnits[lemma] += perItem;
            for (int k = 0; k < scope.clusters()[lemma]; k++) {
                if ((mask & (1 << k)) != 0) {
                    senseUnits.merge(new Sense(lemma, k), perItem / Integer.bitCount(mask), Long::sum);
                }
            }
        }
        var gates = new SenseGates();
        var low = new ArrayList<String>();
        for (Sense sense : scope.senses()) {
            if (gates.lowFrequency(senseUnits.getOrDefault(sense, 0L), lemmaUnits[sense.lemma()])) {
                low.add(scope.lemmas().get(sense.lemma()) + "#" + sense.cluster());
            }
        }
        return low.isEmpty() ? "low-frequency senses were not testable in this direction (no scored sense below 20%)"
                : "scored low-frequency senses: " + String.join(", ", low);
    }

    private Cost cost(SenseDataset dataset, Direction direction, Pipeline pipeline, int a,
                      List<BenchmarkRunResult> resources) {
        SenseArm arm = pipeline.arms().get(a);
        Selection point = pipeline.selections().get(direction)[a];
        List<SenseItem> items = dataset.heldOut().stream().map(DatasetItem::input)
                .filter(input -> input.direction() == direction).toList();
        long median = -1;
        long p95 = -1;
        double bytesPerItem = Double.NaN;
        if (measureCost && !items.isEmpty()) {
            for (int w = 0; w < WARMUP; w++) {
                predict(arm, point, items);
            }
            var samples = new long[MEASURED * items.size()];
            int s = 0;
            for (int m = 0; m < MEASURED; m++) {
                for (SenseItem item : items) {
                    // Without the cached tokenization, the measured time includes tokenizing the item.
                    pipeline.windows().clear();
                    long start = System.nanoTime();
                    arm.predict(item, point.configuration());
                    samples[s++] = System.nanoTime() - start;
                }
            }
            Arrays.sort(samples);
            median = samples[samples.length / 2];
            p95 = samples[(int) Math.min(samples.length - 1, Math.ceil(0.95 * samples.length) - 1)];
            BenchmarkRunResult result = new EvaluationMetricsCollector().measure(
                    "bilingual-sense." + direction.label() + "." + arm.name(), items.size() + " held-out items",
                    WARMUP, MEASURED, items.size(), pipeline.windows()::clear, () -> predict(arm, point, items),
                    Map.of("retainedBytes", Long.toString(arm.retainedBytes())));
            resources.add(result);
            bytesPerItem = result.allocation().bytesPerOp();
        }
        return new Cost(pipeline.fitNanos()[a], pipeline.selectNanos().get(direction)[a], median, p95,
                bytesPerItem, arm.retainedBytes());
    }

    private Check inputSurface() {
        var names = new ArrayList<String>();
        for (RecordComponent component : SenseItem.class.getRecordComponents()) {
            names.add(component.getName());
        }
        boolean passed = SenseItem.ALLOWED_COMPONENTS.containsAll(names);
        return new Check("leakage.input-surface", passed, "SenseItem components " + names
                + (passed ? " carry no translation, alignment, pass-B, family or split field" : " exceed the allowed set"));
    }

    private Check familyDisjoint(SenseDataset dataset) {
        var seen = new HashMap<String, String>();
        var violations = new ArrayList<String>();
        for (List<DatasetItem> split : List.of(dataset.training(), dataset.validation(), dataset.heldOut())) {
            for (DatasetItem item : split) {
                String place = item.split() + "/" + item.input().direction();
                String previous = seen.putIfAbsent(item.familyId(), place);
                if (previous != null && !previous.equals(place)) {
                    violations.add(item.familyId() + " in " + previous + " and " + place);
                }
                if (dataset.calibrationFamilies().contains(item.familyId())
                        || dataset.pilotFamilies().contains(item.familyId())) {
                    violations.add(item.familyId() + " is a calibration or pilot family in " + place);
                }
            }
        }
        return new Check("leakage.family-disjoint", violations.isEmpty(), violations.isEmpty()
                ? seen.size() + " families, each in one split and one direction; no calibration or pilot family reused"
                : violations.size() + " violations, first: " + violations.getFirst());
    }

    private Check nearDuplicate(SenseDataset dataset) {
        var tokenizer = new SenseTokenizer();
        var sequences = new ArrayList<List<String>>();
        var splits = new ArrayList<String>();
        for (List<DatasetItem> split : List.of(dataset.training(), dataset.validation(), dataset.heldOut())) {
            for (DatasetItem item : split) {
                var tokens = tokenizer.tokenize(item.input().sentence());
                sequences.add(tokenizer.masked(tokens, tokenizer.tokenAt(tokens, item.input().start(),
                        item.input().end())));
                splits.add(item.split().name());
            }
        }
        int[] components = new NearDuplicateMerger().components(sequences);
        var splitOf = new HashMap<Integer, String>();
        int crossing = 0;
        for (int i = 0; i < components.length; i++) {
            String previous = splitOf.putIfAbsent(components[i], splits.get(i));
            if (previous != null && !previous.equals(splits.get(i))) {
                crossing++;
            }
        }
        return new Check("leakage.near-duplicate", crossing == 0, crossing == 0
                ? "no near-duplicate pair crosses splits over " + sequences.size() + " occurrences"
                : crossing + " occurrences are near-duplicates of an item in another split");
    }

    private Check inventoryHash(SenseDataset dataset) {
        SenseDataset.CommittedHashes hashes = dataset.hashes();
        String inventory = SenseDataset.sha256(hashes.inventoryBytes());
        String guideline = SenseDataset.sha256(hashes.guidelineBytes());
        boolean passed = inventory.equals(hashes.inventorySha256()) && guideline.equals(hashes.guidelineSha256());
        return new Check("leakage.inventory-hash", passed, "inventory " + inventory + ", guidelines " + guideline
                + (passed ? " equal the committed hashes" : " differ from the committed hashes"));
    }

    private Check heldOutCounterfactual(Pipeline pipeline, Pipeline counterfactual) {
        for (Direction direction : pipeline.heldOut().keySet()) {
            Outputs[] a = pipeline.heldOut().get(direction);
            Outputs[] b = counterfactual.heldOut().get(direction);
            for (int arm = 0; arm < a.length; arm++) {
                if (!Arrays.equals(a[arm].choice(), b[arm].choice())
                        || !Arrays.equals(a[arm].confidence(), b[arm].confidence())
                        || !Arrays.equals(a[arm].answered(), b[arm].answered())
                        || !pipeline.selections().get(direction)[arm].equals(
                        counterfactual.selections().get(direction)[arm])) {
                    return new Check("nc.heldout-counterfactual", false, direction.label() + " arm "
                            + pipeline.arms().get(arm).name() + " changed when held-out labels were permuted");
                }
            }
        }
        return new Check("nc.heldout-counterfactual", true,
                "with held-out labels permuted within each lemma, every arm's k*, s and answer are bit-identical");
    }

    private Check unseenLemma(SenseDataset dataset, Pipeline pipeline) {
        int count = 0;
        for (int i = 0; i < dataset.heldOut().size(); i++) {
            SenseItem input = dataset.heldOut().get(i).input();
            if (input.hasInventory() || !pipeline.heldOut().containsKey(input.direction())) {
                continue;
            }
            count++;
            for (int a = 0; a < pipeline.arms().size(); a++) {
                if (pipeline.heldOut().get(input.direction())[a].answered()[i]) {
                    return new Check("unseen-lemma.abstain", false, "arm " + pipeline.arms().get(a).name()
                            + " answered unseen-lemma item " + dataset.heldOut().get(i).occurrenceId());
                }
            }
        }
        return new Check("unseen-lemma.abstain", count > 0, count > 0
                ? "every arm abstains on all " + count + " unseen-lemma items" : "no unseen-lemma item exists");
    }

    private Check contextMask(SenseDataset dataset, NeuronSenseArm neuron) {
        int checked = 0;
        for (List<DatasetItem> split : List.of(dataset.validation(), dataset.heldOut())) {
            for (DatasetItem item : split) {
                if (!item.input().hasInventory()
                        || !dataset.scored().testableDirections().contains(item.input().direction())) {
                    continue;
                }
                for (int c = 0; c < neuron.configurations(); c++) {
                    Scored scored = neuron.scoreMasked(item.input(), c);
                    boolean zero = Arrays.stream(scored.scores()).allMatch(score -> score == 0.0);
                    Prediction prediction = new Prediction(scored.choice(), scored.scores()[scored.choice()]);
                    boolean abstains = true;
                    for (double tau : neuron.thresholds()) {
                        abstains &= tau == 0.0 || !prediction.answers(tau);
                    }
                    if (!zero || scored.choice() != 0 || !abstains) {
                        return new Check("nc.context-mask", false, "masked item " + item.occurrenceId()
                                + " scored " + Arrays.toString(scored.scores()));
                    }
                    checked++;
                }
            }
        }
        return new Check("nc.context-mask", true, checked + " masked (item, configuration) pairs: every cluster "
                + "scores exactly 0, k* is the lowest ordinal and N abstains whenever tau > 0");
    }

    private Check policyEquivalence(SenseDataset dataset, NeuronSenseArm neuron) {
        var oracle = new NeuronPolicyOracle();
        int checked = 0;
        for (DatasetItem item : dataset.validation()) {
            if (!item.input().hasInventory()
                    || !dataset.scored().testableDirections().contains(item.input().direction())) {
                continue;
            }
            for (int c = 0; c < neuron.configurations(); c++) {
                String mismatch = oracle.mismatch(neuron.score(item.input(), c));
                if (mismatch != null) {
                    return new Check("n.policy-equivalence", false, item.occurrenceId() + " configuration "
                            + neuron.describe(c) + ": " + mismatch);
                }
                checked++;
            }
        }
        return new Check("n.policy-equivalence", checked > 0, checked > 0 ? checked
                + " (validation item, configuration) pairs: N's scores and ranking equal "
                + "ReferenceHypothesisEvaluationPolicy's bit for bit" : "no validation item to check");
    }

    private Check budget(List<DirectionResult> directions) {
        var over = new ArrayList<String>();
        for (DirectionResult direction : directions) {
            for (ArmResult arm : direction.arms()) {
                if (!arm.cost().withinBudget()) {
                    over.add(direction.direction().label() + " " + arm.arm()
                            + (arm.cost().measured() ? "" : " (inference not measured)"));
                }
            }
        }
        long heap = Runtime.getRuntime().maxMemory();
        boolean heapOk = heap <= HEAP_BUDGET_BYTES;
        boolean passed = over.isEmpty() && heapOk;
        return new Check("budget", passed, (over.isEmpty() ? "every arm within its fit, inference and state budget"
                : "over budget or unverified: " + over) + "; max heap " + (heap >> 20) + " MiB"
                + (heapOk ? " (limit 2048)" : " exceeds the 2 GiB limit"));
    }

    /** Training labels permuted within each lemma: the {@code ANS} gold sets shuffled among the lemma's ANS items. */
    private List<SenseGold> permutedTraining(SenseDataset dataset) {
        var members = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < dataset.training().size(); i++) {
            if (dataset.trainingGold().get(i).answerable()) {
                members.computeIfAbsent(dataset.training().get(i).input().lemmaKey(), _ -> new ArrayList<>()).add(i);
            }
        }
        var permuted = new ArrayList<>(dataset.trainingGold());
        for (var entry : members.entrySet()) {
            int[] order = hash.permutation("training|" + entry.getKey(), entry.getValue().size());
            for (int r = 0; r < order.length; r++) {
                permuted.set(entry.getValue().get(r), dataset.trainingGold().get(entry.getValue().get(order[r])));
            }
        }
        return permuted;
    }

    private List<TrainingExample> trainingExamples(List<DatasetItem> items, List<SenseGold> gold,
                                                   Set<Direction> directions) {
        var examples = new ArrayList<TrainingExample>();
        for (int i = 0; i < items.size(); i++) {
            if (gold.get(i) instanceof SenseGold.Answerable answerable && items.get(i).input().hasInventory()
                    && directions.contains(items.get(i).input().direction())) {
                examples.add(new TrainingExample(items.get(i).input(), answerable));
            }
        }
        return examples;
    }

    /** Builds labels; {@code lemmaIndex} assigns lemma indices in first-seen order. */
    private Labels labels(List<SenseItem> items, List<SenseGold> gold, Map<String, Integer> lemmaIndex) {
        var lemma = new int[items.size()];
        var kind = new byte[items.size()];
        var mask = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            lemma[i] = lemmaIndex.computeIfAbsent(items.get(i).lemmaKey(), _ -> lemmaIndex.size());
            switch (gold.get(i)) {
                case SenseGold.Answerable answerable -> {
                    kind[i] = SenseMetrics.ANS;
                    mask[i] = answerable.mask();
                }
                case SenseGold.Abstain abstain -> kind[i] = abstain.outOfInventory() ? SenseMetrics.OOI : SenseMetrics.UNA;
            }
        }
        return new Labels(lemma, kind, mask);
    }

    private Outputs restrict(Outputs outputs, int[] items) {
        var choice = new int[items.length];
        var confidence = new double[items.length];
        var answered = new boolean[items.length];
        for (int s = 0; s < items.length; s++) {
            choice[s] = outputs.choice()[items[s]];
            confidence[s] = outputs.confidence()[items[s]];
            answered[s] = outputs.answered()[items[s]];
        }
        return new Outputs(choice, confidence, answered);
    }

    /** Returns the statistic on every held-out item and its paired 95% bootstrap interval. */
    private Estimate estimate(ClusterBootstrap bootstrap, ToDoubleFunction<int[]> statistic, int[] all) {
        double point = statistic.applyAsDouble(all);
        return new Estimate(Double.isNaN(point) ? OptionalDouble.empty() : OptionalDouble.of(point),
                bootstrap.percentile95(bootstrap.values(statistic)));
    }

    private double value(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : Double.NaN;
    }

    private double difference(OptionalDouble a, OptionalDouble b) {
        return a.isPresent() && b.isPresent() ? a.getAsDouble() - b.getAsDouble() : Double.NaN;
    }

    private void add(Map<String, List<Integer>> groups, String name, int item) {
        groups.computeIfAbsent(name, _ -> new ArrayList<>()).add(item);
    }

    /** Formats a value with three decimals, {@code n/a} when undefined. */
    static String format(OptionalDouble value) {
        return value.isPresent() ? format(value.getAsDouble()) : "n/a";
    }

    /** Formats a value with three decimals, {@code n/a} for {@code NaN}. */
    static String format(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.3f", value);
    }
}
