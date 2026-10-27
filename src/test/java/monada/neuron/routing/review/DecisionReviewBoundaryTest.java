package monada.neuron.routing.review;

import monada.neuron.routing.catalog.EligibilityReason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static monada.neuron.routing.review.ReviewFixtures.A;
import static monada.neuron.routing.review.ReviewFixtures.B;
import static monada.neuron.routing.review.ReviewFixtures.exclusion;
import static monada.neuron.routing.review.ReviewFixtures.noEligibleRoute;
import static monada.neuron.routing.review.ReviewFixtures.selected;
import static monada.neuron.routing.review.ReviewFixtures.tradeoff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionReviewBoundaryTest {

    private static final String SENTENCE = "A review is information only: it is never eligibility, readiness,"
            + " authentication or authorization, and the selected route stays advice that the host must recheck"
            + " before execution.";

    private static final List<String> FORBIDDEN = List.of("ready", "allowed", "authorized", "authenticated",
            "granted", "approved", "safe");

    private static final Path SOURCES = Path.of("src/main/java/monada/neuron/routing/review");

    private static final List<Class<?>> TOP_LEVEL = List.of(DecisionReview.class, ExclusionGroup.class,
            ReviewNotice.class);

    private static List<Class<?>> allTypes() {
        List<Class<?>> types = new ArrayList<>(TOP_LEVEL);
        for (Class<?> type : TOP_LEVEL) {
            types.addAll(List.of(type.getDeclaredClasses()));
        }
        return types;
    }

    // covers: AC-9, AC-10
    @Test
    void coversSixTypes() {
        assertEquals(6, allTypes().size(), allTypes().toString());
    }

    // covers: AC-9
    @Test
    void onlyBooleansWithoutArgumentsEchoDecisionFlags() throws NoSuchMethodException {
        Set<Method> booleans = new HashSet<>();
        for (Class<?> type : allTypes()) {
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == boolean.class) {
                    booleans.add(method);
                }
            }
        }

        assertEquals(Set.of(DecisionReview.Selected.class.getMethod("overflowUsed"),
                DecisionReview.Selected.class.getMethod("rankingTruncated"),
                DecisionReview.Abstain.class.getMethod("rankingTruncated")), booleans);
    }

    // covers: AC-9
    @Test
    void noPublicNameReadsAsAGrant() {
        List<String> names = new ArrayList<>();
        for (Class<?> type : allTypes()) {
            names.add(type.getSimpleName());
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    names.add(method.getName());
                }
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    names.add(component.getName());
                }
            }
            if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) {
                    names.add(((Enum<?>) constant).name());
                }
            }
        }

        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN) {
                assertFalse(lower.contains(forbidden), name + " contains " + forbidden);
            }
        }
    }

    // covers: AC-10
    @Test
    void everyTypeCarriesTheNonAuthoritySentenceOnceInItsOwnJavadoc() throws IOException {
        Map<Class<?>, Integer> typesPerFile = new HashMap<>();
        for (Class<?> type : allTypes()) {
            Class<?> topLevel = type.getEnclosingClass() == null ? type : type.getEnclosingClass();
            String source = Files.readString(SOURCES.resolve(topLevel.getSimpleName() + ".java"));

            assertEquals(1, occurrences(javadocText(typeJavadoc(source, type.getSimpleName()))),
                    type.getSimpleName());
            typesPerFile.merge(topLevel, 1, Integer::sum);
        }
        for (Map.Entry<Class<?>, Integer> entry : typesPerFile.entrySet()) {
            String source = Files.readString(SOURCES.resolve(entry.getKey().getSimpleName() + ".java"));
            assertEquals(entry.getValue(), occurrences(javadocText(source)), entry.getKey().getSimpleName());
        }
    }

    // covers: AC-10
    @Test
    void packageExampleSwitchesOverEveryReviewAndImportsNothingElse() throws IOException {
        String source = Files.readString(SOURCES.resolve("package-info.java"));

        for (Class<?> variant : DecisionReview.class.getPermittedSubclasses()) {
            assertTrue(source.contains("case DecisionReview." + variant.getSimpleName() + " "), variant.getName());
        }
        assertFalse(source.contains("default ->"), "the example switch must be exhaustive without a default");
        for (String outside : List.of("forge.", "javafx", "import ")) {
            assertFalse(source.contains(outside), outside);
        }
    }

    // covers: AC-10
    @Test
    void packageExampleMatchesTheCompiledMirrorBelow() throws IOException {
        String example = javadocText(Files.readString(SOURCES.resolve("package-info.java")));

        for (String line : EXAMPLE_CASES) {
            assertTrue(example.contains(line), "package-info no longer shows: " + line);
        }
        assertEquals("advice: route-00 via TIER", headline(DecisionReview.from(selected(List.of(A, B), List.of()))));
        assertEquals("advice: route-00 via only eligible route",
                headline(DecisionReview.from(ReviewFixtures.sole(A, List.of()))));
        assertEquals("no advice: POLICY_TRADEOFF_UNRESOLVED",
                headline(DecisionReview.from(tradeoff(List.of(A, B), List.of()))));
        assertEquals("no eligible route, 1 reasons", headline(DecisionReview.from(
                noEligibleRoute(List.of(exclusion(A, EligibilityReason.ROUTE_UNAVAILABLE))))));
    }

    /** The {@code case} lines of the {@code package-info} example, joined as {@link #javadocText} joins them. */
    private static final List<String> EXAMPLE_CASES = List.of(
            "case DecisionReview.Selected selected -> \"advice: \" + selected.route().routeId() + \" via \""
                    + " + selected.decisiveRule().map(Enum::name).orElse(\"only eligible route\");",
            "case DecisionReview.Abstain abstain -> \"no advice: \" + abstain.reason();",
            "case DecisionReview.NoEligibleRoute none -> \"no eligible route, \" + none.exclusionGroups().size()"
                    + " + \" reasons\";");

    /** The switch of the {@code package-info} example, copied exactly; {@link #EXAMPLE_CASES} pins the copy. */
    private static String headline(DecisionReview review) {
        return switch (review) {
            case DecisionReview.Selected selected -> "advice: " + selected.route().routeId() + " via "
                    + selected.decisiveRule().map(Enum::name).orElse("only eligible route");
            case DecisionReview.Abstain abstain -> "no advice: " + abstain.reason();
            case DecisionReview.NoEligibleRoute none -> "no eligible route, " + none.exclusionGroups().size() + " reasons";
        };
    }

    /** Returns the Javadoc block that directly precedes the declaration of {@code name}. */
    private static String typeJavadoc(String source, String name) {
        Matcher declaration = Pattern.compile("(?m)^[ \\t]*(?:public\\s+|final\\s+|sealed\\s+)*"
                + "(?:record|interface|class|enum)\\s+" + name + "\\b").matcher(source);
        assertTrue(declaration.find(), "no declaration of " + name);
        String before = source.substring(0, declaration.start()).stripTrailing();
        assertTrue(before.endsWith("*/"), name + " has no Javadoc directly above its declaration");
        return before.substring(before.lastIndexOf("/**"));
    }

    /** Joins Javadoc lines without their leading {@code *}, so a wrapped sentence still matches. */
    private static String javadocText(String source) {
        return source.replaceAll("(?m)^\\s*\\*\\s?", " ").replaceAll("\\s+", " ");
    }

    private static int occurrences(String text) {
        int count = 0;
        for (int at = text.indexOf(SENTENCE); at >= 0; at = text.indexOf(SENTENCE, at + 1)) {
            count++;
        }
        return count;
    }
}
