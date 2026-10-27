package monada.neuron.routing.comparison;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A preview reaches no state, credit, memory, receipt or disposition type and names no spend, quality or permission. */
class ComparisonBoundaryTest {

    private static final List<String> BANNED_PACKAGES = List.of("monada.neuron.routing.adaptation",
            "monada.neuron.routing.feedback", "monada.neuron.routing.outcome", "monada.neuron.routing.receipt",
            "monada.neuron.routing.disposition", "monada.neuron.resonance");

    private static final List<String> FORBIDDEN = List.of("authoriz", "approv", "grant", "permit", "apply", "applied",
            "bill", "cost", "price", "spend", "saving", "improv", "better", "worse", "quality", "credit", "feedback",
            "reward", "ledger");

    private static final List<Class<?>> TOP_LEVEL = List.of(EligibilityChange.class, PreviewNotice.class,
            RouteChange.class, RouteStanding.class, RoutingScenario.class, ScenarioComparator.class,
            ScenarioComparison.class, ScenarioDelta.class, ScenarioDifference.class);

    private static final Path SOURCES = Path.of("src/main/java/monada/neuron/routing/comparison");

    private static List<Class<?>> publicTypes() {
        List<Class<?>> types = new ArrayList<>(TOP_LEVEL);
        for (Class<?> type : TOP_LEVEL) {
            for (Class<?> nested : type.getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers())) {
                    types.add(nested);
                }
            }
        }
        return types;
    }

    @Test
    void coversEveryPublicTypeOfThePackage() throws IOException {
        assertEquals(List.of("Compared", "EligibilityChange", "Eligible", "Excluded", "Incomparable", "NotInCatalog",
                "PreviewNotice", "RouteChange", "RouteStanding", "RoutingScenario", "ScenarioComparator",
                "ScenarioComparison", "ScenarioDelta", "ScenarioDifference"),
                publicTypes().stream().map(Class::getSimpleName).sorted().toList());
        List<String> files;
        try (var listing = Files.list(SOURCES)) {
            files = listing.map(path -> path.getFileName().toString()).sorted().toList();
        }
        List<String> expected = new ArrayList<>(TOP_LEVEL.stream().map(type -> type.getSimpleName() + ".java").toList());
        expected.add("package-info.java");
        assertEquals(expected.stream().sorted().toList(), files);
    }

    // covers: AC-5
    @Test
    void noSourceFileImportsStateCreditMemoryReceiptOrDispositionPackages() throws IOException {
        List<Path> sources;
        try (var listing = Files.list(SOURCES)) {
            sources = listing.sorted().toList();
        }
        for (Path source : sources) {
            for (String line : Files.readAllLines(source)) {
                if (!line.startsWith("import ")) {
                    continue;
                }
                for (String banned : BANNED_PACKAGES) {
                    assertFalse(line.contains(banned), source.getFileName() + ": " + line);
                }
            }
        }
    }

    // covers: AC-5
    @Test
    void noPublicNameClaimsSpendQualityOrPermission() {
        List<String> names = new ArrayList<>();
        for (Class<?> type : publicTypes()) {
            names.add(type.getSimpleName());
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    names.add(method.getName());
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())) {
                    names.add(field.getName());
                }
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    names.add(component.getName());
                }
            }
        }
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String word : FORBIDDEN) {
                assertFalse(lower.contains(word), name + " contains " + word);
            }
        }
    }

    // covers: AC-5
    @Test
    void theComparatorKeepsNoStateBesidesTheTrustedAdvice() {
        Field[] fields = ScenarioComparator.class.getDeclaredFields();
        assertEquals(1, fields.length);
        assertEquals("TrustedRoutingAdvice", fields[0].getType().getSimpleName());
        for (Field field : fields) {
            assertFalse(Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()));
        }
    }
}
