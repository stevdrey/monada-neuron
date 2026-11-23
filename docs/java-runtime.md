# Java 27 runtime setup

Use Eclipse Temurin **27.0.1.1+4** or a later Java 27 build that the vendor confirms contains the fix for
CVE-2026-114938 (Java4Shell). Verify other distributions against their own advisories.
[Adoptium advisory](https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell) ·
[Temurin 27.0.1.1+4 release](https://github.com/adoptium/temurin27-binaries/releases/tag/jdk-27.0.1.1%2B4)

## Build and test

Install a patched JDK and set `JAVA_HOME` and `PATH` to use it. Point your IDE's Gradle JVM at the same installation.
Stop existing Gradle daemons after switching JDKs, then verify the full build and vendor:

```bash
"$JAVA_HOME/bin/java" -version
./gradlew --stop
./gradlew --version
./gradlew --no-daemon -q javaToolchains
./gradlew --no-daemon test
```

If Gradle discovers other Java 27 installations, restrict it to the patched JDK for builds and nested consumer tests.
In your Gradle user-home `gradle.properties`, set the following properties using the absolute path to that JDK.
These user-home settings also apply to other Gradle builds that use that Gradle user home.

```properties
org.gradle.java.installations.paths=/absolute/path/to/patched-jdk
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
```

Every settings file in the repository, including the standalone consumer fixtures, applies
`gradle/java-runtime-floor.settings.gradle.kts`. It checks the Gradle daemon JVM and the Java toolchain of every Java
project, which also runs the test workers and fixture hosts. An Eclipse Adoptium Java 27 build older than 27.0.1.1
fails the build. Another vendor's older Java 27 build only logs a warning, because its fix has to be checked against
that vendor's advisory. The check runs while Gradle configures the build, so run `./gradlew --stop` after switching
JDKs to make sure no reused daemon or configuration cache entry skips it. When the Gradle property
`monada.requiredJavaHome` is set, the daemon and every toolchain must be exactly that installation, and the build logs
the selected runtime.

The Java CI build, adapter job and dependency submission share one checksum-pinned Temurin archive for Ubuntu x64. The
setup fails on any other runner OS or architecture, caches the archive by its SHA-256 and verifies that digest on every
run, including cache hits, then checks the installed vendor and full version. It writes `org.gradle.java.home`, the
three toolchain properties above and `monada.requiredJavaHome` to the runner's user-home `gradle.properties`. This
restricts Gradle's daemon and toolchain selection to the installed JDK, including in nested consumer builds, and the
first build of each job enforces it. When moving the pin, check the vendor advisory and update the release identity and
SHA-256 together.

## Running Neuron

An embedded library runs on the host application's JVM. Update the host runtime for both classpath and JPMS
consumers, including any separately configured JDK, service or container image. Restart the host and verify the
running process's `java.vendor` and `java.runtime.version`, for example through
`jcmd <pid> VM.system_properties` when the JVM can be attached to. Record the exact build and image digest if applicable.

| Runtime in scope | Patch status |
| --- | --- |
| GitHub-hosted Java CI build, adapter and consumer fixtures | Pinned to Temurin 27.0.1.1+4. Confirm the selected build and results in a fresh Java CI run. |
| GitHub-hosted dependency submission | Pinned to Temurin 27.0.1.1+4. Confirm a run of the Dependency Submission workflow on the same revision. |
| Local development, demos and IDE launches | Operator-managed. Gradle builds fail on an older Temurin 27 daemon or toolchain and warn for other vendors. Verify any IDE runtime that launches code outside Gradle. |
| External Forge or other embedded consumers | Operator-managed. The repository exercises classpath and JPMS fixtures. Obtain runtime evidence from each external host owner. |

The repository ships a library and development fixtures. It does not define a production deployment or identify
the JVMs running on external hosts. Those hosts' patch status and any active startup mitigation remain unverified
until their operators provide runtime evidence.

If a Temurin HotSpot host cannot upgrade immediately, Adoptium documents `-XX:-UseBASE64Intrinsics` as an interim
mitigation. Add it to the affected service's startup options, restart the service and verify that the flag is
active in the running JVM. Remove the flag after confirming the restarted service uses a vendor-confirmed fixed
build. Treat that verification as part of the host rollout. [Adoptium advisory](https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell)
