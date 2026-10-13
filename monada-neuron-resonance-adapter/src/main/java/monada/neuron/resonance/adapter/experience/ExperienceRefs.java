package monada.neuron.resonance.adapter.experience;

import com.monada.core.execution.ExperienceRef;

/**
 * Canonical text of a Store {@link ExperienceRef} without its revision, so every revision of one event shares one
 * reference. Each part is written as {@code <code-point-count>:<text>}, so no token can forge a delimiter; an absent
 * attempt is {@code -}. The text is not hashed: it names the exact Store event.
 */
final class ExperienceRefs {

    private ExperienceRefs() {
    }

    static String format(ExperienceRef ref) {
        var out = new StringBuilder(96);
        out.append("xr1");
        part(out, ref.scope().value());
        part(out, ref.task().value());
        part(out, ref.execution().value());
        ref.attempt().ifPresentOrElse(a -> part(out, a.value()), () -> out.append("|-"));
        part(out, ref.eventId().value());
        return out.toString();
    }

    private static void part(StringBuilder out, String value) {
        out.append('|').append(value.codePointCount(0, value.length())).append(':').append(value);
    }
}
