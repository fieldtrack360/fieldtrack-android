import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // AGP 8.x has no built-in Kotlin support — the Kotlin Android plugin must be explicit.
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * Gradle property, then environment variable, then `local.properties`. The first two exist
 * because CI and JitPack have no `local.properties`; without them a release built there
 * ships with the licence layer inert and **nothing in the build output says so**.
 */
fun secret(gradleProperty: String, key: String): String =
    providers.gradleProperty(gradleProperty).orNull
        ?: providers.environmentVariable(key).orNull
        ?: localProperties.getProperty(key, "")

/**
 * The licence API root, e.g. `https://licence.example.com/api/v1`.
 *
 * **This keeps the URL out of version control, not out of the artifact.** It is compiled
 * into BuildConfig and is readable in any published AAR or installed APK, exactly like the
 * two public keys below it. That is fine — an endpoint the device has to reach is not a
 * secret, and nothing here should ever be a credential.
 *
 * Blank is a supported state and the default: `LicenseConfig.baseUrl` falls back to the
 * host manifest, and with neither set the revocation check makes no request at all.
 */
val licenseBaseUrl: String = secret("fieldtrackLicenseUrl", "FIELDTRACK_LICENSE_URL")

/**
 * The key that verifies `/verify` **responses**, standard base64 of 32 raw bytes.
 *
 * A different key from the one above, deliberately: one authenticates what we issued, the
 * other authenticates what the server says about it today. Blank leaves the online check
 * inert — no request is made and no response is ever trusted.
 */
val licenseResponseKey: String = secret("fieldtrackResponseKey", "FIELDTRACK_RESPONSE_KEY")


android {
    namespace = "com.field360.tracker"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "SDK_LOGGING_ENABLED", "true")
        // Reported to the licence API as `sdk_version`. Read from the catalog rather than
        // hardcoded so a release cannot ship claiming to be the previous one.
        buildConfigField("String", "SDK_VERSION", "\"${libs.versions.fieldtrack.get()}\"")
        buildConfigField("String", "LICENSE_BASE_URL", "\"$licenseBaseUrl\"")
        buildConfigField("String", "LICENSE_RESPONSE_KEY", "\"$licenseResponseKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("boolean", "SDK_LOGGING_ENABLED", "false")
            // The published AAR ships R8-obfuscated: internals renamed and flattened
            // into com.field360.tracker.internal, so decompiling the artifact yields
            // the public contract and little else — whether or not the host minifies.
            // proguard-rules.pro is the build-time config; consumer-rules.pro remains
            // the host-side one, and the two are deliberately separate files.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaBytecode.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaBytecode.get())
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
    // NOTE: schemas/ is deliberately NOT wired into androidTest assets. AGP 9 throws
    // `DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet`
    // on sourceSets.getByName(...).assets. Room's MigrationTestHelper will need that
    // path once version 2 exists — revisit with the Room Gradle plugin then.

    // Publishes the release variant with a javadoc jar — and deliberately NO sources
    // jar. The release AAR is obfuscated for IP protection, and a -sources.jar next to
    // it would hand every consumer the exact code the obfuscation hides. The cost is
    // real and accepted: no source navigation in a host's IDE (BUILD.md §5.6).
    //
    // Declared per module rather than in gradle/publish.gradle.kts because AGP's types do
    // not resolve inside a script plugin — see that file. Without this block AGP creates
    // no `release` software component and there is nothing to publish.
    publishing {
        singleVariant("release") {
            withJavadocJar()
        }
    }
}

kotlin {
    // Every public declaration needs an explicit visibility and return type - accidental
    // API surface is how an SDK grows things it can never remove.
    explicitApi()

    jvmToolchain(libs.versions.javaTarget.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaBytecode.get()))
        allWarningsAsErrors.set(true)
    }
}

// EC-83 — schemas are committed and migrations are real; never destructive.
//
// The Room Gradle plugin owns the export location, and it gives each variant its own
// subdirectory under the one named here. That separation is the point. Setting
// `room.schemaLocation` as a KSP argument — which is what this used to do — pointed every
// variant at a single directory, so `kspDebugKotlin` and `kspReleaseKotlin` generated the
// same `7.json` at the same time. KSP runs its processors in Gradle worker actions, so
// `--no-parallel` does not separate them, and whichever task read the file while the other
// was still writing it died on a half-written document:
//
//     JsonDecodingException: Expected end of the array or comma at path: $.database.entities[0]
//
// A working tree that already holds valid schemas hides this, because both tasks read a
// complete file. A fresh clone does not, which is why it surfaced on JitPack first.
room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":fieldtrack-geo"))

    // Custom lint rules, packaged into this AAR so they run in the HOST app's build.
    // They guard the one thing runtime code cannot: a release build that shipped with the
    // device-integrity layer switched off (docs/DEVICE-INTEGRITY-PLAN.md §7).
    lintPublish(project(":fieldtrack-lint"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // No DI framework, deliberately. The graph is wired by hand in di/TrackerGraph.kt so
    // that a host needs no Gradle plugin, no `@HiltAndroidApp`, and no annotation
    // processor of its own — see Tracker's KDoc and CROSS-PLATFORM.md B-1. KSP below is
    // Room's alone.

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Licensing. Retrofit over OkHttp for the one revocation call, Gson for the wire
    // (and for Retrofit's request converter), Tink for Ed25519 — java.security has none
    // below API 33 and minSdk is 26. All `implementation` rather than `compileOnly`:
    // unlike fieldtrack-sync's optional transport, the licence check has to work in a
    // host that brought no HTTP client of its own.
    implementation(libs.gson)
    implementation(libs.tink.android)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // Pulling OkHttp 5 above is not free for the host. OkHttp 5 deleted the internal
    // class `okhttp3.internal.Util`, and Gradle's conflict resolution only upgrades the
    // `okhttp` module itself — every sibling artifact a host already had stays where it
    // was. React Native ships `okhttp-urlconnection` 4.x, whose `JavaNetCookieJar` calls
    // straight into that deleted class, so the app dies on its first cookie-bearing
    // request with a stack that names none of our code:
    //
    //     java.lang.NoClassDefFoundError: Failed resolution of: Lokhttp3/internal/Util;
    //         at okhttp3.JavaNetCookieJar.decodeHeaderAsJavaNetCookies(JavaNetCookieJar.kt:81)
    //         at com.facebook.react.modules.network.ReactCookieJarContainer.loadForRequest
    //
    // A constraint — not a dependency — drags that sibling up to match. We never link
    // against okhttp-urlconnection ourselves, so this adds nothing to the AAR and stays
    // inert in a host that does not use it. `required`, deliberately, not `strictly`: a
    // host is free to move the whole family past us, only never to split it.
    constraints {
        implementation(libs.okhttp.urlconnection) {
            because(
                "OkHttp 5 removed okhttp3.internal.Util; an okhttp-urlconnection left " +
                    "at 4.x crashes against the 5.x core jar this module brings in",
            )
        }
    }

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    // WorkManager needs explicit initialisation under Robolectric: ready() enqueues
    // two unique workers, and without this getInstance() throws before the licence
    // check under test is ever reached.
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
}

// Publishing — coordinates, POM, sources and javadoc jars. See the script for why it
// is shared rather than repeated in six build files (CROSS-PLATFORM.md R-44).
apply(from = rootProject.file("gradle/publish.gradle.kts"))
