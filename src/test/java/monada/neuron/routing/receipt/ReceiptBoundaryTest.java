package monada.neuron.routing.receipt;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.receipt.ReceiptFixtures.Base;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptBoundaryTest {

    private static final String SENTENCE = "A receipt is a canonical copy of advice for byte comparison only: it is not"
            + " proof that anything ran or was accepted, not authentication, not a signature and not a routing ledger"
            + " digest.";

    private static final List<String> FORBIDDEN = List.of("execut", "accept", "authoriz", "authentic", "grant",
            "approv", "verif", "signature", "signed", "ledger");

    private static final List<Class<?>> TOP_LEVEL = List.of(AdviceReceipt.class, DecodeResult.class,
            RejectReason.class);

    private static final Path SOURCES = Path.of("src/main/java/monada/neuron/routing/receipt");

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

    // covers: AC-9
    @Test
    void coversEveryPublicTypeOfThePackage() throws IOException {
        assertEquals(5, publicTypes().size(), publicTypes().toString());
        List<String> files;
        try (var listing = Files.list(SOURCES)) {
            files = listing.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("AdviceReceipt.java", "DecodeResult.java", "ReceiptCodec.java", "RejectReason.java",
                "package-info.java"), files);
        assertFalse(Modifier.isPublic(ReceiptCodec.class.getModifiers()));
    }

    // covers: AC-9
    @Test
    void noPublicNameClaimsMoreThanTheAdvice() {
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
        assertTrue(names.containsAll(List.of("OVERSIZE", "MALFORMED", "sha256", "decode")), names.toString());
    }

    // covers: AC-9
    @Test
    void noPublicMethodAnswersYesOrNo() {
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

    // covers: AC-9
    @Test
    void toStringShowsOnlyFormatSizeAndDigest() {
        for (Base base : Base.values()) {
            RoutingDecision decision = base.decision();
            AdviceReceipt receipt = AdviceReceipt.of(decision);

            assertEquals("AdviceReceipt[format=routing-advice-receipt/1, size=" + receipt.size() + ", sha256="
                    + receipt.sha256() + "]", receipt.toString());
            for (String token : List.of("scope-demo", "task-17", "exec-1", "sub-a", "ex-gone", "cat-demo-7")) {
                assertFalse(receipt.toString().contains(token), token);
            }
        }
    }

    // covers: AC-9
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
        for (Map.Entry<Class<?>, Integer> entry : typesPerFile.entrySet()) {
            String source = Files.readString(SOURCES.resolve(entry.getKey().getSimpleName() + ".java"));
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
