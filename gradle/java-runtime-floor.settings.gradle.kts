// CVE-2026-114938 (Java4Shell) runtime floor, applied by every settings file of this repository, including the
// standalone consumer fixtures. It checks the Gradle daemon JVM and the Java toolchain of every Java project,
// which also runs the test workers and fixture hosts. See docs/java-runtime.md.
//
// An Eclipse Adoptium Java 27 build older than the floor fails the build; another vendor's older build only warns,
// because its fix has to be checked against that vendor's advisory. When the Gradle property
// `monada.requiredJavaHome` is set (CI sets it), the daemon and every toolchain must be that exact installation.

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File

val patchedFloor: List<Int> = listOf(27, 0, 1, 1)
val requiredJavaHome: File? = providers.gradleProperty("monada.requiredJavaHome").orNull?.let { File(it).canonicalFile }
val reportedHomes = mutableSetOf<File>()

fun isBelowFloor(version: List<Int>): Boolean {
    for (i in patchedFloor.indices) {
        val part = version.getOrElse(i) { 0 }
        if (part != patchedFloor[i]) return part < patchedFloor[i]
    }
    return false
}

fun checkJavaRuntime(role: String, vendor: String, runtimeVersion: String, home: File) {
    val canonicalHome = home.canonicalFile
    if (requiredJavaHome != null && canonicalHome != requiredJavaHome) {
        throw GradleException("$role runs on $canonicalHome, but monada.requiredJavaHome is $requiredJavaHome.")
    }
    val description = "$vendor $runtimeVersion ($canonicalHome)"
    if (reportedHomes.add(canonicalHome)) {
        if (requiredJavaHome != null) logger.lifecycle("Java runtime: $description") else logger.info("Java runtime: $description")
    }
    val version = runCatching { Runtime.Version.parse(runtimeVersion).version() }.getOrNull()
    if (version == null) {
        logger.warn("$role runs on $description, whose version cannot be checked against the CVE-2026-114938 floor.")
        return
    }
    if (version.first() != patchedFloor.first() || !isBelowFloor(version)) return
    val message = "$role runs on $description, older than the CVE-2026-114938 floor " +
        "${patchedFloor.joinToString(".")}. See docs/java-runtime.md."
    if (vendor.contains("Adoptium") || vendor.contains("Temurin")) throw GradleException(message)
    logger.warn("$message Verify the fix against your vendor's advisory.")
}

checkJavaRuntime(
    "The Gradle daemon",
    System.getProperty("java.vendor"),
    System.getProperty("java.runtime.version"),
    File(System.getProperty("java.home"))
)

gradle.allprojects {
    afterEvaluate {
        val java = extensions.findByType(JavaPluginExtension::class.java) ?: return@afterEvaluate
        val metadata = extensions.getByType(JavaToolchainService::class.java).launcherFor(java.toolchain).get().metadata
        checkJavaRuntime(
            "The Java toolchain of project $path",
            metadata.vendor,
            metadata.javaRuntimeVersion,
            metadata.installationPath.asFile
        )
    }
}
