package monada.neuron;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the packaging contract of ADR 0025: no {@code jdk.incubator.*} type appears in the signature
 * of any public or protected member of the published classes, so consumers on the portable path never
 * need the incubating Vector module to compile against or inspect the artifact.
 */
class PublicApiIncubatorLeakTest {

    private static final String INCUBATOR_PREFIX = "jdk.incubator.";

    @Test
    void publishedSignaturesDoNotExposeIncubatorTypes() throws Exception {
        Path classesRoot = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> leaks = new ArrayList<>();

        for (String className : classNames(classesRoot)) {
            Class<?> type = Class.forName(className, false, Main.class.getClassLoader());
            if (!isExposed(type.getModifiers())) {
                continue;
            }
            inspect(type.getGenericSuperclass(), className + " superclass", leaks);
            for (Type itf : type.getGenericInterfaces()) {
                inspect(itf, className + " interface", leaks);
            }
            for (Field field : type.getDeclaredFields()) {
                if (isExposed(field)) {
                    inspect(field.getGenericType(), className + "#" + field.getName(), leaks);
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (isExposed(constructor)) {
                    inspectAll(constructor.getGenericParameterTypes(), className + " constructor", leaks);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (isExposed(method)) {
                    String where = className + "#" + method.getName();
                    inspect(method.getGenericReturnType(), where, leaks);
                    inspectAll(method.getGenericParameterTypes(), where, leaks);
                    inspectAll(method.getGenericExceptionTypes(), where, leaks);
                }
            }
        }

        assertTrue(leaks.isEmpty(), "incubator types leak into the public API: " + leaks);
    }

    private static boolean isExposed(Member member) {
        return !member.isSynthetic() && isExposed(member.getModifiers());
    }

    private static boolean isExposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void inspectAll(Type[] types, String where, List<String> leaks) {
        for (Type type : types) {
            inspect(type, where, leaks);
        }
    }

    private static void inspect(Type type, String where, List<String> leaks) {
        if (type != null && type.getTypeName().contains(INCUBATOR_PREFIX)) {
            leaks.add(where + " -> " + type.getTypeName());
        }
    }

    private static List<String> classNames(Path classesRoot) throws IOException {
        try (Stream<Path> files = Files.walk(classesRoot)) {
            return files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> classesRoot.relativize(path).toString())
                    .filter(name -> !name.equals("module-info.class") && !name.endsWith("package-info.class"))
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.').replace('\\', '.'))
                    .toList();
        }
    }
}
