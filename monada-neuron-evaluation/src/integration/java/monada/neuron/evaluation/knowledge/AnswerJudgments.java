package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.AnswerTrace.ClaimRow;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The frozen human judgments of the L4 answers (Issue #109, {@code html-l4-judgments-v1}). The protocol requires a
 * human verdict, with a reason and a reviewer, for what no rule can decide: whether a claim with an eligible citation is
 * supported (unless it is an exact quote, which #109 verifies mechanically), whether it contradicts the curated
 * evidence, every candidate key-claim edge, and the {@code premiseAccepted} and {@code conflictDisclosed} flags.
 *
 * <p>A judgment is keyed by the query and the exact answer: every claim text and its citations. A changed answer
 * therefore never inherits an old verdict. The file is read only by the scorer, never by a phase or an answer
 * component, and its hash is frozen in the report.
 *
 * <p>Format: comment lines, the header {@code queryId answer item verdict reason reviewer draftedBy}, then one row per
 * item. {@code answer} is {@link #canonical} escaped like the workload. Items: {@code claim<i>.supported},
 * {@code claim<i>.contradicted} ({@code true}/{@code false}), {@code claim<i>.edge<j>} ({@code accept}/{@code reject},
 * {@code j} the 1-based {@code keyClaim} index in curated order), {@code premiseAccepted} and
 * {@code conflictDisclosed} ({@code true}/{@code false}).
 */
final class AnswerJudgments {

    /** Version of the bundled judgments. */
    static final String VERSION = "html-l4-judgments-v1";

    static final String HEADER = "queryId\tanswer\titem\tverdict\treason\treviewer\tdraftedBy";

    private static final String RESOURCE = "/" + VERSION + "/judgments.tsv";
    private static final int MAX_BYTES = 256 * 1024;
    private static final Pattern ITEM = Pattern.compile(
            "claim[1-9][0-9]*\\.(supported|contradicted|edge[1-9][0-9]*)|premiseAccepted|conflictDisclosed");

    /**
     * The key of one judged item.
     *
     * @param queryId the query
     * @param answer the canonical answer
     * @param item the item name
     */
    record Key(String queryId, String answer, String item) {

        Key {
            Objects.requireNonNull(queryId, "queryId must not be null");
            Objects.requireNonNull(answer, "answer must not be null");
            Objects.requireNonNull(item, "item must not be null");
        }
    }

    /**
     * One recorded verdict.
     *
     * @param value {@code true}, {@code false}, {@code accept} or {@code reject}
     * @param reason why
     * @param reviewer the human reviewer of record
     * @param draftedBy who drafted the verdict for the reviewer, or {@code -}
     */
    record Verdict(String value, String reason, String reviewer, String draftedBy) {

        Verdict {
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(reviewer, "reviewer must not be null");
            Objects.requireNonNull(draftedBy, "draftedBy must not be null");
        }

        /** Returns the verdict as a boolean; {@code accept} is {@code true}. */
        boolean asBoolean() {
            return value.equals("true") || value.equals("accept");
        }
    }

    private final Map<Key, Verdict> verdicts;
    private final String hash;
    private final String origin;

    private AnswerJudgments(Map<Key, Verdict> verdicts, String hash, String origin) {
        this.verdicts = Map.copyOf(verdicts);
        this.hash = hash;
        this.origin = origin;
    }

    /** Loads the bundled judgments. */
    static AnswerJudgments bundled() {
        try (var input = AnswerJudgments.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new CorpusValidationException(RESOURCE, "bundled judgments are missing");
            }
            var bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new CorpusValidationException(RESOURCE, "file exceeds the bound of " + MAX_BYTES + " bytes");
            }
            return parse(new CorpusNormalizer().normalize(bytes, RESOURCE), RESOURCE);
        } catch (IOException e) {
            throw new CorpusValidationException(RESOURCE, "cannot read bundled judgments", e);
        }
    }

    /**
     * Parses judgments strictly.
     *
     * @throws CorpusValidationException on a malformed row, an unknown item, an invalid verdict or a duplicate key
     */
    static AnswerJudgments parse(String tsv, String origin) {
        var lines = tsv.split("\n", -1);
        var index = 0;
        while (index < lines.length && lines[index].startsWith("#")) {
            index++;
        }
        if (index >= lines.length || !lines[index].equals(HEADER)) {
            throw new CorpusValidationException(origin, "missing header " + HEADER.replace('\t', ' '));
        }
        var verdicts = new LinkedHashMap<Key, Verdict>();
        for (index++; index < lines.length; index++) {
            if (lines[index].isEmpty() && index == lines.length - 1) {
                break;
            }
            var where = origin + ":" + (index + 1);
            var cells = lines[index].split("\t", -1);
            if (cells.length != 7) {
                throw new CorpusValidationException(where, "expected 7 columns, found " + cells.length);
            }
            var item = cells[2];
            if (!ITEM.matcher(item).matches()) {
                throw new CorpusValidationException(where, "unknown item " + item);
            }
            var value = cells[3];
            var allowed = item.contains(".edge") ? List.of("accept", "reject") : List.of("true", "false");
            if (!allowed.contains(value)) {
                throw new CorpusValidationException(where, item + " takes " + allowed + ", not '" + value + "'");
            }
            if (cells[4].isBlank() || cells[5].isBlank() || cells[6].isBlank()) {
                throw new CorpusValidationException(where, "reason, reviewer and draftedBy are required");
            }
            var key = new Key(cells[0], QueryText.unescape(where, cells[1]), item);
            if (verdicts.put(key, new Verdict(value, cells[4], cells[5], cells[6])) != null) {
                throw new CorpusValidationException(where, "duplicate judgment of " + item + " for " + cells[0]);
            }
        }
        return new AnswerJudgments(verdicts, CorpusDigests.sha256(tsv), origin);
    }

    /** Judgments with no verdict at all, for tests and the first, unjudged run. */
    static AnswerJudgments none() {
        return new AnswerJudgments(Map.of(), CorpusDigests.sha256(HEADER + "\n"), "none");
    }

    /** Returns the SHA-256 of the normalized judgments text. */
    String hash() {
        return hash;
    }

    /** Returns where the judgments were loaded from. */
    String origin() {
        return origin;
    }

    /** Returns every key, for the stale-entry check. */
    Collection<Key> keys() {
        return verdicts.keySet();
    }

    /** Returns the verdict of {@code key}. */
    Optional<Verdict> verdict(Key key) {
        return Optional.ofNullable(verdicts.get(key));
    }

    /** Returns the canonical answer: each claim as {@code text<TAB>citations}, claims separated by newlines. */
    static String canonical(List<ClaimRow> claims) {
        return claims.stream().map(claim -> claim.text() + "\t" + claim.citations().stream()
                        .map(span -> span.citation().render()).collect(Collectors.joining("|")))
                .collect(Collectors.joining("\n"));
    }

    /**
     * One row the reviewer still has to judge.
     *
     * @param key the missing item
     * @param proposal what the deterministic rules suggest (the matcher's verdict on an edge), or empty
     * @param context the query text, the claim and the relevant key claim, to judge without opening other files
     */
    record Missing(Key key, String proposal, String context) {
    }

    /** Renders the template of {@code missing}: the judgment header plus {@code proposal} and {@code context}. */
    static String template(List<Missing> missing) {
        var tsv = new StringBuilder("# " + VERSION + " template: fill verdict, reason, reviewer and draftedBy, then"
                + " drop the proposal and context columns\n");
        tsv.append(HEADER).append("\tproposal\tcontext\n");
        var rows = new ArrayList<>(missing);
        for (var row : rows) {
            tsv.append(String.join("\t", row.key().queryId(), QueryText.escape(row.key().answer()), row.key().item(),
                    "", "", "", "", row.proposal(), QueryText.escape(row.context()))).append('\n');
        }
        return tsv.toString();
    }
}
