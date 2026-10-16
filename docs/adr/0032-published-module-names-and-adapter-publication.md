# ADR 0032: Published Module Names and Adapter Publication

## Status

Accepted. Amends [ADR 0025](0025-consumable-library-packaging.md) (its "No preview, no JPMS" decision and its
"adapter stays unpublished" consequence) and [ADR 0018](0018-resonance-store-embedded-recall-adapter.md) (how the
adapter reaches the Store).

## Context

Monada Forge is a JPMS application (issue #68). Before this change, Neuron was verified only as a classpath
dependency. The core jar had no `Automatic-Module-Name`, so Gradle placed it on the classpath even for a
modular consumer, and its JDK-derived module name came from the jar file name. The Resonance Store adapter was
not published at all. Its Store dependencies had no version and resolved only through composite-build
substitution of the sibling checkout, so no external host could consume it.

ADR 0025 rejected a `module-info` descriptor because the core jar contains the optional
`VectorBatchResonanceEvaluator`. A descriptor would have to declare `jdk.incubator.vector`: either as
`requires`, which makes the incubator module mandatory for every consumer, or as `requires static`, which still
leaks an incubator decision into the published contract.

Store issue #94 publishes `com.monada:monada-{core,encoder,storage,index,learning,api}`. Each jar carries an
`Automatic-Module-Name`, and the version is `0.1.0-SNAPSHOT`. They go only into the Store's own temporary
`build/verification-repo`, which is deleted on every publish. No non-SNAPSHOT Store version exists.

## Decision

- **Automatic module names, no descriptors.** The core jar's manifest declares
  `Automatic-Module-Name: monada.neuron`. The adapter jar declares
  `Automatic-Module-Name: monada.neuron.resonance.adapter`. Both archives are reproducible (no file timestamps,
  fixed order) and carry `Implementation-Title`/`Implementation-Version`.
  - An automatic module declares no `requires`, so `jdk.incubator.vector` is never resolved for a consumer.
  - The reflective Vector loader therefore still falls back to scalar (ADR 0013, ADR 0017).
  - No incubator flag reaches consumers.
  - There are no split packages: the adapter owns only `monada.neuron.resonance.adapter[.experience]`.
- **The adapter is a published `java-library`.**
  - The adapter exposes root types (`RoutingObservation`, `ResonanceMemoryPort`) and Store types
    (`ExecutionMemory`, `MonadaMemory`, `ResonanceResult`) in public signatures. Its root, `monada-api` and
    `monada-core` dependencies are therefore `api`, with explicit versions. The Store version comes from
    `resonanceStoreVersion` in `gradle.properties`.
  - Inside this build the Store coordinates are still substituted from the sibling checkout (ADR 0018). The
    published POM and Gradle module metadata carry the pinned coordinates.
  - The root artifact keeps no runtime dependency at all.
- **Isolated local repositories only.**
  - Core: `jpmsConsumerTest` reuses `build/consumer-repo`, the same repository as `consumerSmokeTest`.
  - Adapter: when the adapter module is included, `publishIntegrationRepository` performs these steps:
    1. Cleans `build/integration-repo`.
    2. Publishes the root and adapter into it.
    3. Runs the Store's own `publishLibrariesToVerificationRepository`.
    4. Copies the actual Store publications (`com/monada/**`) unchanged.
    5. Fails if any copied Store module is not exactly at the pinned version.
    6. Writes `STORE-PROVENANCE.txt` with the Store commit, a dirty-tree flag, and the SHA-256 of every
       resolution-relevant Store file: jars, POMs, Gradle `.module` metadata and both `maven-metadata.xml`
       levels (checksum sidecars excluded). The `maven-metadata.xml` files carry publish timestamps, so the
       digests identify one publication, not a commit.
  - There is no remote repository, no Store source copying, and no edit to the Store checkout.
- **Independent consumers prove the metadata.**
  - `consumer-fixture/module-consumer` (`jpmsConsumerTest`) is a module-path host with
    `requires monada.neuron`. Its only repository is the local one, and its runtime graph must be exactly
    `{monada-neuron}`.
  - `consumer-fixture/embedded-host` (`embeddedHostConsumerTest`) runs one shared host source twice: as a named
    module (`module-host`) and on the classpath (`classpath-host`). Each declares only the adapter coordinate,
    and both must resolve exactly:
    - compile: Neuron, the adapter, and Store `api`/`core`/`encoder`/`storage`;
    - runtime: the compile set plus Store `index`/`learning`.
    `classpath-host` disables Gradle's module-path inference, because the shared source directory holds
    `module-host`'s descriptor.
  - Every consumer reads the `Automatic-Module-Name` from the manifest of the jar that actually loaded Neuron
    (and the adapter). The names the jar file names would derive are identical, so checking only the resolved
    module name would not prove the manifest.
  - None of these builds uses `includeBuild`, project substitution, or manually supplied jars.
  - The existing `consumerSmokeTest` classpath fixture is unchanged.

## Alternatives

- **A real `module-info` with `requires static jdk.incubator.vector`.** It gives strong encapsulation (for
  example of `monada.neuron.routing.token`, ADR 0028), but it makes an incubator module part of the published
  descriptor and has to be re-checked on every JDK. Deferred until Forge needs encapsulation.
- **A multi-release jar or a separate SIMD artifact.** This would move the Vector backend out of the core module.
  It is a larger packaging change with no consumer asking for it.
- **Pointing consumers at the Store's `build/verification-repo` directly.** That repository is wiped and
  republished by Store tasks. A copy plus provenance gives Neuron one coherent snapshot to verify.
- **Waiting for a non-SNAPSHOT Store release.** That needs a Store issue (#94 forbade a release-version change)
  and would block this verification.

## Consequences

- Module-path consumers write `requires monada.neuron;` and, for the adapter,
  `requires monada.neuron.resonance.adapter;`. As automatic modules, both export every package. The
  `routing.token` package remains "public, not an extension point" by documentation only (ADR 0028).
- The pinned Store version is still a SNAPSHOT. Its immutability rests on `STORE-PROVENANCE.txt`, not on the
  version string. A Store change at the same version is visible in the recorded commit and digests, but it is
  not rejected.
- Store-dependent verification exists only when the sibling checkout is present. Core-only builds, `test`,
  `consumerSmokeTest` and `jpmsConsumerTest` never need it.
- CI is unchanged by this decision. The new tasks are run locally and are a CI follow-up.

## Follow-Up

- Add `jpmsConsumerTest` to the core CI job and `embeddedHostConsumerTest` to the adapter CI job.
- Request a non-SNAPSHOT Store release, then pin it.
- Revisit real descriptors if Forge needs strong encapsulation or `jlink`.
