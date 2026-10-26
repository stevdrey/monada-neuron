package monada.neuron.routing.features;

import monada.neuron.routing.RoutingPolicy;
import monada.neuron.routing.TrustedRoutingProfile;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureReadinessBoundaryTest {

    private static final String SENTENCE = "Diagnostics are information only: they are never eligibility, readiness,"
            + " authentication or authorization, and no routing decision reads them.";

    private static final List<String> FORBIDDEN = List.of("ready", "allowed", "authorized", "authenticated",
            "granted", "pass");

    private static final List<Class<?>> TOP_LEVEL = List.of(TaskFeatureReadiness.class,
            FeatureReadinessAssessment.class, FieldDiagnostic.class, FieldStatus.class, DiagnosticSeverity.class);

    private static List<Class<?>> allTypes() {
        List<Class<?>> types = new ArrayList<>(TOP_LEVEL);
        for (Class<?> type : TOP_LEVEL) {
            types.addAll(List.of(type.getDeclaredClasses()));
        }
        return types;
    }

    @Test
    void coversSevenTypes() {
        assertEquals(7, allTypes().size(), allTypes().toString());
    }

    @Test
    void onlyBooleanWithoutArgumentsIsTheRequiredEcho() throws NoSuchMethodException {
        List<Method> booleans = new ArrayList<>();
        for (Class<?> type : allTypes()) {
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == boolean.class) {
                    booleans.add(method);
                }
            }
        }

        assertEquals(List.of(FieldDiagnostic.class.getMethod("required")), booleans);
    }

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

    @Test
    void assessTakesNoRoutingType() throws NoSuchMethodException {
        Method assess = TaskFeatureReadiness.class.getMethod("assess", TaskFeatures.class, String.class,
                Set.class);

        for (Class<?> parameter : assess.getParameterTypes()) {
            for (Class<?> routing : List.of(RoutingRequest.class, RouteCatalog.class, RoutingPolicy.class,
                    TrustedRoutingProfile.class)) {
                assertFalse(routing.isAssignableFrom(parameter), parameter + " is a routing type");
            }
        }
    }

    @Test
    void everyTypeCarriesTheNonAuthoritySentenceOnceInItsOwnJavadoc() throws IOException {
        Map<Class<?>, Integer> typesPerFile = new HashMap<>();
        for (Class<?> type : allTypes()) {
            Class<?> topLevel = type.getEnclosingClass() == null ? type : type.getEnclosingClass();
            String source = Files.readString(Path.of("src/main/java/monada/neuron/routing/features",
                    topLevel.getSimpleName() + ".java"));

            assertEquals(1, occurrences(javadocText(typeJavadoc(source, type.getSimpleName()))),
                    type.getSimpleName());
            typesPerFile.merge(topLevel, 1, Integer::sum);
        }
        for (Map.Entry<Class<?>, Integer> entry : typesPerFile.entrySet()) {
            String source = Files.readString(Path.of("src/main/java/monada/neuron/routing/features",
                    entry.getKey().getSimpleName() + ".java"));
            assertEquals(entry.getValue(), occurrences(javadocText(source)), entry.getKey().getSimpleName());
        }
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
