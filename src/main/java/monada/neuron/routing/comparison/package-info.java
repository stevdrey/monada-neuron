/**
 * Read-only previews of two routing scenarios side by side for the opt-in Forge routing extension (issue #99, ADR 0043).
 *
 * <p>A host that lets a person adjust an approved catalog, requirement or preference can ask what the advice would be
 * under the change. {@link monada.neuron.routing.comparison.ScenarioComparator#compare} decides both caller-provided
 * {@link monada.neuron.routing.comparison.RoutingScenario}s with their own trusted profile and returns the two decisions
 * unchanged, every input that differs and, only when the pair is comparable, a canonical
 * {@link monada.neuron.routing.comparison.ScenarioDelta}:
 *
 * <pre>{@code
 * ScenarioComparison preview = new ScenarioComparator().compare(current, adjusted);
 * String headline = switch (preview) {
 *     case ScenarioComparison.Compared compared -> "advice would change: " + compared.delta().routeChange();
 *     case ScenarioComparison.Incomparable incomparable -> "not comparable: " + incomparable.breaks();
 * };
 * }</pre>
 *
 * <p>A pair that differs in scope, task, stage kind, feature schema, evaluation policy or cutoff is
 * {@link monada.neuron.routing.comparison.ScenarioComparison.Incomparable} and has no delta. Every comparison lists
 * {@link monada.neuron.routing.comparison.PreviewNotice#HYPOTHETICAL}: a different selection establishes neither a
 * route's performance nor its spend. No type here reaches the adaptation, feedback, outcome, receipt or disposition
 * packages, or the resonance package; a preview is never applied, persisted or fed back.
 */
package monada.neuron.routing.comparison;
