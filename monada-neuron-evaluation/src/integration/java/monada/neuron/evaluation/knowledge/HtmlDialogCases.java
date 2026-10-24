package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Constraint;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Family;
import monada.neuron.evaluation.knowledge.HtmlDecisionCases.Tri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The validated, versioned multi-turn dialog set {@value #VERSION} (Issue #113): short HTML design dialogs whose turns
 * state typed constraint facts or revisions, over the families, constraints and candidates of
 * {@link HtmlDecisionCases}.
 *
 * <p>This is the <b>system-visible</b> half of the fixture: it holds what a host would know about the conversation and
 * no gold label ({@link HtmlDialogGold} holds those, read only by the scorer). Dialogs are exposed in {@code dialogId}
 * order and turns in turn order, which is also the run order. Load-time invariants: bounded dialog and turn counts,
 * turns numbered {@code 1..n} without gaps, bounded one-line utterances, statements and candidates drawn from the
 * stated or carried family, and {@code carried}/{@code antecedent} naming earlier turns only. A violation throws
 * {@link CorpusValidationException}.
 */
final class HtmlDialogCases {

    /** Version of the authored dialog set. */
    static final String VERSION = "html-dialog-v1";

    /** Header row of {@code turns.tsv}. */
    static final String HEADER = "dialogId\tturn\tfamily\tutterance\tstatements\tcandidates\tcarried\tantecedent"
            + "\tunmodeled";

    static final int MIN_DIALOGS = 5;
    static final int MAX_DIALOGS = 8;

    /** Most turns of one dialog. */
    static final int MAX_TURNS = 4;

    /** Longest utterance, in code points; the same bound as a single-turn requirement. */
    static final int MAX_UTTERANCE_CODE_POINTS = HtmlDecisionCases.MAX_REQUIREMENT_CODE_POINTS;

    /** The {@code carried} value meaning every earlier turn of the dialog. */
    static final String ALL_EARLIER = "*";

    private static final String FILE = "turns.tsv";
    private static final String RESOURCE = "/" + VERSION + "/" + FILE;
    private static final Pattern DIALOG_ID = Pattern.compile("dialog:[a-z]+(?:-[a-z]+)*");
    private static final Pattern UNMODELED = Pattern.compile("[a-z][A-Za-z]*");

    /** How a statement relates to what the dialog said before. */
    enum Kind {
        /** A new fact; contradicting an earlier known value is a contradiction, not a revision. */
        SET,
        /** An explicit revision of an earlier value. */
        CHANGE;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One typed constraint statement of a turn.
     *
     * @param kind set or change
     * @param constraint the constraint
     * @param value the stated value
     */
    record Statement(Kind kind, Constraint constraint, Tri value) {

        Statement {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(constraint, "constraint must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }

        String render() {
            return kind.label() + ":" + constraint.label() + "=" + value.label();
        }
    }

    /**
     * One user turn as the host knows it.
     *
     * @param dialogId the dialog
     * @param turn the 1-based turn number
     * @param family the family the turn names, or empty for an elliptical turn
     * @param utterance the user's text
     * @param statements the typed constraint statements, in stated order
     * @param candidates the candidates the turn names, or empty to carry them
     * @param carried the earlier turns the host supplies, in turn order
     * @param antecedent the earlier turn an ellipsis refers to, or empty
     * @param unmodeled requirement keys outside the html-decision-v1 rubric
     */
    record Turn(String dialogId, int turn, Optional<Family> family, String utterance, List<Statement> statements,
            List<String> candidates, List<Integer> carried, Optional<Integer> antecedent, List<String> unmodeled) {

        Turn {
            Objects.requireNonNull(dialogId, "dialogId must not be null");
            Objects.requireNonNull(family, "family must not be null");
            Objects.requireNonNull(utterance, "utterance must not be null");
            Objects.requireNonNull(antecedent, "antecedent must not be null");
            statements = List.copyOf(statements);
            candidates = List.copyOf(candidates);
            carried = List.copyOf(carried);
            unmodeled = List.copyOf(unmodeled);
        }

        /** Returns {@code dialog:<slug>#<turn>}, the id of the turn in every trace and label. */
        String turnId() {
            return HtmlDialogCases.turnId(dialogId, turn);
        }

        /** Renders the statements as the fixture writes them. */
        String renderStatements() {
            return statements.isEmpty() ? "-" : String.join(HtmlDecisionCases.LIST_SEPARATOR,
                    statements.stream().map(Statement::render).toList());
        }
    }

    /**
     * One dialog.
     *
     * @param dialogId {@code dialog:<slug>}
     * @param turns its turns in turn order
     */
    record Dialog(String dialogId, List<Turn> turns) {

        Dialog {
            Objects.requireNonNull(dialogId, "dialogId must not be null");
            turns = List.copyOf(turns);
        }
    }

    private final String version;
    private final List<Dialog> dialogs;
    private final String dialogHash;

    private HtmlDialogCases(String version, List<Dialog> dialogs) {
        this.version = version;
        this.dialogs = List.copyOf(dialogs);
        this.dialogHash = hash(version, this.dialogs);
    }

    /** Returns the id of {@code turn} of {@code dialogId}. */
    static String turnId(String dialogId, int turn) {
        return dialogId + "#" + turn;
    }

    /** Loads and validates the bundled dialog set. */
    static HtmlDialogCases bundled() {
        return parse(VERSION, HtmlKnowledgeCorpus.bundledTable(VERSION, FILE), RESOURCE);
    }

    /** Parses and validates a dialog table, as the loader does. */
    static HtmlDialogCases parse(String version, String text, String origin) {
        var turns = new ArrayList<Turn>();
        for (var row : HtmlKnowledgeCorpus.rows(text, HEADER, origin)) {
            turns.add(turnOf(row));
        }
        return of(version, turns);
    }

    /** Validates already built turns, grouped by dialog. */
    static HtmlDialogCases of(String version, List<Turn> turns) {
        Objects.requireNonNull(version, "version must not be null");
        var origin = "dialog set " + version;
        var byDialog = new LinkedHashMap<String, List<Turn>>();
        for (var turn : turns) {
            if (!DIALOG_ID.matcher(turn.dialogId()).matches()) {
                throw new CorpusValidationException(origin + " " + turn.turnId(), "dialogId must match"
                        + " dialog:<slug>");
            }
            byDialog.computeIfAbsent(turn.dialogId(), key -> new ArrayList<>()).add(turn);
        }
        if (byDialog.size() < MIN_DIALOGS || byDialog.size() > MAX_DIALOGS) {
            throw new CorpusValidationException(origin, byDialog.size() + " dialogs are outside " + MIN_DIALOGS + ".."
                    + MAX_DIALOGS);
        }
        var dialogs = new ArrayList<Dialog>();
        for (var entry : new TreeMap<>(byDialog).entrySet()) {
            var dialogTurns = entry.getValue();
            if (dialogTurns.size() > MAX_TURNS) {
                throw new CorpusValidationException(origin + " " + entry.getKey(), dialogTurns.size()
                        + " turns exceed " + MAX_TURNS);
            }
            Optional<Family> latest = Optional.empty();
            for (var index = 0; index < dialogTurns.size(); index++) {
                var turn = dialogTurns.get(index);
                var where = origin + " " + turn.turnId();
                if (turn.turn() != index + 1) {
                    throw new CorpusValidationException(where, "turns must be numbered 1.." + dialogTurns.size()
                            + " in file order");
                }
                validate(where, turn, turn.family().isPresent() ? turn.family() : latest);
                if (turn.family().isPresent()) {
                    latest = turn.family();
                }
            }
            dialogs.add(new Dialog(entry.getKey(), dialogTurns));
        }
        return new HtmlDialogCases(version, dialogs);
    }

    private static void validate(String where, Turn turn, Optional<Family> family) {
        var utterance = turn.utterance();
        if (utterance.isBlank() || utterance.codePointCount(0, utterance.length()) > MAX_UTTERANCE_CODE_POINTS
                || utterance.contains("\t") || utterance.contains("\n")) {
            throw new CorpusValidationException(where, "utterance must be one non-blank line of at most "
                    + MAX_UTTERANCE_CODE_POINTS + " code points");
        }
        if (turn.carried().stream().anyMatch(earlier -> earlier < 1 || earlier >= turn.turn())
                || turn.antecedent().filter(earlier -> earlier < 1 || earlier >= turn.turn()).isPresent()) {
            throw new CorpusValidationException(where, "carried and antecedent may name earlier turns only");
        }
        if (!turn.statements().isEmpty() || !turn.candidates().isEmpty()) {
            var named = family.orElseThrow(() -> new CorpusValidationException(where, "statements and candidates"
                    + " need a family stated by this or an earlier turn"));
            if (turn.statements().stream().anyMatch(statement -> statement.constraint().family() != named)) {
                throw new CorpusValidationException(where, "every statement must be a constraint of "
                        + named.label());
            }
            var ordered = named.choices().stream().filter(turn.candidates()::contains).toList();
            if (!turn.candidates().isEmpty() && (turn.candidates().size() < 2
                    || !ordered.equals(turn.candidates()))) {
                throw new CorpusValidationException(where, "candidates must be at least two distinct choices of "
                        + named.label() + " in the order " + named.choices());
            }
        }
        if (turn.unmodeled().stream().anyMatch(key -> !UNMODELED.matcher(key).matches())) {
            throw new CorpusValidationException(where, "unmodeled keys must be lowerCamelCase");
        }
    }

    String version() {
        return version;
    }

    /** Returns the dialogs in {@code dialogId} order. */
    List<Dialog> dialogs() {
        return dialogs;
    }

    /** Returns every turn, in dialog then turn order. */
    List<Turn> turns() {
        return dialogs.stream().flatMap(dialog -> dialog.turns().stream()).toList();
    }

    /** Returns the turn with {@code turnId}, or throws. */
    Turn turn(String turnId) {
        return turns().stream().filter(turn -> turn.turnId().equals(turnId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown turn " + turnId));
    }

    /** Returns the canonical {@code dialogHash}. */
    String dialogHash() {
        return dialogHash;
    }

    private static Turn turnOf(HtmlKnowledgeCorpus.Row row) {
        var family = row.optional(2).map(label -> Family.ofLabel(row.origin(), label));
        var statements = new ArrayList<Statement>();
        for (var item : row.list(4)) {
            var colon = item.indexOf(':');
            var parts = colon < 0 ? new String[0] : item.substring(colon + 1).split("=", -1);
            if (colon < 0 || parts.length != 2) {
                throw row.reject("statement '" + item + "' must be set:name=value or change:name=value");
            }
            var kind = switch (item.substring(0, colon)) {
                case "set" -> Kind.SET;
                case "change" -> Kind.CHANGE;
                default -> throw row.reject("statement '" + item + "' must start with set: or change:");
            };
            statements.add(new Statement(kind, Constraint.ofLabel(row.origin(), parts[0]),
                    Tri.ofLabel(row.origin(), parts[1])));
        }
        var turn = row.integer(1, "turn");
        var carriedCell = row.required(6, "carried");
        var carried = new ArrayList<Integer>();
        if (carriedCell.equals(ALL_EARLIER)) {
            for (var earlier = 1; earlier < turn; earlier++) {
                carried.add(earlier);
            }
        } else {
            for (var item : row.list(6)) {
                try {
                    carried.add(Integer.parseInt(item));
                } catch (NumberFormatException e) {
                    throw row.reject("carried must be * or earlier turn numbers");
                }
            }
            if (!carried.equals(carried.stream().sorted().distinct().toList())) {
                throw row.reject("carried turns must be ascending without repeats");
            }
        }
        Optional<Integer> antecedent;
        try {
            antecedent = row.optional(7).map(Integer::parseInt);
        } catch (NumberFormatException e) {
            throw row.reject("antecedent must be a turn number or -");
        }
        return new Turn(row.required(0, "dialogId"), turn, family, row.required(3, "utterance"), statements,
                row.list(5), carried, antecedent, row.list(8));
    }

    private static String hash(String version, List<Dialog> dialogs) {
        var digest = new CanonicalRecordDigest().field(version).field(dialogs.size());
        for (var dialog : dialogs) {
            digest.field(dialog.dialogId()).field(dialog.turns().size());
            for (var turn : dialog.turns()) {
                digest.field(turn.turn()).field(turn.family().map(Family::label).orElse("-"))
                        .field(turn.utterance()).field(turn.renderStatements())
                        .field(String.join(HtmlDecisionCases.LIST_SEPARATOR, turn.candidates()))
                        .field(String.join(HtmlDecisionCases.LIST_SEPARATOR,
                                turn.carried().stream().map(String::valueOf).toList()))
                        .field(turn.antecedent().map(String::valueOf).orElse("-"))
                        .field(String.join(HtmlDecisionCases.LIST_SEPARATOR, turn.unmodeled()));
            }
        }
        return digest.hex();
    }
}
