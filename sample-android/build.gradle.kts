import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 8.x has no built-in Kotlin support — the Kotlin Android plugin must be explicit.
    alias(libs.plugins.kotlin.android)
    // Required from Kotlin 2.0 whenever buildFeatures.compose is on.
    alias(libs.plugins.compose.compiler)
}

val localProperties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * Maps key, from `local.properties` (gitignored) — e.g. `MAPS_API_KEY=AIza…`.
 *
 * **There is no committed fallback, and that is the point.** One used to live here, which
 * meant a live Google API key sat in version control while the comment above it claimed
 * the opposite. A default that works out of the box is worth very little next to a
 * credential that is readable by anyone with repo access, forever, in every clone.
 *
 * Absent is a supported state: the Track and Debug screens say so instead of showing a
 * blank map, so the sample still builds and runs without a key.
 */
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY", "")

/**
 * OSRM base URL for road snapping, from `local.properties` — e.g.
 * `OSRM_BASE_URL=https://osrm.internal.example.com`.
 *
 * Blank by default and blank is a working configuration: the sample installs no
 * `RoadSnapProvider`, `buildTrack` never leaves the device, and the track renders from
 * raw fixes with no warning. Only set this if you are pointing at an instance you run.
 *
 * A hardcoded default used to live in the SDK and was removed on purpose. The OSRM demo
 * server publishes no availability guarantee and no usable rate limit, so shipping it as
 * a default would put every host's traffic on somebody else's free instance without
 * anyone choosing to. This is host configuration, not an SDK constant.
 */
val osrmBaseUrl: String = localProperties.getProperty("OSRM_BASE_URL", "")

/**
 * Upload endpoint for `TrackerSync`, from `local.properties` — e.g.
 * `SYNC_URL=https://api.example.com/locations`.
 *
 * The **full** endpoint, not a base: `SyncConfig.url` is what the batch is POSTed to.
 *
 * Blank by default and blank is a working configuration — the sample configures no
 * upload half, points accumulate in Room, and nothing opens a socket. That is the
 * offline-first default the SDK is built around, so it has to keep working with this
 * unset.
 *
 * In `local.properties` rather than committed here for the same reason the Maps key is:
 * an upload endpoint is per-developer, and a dev tunnel URL in particular is ephemeral
 * and personal. Unlike the Maps key it is not a credential — but a committed default that
 * points at somebody's laptop is a default that silently 404s for everyone else.
 */
val syncUrl: String = localProperties.getProperty("SYNC_URL", "")

/**
 * Optional Tracker release license token, from `local.properties` — e.g.
 * `TRACKER_LICENSE=TRACKIT-...`.
 *
 * Blank is a valid development state because debuggable installs are waived by the SDK.
 * Release builds still need a token, whether that comes from this field or from the
 * `TrackItLicense` manifest meta-data entry.
 */
val trackerLicense: String = localProperties.getProperty("TRACKER_LICENSE", "")

android {
    namespace = "com.field360.fieldtrack.sample"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.field360.fieldtrack.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.1-alpha01"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        manifestPlaceholders["TRACKER_LICENSE"] = trackerLicense
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        buildConfigField("String", "OSRM_BASE_URL", "\"$osrmBaseUrl\"")
        buildConfigField("String", "SYNC_URL", "\"$syncUrl\"")
        buildConfigField("String", "TRACKER_LICENSE", "\"$trackerLicense\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            // Minified on purpose, and it is the only place the SDK's `consumer-rules.pro`
            // files are ever executed. With this off — as it was — every host inherited
            // rules that had never run once, and two of them were wrong: core kept a
            // package that does not exist, and nothing preserved the enum constant names
            // that stored points are read back by. `assembleRelease` is now that test.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(libs.versions.javaTarget.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Linked as a project dependency for local development and JitPack builds.
    // Transitive dependencies are resolved via the sub-project's definitions.
    //
    // The published artifact (`libs.fieldtrack`) is deliberately not used here: it still
    // ships the pre-rename `com.devstree.traker` namespace, so the sample cannot compile
    // against it until a tag carrying `com.field360.*` is published.
    implementation(project(":fieldtrack"))


    // OkHttp is compileOnly inside fieldtrack-snap, so the host that wants the shipped
    // provider supplies the client — that is the artifact's whole bargain: no host
    // inherits an HTTP stack it did not ask for.
    implementation(libs.okhttp)

    // The same bargain in fieldtrack-sync: OkHttpSyncTransport runs Retrofit over the
    // client above, and both are compileOnly there. Stated explicitly rather than left to
    // fieldtrack-core's transitive `implementation` of them — that puts Retrofit on the
    // runtime classpath by accident, and the failure when it stops doing so is
    // `defaultTransport()` silently degrading to NoOpTransport at configure() time.
    implementation(libs.retrofit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)

    // Not used directly: play-services-maps drags in androidx.fragment 1.1.0, which
    // predates the ActivityResult APIs MainActivity uses and trips lint
    // (InvalidFragmentVersionForActivityResult). Hilt used to constrain this
    // transitively; with Hilt gone the floor has to be stated here.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
