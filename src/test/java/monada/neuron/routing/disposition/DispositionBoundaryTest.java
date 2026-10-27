package monada.neuron.routing.disposition;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.disposition.AdviceDisposition.AlternativeProposed;
import monada.neuron.routing.disposition.AdviceDisposition.Declined;
import monada.neuron.routing.disposition.AdviceDisposition.Deferred;
import monada.neuron.routing.disposition.AdviceDisposition.Followed;
import monada.neuron.routing.disposition.DispositionFixtures.Base;
import monada.neuron.routing.receipt.AdviceReceipt;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static monada.neuron.routing.disposition.DispositionFixtures.SUB_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispositionBoundaryTest {

    private static final String SENTENCE = "A disposition records what a host did with advice before anything ran: it"
            + " is not execution, not authorization and not quality evidence, and it awards no credit.";

    private static final List<String> FORBIDDEN = List.of("execut", "authoriz", "authentic", "grant", "approv",
            "permit", "permission", "accept", "invoc", "invok", "credit", "ledger", "evidence");

    private static final List<String> BANNED_PACKAGES = List.of("monada.neuron.routing.adaptation",
            "monada.neuron.routing.feedback", "monada.neuron.routing.outcome", "monada.neuron.routing.review",
            "monada.neuron.resonance");

    private static final List<Class<?>> TOP_LEVEL = List.of(AdviceDisposition.class, AdviceReference.class,
            AdviceKind.class, AlternativeStanding.class, DispositionResult.class, DispositionRejection.class);

    private static final Path SOURCES = Path.of("src/main/java/monada/neuron/routing/disposition");

    private static final String HEX = "[0-9a-f]{64}";

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

    // covers: AC-1
    @Test
    void coversEveryPublicTypeOfThePackage() throws IOException {
        assertEquals(12, publicTypes().size(), publicTypes().toString());
        List<String> files;
        try (var listing = Files.list(SOURCES)) {
            files = listing.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("AdviceDisposition.java", "AdviceKind.java", "AdviceReference.java",
                "AlternativeStanding.java", "DispositionRejection.java", "DispositionResult.java",
                "package-info.java"), files);
    }

    // covers: AC-7
    @Test
    void noSourceFileImportsStateCreditOrMemoryPackages() throws IOException {
        List<Path> sources;
        try (var listing = Files.list(SOURCES)) {
            sources = listing.sorted().toList();
        }
        assertEquals(7, sources.size());

        for (Path source : sources) {
            for (String line : Files.readAllLines(source)) {
                if (line.startsWith("import ")) {
                    for (String banned : BANNED_PACKAGES) {
                        assertFalse(line.contains(banned), source.getFileName() + ": " + line);
                    }
                }
            }
        }
    }

    // covers: AC-7
    @Test
    void noPublicSignatureMentionsAStateCreditOrMemoryType() {
        List<Type> mentioned = new ArrayList<>();
        for (Class<?> type : publicTypes()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    mentioned.add(method.getGenericReturnType());
                    mentioned.addAll(List.of(method.getGenericParameterTypes()));
                }
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    mentioned.add(component.getGenericType());
                }
            }
        }
        assertTrue(mentioned.stream().anyMatch(t -> t instanceof ParameterizedType), "generic components expected");

        List<String> names = new ArrayList<>();
        for (Type type : mentioned) {
            collectTypeNames(type, names);
        }
        for (String name : names) {
            for (String banned : BANNED_PACKAGES) {
                assertFalse(name.startsWith(banned), name);
            }
        }
        assertTrue(names.contains(DecisionRef.class.getName()));
        assertTrue(names.contains("monada.neuron.routing.catalog.RouteKey"), names.toString());
    }

    private static void collectTypeNames(Type type, List<String> into) {
        switch (type) {
            case ParameterizedType parameterized -> {
                collectTypeNames(parameterized.getRawType(), into);
                for (Type argument : parameterized.getActualTypeArguments()) {
                    collectTypeNames(argument, into);
                }
            }
            case Class<?> raw -> into.add(raw.getName());
            default -> into.add(type.getTypeName());
        }
    }

    // covers: AC-7
    @Test
    void noPublicMethodAnswersYesOrNoWithoutAnArgument() {
        List<Method> booleans = new ArrayList<>();
        for (Class<?> type : publicTypes()) {
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == boolean.class) {
                    booleans.add(method);
                }
            }
        }

        assertEquals(List.of(), booleans);
    }

    // covers: AC-8
    @Test
    void noPublicNameClaimsMoreThanIntent() {
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
            for (String forbidden : FORBIDDEN) {
                assertFalse(lower.contains(forbidden), name + " contains " + forbidden);
            }
        }
        assertTrue(names.containsAll(List.of("followed", "alternative", "deferred", "declined", "refersTo",
                "HOST_CLAIM", "REFERENCE_MISMATCH", "CONTRACT_VERSION")), names.toString());
    }

    // covers: AC-8
    @Test
    void toStringShowsOnlyKindOrdinalDigestAndStanding() {
        for (Base base : Base.values()) {
            AdviceReceipt receipt = base.receipt();
            String digest = receipt.sha256();
            String kind = AdviceReference.of(receipt).kind().name();
            String fields = "kind=" + kind + ", requestOrdinal=101, receiptSha256=" + digest;
            assertTrue(digest.matches(HEX));

            assertEquals("AdviceReference[" + fields + "]", AdviceReference.of(receipt).toString());
            assertEquals("Deferred[" + fields + "]", bound(AdviceDisposition.deferred(receipt, base.ref())).toString());
            assertEquals("Declined[" + fields + "]", bound(AdviceDisposition.declined(receipt, base.ref())).toString());
            var proposed = assertInstanceOf(AlternativeProposed.class,
                    bound(AdviceDisposition.alternative(receipt, base.ref(), SUB_B)));
            assertEquals("AlternativeProposed[" + fields + ", standing=" + proposed.standing() + "]",
                    proposed.toString());
            if (base.isSelected()) {
                assertEquals("Followed[" + fields + "]", bound(AdviceDisposition.followed(receipt, base.ref())).toString());
            }
            for (String token : List.of("scope-demo", "task-17", "exec-1", "att-1", "s-impl", "sub-a", "sub-b",
                    "ex-gone", "cat-demo-7")) {
                assertFalse(proposed.toString().contains(token), token);
                assertFalse(proposed.advice().toString().contains(token), token);
            }
        }
        assertEquals("Bound[disposition=Deferred[kind=SELECTED, requestOrdinal=101, receiptSha256="
                + Base.SELECTED.receipt().sha256() + "]]",
                AdviceDisposition.deferred(Base.SELECTED.receipt(), Base.SELECTED.ref()).toString());
        assertEquals("Rejected[reason=ADVICE_NOT_SELECTED]",
                AdviceDisposition.followed(Base.NO_ELIGIBLE_ROUTE.receipt(), Base.NO_ELIGIBLE_ROUTE.ref()).toString());
    }

    private static AdviceDisposition bound(DispositionResult result) {
        return assertInstanceOf(DispositionResult.Bound.class, result).disposition();
    }

    // covers: AC-8
    @Test
    void everyTypeCarriesTheSentenceOnceInItsOwnJavadoc() throws IOException {
        Map<Class<?>, Integer> typesPerFile = new HashMap<>();
        for (Class<?> type : publicTypes()) {
            Class<?> topLevel = type.getEnclosingClass() == null ? type : type.getEnclosingClass();
            String source = Files.readString(SOURCES.resolve(topLevel.getSimpleName() + ".java"));

            assertEquals(1, occurrences(javadocText(typeJavadoc(source, type.getSimpleName()))),
                    type.getSimpleName());
            typesPerFile.merge(topLevel, 1, Integer::sum);
        }
        assertEquals(5, typesPerFile.get(AdviceDisposition.class));
        assertEquals(3, typesPerFile.get(DispositionResult.class));
        for (Map.Entry<Class<?>, Integer> entry : typesPerFile.entrySet()) {
            String source = Files.readString(SOURCES.resolve(entry.getKey().getSimpleName() + ".java"));
            assertEquals(entry.getValue(), occurrences(javadocText(source)), entry.getKey().getSimpleName());
        }
        assertEquals(List.of(Followed.class, AlternativeProposed.class, Deferred.class, Declined.class),
                List.of(AdviceDisposition.class.getPermittedSubclasses()));
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
