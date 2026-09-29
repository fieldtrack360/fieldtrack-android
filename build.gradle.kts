import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.google.services) apply false
}

val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

/**
 * Coordinates. The version follows `-Pversion` when CI supplies one; the group never
 * follows `-Pgroup`.
 *
 * JitPack invokes the build as `-Pgroup=com.github.fieldtrack360 -Pversion=v…`, and the
 * two properties deserve opposite treatment.
 *
 * The version was being discarded: `findProperty("version")` reads the *project's*
 * version, and Gradle only applies a command-line property to the root project, so every
 * module saw `unspecified`, fell back to the catalog, and published POMs stamped
 * 0.1.1-alpha01 for a build of tag v1.0.0 — including the inter-module dependencies,
 * which then named versions that were never published.
 * `providers.gradleProperty` reads the property itself, from any project, and is
 * configuration-cache safe.
 *
 * The group is deliberately hardcoded and must stay that way. JitPack passes the *owner*
 * (`com.github.fieldtrack360`), but a multi-module repository is served under
 * `com.github.<owner>.<repo>` — honouring the property publishes `com.github.fieldtrack360:fieldtrack`,
 * which is not the coordinate the docs, the version catalog, or any released artifact use.
 */
val publishGroup = "com.github.fieldtrack360.fieldtrack"
val publishVersion: String = providers.gradleProperty("version").orNull
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
    ?: catalog.findVersion("traker").get().requiredVersion

group = publishGroup
version = publishVersion

subprojects {
    group = publishGroup
    version = publishVersion
}

tasks.register<VerifyReleaseObfuscationTask>("verifyReleaseObfuscation") {
    group = "verification"
    description = "Builds and audits every Tracker release artifact for obfuscation leaks."
    repositoryRoot.set(layout.projectDirectory)

    dependsOn(
        ":fieldtrack-geo:assembleRelease",
        ":fieldtrack-core:assembleRelease",
        ":fieldtrack-maps:assembleRelease",
        ":fieldtrack-snap:assembleRelease",
        ":fieldtrack-sync:assembleRelease",
        ":fieldtrack:assembleRelease",
        ":fieldtrack-geo:javaDocReleaseJar",
        ":fieldtrack-core:javaDocReleaseJar",
        ":fieldtrack-maps:javaDocReleaseJar",
        ":fieldtrack-snap:javaDocReleaseJar",
        ":fieldtrack-sync:javaDocReleaseJar",
        ":fieldtrack:javaDocReleaseJar",
    )
}

tasks.register<VerifyReleaseIntegrityTask>("verifyReleaseIntegrity") {
    group = "verification"
    description = "Audits the release security posture: R8 on, no hardcoded debuggable, integrity defaults intact."
    repositoryRoot.set(layout.projectDirectory)
}

// Publishing an artifact with the security layer disabled is the failure this task exists
// to prevent, so it runs before anything leaves the machine rather than as a separate step
// somebody has to remember.
subprojects {
    tasks.matching { it.name.startsWith("publish") }.configureEach {
        dependsOn(rootProject.tasks.named("verifyReleaseIntegrity"))
    }
}

tasks.register<ArchiveReleaseMappingsTask>("archiveReleaseMappings") {
    group = "distribution"
    description = "Copies R8 release mappings into local release storage."
    repositoryRoot.set(layout.projectDirectory)
    version.set(publishVersion)
    commitSha.set(
        listOfNotNull(
            (findProperty("trakerCommitSha") as String?)?.takeIf { it.isNotBlank() },
            System.getenv("TRAKER_COMMIT_SHA")?.takeIf { it.isNotBlank() },
            System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() },
            System.getenv("CI_COMMIT_SHA")?.takeIf { it.isNotBlank() },
        ).firstOrNull() ?: "unknown",
    )

    dependsOn(
        ":fieldtrack-geo:assembleRelease",
        ":fieldtrack-core:assembleRelease",
        ":fieldtrack-maps:assembleRelease",
        ":fieldtrack-snap:assembleRelease",
        ":fieldtrack-sync:assembleRelease",
    )
}
