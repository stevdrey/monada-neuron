package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.JudgedQuery.Answerability;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;
import monada.neuron.evaluation.knowledge.JudgedQuery.EvidenceSpan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The validated, versioned judged query set {@value #VERSION} over one corpus (protocol section "Query set and gold
 * labels").
 *
 * <p>The bundled set is the resource {@code html-kq-v1/queries.tsv}. Every load-time invariant of the protocol, and
 * the encodability of every text by the L2 codec, is checked against the corpus before a query set exists; a
 * violation throws {@link CorpusValidationException}. Queries are exposed in {@code queryId} code point order, which is
 * also the run order. The labels are curated, never derived from system output, and read only by the scorer.
 */
public final class HtmlKnowledgeQuerySet {

    /** Version of the authored query set. */
    public static final String VERSION = "html-kq-v1";

    /** Header row of {@code queries.tsv}. */
    public static final String HEADER =
            "queryId\tcategory\tanswerability\ttext\trequired\tsupporting\tevidenceSpans\tkeyClaims\tavoidedTerms";

    /** Minimum and maximum query count. */
    static final int MIN_QUERIES = 25;
    static final int MAX_QUERIES = 40;

    /** Minimum queries per category. */
    static final int MIN_PER_CATEGORY = 2;

    /** Largest relevant set: the largest evaluated limit, so a full hit is always attainable. */
    static final int MAX_RELEVANT = 5;

    private static final String RESOURCE = "/" + VERSION + "/queries.tsv";
    private static final String FILE = "queries.tsv";
    private static final int MAX_TABLE_BYTES = 64 * 1024;
    private static final int COLUMNS = HEADER.split("\t").length;
    private static final String LIST_SEPARATOR = "|";
    private static final Pattern QUERY_ID = Pattern.compile("q:([a-z]+(?:-[a-z]+)*)-(\\d{2})");

    private final String version;
    private final HtmlKnowledgeCorpus corpus;
    private final List<JudgedQuery> queries;
    private final String queryHash;

    private HtmlKnowledgeQuerySet(String version, HtmlKnowledgeCorpus corpus, List<JudgedQuery> queries) {
        this.version = version;
        this.corpus = corpus;
        this.queries = queries.stream().sorted(Comparator.comparing(JudgedQuery::queryId)).toList();
        this.queryHash = hash(version, this.queries);
    }

    /** Loads and validates the bundled query set against {@code corpus}. */
    public static HtmlKnowledgeQuerySet bundled(HtmlKnowledgeCorpus corpus) {
        try (var input = HtmlKnowledgeQuerySet.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new CorpusValidationException(RESOURCE, "bundled query set is missing");
            }
            return parse(VERSION, corpus, bounded(input.readNBytes(MAX_TABLE_BYTES + 1), RESOURCE), RESOURCE);
        } catch (IOException e) {
            throw new CorpusValidationException(RESOURCE, "cannot read bundled query set", e);
        }
    }

    /** Loads and validates {@code queries.tsv} from {@code directory} against {@code corpus}. */
    public static HtmlKnowledgeQuerySet load(Path directory, HtmlKnowledgeCorpus corpus) {
        var file = HtmlKnowledgeCorpus.resolveInside(Objects.requireNonNull(directory, "directory must not be null"),
                FILE);
        try (var input = Files.newInputStream(file)) {
            return parse(VERSION, corpus, bounded(input.readNBytes(MAX_TABLE_BYTES + 1), file.toString()),
                    file.toString());
        } catch (IOException e) {
            throw new CorpusValidationException(file.toString(), "cannot read query set", e);
        }
    }

    /** Validates already parsed queries against {@code corpus}, as the loaders do. */
    static HtmlKnowledgeQuerySet of(String version, HtmlKnowledgeCorpus corpus, List<JudgedQuery> queries) {
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(corpus, "corpus must not be null");
        validate(version, corpus, List.copyOf(queries));
        return new HtmlKnowledgeQuerySet(version, corpus, queries);
    }

    /** Returns the query-set version. */
    public String version() {
        return version;
    }

    /** Returns the corpus the labels were validated against. */
    public HtmlKnowledgeCorpus corpus() {
        return corpus;
    }

    /** Returns the queries in {@code queryId} order. */
    public List<JudgedQuery> queries() {
        return queries;
    }

    /** Returns the canonical {@code queryHash}. */
    public String queryHash() {
        return queryHash;
    }

    private static byte[] bounded(byte[] bytes, String origin) {
        if (bytes.length > MAX_TABLE_BYTES) {
            throw new CorpusValidationException(origin, "file exceeds the bound of " + MAX_TABLE_BYTES + " bytes");
        }
        if (bytes.length == 0) {
            throw new CorpusValidationException(origin, "file is empty");
        }
        return bytes;
    }

    private static HtmlKnowledgeQuerySet parse(String version, HtmlKnowledgeCorpus corpus, byte[] bytes,
            String origin) {
        var text = new CorpusNormalizer().normalize(bytes, origin);
        var lines = text.split("\n", -1);
        var queries = new ArrayList<JudgedQuery>();
        var headerSeen = false;
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            var where = origin + ":" + (i + 1);
            if (!headerSeen) {
                if (!line.equals(HEADER)) {
                    throw new CorpusValidationException(where, "expected header '" + HEADER + "'");
                }
                headerSeen = true;
                continue;
            }
            var cells = line.split("\t", -1);
            if (cells.length != COLUMNS) {
                throw new CorpusValidationException(where,
                        "expected " + COLUMNS + " tab-separated columns, found " + cells.length);
            }
            queries.add(row(where, cells));
        }
        if (!headerSeen) {
            throw new CorpusValidationException(origin, "missing header row");
        }
        return of(version, corpus, queries);
    }

    private static JudgedQuery row(String where, String[] cells) {
        var queryId = cells[0].strip();
        var category = Arrays.stream(Category.values()).filter(value -> value.label().equals(cells[1].strip()))
                .findFirst()
                .orElseThrow(() -> new CorpusValidationException(where, "unknown category '" + cells[1] + "'"));
        Answerability answerability;
        try {
            answerability = Answerability.valueOf(cells[2].strip());
        } catch (IllegalArgumentException e) {
            throw new CorpusValidationException(where, "unknown answerability '" + cells[2] + "'");
        }
        if (answerability != category.answerability()) {
            throw new CorpusValidationException(where, "answerability " + answerability + " does not match category "
                    + category.label() + ", which fixes " + category.answerability());
        }
        var spans = new ArrayList<EvidenceSpan>();
        for (var span : list(where, "evidenceSpans", cells[6])) {
            var parts = span.split(" ", -1);
            if (parts.length != 4) {
                throw new CorpusValidationException(where, "evidence span '" + span
                        + "' must be 'chunkId start end sha256'");
            }
            try {
                spans.add(new EvidenceSpan(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        parts[3]));
            } catch (NumberFormatException e) {
                throw new CorpusValidationException(where, "evidence span '" + span + "' has a non-integer bound");
            }
        }
        return new JudgedQuery(queryId, category, HtmlKnowledgeRecall.QueryText.unescape(where, cells[3]),
                new LinkedHashSet<>(list(where, "required", cells[4])),
                new LinkedHashSet<>(list(where, "supporting", cells[5])),
                spans,
                list(where, "keyClaims", cells[7]),
                new LinkedHashSet<>(list(where, "avoidedTerms", cells[8])));
    }

    private static List<String> list(String where, String name, String cell) {
        var value = cell.strip();
        if (value.equals(HtmlKnowledgeCorpus.ABSENT)) {
            return List.of();
        }
        if (value.isEmpty()) {
            throw new CorpusValidationException(where, name + " must be '" + HtmlKnowledgeCorpus.ABSENT
                    + "' when empty");
        }
        var elements = List.of(value.split(Pattern.quote(LIST_SEPARATOR), -1));
        for (var element : elements) {
            if (element.isBlank() || !element.equals(element.strip())) {
                throw new CorpusValidationException(where, name + " has a blank or padded element");
            }
        }
        if (new HashSet<>(elements).size() != elements.size()) {
            throw new CorpusValidationException(where, name + " repeats an entry");
        }
        return elements;
    }

    private static void validate(String version, HtmlKnowledgeCorpus corpus, List<JudgedQuery> queries) {
        var origin = version;
        if (queries.size() < MIN_QUERIES || queries.size() > MAX_QUERIES) {
            throw new CorpusValidationException(origin, queries.size() + " queries; the set must hold "
                    + MIN_QUERIES + " to " + MAX_QUERIES);
        }
        var conflictPair = new TreeSet<String>();
        corpus.chunks().stream().filter(chunk -> chunk.conflict().isPresent())
                .forEach(chunk -> conflictPair.add(chunk.chunkId()));
        var ids = new HashSet<String>();
        var texts = new HashSet<String>();
        var perCategory = new EnumMap<Category, Integer>(Category.class);
        var emptyRelevant = 0;
        for (var query : queries) {
            var where = origin + ":" + query.queryId();
            var id = QUERY_ID.matcher(query.queryId());
            if (!id.matches() || !id.group(1).equals(query.category().label())) {
                throw new CorpusValidationException(where, "queryId must be q:" + query.category().label() + "-<nn>");
            }
            if (!ids.add(query.queryId())) {
                throw new CorpusValidationException(where, "duplicate queryId");
            }
            if (!texts.add(query.text())) {
                throw new CorpusValidationException(where, "duplicate query text");
            }
            perCategory.merge(query.category(), 1, Integer::sum);
            validateQuery(where, corpus, conflictPair, query);
            if (query.relevant().isEmpty()) {
                emptyRelevant++;
            }
        }
        for (var category : Category.values()) {
            if (perCategory.getOrDefault(category, 0) < MIN_PER_CATEGORY) {
                throw new CorpusValidationException(origin, "category " + category.label() + " has fewer than "
                        + MIN_PER_CATEGORY + " queries");
            }
        }
        if (emptyRelevant * 10 < queries.size() * 3) {
            throw new CorpusValidationException(origin, emptyRelevant + " of " + queries.size()
                    + " queries have an empty relevant set; at least 30 % are required");
        }
    }

    private static void validateQuery(String where, HtmlKnowledgeCorpus corpus, Set<String> conflictPair,
            JudgedQuery query) {
        var required = query.required();
        var supporting = query.supporting();
        var relevant = query.relevant();
        if (required.isEmpty() && !supporting.isEmpty()) {
            throw new CorpusValidationException(where, "supporting must be empty when required is empty");
        }
        if (relevant.size() != required.size() + supporting.size()) {
            throw new CorpusValidationException(where, "required and supporting overlap");
        }
        if (relevant.size() > MAX_RELEVANT) {
            throw new CorpusValidationException(where, "relevant set exceeds " + MAX_RELEVANT + " chunks");
        }
        for (var chunkId : relevant) {
            if (corpus.chunk(chunkId).isEmpty()) {
                throw new CorpusValidationException(where, "unknown chunk " + chunkId);
            }
        }
        switch (query.answerability()) {
            case ANSWERABLE, CONFLICTING -> {
                if (required.isEmpty()) {
                    throw new CorpusValidationException(where, query.answerability() + " requires a required chunk");
                }
            }
            case NO_ANSWER_IN_CORPUS, OUT_OF_DOMAIN, BLANK -> {
                if (!relevant.isEmpty()) {
                    throw new CorpusValidationException(where, query.answerability() + " must have no relevant chunk");
                }
            }
            case FALSE_PREMISE -> {
            }
        }
        if (query.answerability() == Answerability.CONFLICTING && !required.equals(conflictPair)) {
            throw new CorpusValidationException(where, "a conflicting-source query requires exactly the conflict pair "
                    + conflictPair);
        }
        if (query.text().isBlank() != (query.category() == Category.BLANK)) {
            throw new CorpusValidationException(where, "text is blank exactly for blank queries");
        }
        validateSpans(where, corpus, query);
        if (!relevant.isEmpty() && query.keyClaims().isEmpty()) {
            throw new CorpusValidationException(where, "a query with relevant chunks needs at least one keyClaim");
        }
        if ((query.category() == Category.PARAPHRASE) == query.avoidedTerms().isEmpty()) {
            throw new CorpusValidationException(where, "avoidedTerms are required exactly for paraphrase queries");
        }
        var textTokens = HtmlTokens.distinct(query.text());
        for (var term : query.avoidedTerms()) {
            for (var token : HtmlTokens.tokens(term)) {
                if (textTokens.contains(token)) {
                    throw new CorpusValidationException(where, "text uses the avoided term '" + term + "'");
                }
            }
        }
        try {
            new TokenPackingSignalCodec().querySignals(query.text());
        } catch (IllegalArgumentException e) {
            throw new CorpusValidationException(where, "text cannot be encoded by the L2 codec: " + e.getMessage());
        }
    }

    private static void validateSpans(String where, HtmlKnowledgeCorpus corpus, JudgedQuery query) {
        var spanned = new HashSet<String>();
        var seen = new HashSet<EvidenceSpan>();
        EvidenceSpan previous = null;
        for (var span : query.evidenceSpans()) {
            if (!query.relevant().contains(span.chunkId())) {
                throw new CorpusValidationException(where, "evidence span names " + span.chunkId()
                        + ", which is not relevant");
            }
            if (!seen.add(span)) {
                throw new CorpusValidationException(where, "duplicate evidence span");
            }
            if (previous != null && SPAN_ORDER.compare(previous, span) > 0) {
                throw new CorpusValidationException(where, "evidence spans must be sorted by (chunkId, start, end)");
            }
            previous = span;
            var text = corpus.chunk(span.chunkId()).orElseThrow().text();
            var length = text.codePointCount(0, text.length());
            if (span.start() < 0 || span.start() >= span.end() || span.end() > length) {
                throw new CorpusValidationException(where, "evidence span [" + span.start() + ", " + span.end()
                        + ") is out of bounds for " + span.chunkId() + " of " + length + " code points");
            }
            var substring = text.substring(text.offsetByCodePoints(0, span.start()),
                    text.offsetByCodePoints(0, span.end()));
            if (!CorpusDigests.sha256(substring).equals(span.sha256())) {
                throw new CorpusValidationException(where, "evidence span digest does not match " + span.chunkId()
                        + " [" + span.start() + ", " + span.end() + ")");
            }
            spanned.add(span.chunkId());
        }
        if (!spanned.containsAll(query.required())) {
            throw new CorpusValidationException(where, "every required chunk needs an evidence span");
        }
    }

    private static final Comparator<EvidenceSpan> SPAN_ORDER = Comparator.comparing(EvidenceSpan::chunkId)
            .thenComparingInt(EvidenceSpan::start)
            .thenComparingInt(EvidenceSpan::end);

    private static String hash(String version, List<JudgedQuery> sortedQueries) {
        var digest = new CanonicalRecordDigest().field(version).field(sortedQueries.size());
        for (var query : sortedQueries) {
            digest.field(query.queryId())
                    .field(query.category().label())
                    .field(query.text())
                    .field(query.answerability().name());
            sortedField(digest, query.required());
            sortedField(digest, query.supporting());
            digest.field(query.evidenceSpans().size());
            for (var span : query.evidenceSpans().stream().sorted(SPAN_ORDER).toList()) {
                digest.field(span.chunkId()).field(span.start()).field(span.end()).field(span.sha256());
            }
            digest.field(query.keyClaims().size());
            query.keyClaims().forEach(digest::field);
            sortedField(digest, query.avoidedTerms());
        }
        return digest.hex();
    }

    private static void sortedField(CanonicalRecordDigest digest, Set<String> values) {
        digest.field(values.size());
        new TreeSet<>(values).forEach(digest::field);
    }

    /** Returns the queries grouped by category, in category order then {@code queryId} order. */
    Map<Category, List<JudgedQuery>> byCategory() {
        var grouped = new EnumMap<Category, List<JudgedQuery>>(Category.class);
        queries.forEach(query -> grouped.computeIfAbsent(query.category(), ignored -> new ArrayList<>()).add(query));
        grouped.replaceAll((category, list) -> List.copyOf(list));
        return grouped;
    }

    @Override
    public String toString() {
        return "HtmlKnowledgeQuerySet[" + version + ", " + queries.size() + " queries, queryHash=" + queryHash + "]";
    }
}
