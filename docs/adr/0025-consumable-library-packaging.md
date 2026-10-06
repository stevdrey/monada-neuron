# ADR 0025: Consumable Library Packaging

## Status

Accepted

## Context

Monada Forge consumes Neuron as an embedded Java dependency (ADR 0022 to ADR 0024), but the root project was
shaped as a standalone application: it applied `application`, had no explicit publication, and enabled
`jdk.incubator.vector` for every compile, test, run, and Javadoc task. A host needs a clean artifact boundary,
no transitive evaluation/benchmark code, and no obligation to opt into an incubating JDK module. The
portable/adaptive path reaches the Vector backend reflectively (ADR 0013, ADR 0017), and no incubator type
appears in public signatures, so the optional SIMD backend already degrades to the scalar reference path.
`VectorBatchResonanceEvaluator` itself remains a public class in the artifact with direct links to the Vector
API internally; it is safe as long as a consumer does not load it without the module, which only the
reflective selection path does, guarded by the module check.

## Decision

- **One artifact, no module split.** The root project publishes `monada.neuron:monada-neuron` (snapshot
  `0.1.0-SNAPSHOT`) through `java-library` and `maven-publish`, with sources and Javadoc jars. The artifact
  has no runtime dependencies. `monada-neuron-evaluation` depends on the root, never the reverse, so it and
  JMH remain development-only and non-transitive. The resonance adapter stays a separate optional module
  (ADR 0018).
- **Demo is a task, not a plugin.** The `application` plugin is removed. `./gradlew runDemo` runs
  `monada.neuron.Main` so no distribution or start-script semantics attach to the library.
- **Vector API stays optional.** `--add-modules jdk.incubator.vector` remains a build-time and dev-run
  concern needed to compile and test the optional backend. It is not part of publication metadata, and a
  consumer on the portable path needs no flag. Consumers may opt in at runtime to enable SIMD.
- **No preview, no JPMS.** The consumer contract uses stable Java 27 APIs only; no module descriptor is added
  because no concrete benefit has been demonstrated.
- **Reproducible local consumption.** `publishToMavenLocal` supports local product development.
  `./gradlew consumerSmokeTest` publishes to a repository-local directory (`build/consumer-repo`) and builds
  `consumer-fixture/`, a standalone Gradle build that embeds a `NeuronRuntime` without any incubator flag,
  verifies the scalar fallback, and verifies that evaluation/JMH are absent from its classpath. The task
  runs the tracked Gradle wrapper (`gradlew` or `cmd /c gradlew.bat` by host OS).
- **Public API guard.** `PublicApiIncubatorLeakTest` reflects over every published class and fails if any
  public or protected signature mentions a `jdk.incubator.*` type (for example the evaluator reports its
  vector species as a string via `speciesName()`).

## Alternatives

- Split into `monada-neuron-core` and an `app` module. Cleaner long term, but unnecessary churn for the first
  consumer because dependency isolation is already guaranteed.
- Add a JPMS `module-info`. Rejected for now: it would force a decision about `jdk.incubator.vector` as a
  `requires` and has no demonstrated benefit.
- Keep `application` and publish only the `java` component. Works, but leaves application semantics defining
  the project shape.

## Consequences

- Consumers resolve a POM/Gradle module with no dependencies and Java 27 as the baseline.
- `Main` still ships in the jar; it is a trivial demo class and a split is deferred.
- `consumerSmokeTest` is intentionally separate from `test` because it runs a nested Gradle build.

## Follow-Up

- Remote repository publication and release versioning once release requirements are clear.
- Revisit a core/demo module split if the demo grows or dependencies appear.
