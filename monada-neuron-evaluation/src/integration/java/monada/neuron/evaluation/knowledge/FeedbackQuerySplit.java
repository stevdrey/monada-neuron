package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.FeedbackArm.KeyStrategy;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.JudgedQuery.Category;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The deterministic calibration / held-out split of the relevance-feedback experiment (Issue #110, split rule
 * {@value #VERSION}). It reads only each query's id, category and text, never a gold label, so held-out labels cannot
 * influence it.
 *
 * <p>Queries are first grouped by their Store feedback key: two queries whose effective keys are equal under any of
 * the experiment's key strategies belong to one group (transitively), so no strategy can carry an event from a
 * calibration query to a held-out query. Blank queries are singleton groups; they never receive events. A group's id
 * is its smallest query id (code point order) and its category is that query's category. Within each category, in
 * declaration order, groups are sorted by id and assigned alternately to calibration and held-out, starting with
 * calibration; a group is never split.
 */
final class FeedbackQuerySplit {

    /** Version of the split rule, part of {@link #splitHash()}. */
    static final String VERSION = "html-kf-split-v1";

    /** Which side of the split a query is on. */
    enum Side {
        CALIBRATION("calibration"),
        HELD_OUT("held-out");

        private final String label;

        Side(String label) {
            this.label = label;
        }

        /** Returns the report label. */
        String label() {
            return label;
        }
    }

    /**
     * One group of queries that share a feedback key under some strategy.
     *
     * @param groupId the smallest member query id
     * @param category the category of the query named by {@code groupId}
     * @param members member query ids in code point order
     * @param side the side every member is on
     */
    record Group(String groupId, Category category, List<String> members, Side side) {

        Group {
            members = List.copyOf(members);
        }
    }

    private final List<JudgedQuery> queries;
    private final List<Group> groups;
    private final Map<String, Side> sides;
    private final Map<String, String> groupOf;

    private FeedbackQuerySplit(List<JudgedQuery> queries, List<Group> groups) {
        this.queries = List.copyOf(queries);
        this.groups = List.copyOf(groups);
        var sides = new LinkedHashMap<String, Side>();
        var groupOf = new HashMap<String, String>();
        for (var group : groups) {
            group.members().forEach(member -> {
                sides.put(member, group.side());
                groupOf.put(member, group.groupId());
            });
        }
        this.sides = Map.copyOf(sides);
        this.groupOf = Map.copyOf(groupOf);
    }

    /** Splits {@code queries} by the rule above; reads only query id, category and text. */
    static FeedbackQuerySplit of(List<JudgedQuery> queries) {
        Objects.requireNonNull(queries, "queries must not be null");
        var ids = queries.stream().map(JudgedQuery::queryId).toList();
        var parent = new HashMap<String, String>();
        ids.forEach(id -> parent.put(id, id));
        var owners = new HashMap<String, String>();
        for (var query : queries) {
            if (query.text().isBlank()) {
                continue;
            }
            for (var strategy : KeyStrategy.values()) {
                var key = strategy.name() + "\u0000" + strategy.effectiveKey(query.text());
                var owner = owners.putIfAbsent(key, query.queryId());
                if (owner != null) {
                    union(parent, owner, query.queryId());
                }
            }
        }
        var members = new TreeMap<String, List<String>>();
        for (var id : ids) {
            members.computeIfAbsent(find(parent, id), root -> new ArrayList<>()).add(id);
        }
        var byId = new HashMap<String, JudgedQuery>();
        queries.forEach(query -> byId.put(query.queryId(), query));
        var byCategory = new EnumMap<Category, List<List<String>>>(Category.class);
        for (var group : members.values()) {
            var sorted = group.stream().sorted().toList();
            byCategory.computeIfAbsent(byId.get(sorted.getFirst()).category(), category -> new ArrayList<>())
                    .add(sorted);
        }
        var groups = new ArrayList<Group>();
        for (var entry : byCategory.entrySet()) {
            var sorted = entry.getValue().stream().sorted(Comparator.comparing(List::getFirst)).toList();
            for (var index = 0; index < sorted.size(); index++) {
                groups.add(new Group(sorted.get(index).getFirst(), entry.getKey(), sorted.get(index),
                        index % 2 == 0 ? Side.CALIBRATION : Side.HELD_OUT));
            }
        }
        return new FeedbackQuerySplit(queries, groups);
    }

    private static String find(Map<String, String> parent, String id) {
        var root = id;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        return root;
    }

    private static void union(Map<String, String> parent, String first, String second) {
        var a = find(parent, first);
        var b = find(parent, second);
        if (!a.equals(b)) {
            // The smaller id becomes the root, so the result does not depend on the order of the unions.
            parent.put(a.compareTo(b) < 0 ? b : a, a.compareTo(b) < 0 ? a : b);
        }
    }

    /** Returns the side of {@code queryId}. */
    Side side(String queryId) {
        var side = sides.get(queryId);
        if (side == null) {
            throw new IllegalArgumentException("query " + queryId + " is not part of the split");
        }
        return side;
    }

    /** Returns the group id of {@code queryId}. */
    String groupOf(String queryId) {
        return groupOf.get(queryId);
    }

    /** Returns the calibration queries in query set order. */
    List<JudgedQuery> calibration() {
        return queries.stream().filter(query -> sides.get(query.queryId()) == Side.CALIBRATION).toList();
    }

    /** Returns the held-out queries in query set order. */
    List<JudgedQuery> heldOut() {
        return queries.stream().filter(query -> sides.get(query.queryId()) == Side.HELD_OUT).toList();
    }

    /** Returns the groups in assignment order: category, then group id. */
    List<Group> groups() {
        return groups;
    }

    /** Returns the groups with more than one member, which some key strategy merged. */
    List<Group> mergedGroups() {
        return groups.stream().filter(group -> group.members().size() > 1).toList();
    }

    /**
     * {@code split.valid}: every query is on exactly one side, no group is split, and every category with at least two
     * queries has queries on both sides. A key group that merges a category's queries can therefore fail it, and that
     * is reported instead of splitting the group.
     */
    Check validity() {
        var problems = new ArrayList<String>();
        if (sides.size() != queries.size()) {
            problems.add(sides.size() + " of " + queries.size() + " queries assigned");
        }
        for (var group : groups) {
            if (group.members().stream().map(sides::get).distinct().count() != 1) {
                problems.add("group " + group.groupId() + " is split");
            }
        }
        var sidesByCategory = new EnumMap<Category, List<Side>>(Category.class);
        queries.forEach(query -> sidesByCategory.computeIfAbsent(query.category(), c -> new ArrayList<>())
                .add(sides.get(query.queryId())));
        for (var entry : sidesByCategory.entrySet()) {
            if (entry.getValue().size() >= 2 && entry.getValue().stream().distinct().count() != 2) {
                problems.add("category " + entry.getKey().label() + " has queries on one side only");
            }
        }
        var merged = mergedGroups();
        return new Check("split.valid", problems.isEmpty(), (problems.isEmpty()
                ? calibration().size() + " calibration and " + heldOut().size() + " held-out queries in "
                        + groups.size() + " key groups; every category with two or more queries is on both sides"
                : String.join("; ", problems))
                + "; merged key groups: " + (merged.isEmpty() ? "none"
                        : merged.stream().map(group -> group.members().toString()).toList()));
    }

    /**
     * Returns the split hash: the {@link CanonicalRecordDigest} of {@value #VERSION}, the query count, then per query in
     * id order its id, side label and group id.
     */
    String splitHash() {
        var digest = new CanonicalRecordDigest().field(VERSION).field(queries.size());
        sides.keySet().stream().sorted().forEach(id -> digest.field(id).field(sides.get(id).label())
                .field(groupOf.get(id)));
        return digest.hex();
    }

    /** Renders one row per query, in query set order: id, category, side and group id. */
    String toTsv() {
        var tsv = new StringBuilder("# split ").append(VERSION).append(' ').append(splitHash()).append('\n')
                .append("queryId\tcategory\tside\tgroupId\n");
        for (var query : queries) {
            tsv.append(query.queryId()).append('\t').append(query.category().label()).append('\t')
                    .append(side(query.queryId()).label()).append('\t').append(groupOf(query.queryId()))
                    .append('\n');
        }
        return tsv.append("end\t").append(queries.size()).append('\n').toString();
    }
}
