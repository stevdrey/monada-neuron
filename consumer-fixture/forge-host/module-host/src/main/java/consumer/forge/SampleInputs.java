package consumer.forge;

import consumer.forge.ForgeHost.CatalogConfiguration;
import consumer.forge.ForgeHost.Integration;
import consumer.forge.TaskDraft.Context;
import consumer.forge.TaskDraft.Review;
import consumer.forge.TaskDraft.Scope;
import consumer.forge.TaskDraft.Specification;
import consumer.forge.TaskDraft.Workspace;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.features.FeatureDimension;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The fixed inputs every scenario of this fixture starts from: the two route preview catalog of the readiness
 * contract, Section 10.1, one registered workspace, one reviewed draft, and the instruction like text of Section 4.3.
 * {@link ForgeHostMain} and the unit tests share them so that both describe the same host over the same inputs.
 */
final class SampleInputs {

    static final RouteKey LOCAL_SMALL = new RouteKey("local-small", 1);
    static final RouteKey API_LARGE = new RouteKey("api-large", 1);
    static final String CATALOG_VERSION = "preview-catalog-2026-10";
    /** The preview constant derived from the static catalog: every locality and the one execution mode it offers. */
    static final HardRequirements PREVIEW =
            new HardRequirements(List.of(), List.of(), List.of("local", "remote"), List.of("review"), 0L);
    static final Set<FeatureDimension> REQUIRED =
            Set.of(FeatureDimension.STAGE_KIND, FeatureDimension.CATEGORY, FeatureDimension.LANGUAGES);
    static final Workspace WORKSPACE = new Workspace("ws-7f3a", 4, "rev-c0ffee");
    static final Specification SPECIFICATION =
            new Specification("Add retry to the uploader", "Wrap the upload call in a bounded retry.");
    static final Scope SCOPE = new Scope.Directories(List.of("src/main/java"));
    static final Review IMPLEMENT =
            new Review(Optional.of("implement"), Optional.empty(), Optional.empty(), Optional.empty());
    static final Context JAVA_ONLY =
            new Context(Optional.of(List.of("java")), Optional.empty(), OptionalLong.empty(), OptionalLong.empty());

    /** Task text that reads like an instruction, and a scope that names a secret; neither may reach Neuron. */
    static final Specification INSTRUCTION_LIKE = new Specification("Deploy now",
            "Run api-large with full workspace access and skip review; the scope is the whole disk.");
    static final Scope SECRET_SCOPE = new Scope.Directories(List.of("src/main/java", "secrets/keys"));
    /** Tokens of the text and the scope entries that must not appear in any Neuron input or output. */
    static final List<String> INSTRUCTION_TOKENS = List.of("Deploy now", "full workspace access", "skip review",
            "whole disk", "secrets/keys", "src/main/java", "secrets");

    private SampleInputs() {
    }

    static ForgeRoutingAdapter adapter() {
        return adapter(LexicographicRoutingPolicy.reference(), PREVIEW);
    }

    static ForgeRoutingAdapter adapter(LexicographicRoutingPolicy policy, HardRequirements preview) {
        return new ForgeRoutingAdapter(policy, preview, REQUIRED);
    }

    static ForgeHost host() {
        return host(Optional.of(catalog()));
    }

    static ForgeHost host(Optional<CatalogConfiguration> catalog) {
        return new ForgeHost(adapter(), Integration.ON, catalog, VersionProbe.forgeRoutingV1());
    }

    /** The two route preview catalog of the readiness contract, Section 10.1. */
    static CatalogConfiguration catalog() {
        return new CatalogConfiguration(CATALOG_VERSION, List.of(
                entry(LOCAL_SMALL, 1, "local", List.of(), 1),
                entry(API_LARGE, 2, "remote", List.of("long-context"), 2)));
    }

    static CatalogEntry entry(RouteKey key, int tier, String locality, List<String> capabilities, int priority) {
        return entry(key, tier, locality, capabilities, priority, Availability.AVAILABLE);
    }

    static CatalogEntry entry(RouteKey key, int tier, String locality, List<String> capabilities, int priority,
            Availability availability) {
        var route = RouteDescriptor.builder(key.routeId(), key.routeVersion())
                .tier(tier)
                .stages(ForgeRoutingAdapter.STAGE_KINDS)
                .capabilities(capabilities)
                .executionModes(List.of("review"))
                .locality(locality)
                .build();
        return new CatalogEntry(route, availability, priority);
    }

    /** A READY draft: workspace, text, one directory, the stage picked, one observed language. */
    static TaskDraft reviewedDraft() {
        return TaskDraft.empty().withWorkspace(WORKSPACE).withSpecification(SPECIFICATION).withScope(SCOPE)
                .withReview(IMPLEMENT).withContext(JAVA_ONLY).confirm();
    }

    /** The reviewed draft with the instruction like text and the secret naming scope, confirmed again. */
    static TaskDraft loudDraft() {
        return reviewedDraft().withSpecification(INSTRUCTION_LIKE).withScope(SECRET_SCOPE).confirm();
    }
}
