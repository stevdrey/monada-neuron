package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.encoder.NoOpTextNormalizer;
import com.monada.storage.Manifest;
import com.monada.storage.VectorFormatProfile;
import com.monada.storage.audit.StorageIntegrityAuditor;
import com.monada.storage.audit.StorageIntegrityCategory;
import com.monada.storage.audit.StorageIntegrityReport;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Row;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Snapshot;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.resonance.adapter.HashedSignalDecoder;
import monada.neuron.resonance.adapter.ResonanceStoreAdapterConfig;
import monada.neuron.resonance.adapter.ResonanceStoreAdapterException;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persistence, reopen and source-level readback of the {@code html-kc-v1} corpus through the Store and through
 * Neuron's {@code ResonanceStoreMemoryAdapter} (Issue #106).
 *
 * <p>{@link #observe} is what one process sees on one root: the Store's own integrity audit, the persisted profile,
 * a readback of every chunk through a fresh {@code MonadaMemory}, and the bounded probes through that handle
 * ({@code direct}) and through a separately opened adapter ({@code neuron}) using {@link ChunkTextProbeCodec}.
 * Every recalled result is attributed to a chunk twice, by the adapter's opaque reference through the
 * {@link IngestionManifest} and by the identity-preserving decoded Signal, and the two must agree.
 *
 * <p>Persistence evidence comes from comparing the {@code seed} process's snapshot with the snapshot of a
 * <em>separate</em> {@code reopen} process (see {@link HtmlKnowledgePersistenceRunner}). The Store has no
 * {@code close}, so a reopen inside one JVM is reported only as {@code persistence.freshHandleConsistency}.
 *
 * <p>{@link #audit} records the actual behaviour of failure cases on copies of the root. A case passes only when the
 * failure is visible (an exception, a non-{@code COMPLETE} status or a failed readback naming the chunk), never when
 * a result is silently synthesized.
 */
public final class HtmlKnowledgePersistence {

    /** Result limit of every probe on both paths. */
    public static final int TOP_K = 5;

    /** Score threshold of every probe on both paths. */
    public static final double THRESHOLD = 0.0;

    /** Snapshot path name of direct {@code MonadaMemory.resonate} results. */
    public static final String DIRECT = "direct";

    /** Snapshot path name of Neuron adapter {@code recall} results. */
    public static final String NEURON = "neuron";

    /** Chunk id recorded for a result that maps to no manifest entry. */
    static final String UNMAPPED = "<unmapped>";

    private static final String MANIFEST_FILE = "manifest.json";

    private final HtmlKnowledgeCorpus corpus;
    private final List<PersistenceProbes.Probe> probes;
    private final ChunkTextProbeCodec codec;
    private final HtmlKnowledgeIngestion ingestion;
    private final StorageIntegrityAuditor auditor = new StorageIntegrityAuditor();

    /** Creates the experiment for {@code corpus}. */
    public HtmlKnowledgePersistence(HtmlKnowledgeCorpus corpus) {
        this.corpus = Objects.requireNonNull(corpus, "corpus must not be null");
        this.probes = PersistenceProbes.of(corpus);
        this.codec = new ChunkTextProbeCodec(corpus, probes);
        this.ingestion = new HtmlKnowledgeIngestion(corpus);
    }

    /**
     * What one process observed on one root.
     *
     * @param snapshot persisted profile and every probe result on both paths
     * @param checks semantic verdicts, in a fixed order
     * @param metadata exploratory timings and counts; never part of a verdict
     */
    public record Observation(Snapshot snapshot, List<Check> checks, Map<String, String> metadata) {

        public Observation {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            checks = List.copyOf(checks);
            metadata = Map.copyOf(metadata);
        }
    }

    /**
     * Outcome of the {@code seed} phase: the import and the observation of the freshly written root.
     *
     * @param manifest chunk-to-atom links that {@code remember} returned
     * @param observation the in-process observation after the import
     * @param checks the import's checks followed by the observation's
     */
    public record Seeded(IngestionManifest manifest, Observation observation, List<Check> checks) {

        public Seeded {
            Objects.requireNonNull(manifest, "manifest must not be null");
            Objects.requireNonNull(observation, "observation must not be null");
            checks = List.copyOf(checks);
        }
    }

    /** Imports the corpus into the fresh {@code root} and observes it in this process. */
    public Seeded seed(Path root) {
        var imported = ingestion.ingest(root);
        var observation = observe(root, imported.manifest());
        var checks = new ArrayList<Check>(imported.checks());
        checks.addAll(observation.checks());
        return new Seeded(imported.manifest(), observation, checks);
    }

    /**
     * Observes an existing {@code root} with new handles and compares it with the {@code seed} process's snapshot,
     * then observes it once more with a second set of handles in this process (fresh-handle consistency).
     *
     * @throws IngestionInputException when {@code root} holds no Store manifest
     */
    public Observation reopen(Path root, IngestionManifest manifest, Snapshot seeded) {
        Objects.requireNonNull(seeded, "seeded must not be null");
        var observed = observe(root, manifest);
        var checks = new ArrayList<Check>(observed.checks());
        var snapshot = observed.snapshot();
        checks.add(new Check("persistence.restartProfileEqual", snapshot.profile().equals(seeded.profile()),
                snapshot.profile().equals(seeded.profile())
                        ? "the persisted encoding and vector-format profile read in this process equals the seed's"
                        : "profile changed: seed [" + seeded.profile() + "], now [" + snapshot.profile() + "]"));
        checks.add(equalRows("persistence.restartRecallEqual", seeded.rows(DIRECT), snapshot.rows(DIRECT),
                "direct MonadaMemory results of every probe equal the seed process's: chunk ids through the manifest,"
                        + " order and bit-exact scores"));
        checks.add(equalRows("neuron.restartEqual", seeded.rows(NEURON), snapshot.rows(NEURON),
                "Neuron adapter results of every probe equal the seed process's: references through the manifest,"
                        + " order and bit-exact scores"));
        var fresh = observe(root, manifest);
        var consistent = fresh.snapshot().equals(snapshot) && fresh.checks().stream().allMatch(Check::passed);
        checks.add(new Check("persistence.freshHandleConsistency", consistent, (consistent
                ? "a second MonadaMemory and adapter opened in this same process observed the identical snapshot"
                : "a second set of handles in this process observed a different snapshot or failed a check")
                + "; same-process handles only, not persistence evidence"));
        return new Observation(snapshot, checks, observed.metadata());
    }

    /**
     * Observes {@code root} through a new {@code MonadaMemory} and a separately opened adapter.
     *
     * @throws IngestionInputException when {@code root} holds no Store manifest
     */
    public Observation observe(Path root, IngestionManifest manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        requireExistingStore(root);
        var checks = new ArrayList<Check>();
        var metadata = new LinkedHashMap<String, String>();
        var count = corpus.chunks().size();

        checks.add(new Check("persistence.manifestPresent", true,
                root.getFileName() + "/" + MANIFEST_FILE + " present before any open"));

        var audit = auditor.audit(root);
        var stats = audit.statistics();
        var counted = audit.isHealthy() && stats.activeUniqueAtoms() == count && stats.activeAtomsWithVector() == count
                && stats.activeAtomsWithoutVector() == 0 && stats.vectorEntriesWithoutActiveAtom() == 0
                && stats.duplicateVectorIndexIds() == 0;
        checks.add(new Check("persistence.atomCount", counted, "Store integrity audit: healthy=" + audit.isHealthy()
                + ", activeUniqueAtoms=" + stats.activeUniqueAtoms() + ", activeAtomsWithVector="
                + stats.activeAtomsWithVector() + ", atomsWithoutVector=" + stats.activeAtomsWithoutVector()
                + ", vectorsWithoutAtom=" + stats.vectorEntriesWithoutActiveAtom() + ", duplicateVectorIds="
                + stats.duplicateVectorIndexIds() + "; expected " + count + " atoms" + findings(audit)));

        var persisted = audit.manifest();
        var profile = profile(persisted);
        var expected = persisted == null ? null : MonadaMemory.getExpectedProfile(persisted.dimensions(),
                MonadaMemoryOptions.defaults());
        var profileMatches = persisted != null && Objects.equals(persisted.encodingProfile(), expected)
                && Objects.equals(persisted.vectorFormatProfile(),
                        VectorFormatProfile.currentFixedRaw(persisted.dimensions()));
        checks.add(new Check("persistence.encodingProfile", profileMatches, profileMatches
                ? "persisted encoding profile equals MonadaMemory.getExpectedProfile(" + persisted.dimensions()
                        + ", defaults()) and the vector format is the current FIXED_RAW"
                : "persisted [" + profile + "] differs from the expected default profile [" + expected + "]"));

        var openStart = System.nanoTime();
        var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
        metadata.put("perf.directOpenNanos", Long.toString(System.nanoTime() - openStart));
        var unread = ingestion.readback(memory, manifest);
        checks.add(new Check("persistence.readback", unread.isEmpty(), unread.isEmpty()
                ? "all " + count + " chunks recalled their manifest atom with content sha256 equal to the corpus,"
                        + " via resonate(text).topK(" + count + ").threshold(" + HtmlKnowledgeIngestion.READBACK_THRESHOLD
                        + ")"
                : "readback failed (atom missing or content changed) for " + unread));

        var seen = new HashSet<String>();
        var unmapped = new HashSet<String>();
        for (var chunk : corpus.chunks()) {
            for (var result : memory.resonate(chunk.text()).topK(count)
                    .threshold(HtmlKnowledgeIngestion.READBACK_THRESHOLD).execute().results()) {
                seen.add(result.atom().id());
                if (manifest.chunkIdForAtom(result.atom().id()).isEmpty()) {
                    unmapped.add(result.atom().id());
                }
            }
        }
        checks.add(new Check("persistence.noUnmappedAtoms", unmapped.isEmpty(), unmapped.isEmpty()
                ? seen.size() + " distinct atoms recalled across the readback sweep, all in the manifest"
                : unmapped.size() + " recalled atoms are not in the manifest: " + unmapped.stream().sorted().toList()));

        var rows = new ArrayList<Row>();
        var referenceFailures = new ArrayList<String>();
        var signalFailures = new ArrayList<String>();
        var statusFailures = new ArrayList<String>();
        var adapterOpenStart = System.nanoTime();
        try (var adapter = ResonanceStoreMemoryAdapter.open(root, config())) {
            metadata.put("perf.adapterOpenNanos", Long.toString(System.nanoTime() - adapterOpenStart));
            var warm = new long[probes.size()];
            for (var index = 0; index < probes.size(); index++) {
                var probe = probes.get(index);
                var direct = memory.resonate(probe.query()).topK(TOP_K).threshold(THRESHOLD).execute().results();
                for (var rank = 0; rank < direct.size(); rank++) {
                    var result = direct.get(rank);
                    rows.add(new Row(probe.probeId(), DIRECT, rank,
                            manifest.chunkIdForAtom(result.atom().id()).orElse(UNMAPPED), result.score()));
                }
                var recallStart = System.nanoTime();
                var response = adapter.recall(new ResonanceMemoryRequest(
                        List.of(codec.querySignal(probe.probeId())), TOP_K));
                warm[index] = System.nanoTime() - recallStart;
                if (response.status() != ResonanceMemoryStatus.COMPLETE) {
                    statusFailures.add(probe.probeId() + "=" + response.status());
                }
                for (var rank = 0; rank < response.results().size(); rank++) {
                    var result = response.results().get(rank);
                    var byReference = manifest.chunkIdForReference(result.reference());
                    var bySignal = codec.chunkIdOf(result.signal());
                    if (byReference.isEmpty()) {
                        referenceFailures.add(probe.probeId() + "#" + rank);
                    } else if (!byReference.equals(bySignal)) {
                        signalFailures.add(probe.probeId() + "#" + rank + " reference=" + byReference.get()
                                + " signal=" + bySignal.orElse(UNMAPPED));
                    }
                    rows.add(new Row(probe.probeId(), NEURON, rank, byReference.orElse(UNMAPPED), result.score()));
                }
            }
            metadata.put("perf.firstRecallNanos", Long.toString(warm[0]));
            metadata.put("perf.warmRecallMedianNanos", Long.toString(median(Arrays.copyOfRange(warm, 1, warm.length))));
        }

        checks.add(new Check("neuron.recallComplete", statusFailures.isEmpty(), statusFailures.isEmpty()
                ? "every probe answered COMPLETE through the adapter"
                : "non-COMPLETE adapter responses: " + statusFailures));
        var neuronResults = rows.stream().filter(row -> row.path().equals(NEURON)).count();
        checks.add(new Check("neuron.referenceAttribution", referenceFailures.isEmpty() && neuronResults > 0,
                referenceFailures.isEmpty()
                        ? "all " + neuronResults + " adapter results map by opaque reference to a manifest chunk"
                        : "references of no manifest chunk: " + referenceFailures));
        checks.add(new Check("neuron.signalAttribution", signalFailures.isEmpty() && neuronResults > 0,
                signalFailures.isEmpty()
                        ? "every decoded Signal names the same chunk as its reference (sha256 of the persisted content)"
                        : "decoded Signal and reference disagree: " + signalFailures));
        checks.add(directVersusAdapter(rows));
        metadata.put("probes", Integer.toString(probes.size()));
        metadata.put("probeResults.direct", Long.toString(rows.size() - neuronResults));
        metadata.put("probeResults.neuron", Long.toString(neuronResults));
        return new Observation(new Snapshot(profile, rows), checks, metadata);
    }

    private Check directVersusAdapter(List<Row> rows) {
        var direct = new LinkedHashMap<String, List<String>>();
        var neuron = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            var target = row.path().equals(DIRECT) ? direct : neuron;
            target.computeIfAbsent(row.probeId(), _ -> new ArrayList<>())
                    .add(row.chunkId() + "@" + Double.toHexString(row.score()));
        }
        var differing = new ArrayList<String>();
        for (var probe : probes) {
            var left = direct.getOrDefault(probe.probeId(), List.of());
            var right = neuron.getOrDefault(probe.probeId(), List.of());
            if (!left.equals(right)) {
                differing.add(probe.probeId() + " direct=" + left + " neuron=" + right);
            }
        }
        return new Check("neuron.directVsAdapterEqual", differing.isEmpty(), differing.isEmpty()
                ? "for every single-Signal probe the adapter returned the same chunks, order and scores as"
                        + " MonadaMemory.resonate(query).topK(" + TOP_K + ").threshold(" + THRESHOLD + ")"
                : "adapter differs from direct recall: " + differing);
    }

    /**
     * Measures what the adapter's default interim codecs ({@code CanonicalSignalQueryEncoder} and
     * {@code HashedSignalDecoder}) return on {@code root} for the probe Signals, without changing either default.
     *
     * <p>Passes when the measurement shows the default is insufficient for recoverable text: the canonical query does
     * not reproduce the probe codec's top result for every probe, and each hashed Signal is only attributable by
     * re-hashing the external corpus (it equals {@code HashedSignalDecoder.decode} of the mapped chunk's text and
     * carries no text itself). Every probe must answer {@code COMPLETE} with at least one result whose reference maps
     * to a persisted chunk, so a failed or empty default is reported as a failure, not as insufficiency.
     */
    public Check defaultCodecInsufficiency(Path root, IngestionManifest manifest, Snapshot snapshot) {
        requireExistingStore(root);
        var hashed = new HashedSignalDecoder();
        var chunkBySignal = new HashMap<Object, List<String>>();
        for (var chunk : corpus.chunks()) {
            chunkBySignal.computeIfAbsent(hashed.decode(chunk.text()), _ -> new ArrayList<>()).add(chunk.chunkId());
        }
        var collisions = chunkBySignal.values().stream().filter(ids -> ids.size() > 1).count();
        var topByProbe = new HashMap<String, String>();
        snapshot.rows(NEURON).stream().filter(row -> row.rank() == 0)
                .forEach(row -> topByProbe.put(row.probeId(), row.chunkId()));

        var agreements = 0;
        var resultSets = new HashSet<List<String>>();
        var mismatches = new ArrayList<String>();
        var unusable = new ArrayList<String>();
        var statuses = new LinkedHashMap<ResonanceMemoryStatus, Integer>();
        try (var adapter = ResonanceStoreMemoryAdapter.open(root, ResonanceStoreAdapterConfig.defaults())) {
            for (var probe : probes) {
                var response = adapter.recall(new ResonanceMemoryRequest(
                        List.of(codec.querySignal(probe.probeId())), TOP_K));
                statuses.merge(response.status(), 1, Integer::sum);
                if (response.status() != ResonanceMemoryStatus.COMPLETE || response.results().isEmpty()) {
                    unusable.add(probe.probeId() + "=" + response.status() + "/" + response.results().size());
                }
                var chunks = new ArrayList<String>();
                for (var result : response.results()) {
                    var chunkId = manifest.chunkIdForReference(result.reference()).orElse(UNMAPPED);
                    chunks.add(chunkId);
                    var recovered = chunkBySignal.getOrDefault(result.signal(), List.of());
                    if (chunkId.equals(UNMAPPED) || !recovered.contains(chunkId)) {
                        mismatches.add(probe.probeId() + ":" + chunkId);
                    }
                }
                resultSets.add(chunks);
                if (!chunks.isEmpty() && chunks.getFirst().equals(topByProbe.get(probe.probeId()))) {
                    agreements++;
                }
            }
        }
        var insufficient = agreements < probes.size();
        // Disagreement is evidence only when the default produced usable, attributable output for every probe; a
        // failed, unavailable or empty response would otherwise look like insufficiency.
        var passed = insufficient && mismatches.isEmpty() && unusable.isEmpty();
        return new Check("neuron.defaultCodecInsufficient", passed, "defaults() on the same root: statuses "
                + statuses + "; " + resultSets.size() + " distinct result lists over " + probes.size()
                + " probes; top-1 agrees with the probe codec for " + agreements + "/" + probes.size()
                + "; hashed Signals carry no text and were attributable only by re-hashing all " + corpus.chunks().size()
                + " corpus chunks (" + collisions + " collisions)"
                + (mismatches.isEmpty() ? "" : "; unattributable: " + mismatches)
                + (unusable.isEmpty() ? "" : "; not COMPLETE or empty: " + unusable));
    }

    /**
     * Measures the default codecs ({@link #defaultCodecInsufficiency}) and runs the failure audits, each on its own
     * copy of {@code root} under {@code scratch}; {@code root} itself is never opened or changed.
     *
     * @param snapshot the probe codec's snapshot of {@code root}, the reference the default codecs are compared with
     */
    public List<Check> audit(Path root, IngestionManifest manifest, Snapshot snapshot, Path scratch) {
        requireExistingStore(root);
        var checks = new ArrayList<Check>();
        checks.add(defaultCodecInsufficiency(copy(root, scratch, "default-codec"), manifest, snapshot));
        checks.add(missingPath(manifest, scratch));
        checks.add(pathIsFile(scratch));
        checks.add(changedEncodingProfile(copy(root, scratch, "changed-profile")));
        checks.add(removedVectorSegment(copy(root, scratch, "removed-vectors"), manifest));
        checks.add(duplicateRemember(copy(root, scratch, "duplicate"), manifest));
        checks.add(closedAdapter(copy(root, scratch, "closed-adapter")));
        checks.add(alteredAtom(copy(root, scratch, "altered-atom"), manifest));
        checks.add(removedAtom(copy(root, scratch, "removed-atom"), manifest));
        // Temporary locations differ per run; keep details comparable across runs.
        var location = scratch.toAbsolutePath().toString();
        return checks.stream()
                .map(check -> new Check(check.name(), check.passed(), check.detail().replace(location, "<scratch>")))
                .toList();
    }

    private Check missingPath(IngestionManifest manifest, Path scratch) {
        var missing = scratch.resolve("missing-root");
        var refused = attempt(() -> {
            requireExistingStore(missing);
            return null;
        });
        var memory = MonadaMemory.open(missing);
        var created = Files.exists(missing.resolve(MANIFEST_FILE));
        var unread = ingestion.readback(memory, manifest);
        var count = corpus.chunks().size();
        var passed = refused.failure().filter(IngestionInputException.class::isInstance).isPresent()
                && created && unread.size() == count;
        return new Check("audit.missingPath", passed, "harness preflight: " + refused.describe()
                + "; raw MonadaMemory.open on the same missing path created an empty store (manifest.json written: "
                + created + ") and readback failed for " + unread.size() + "/" + count + " chunks");
    }

    private Check pathIsFile(Path scratch) {
        var file = scratch.resolve("plain-file");
        try {
            Files.writeString(file, "not a store", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var direct = attempt(() -> MonadaMemory.open(file));
        var adapter = attempt(() -> ResonanceStoreMemoryAdapter.open(file, config()));
        var passed = direct.failure().isPresent()
                && adapter.failure().filter(ResonanceStoreAdapterException.class::isInstance).isPresent();
        return new Check("audit.pathIsFile", passed, "direct open: " + direct.describe() + "; adapter open: "
                + adapter.describe());
    }

    private Check changedEncodingProfile(Path copy) {
        var before = profile(auditor.audit(copy).manifest());
        var options = new MonadaMemoryOptions(new NoOpTextNormalizer(), false);
        var direct = attempt(() -> MonadaMemory.open(copy, options));
        var adapter = attempt(() -> ResonanceStoreMemoryAdapter.open(copy, new ResonanceStoreAdapterConfig(
                codec, codec, options, THRESHOLD)));
        var unchanged = before.equals(profile(auditor.audit(copy).manifest()));
        var passed = direct.failure().isPresent()
                && adapter.failure().filter(ResonanceStoreAdapterException.class::isInstance).isPresent() && unchanged;
        return new Check("audit.changedEncodingProfile", passed, "open with NoOpTextNormalizer options: direct "
                + direct.describe() + "; adapter " + adapter.describe() + "; persisted profile unchanged: " + unchanged);
    }

    private Check removedVectorSegment(Path copy, IngestionManifest manifest) {
        var persisted = auditor.audit(copy).manifest();
        delete(copy.resolve(persisted.vectorSegment()));
        var audit = auditor.audit(copy);
        var flagged = audit.findings().stream().anyMatch(f -> f.category() == StorageIntegrityCategory.SEGMENT_MISSING);
        var direct = attempt(() -> MonadaMemory.open(copy));
        var directVisible = direct.failure().isPresent()
                || !ingestion.readback(direct.value().orElseThrow(), manifest).isEmpty();
        var adapter = attempt(() -> ResonanceStoreMemoryAdapter.open(copy, config()));
        var adapterVisible = adapter.failure().isPresent() || failedRecall(adapter.value().orElseThrow());
        var passed = !audit.isHealthy() && flagged && directVisible && adapterVisible;
        return new Check("audit.removedVectorSegment", passed, "deleted " + persisted.vectorSegment()
                + "; integrity audit healthy=" + audit.isHealthy() + ", SEGMENT_MISSING=" + flagged + "; direct open: "
                + direct.describe() + "; adapter open: " + adapter.describe());
    }

    private Check duplicateRemember(Path copy, IngestionManifest manifest) {
        var before = auditor.audit(copy).statistics();
        var chunk = corpus.chunks().getFirst();
        var atom = MonadaMemory.open(copy).remember(chunk.text());
        var after = auditor.audit(copy).statistics();
        var sameAtom = manifest.chunkIdForAtom(atom.id()).filter(chunk.chunkId()::equals).isPresent();
        var nothingWritten = before.atomLogPhysicalRecords() == after.atomLogPhysicalRecords()
                && before.activeUniqueAtoms() == after.activeUniqueAtoms()
                && before.vectorIndexEntries() == after.vectorIndexEntries()
                && after.duplicateVectorIndexIds() == 0;
        var guard = attempt(() -> {
            HtmlKnowledgeIngestion.requireFreshRoot(copy);
            return null;
        });
        var refused = guard.failure().filter(IngestionInputException.class::isInstance).isPresent();
        return new Check("audit.duplicateRemember", sameAtom && nothingWritten && refused, "remember of "
                + chunk.chunkId() + " again returned its manifest atom: " + sameAtom + "; atom log records "
                + before.atomLogPhysicalRecords() + "->" + after.atomLogPhysicalRecords() + ", vector entries "
                + before.vectorIndexEntries() + "->" + after.vectorIndexEntries() + "; a second import into the"
                + " root: " + guard.describe());
    }

    private Check closedAdapter(Path copy) {
        var adapter = ResonanceStoreMemoryAdapter.open(copy, config());
        var request = new ResonanceMemoryRequest(List.of(codec.querySignal(probes.getFirst().probeId())), TOP_K);
        var open = adapter.recall(request);
        adapter.close();
        var closed = adapter.recall(request);
        var passed = open.status() == ResonanceMemoryStatus.COMPLETE && !open.results().isEmpty()
                && closed.status() == ResonanceMemoryStatus.UNAVAILABLE && closed.results().isEmpty();
        return new Check("audit.closedAdapter", passed, "before close: " + open.status() + " with "
                + open.results().size() + " results; after close: " + closed.status() + " with "
                + closed.results().size() + " results (MonadaMemory itself has no close)");
    }

    private Check alteredAtom(Path copy, IngestionManifest manifest) {
        var target = manifest.entries().get(1);
        var chunk = corpus.chunk(target.chunkId()).orElseThrow();
        rewriteAtomLog(copy, target.atomId(), columns -> {
            columns[4] = Base64.getEncoder().encodeToString(
                    (chunk.text() + " [altered]").getBytes(StandardCharsets.UTF_8));
            return Optional.of(String.join("\t", columns));
        });
        var unread = ingestion.readback(MonadaMemory.open(copy), manifest);
        ResonanceMemoryResponse response;
        try (var adapter = ResonanceStoreMemoryAdapter.open(copy, config())) {
            response = adapter.recall(new ResonanceMemoryRequest(
                    List.of(codec.querySignal("readback:" + target.chunkId())), TOP_K));
        }
        var passed = unread.equals(List.of(target.chunkId())) && response.status() == ResonanceMemoryStatus.FAILED;
        return new Check("audit.alteredAtom", passed, "content of " + target.chunkId()
                + "'s atom altered on disk: readback failed for " + unread + "; adapter recall of its probe: "
                + response.status() + " (decoder rejected unknown content instead of matching it)");
    }

    private Check removedAtom(Path copy, IngestionManifest manifest) {
        var target = manifest.entries().get(2);
        rewriteAtomLog(copy, target.atomId(), _ -> Optional.empty());
        var audit = auditor.audit(copy);
        var flagged = audit.findings().stream()
                .anyMatch(f -> f.category() == StorageIntegrityCategory.VECTOR_WITHOUT_ATOM);
        var unread = ingestion.readback(MonadaMemory.open(copy), manifest);
        var passed = unread.equals(List.of(target.chunkId())) && flagged;
        return new Check("audit.removedAtom", passed, "atom record of " + target.chunkId()
                + " removed on disk: readback failed for " + unread + "; integrity audit VECTOR_WITHOUT_ATOM="
                + flagged + findings(audit));
    }

    private boolean failedRecall(ResonanceStoreMemoryAdapter adapter) {
        try (adapter) {
            var response = adapter.recall(new ResonanceMemoryRequest(
                    List.of(codec.querySignal(probes.getFirst().probeId())), TOP_K));
            return response.status() != ResonanceMemoryStatus.COMPLETE || response.results().isEmpty();
        }
    }

    /**
     * Requires {@code root} to hold a Store manifest. {@code MonadaMemory.open} creates an empty store on a missing
     * path, so without this preflight a wrong path would read back nothing instead of failing.
     *
     * @throws IngestionInputException when {@code root/manifest.json} is not a regular file
     */
    void requireExistingStore(Path root) {
        Objects.requireNonNull(root, "root must not be null");
        if (!Files.isRegularFile(root.resolve(MANIFEST_FILE))) {
            throw new IngestionInputException("store root " + root + " holds no " + MANIFEST_FILE
                    + "; refusing to open it (MonadaMemory.open would create an empty store there)");
        }
    }

    /** Returns the adapter configuration with the probe codec, default Store options and {@link #THRESHOLD}. */
    ResonanceStoreAdapterConfig config() {
        return new ResonanceStoreAdapterConfig(codec, codec, MonadaMemoryOptions.defaults(), THRESHOLD);
    }

    /** Returns the probes in their fixed order. */
    List<PersistenceProbes.Probe> probes() {
        return probes;
    }

    private String profile(Manifest manifest) {
        return manifest == null ? "unreadable" : manifest.encodingProfile() + " " + manifest.vectorFormatProfile();
    }

    private String findings(StorageIntegrityReport audit) {
        if (audit.findings().isEmpty()) {
            return "";
        }
        return "; findings: " + audit.findings().stream()
                .map(f -> f.severity() + " " + f.category() + " " + f.target())
                .collect(Collectors.joining(", "));
    }

    private Check equalRows(String name, List<Row> expected, List<Row> actual, String passedDetail) {
        if (expected.equals(actual)) {
            return new Check(name, !expected.isEmpty(), passedDetail + " (" + expected.size() + " results)");
        }
        var index = 0;
        while (index < Math.min(expected.size(), actual.size()) && expected.get(index).equals(actual.get(index))) {
            index++;
        }
        return new Check(name, false, "first difference at result " + index + ": seed "
                + (index < expected.size() ? expected.get(index).toTsv() : "<none>") + ", now "
                + (index < actual.size() ? actual.get(index).toTsv() : "<none>") + " (" + expected.size() + " vs "
                + actual.size() + " results)");
    }

    private long median(long[] values) {
        if (values.length == 0) {
            return 0L;
        }
        var sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private void rewriteAtomLog(Path root, String atomId,
            Function<String[], Optional<String>> rewrite) {
        var log = root.resolve(auditor.audit(root).manifest().atomSegment());
        try {
            var lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            var rewritten = new ArrayList<String>(lines.size());
            var found = false;
            for (var line : lines) {
                var columns = line.split("\t", -1);
                if (columns.length > 4 && columns[0].equals(atomId)) {
                    found = true;
                    rewrite.apply(columns).ifPresent(rewritten::add);
                } else {
                    rewritten.add(line);
                }
            }
            if (!found) {
                throw new IllegalStateException("atom " + atomId + " has no record in " + log);
            }
            Files.write(log, rewritten, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path copy(Path root, Path scratch, String name) {
        var target = scratch.resolve(name);
        try (Stream<Path> paths = Files.walk(root)) {
            for (var source : paths.toList()) {
                var destination = target.resolve(root.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot copy store root " + root + " to " + target, e);
        }
        return target;
    }

    private void delete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> Attempt<T> attempt(Supplier<T> action) {
        try {
            return new Attempt<>(Optional.ofNullable(action.get()), Optional.empty());
        } catch (RuntimeException e) {
            return new Attempt<>(Optional.empty(), Optional.of(e));
        }
    }

    /** The value or the actual exception of one attempted operation, recorded as observed. */
    private record Attempt<T>(Optional<T> value, Optional<RuntimeException> failure) {

        String describe() {
            return failure.map(e -> {
                var cause = e.getCause() == null ? "" : " (cause " + e.getCause().getClass().getSimpleName() + ")";
                var message = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
                return "threw " + e.getClass().getSimpleName() + cause + ": " + message;
            }).orElse("succeeded");
        }
    }
}
