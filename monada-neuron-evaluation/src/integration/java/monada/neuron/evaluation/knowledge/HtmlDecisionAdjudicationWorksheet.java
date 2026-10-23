package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlDecisionAdjudication.Item;
import monada.neuron.evaluation.knowledge.HtmlDecisionGold.Gold;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * CLI that writes the hand-off for the independent adjudication of the decision gold labels (Issue #152).
 *
 * <p>Outputs in {@code --output-dir} (default {@code build/reports/html-decision-adjudication}):
 * {@code worksheet.md} (the reviewer instructions, every case with its current label and the text of its cited spans,
 * then every corpus chunk) and {@code adjudication-template.tsv}. When an adjudication is bundled and changes a label,
 * it also writes {@code gold-v2.tsv}, the {@value HtmlDecisionGold#REVISION_VERSION} file to bundle unchanged.
 *
 * <p>The worksheet deliberately holds no rule of {@value DecisionRuleBaseline#VERSION}, no rule reason and no system
 * output, so the reviewer judges each label from the case and the corpus alone. Exit status: {@code 0} written,
 * {@code 2} invalid usage or rejected input.
 */
public final class HtmlDecisionAdjudicationWorksheet {

    static final String WORKSHEET = "worksheet.md";
    static final String TEMPLATE = "adjudication-template.tsv";
    static final String REVISION = "gold-v2.tsv";

    private static final String USAGE = "Usage: [--output-dir <dir>]";

    private HtmlDecisionAdjudicationWorksheet() {
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
        Path outputDir;
        try {
            outputDir = outputDir(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        List<Path> written;
        try {
            written = write(outputDir, files());
        } catch (IngestionInputException | CorpusValidationException e) {
            System.err.println("Adjudication input rejected: " + e.getMessage());
            System.exit(2);
            return;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the adjudication hand-off", e);
        }
        System.out.println("Adjudication hand-off saved to:");
        written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
    }

    /**
     * Writes {@code files} into {@code outputDir}. Every output name, {@code gold-v2.tsv} included, is reserved and
     * removed first, so a revision left by an earlier run never survives a run that writes none.
     */
    static List<Path> write(Path outputDir, Map<String, String> files) throws IOException {
        HtmlKnowledgeIngestionRunner.prepareOutput(outputDir, List.of(outputDir.resolve(WORKSHEET),
                outputDir.resolve(TEMPLATE), outputDir.resolve(REVISION)));
        var written = new ArrayList<Path>();
        for (var file : files.entrySet()) {
            written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(file.getKey()), file.getValue()));
        }
        return written;
    }

    static Path outputDir(String[] args) {
        var outputDir = Path.of("build/reports/html-decision-adjudication");
        for (var i = 0; i < args.length; i++) {
            if (!args[i].equals("--output-dir") || i + 1 >= args.length) {
                throw new IllegalArgumentException("unknown or incomplete argument: " + args[i]);
            }
            outputDir = Path.of(args[++i]);
        }
        return outputDir;
    }

    /** Returns the hand-off file contents by file name, built from the bundled fixtures. */
    static Map<String, String> files() {
        var corpus = HtmlKnowledgeCorpus.bundledWithDecisionSupplement();
        var cases = HtmlDecisionCases.bundled();
        var gold = HtmlDecisionGold.bundled(cases, corpus);
        var files = new TreeMap<String, String>();
        files.put(WORKSHEET, worksheet(cases, gold, corpus));
        files.put(TEMPLATE, HtmlDecisionAdjudication.template(cases));
        HtmlDecisionAdjudication.bundled(cases, gold, corpus).filter(adjudication -> adjudication.changes() > 0)
                .ifPresent(adjudication -> files.put(REVISION, adjudication.revisionText(cases)));
        return files;
    }

    static String worksheet(HtmlDecisionCases cases, HtmlDecisionGold gold, HtmlKnowledgeCorpus corpus) {
        var md = new StringBuilder("# Independent adjudication of `" + HtmlDecisionGold.VERSION + "`\n\n");
        md.append(instructions(cases, gold)).append('\n');
        md.append("## Cases\n");
        for (var decisionCase : cases.cases()) {
            var label = gold.of(decisionCase.caseId());
            md.append("\n### `").append(decisionCase.caseId()).append("` (family `")
                    .append(decisionCase.family().label()).append("`)\n\n");
            md.append("Requirement: ").append(decisionCase.requirement()).append("\n\n");
            var cells = gold.cellsOf(decisionCase.caseId());
            md.append("Candidates: ").append(code(decisionCase.candidates())).append("\n\n");
            md.append("Stated constraints:\n\n");
            for (var constraint : decisionCase.family().constraints()) {
                md.append("- `").append(constraint.label()).append("` = `")
                        .append(decisionCase.value(constraint).label()).append("` (").append(constraint.question())
                        .append(")\n");
            }
            md.append("\nCurrent label:\n\n");
            md.append("| item | current value |\n| :--- | :--- |\n");
            for (var item : Item.values()) {
                md.append("| `").append(item.label()).append("` | ").append(cell(value(label, item, cells)))
                        .append(" |\n");
            }
            md.append("\nCited spans:\n\n");
            for (var span : label.evidence()) {
                md.append("- `").append(span.chunkId()).append("` [").append(span.start()).append(", ")
                        .append(span.end()).append("): \"").append(HtmlKnowledgeQuerySet.spanText(
                                corpus.chunk(span.chunkId()).orElseThrow().text(), span)).append("\"\n");
            }
        }
        md.append("\n## Corpus `").append(corpus.version()).append("`\n\n");
        md.append("Every chunk, verbatim. A new or changed span must quote one of these texts exactly.\n");
        for (var chunk : corpus.chunks()) {
            md.append("\n### `").append(chunk.chunkId()).append("`\n\n");
            md.append("Topic `").append(chunk.topic()).append("`, section \"").append(chunk.section())
                    .append("\", source ").append(chunk.source()).append(".\n\n");
            md.append("```text\n").append(chunk.text()).append("\n```\n");
        }
        return md.toString();
    }

    private static String instructions(HtmlDecisionCases cases, HtmlDecisionGold gold) {
        var drafters = new TreeSet<>(gold.drafters());
        drafters.add(HtmlDecisionAdjudication.RULE_AUTHOR);
        return """
                ## Instructions for the reviewer

                You are the independent reviewer of the gold labels of a small HTML design-decision benchmark. The
                labels were drafted by the author of the decision rules being evaluated; your verdicts make them
                independent of that author. You must not be, or act for, any of: %s.

                For each of the %d cases below, judge the current label from the requirement, the stated constraints,
                the candidates and the corpus only. You are not shown the decision rules or any system output, and
                you should not seek them. Judge every item of every case:

                - `expected`: `DECIDE` (one or more acceptable choices, stated without conditions), `CONDITIONAL`
                  (the answer depends on an assumption the requirement leaves open) or `ABSTAIN` (the requirement
                  does not determine a choice; the right answer is to ask).
                - `acceptable`: the choices credited as correct (`|`-separated, `-` for none). For `CONDITIONAL` they
                  are exactly the choices of `assumptions`; for `ABSTAIN` there are none.
                - `ineligible`: the choices that are wrong for this requirement; disjoint from `acceptable`. A
                  candidate may be in neither list.
                - `assumptions`: the `choice@assumption` options a conditional answer must state; `-` unless
                  `CONDITIONAL`.
                - `permissions`: `none` (grant no extra permission) or `requested` (grant exactly the features the
                  requirement asks for, nothing more).
                - `rationale`: the expected human rationale. Change it if it is wrong, unsupported or incomplete.
                - `evidenceSpans`: the exact corpus passages that support the label. Change them if a span does not
                  support the label, a needed fact is not cited, or a span cites more than it needs.

                Fill `adjudication-template.tsv` (tab-separated, one row per case and item, no tab or line break
                inside a cell):

                - `verdict`: `agree` or `change`.
                - `proposed`: `-` for `agree`. For `change`, the complete new value in the syntax above. For
                  `evidenceSpans`, list every span of the new value as `chunkId "exact quote"`, `|`-separated, where
                  the quote is copied verbatim from that chunk's text below, occurs exactly once in it and holds no
                  `|`; offsets are computed for you.
                - `reason`: one or two sentences, for `agree` as well as for `change`.
                - `reviewer`: your exact model or person identifier, the same on every row.

                A change must keep the label consistent: every choice must be a candidate of its case, and the
                `expected` outcome, `acceptable` and `assumptions` must agree as described above. Do not change the
                cases or the corpus; if a case itself is flawed, say so in the reason.
                """.formatted(String.join(", ", drafters), cases.cases().size());
    }

    /** Returns the current value of {@code item}; lists exactly as written in {@code gold.tsv}, in file order. */
    private static String value(Gold label, Item item, List<String> cells) {
        return switch (item) {
            case EXPECTED -> "`" + label.expected() + "`";
            case ACCEPTABLE, INELIGIBLE, ASSUMPTIONS -> "`" + cells.get(item.column()) + "`";
            case PERMISSIONS -> "`" + label.permissions().label() + "`";
            case RATIONALE -> label.rationale();
            case EVIDENCE_SPANS -> label.evidence().size() + " span(s), listed below";
        };
    }

    private static String code(List<String> values) {
        return values.isEmpty() ? "`-`" : "`" + String.join("|", values) + "`";
    }

    /** Escapes {@code |} so a value never splits its Markdown table row (also inside a code span, per GFM). */
    private static String cell(String value) {
        return value.replace("|", "\\|");
    }
}
