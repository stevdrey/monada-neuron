package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseDataset.CommittedHashes;
import monada.neuron.evaluation.bilingual.SenseDataset.DatasetItem;
import monada.neuron.evaluation.bilingual.SenseDataset.ScoredSet;
import monada.neuron.evaluation.bilingual.SensePartitions.Split;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * A deterministic synthetic workload for the harness self-test: generated token sentences with a planted
 * context-to-cluster relation, {@code UNA} items with no cue, {@code OOI} items with foreign cues and unseen-lemma items.
 *
 * <p>It is <b>not</b> data of the protocol. No corpus is read and no sentence is real; its gold is generated, not
 * annotated. Results on it say only whether the harness computes what the protocol registers; they are never evidence
 * for any hypothesis, and the report says so.
 */
final class SyntheticSenseWorkload {

    /** Default generator seed. */
    static final long SEED = 0x1850_5E1FL;

    /** Provenance of every synthetic dataset. */
    static final String PROVENANCE = "synthetic self-test (generated sentences and gold; not protocol data)";

    private record LemmaSpec(Direction direction, String lemma, List<String> forms, List<List<String>> clusters,
                             double[] shares) {
    }

    private static final List<LemmaSpec> LEMMAS = List.of(
            new LemmaSpec(Direction.EN_ES, "bank", List.of("bank", "banks"),
                    List.of(List.of("banco"), List.of("orilla", "ribera")), new double[] {0.6, 0.4}),
            new LemmaSpec(Direction.EN_ES, "light", List.of("light", "lights"),
                    List.of(List.of("luz"), List.of("ligero", "liviano"), List.of("claro")),
                    new double[] {0.5, 0.35, 0.15}),
            new LemmaSpec(Direction.EN_ES, "spring", List.of("spring", "springs"),
                    List.of(List.of("primavera"), List.of("muelle", "resorte"), List.of("manantial", "fuente")),
                    new double[] {0.45, 0.3, 0.25}),
            new LemmaSpec(Direction.EN_ES, "letter", List.of("letter", "letters"),
                    List.of(List.of("carta"), List.of("letra")), new double[] {0.55, 0.45}),
            new LemmaSpec(Direction.EN_ES, "date", List.of("date", "dates"),
                    List.of(List.of("fecha"), List.of("cita"), List.of("dátil")), new double[] {0.5, 0.35, 0.15}),
            new LemmaSpec(Direction.ES_EN, "banco", List.of("banco", "bancos"),
                    List.of(List.of("bank"), List.of("bench"), List.of("school", "shoal")),
                    new double[] {0.5, 0.35, 0.15}),
            new LemmaSpec(Direction.ES_EN, "tiempo", List.of("tiempo", "tiempos"),
                    List.of(List.of("time"), List.of("weather"), List.of("tense")), new double[] {0.45, 0.4, 0.15}),
            new LemmaSpec(Direction.ES_EN, "papel", List.of("papel", "papeles"),
                    List.of(List.of("paper"), List.of("role", "part")), new double[] {0.55, 0.45}),
            new LemmaSpec(Direction.ES_EN, "llave", List.of("llave", "llaves"),
                    List.of(List.of("key"), List.of("tap", "faucet"), List.of("wrench", "spanner")),
                    new double[] {0.45, 0.3, 0.25}),
            new LemmaSpec(Direction.ES_EN, "nota", List.of("nota", "notas"),
                    List.of(List.of("note"), List.of("grade", "mark")), new double[] {0.5, 0.5}));

    private static final List<LemmaSpec> UNSEEN = List.of(
            new LemmaSpec(Direction.EN_ES, "pitch", List.of("pitch"), List.of(), new double[0]),
            new LemmaSpec(Direction.ES_EN, "cola", List.of("cola"), List.of(), new double[0]));

    /** Items per lemma: ANS and UNA/OOI items per split. */
    private static final int[][] SIZES = {{90, 10}, {50, 10}, {100, 20}};
    private static final int UNSEEN_ITEMS = 12;
    private static final int CUES_PER_CLUSTER = 4;
    private static final int FILLERS = 40;

    private final long seed;
    private final KeyedHash hash = new KeyedHash();
    private final SensePartitions partitions = new SensePartitions();

    SyntheticSenseWorkload(long seed) {
        this.seed = seed;
    }

    /** Generates a fresh dataset; equal seeds give equal datasets. */
    SenseDataset generate() {
        var random = new SplittableRandom(seed);
        var splitItems = new ArrayList<List<DatasetItem>>();
        var splitGold = new ArrayList<List<SenseGold>>();
        for (int s = 0; s < 3; s++) {
            splitItems.add(new ArrayList<>());
            splitGold.add(new ArrayList<>());
        }
        Split[] splits = {Split.TRAINING, Split.VALIDATION, Split.HELD_OUT};
        var inventoryText = new StringBuilder();
        int sentence = 0;
        for (LemmaSpec spec : LEMMAS) {
            var inventory = new SenseInventory(spec.direction(), spec.lemma(), spec.clusters());
            inventoryText.append(spec.direction().label()).append('\t').append(spec.lemma()).append('\t')
                    .append(spec.clusters()).append('\n');
            for (int s = 0; s < 3; s++) {
                for (int n = 0; n < SIZES[s][0] + SIZES[s][1]; n++) {
                    SenseGold gold;
                    List<String> cues;
                    if (n < SIZES[s][0]) {
                        int k = draw(random, spec.shares());
                        int mask = 1 << k;
                        if (random.nextDouble() < 0.05) {
                            mask |= 1 << ((k + 1) % spec.clusters().size());
                        }
                        gold = SenseGold.answerable(mask);
                        cues = cueWords(spec, k, random);
                    } else if (n % 2 == 0) {
                        gold = SenseGold.UNANSWERABLE;
                        cues = List.of();
                    } else {
                        gold = SenseGold.OUT_OF_INVENTORY;
                        cues = List.of("foreign" + random.nextInt(6), "foreign" + random.nextInt(6));
                    }
                    splitItems.get(s).add(item(spec, inventory, splits[s], cues, random, sentence++));
                    splitGold.get(s).add(gold);
                }
            }
        }
        for (LemmaSpec spec : UNSEEN) {
            for (int n = 0; n < UNSEEN_ITEMS; n++) {
                splitItems.get(2).add(item(spec, null, Split.HELD_OUT, List.of(), random, sentence++));
                splitGold.get(2).add(SenseGold.OUT_OF_INVENTORY);
            }
        }
        orderTraining(splitItems.get(0), splitGold.get(0));

        var heldOutText = new StringBuilder();
        for (int i = 0; i < splitItems.get(2).size(); i++) {
            heldOutText.append(splitItems.get(2).get(i).occurrenceId()).append('\t')
                    .append(SenseDataset.goldText(splitGold.get(2).get(i))).append('\n');
        }
        ScoredSet scored = new SenseGates().scoredSet(splitItems.get(0), splitGold.get(0), splitItems.get(1),
                splitGold.get(1), splitItems.get(2), splitGold.get(2), Set.of(), Set.of());
        byte[] inventoryBytes = inventoryText.toString().getBytes(StandardCharsets.UTF_8);
        byte[] guidelineBytes = "synthetic self-test: no guidelines exist".getBytes(StandardCharsets.UTF_8);
        byte[] logBytes = "synthetic self-test: no agent log exists".getBytes(StandardCharsets.UTF_8);
        var hashes = new CommittedHashes(SenseDataset.sha256(inventoryBytes), inventoryBytes,
                SenseDataset.sha256(guidelineBytes), guidelineBytes, SenseDataset.sha256(heldOutText.toString()),
                SenseDataset.sha256(logBytes), logBytes);
        return new SenseDataset(PROVENANCE, "synthetic, not agent-labeled", splitItems.get(0), splitGold.get(0),
                splitItems.get(1), splitGold.get(1), splitItems.get(2), new GuardedGold(splitGold.get(2), true),
                Set.of(), Set.of(), scored, hashes);
    }

    /**
     * One item: filler tokens, the cue tokens and the declared form at a random position, plus a sentence-unique token
     * in the middle so no two sentences are near-duplicates.
     */
    private DatasetItem item(LemmaSpec spec, SenseInventory inventory, Split split, List<String> cues,
                             SplittableRandom random, int number) {
        var tokens = new ArrayList<String>();
        int length = 7 + random.nextInt(4);
        for (int t = 0; t < length; t++) {
            tokens.add("filler" + random.nextInt(FILLERS));
        }
        for (String cue : cues) {
            tokens.set(random.nextInt(tokens.size()), cue);
        }
        tokens.add(tokens.size() / 2, "unique" + number);
        String form = spec.forms().get(random.nextInt(spec.forms().size()));
        int at = random.nextInt(tokens.size() + 1);
        tokens.add(at, form);
        String text = String.join(" ", tokens) + ".";
        int start = 0;
        for (int t = 0; t < at; t++) {
            start += tokens.get(t).codePointCount(0, tokens.get(t).length()) + 1;
        }
        int end = start + form.codePointCount(0, form.length());
        var input = new SenseItem(spec.direction(), spec.lemma(), inventory, text, start, end);
        String key = "synthetic:" + number;
        String family = partitions.familyId(List.of(key));
        return new DatasetItem(partitions.occurrenceId(spec.direction(), spec.lemma(), key, start, end), family, split,
                input, "synthetic", random.nextBoolean(), false);
    }

    /** Cue tokens of cluster {@code k}: one of its own, then usually another of its own, sometimes a foreign one. */
    private List<String> cueWords(LemmaSpec spec, int k, SplittableRandom random) {
        String first = cue(spec, k, random.nextInt(CUES_PER_CLUSTER));
        String second = random.nextDouble() < 0.2
                ? cue(spec, (k + 1) % spec.clusters().size(), random.nextInt(CUES_PER_CLUSTER))
                : cue(spec, k, random.nextInt(CUES_PER_CLUSTER));
        return List.of(first, second);
    }

    private String cue(LemmaSpec spec, int k, int j) {
        return spec.lemma() + "cue" + k + "x" + j;
    }

    private int draw(SplittableRandom random, double[] shares) {
        double u = random.nextDouble();
        double cumulative = 0.0;
        for (int k = 0; k < shares.length; k++) {
            cumulative += shares[k];
            if (u < cumulative) {
                return k;
            }
        }
        return shares.length - 1;
    }

    /** Training-file order: families by increasing {@code h(split, family)}, then occurrences by {@code h(occurrence)}. */
    private void orderTraining(List<DatasetItem> items, List<SenseGold> gold) {
        var order = new ArrayList<Integer>();
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        Comparator<String> byFamily = hash.order(KeyedHash.SPLIT);
        Comparator<String> byOccurrence = hash.order(KeyedHash.OCCURRENCE);
        order.sort((a, b) -> {
            int family = byFamily.compare(items.get(a).familyId(), items.get(b).familyId());
            return family != 0 ? family : byOccurrence.compare(items.get(a).occurrenceId(), items.get(b).occurrenceId());
        });
        var sortedItems = order.stream().map(items::get).toList();
        var sortedGold = order.stream().map(gold::get).toList();
        items.clear();
        items.addAll(sortedItems);
        gold.clear();
        gold.addAll(sortedGold);
    }
}
