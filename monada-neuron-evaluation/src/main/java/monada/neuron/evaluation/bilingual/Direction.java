package monada.neuron.evaluation.bilingual;

/**
 * Language direction of protocol {@code bilingual-sense/2}. Directions are gated, scored and decided independently and
 * never pooled (ADR 0051 decision 4).
 */
enum Direction {

    /** English source sentence, Spanish target expressions. */
    EN_ES("EN>ES", "eng"),
    /** Spanish source sentence, English target expressions. */
    ES_EN("ES>EN", "spa");

    private final String label;
    private final String sourceLanguage;

    Direction(String label, String sourceLanguage) {
        this.label = label;
        this.sourceLanguage = sourceLanguage;
    }

    /** Returns the label used in occurrence ids ({@code EN>ES} or {@code ES>EN}, v2 R14). */
    String label() {
        return label;
    }

    /** Returns the Tatoeba / v2 sentence-key language code of the direction's source sentences. */
    String sourceLanguage() {
        return sourceLanguage;
    }
}
