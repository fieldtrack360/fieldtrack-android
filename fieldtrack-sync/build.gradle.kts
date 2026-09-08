import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // AGP 8.x has no built-in Kotlin support — the Kotlin Android plugin must be explicit.
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.field360.traker.sync"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "SDK_LOGGING_ENABLED", "true")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("boolean", "SDK_LOGGING_ENABLED", "false")
            // Published AAR ships R8-obfuscated — see fieldtrack-core/build.gradle.kts.
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

    // Publishes the release variant with a javadoc jar — no sources jar, for the same
    // IP-protection reason as fieldtrack-core: an obfuscated AAR next to its own source
    // is not obfuscated (BUILD.md §5.6).
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

/**
 * The log buffer's own schema, exported and versioned exactly like core's.
 *
 * A second database rather than a table in core's: the diagnostic channel lives entirely
 * in this module, and a host that does not depend on `fieldtrack-sync` should not carry a
 * table it can never write.
 */
room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    // Only the public seam from core — never its internals.
    implementation(project(":fieldtrack-core"))
    implementation(libs.androidx.core.ktx)

    // The diagnostic buffer. Durable on purpose: a process the OEM kills mid-drive is the
    // case these entries exist to explain, and an in-memory buffer loses exactly that.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // compileOnly: the default transport uses Retrofit over OkHttp, but a host supplying
    // its own SyncTransport should inherit neither. See SyncTransport's KDoc.
    compileOnly(libs.okhttp)
    compileOnly(libs.retrofit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.retrofit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// Publishing — coordinates, POM, sources and javadoc jars. See the script for why it
// is shared rather than repeated in six build files (CROSS-PLATFORM.md R-44).
apply(from = rootProject.file("gradle/publish.gradle.kts"))
