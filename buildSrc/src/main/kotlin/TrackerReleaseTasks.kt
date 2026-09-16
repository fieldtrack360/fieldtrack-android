import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

private val releaseModules = listOf("geo", "core", "maps", "snap", "sync")

private data class ReleaseArtifact(
    val module: String,
    val requiredApi: List<String>,
    val obfuscatedPrefix: String?,
)

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = readBytes()
    val hash = digest.digest(bytes)
    return hash.joinToString("") { byte -> "%02x".format(byte) }
}

/** Audits shipped bytecode instead of trusting build flags or ProGuard comments. */
abstract class VerifyReleaseObfuscationTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile
        val artifacts = listOf(
            ReleaseArtifact(
                "geo",
                listOf(
                    "com/field360/traker/geo/math/Bearing.class",
                    "com/field360/traker/geo/math/Geodesy.class",
                    "com/field360/traker/geo/math/Haversine.class",
                    "com/field360/traker/geo/model/FixDecision.class",
                    "com/field360/traker/geo/model/GeoPoint.class",
                    "com/field360/traker/geo/model/MotionState.class",
                    "com/field360/traker/geo/model/TrackFix.class",
                    "com/field360/traker/geo/model/TrackPoint.class",
                    "com/field360/traker/geo/model/Verdict.class",
                    "com/field360/traker/geo/plot/PolylineCodec.class",
                    "com/field360/traker/geo/plot/TrackBuilder.class",
                    "com/field360/traker/geo/plot/model/SegmentType.class",
                    "com/field360/traker/geo/plot/model/Track.class",
                    "com/field360/traker/geo/plot/model/TrackOptions.class",
                    "com/field360/traker/geo/port/TrackLogger.class",
                ),
                "tr/dev/geo/",
            ),
            ReleaseArtifact(
                "core",
                listOf(
                    "com/field360/tracker/AccuracyProfile.class",
                    "com/field360/tracker/LocationProviderType.class",
                    "com/field360/tracker/RawFix.class",
                    "com/field360/tracker/Tracker.class",
                    "com/field360/tracker/TrackerConfig.class",
                    "com/field360/tracker/domain/model/ErrorCode.class",
                    "com/field360/tracker/domain/model/LocationAccuracy.class",
                    "com/field360/tracker/domain/model/PermissionTier.class",
                    "com/field360/tracker/domain/model/PointQuery.class",
                    "com/field360/tracker/domain/model/ProviderState.class",
                    "com/field360/tracker/domain/model/TrackerEvent.class",
                    "com/field360/tracker/domain/model/TrackerResult.class",
                    "com/field360/tracker/domain/model/TrackSession.class",
                    "com/field360/tracker/motion/DeviceSensors.class",
                    "com/field360/tracker/permission/PermissionManager.class",
                    // The cross-module WorkManager seam. Not host API, but fieldtrack-sync links
                    // against this name after R8 — stripping it fails sync's own minify pass, which
                    // is how it surfaced the first time.
                    "com/field360/tracker/work/WorkManagerAccess.class",
                ),
                "tr/dev/core/",
            ),
            ReleaseArtifact(
                "maps",
                listOf(
                    "com/field360/traker/maps/ArrowIcons.class",
                    "com/field360/traker/maps/LiveTrackRenderer.class",
                    "com/field360/traker/maps/LiveTrackRenderer\$CameraFollowMode.class",
                    "com/field360/traker/maps/LiveTrackRenderer\$Options.class",
                    "com/field360/traker/maps/TrackRenderer.class",
                    "com/field360/traker/maps/TrackRenderer\$RendererOptions.class",
                ),
                null,
            ),
            ReleaseArtifact(
                "snap",
                listOf("com/field360/traker/snap/OsrmSnapProvider.class"),
                "tr/dev/snap/",
            ),
            ReleaseArtifact(
                "sync",
                listOf(
                    // The diagnostic channel (docs/APP-LOG-API.md). Listed for the same
                    // reason the Sync* names are: these are reachable from a kept
                    // TrackerSync member, and reachable is not kept — R8 shortened them to
                    // a.class … g.class in the API package until proguard-rules.pro pinned
                    // them. A host cannot name a renamed class, so this is API loss that
                    // compiles and only fails at the far end.
                    "com/field360/traker/sync/LogSyncConfig.class",
                    "com/field360/traker/sync/LogSyncConfig\$Builder.class",
                    "com/field360/traker/sync/LogSyncConfig\$Companion.class",
                    "com/field360/traker/sync/LogSyncQueue.class",
                    "com/field360/traker/sync/LogSyncQueue\$Result.class",
                    "com/field360/traker/sync/LogRecord.class",
                    "com/field360/traker/sync/LogLevel.class",
                    "com/field360/traker/sync/LogType.class",
                    "com/field360/traker/sync/LifecyclePhase.class",
                    "com/field360/traker/sync/LogPayload.class",
                    "com/field360/traker/sync/LogPayload\$Companion.class",
                    "com/field360/traker/sync/LogEntryDto.class",
                    "com/field360/traker/sync/LogEntryDto\$Companion.class",
                    "com/field360/traker/sync/OkHttpSyncTransport.class",
                    "com/field360/traker/sync/OkHttpSyncTransport\$Companion.class",
                    "com/field360/traker/sync/SyncConfig.class",
                    "com/field360/traker/sync/SyncPayload.class",
                    "com/field360/traker/sync/SyncPayload\$Companion.class",
                    "com/field360/traker/sync/SyncPoint.class",
                    "com/field360/traker/sync/SyncPoint\$Companion.class",
                    "com/field360/traker/sync/SyncQueue.class",
                    "com/field360/traker/sync/SyncQueue\$Result.class",
                    "com/field360/traker/sync/SyncRequest.class",
                    "com/field360/traker/sync/SyncResponse.class",
                    "com/field360/traker/sync/SyncTransport.class",
                    "com/field360/traker/sync/TrackerSync.class",
                    "com/field360/traker/sync/TrackerSync\$Companion.class",
                ),
                "tr/dev/sync/",
            ),
        )

        val forbiddenLogs = listOf(
            "Tracker/",
            "Cadence ->",
            "Motion ->",
            "No open session; stopping service",
            "One-shot suppressed after",
            "Activity transitions registered",
            "Auth expired",
            "Upload failed (",
        )

        artifacts.forEach { artifact ->
            val aar = root.resolve("fieldtrack-${artifact.module}/build/outputs/aar/fieldtrack-${artifact.module}-release.aar")
            check(aar.isFile) {
                "Missing release artifact: ${aar.relativeTo(root)}"
            }

            val classesJar = ZipFile(aar).use { zip ->
                val entry = checkNotNull(zip.getEntry("classes.jar")) {
                    "${aar.name} does not contain classes.jar"
                }
                zip.getInputStream(entry).readBytes()
            }

            val classNames = mutableSetOf<String>()
            val classBytes = ArrayList<Byte>()
            ZipInputStream(ByteArrayInputStream(classesJar)).use { jar ->
                while (true) {
                    val entry = jar.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".class")) {
                        classNames += entry.name
                        classBytes += jar.readBytes().toList()
                    }
                }
            }

            artifact.requiredApi.forEach { api ->
                check(api in classNames) { "${artifact.module} release renamed or removed supported API: $api" }
            }
            val generatedLeaks = classNames.filter { className ->
                !className.startsWith("tr/dev/") &&
                    (className.endsWith("\$\$serializer.class") ||
                        className.substringAfterLast('/').matches(Regex("[a-z]\\.class")))
            }
            check(generatedLeaks.isEmpty()) {
                "${artifact.module} release left shortened/generated implementation in an API package: " +
                    generatedLeaks
            }
            if (artifact.obfuscatedPrefix != null) {
                check(classNames.any { it.startsWith(artifact.obfuscatedPrefix) }) {
                    "${artifact.module} release contains no shortened implementation class under ${artifact.obfuscatedPrefix}"
                }
            } else {
                val publicPackage = "com/field360/traker/${artifact.module}/"
                val unexpected = classNames.filter {
                    it.startsWith(publicPackage) && it !in artifact.requiredApi
                }
                check(unexpected.isEmpty()) {
                    "${artifact.module} release left implementation classes in its public package: $unexpected"
                }
            }

            val bytecodeText = classBytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            forbiddenLogs.forEach { message ->
                check(message !in bytecodeText) {
                    "${artifact.module} release still contains SDK log text: $message"
                }
            }

            ZipFile(aar).use { zip ->
                check(zip.getEntry("mapping.txt") == null) {
                    "${artifact.module} AAR must not publish its private R8 mapping"
                }
            }
        }

        val oldPackages = listOf(
            "com/field360/traker/geo/internal/",
            "com/field360/tracker/internal/",
            "com/field360/traker/maps/internal/",
            "com/field360/traker/snap/internal/",
            "com/field360/traker/sync/internal/",
        )
        artifacts.forEach { artifact ->
            val aar = root.resolve("fieldtrack-${artifact.module}/build/outputs/aar/fieldtrack-${artifact.module}-release.aar")
            val classesJar = ZipFile(aar).use { zip ->
                zip.getInputStream(zip.getEntry("classes.jar")).readBytes()
            }
            val listing = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(classesJar)).use { jar ->
                while (true) listing += (jar.nextEntry ?: break).name
            }
            oldPackages.forEach { oldPackage ->
                check(listing.none { it.startsWith(oldPackage) }) {
                    "${artifact.module} release leaked the old implementation package $oldPackage"
                }
            }
        }

        val sourceArchives = Files.walk(root.toPath()).use { paths ->
            paths.filter { path ->
                val value = path.toString()
                path.fileName.toString().endsWith("-sources.jar") &&
                    "/traker-" in value && "/build/" in value
            }.toList()
        }
        check(sourceArchives.isEmpty()) {
            "Source archives must not be published beside obfuscated binaries: $sourceArchives"
        }

        val documentationArchives = Files.walk(root.toPath()).use { paths ->
            paths.filter { path ->
                path.fileName.toString().endsWith("-javadoc.jar") &&
                    "/traker-" in path.toString() && "/build/" in path.toString()
            }.toList()
        }
        documentationArchives.forEach { archive ->
            ZipFile(archive.toFile()).use { zip ->
                val sourceEntries = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".kt") || it.endsWith(".java") }
                    .toList()
                check(sourceEntries.isEmpty()) {
                    "Javadoc archive must contain rendered API documentation only: " +
                        "$archive contains $sourceEntries"
                }
            }
        }
    }
}

/** Copies the exact R8 release outputs into local release storage keyed by version. */
abstract class ArchiveReleaseMappingsTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val commitSha: Property<String>

    @TaskAction
    fun archive() {
        val root = repositoryRoot.get().asFile
        val archiveRoot = root.resolve("build/release-mappings/${version.get()}")

        releaseModules.forEach { module ->
            val aar = root.resolve("fieldtrack-$module/build/outputs/aar/fieldtrack-$module-release.aar")
            check(aar.isFile) {
                "Missing release artifact for mapping archive: ${aar.relativeTo(root)}"
            }

            val mappingDir = root.resolve("fieldtrack-$module/build/outputs/mapping/release")
            val moduleArchive = archiveRoot.resolve(module)
            moduleArchive.mkdirs()

            val mapping = mappingDir.resolve("mapping.txt")
            check(mapping.isFile) {
                "Missing release mapping for mapping archive: ${mapping.relativeTo(root)}"
            }

            listOf("mapping.txt", "seeds.txt", "usage.txt").forEach { name ->
                val source = mappingDir.resolve(name)
                if (source.isFile) {
                    Files.copy(source.toPath(), moduleArchive.resolve(name).toPath(), REPLACE_EXISTING)
                }
            }

            moduleArchive.resolve("manifest.txt").writeText(
                buildString {
                    appendLine("module=$module")
                    appendLine("version=${version.get()}")
                    appendLine("commit=${commitSha.get()}")
                    appendLine("aar=${aar.relativeTo(root)}")
                    appendLine("aarSha256=${aar.sha256()}")
                    appendLine("mappingSha256=${mapping.sha256()}")
                },
            )
        }
    }
}

/**
 * Source-level audit of the release security posture.
 *
 * The runtime device-integrity layer waives itself in a debuggable build, and the license
 * gate does the same. That waiver is deliberate and it is also the one thing no runtime
 * check can police: by the time the SDK is running with security off, the code that would
 * report it is the code that was disabled. So the guard has to live in the build.
 *
 * Three audiences, three mechanisms, deliberately separate:
 *  - the **host app** is guarded by the lint checks in `:fieldtrack-lint`, shipped inside
 *    the AARs, where a FATAL issue fails the host's own `assembleRelease`;
 *  - **this repository** is guarded by this task, wired ahead of publishing;
 *  - the **published artifacts** are guarded by [VerifyReleaseObfuscationTask].
 *
 * Every check here is a text assertion over build files and manifests. That is blunt on
 * purpose: it reads what a human reviewer would read, and cannot be satisfied by a value
 * computed at configuration time.
 */
abstract class VerifyReleaseIntegrityTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile
        val failures = mutableListOf<String>()

        // 1. R8 on for every module that ships code. The umbrella AAR is empty and is
        //    excluded by name rather than by guesswork.
        val minified = listOf("fieldtrack-geo", "fieldtrack-core", "fieldtrack-maps", "fieldtrack-sync", "fieldtrack-snap")
        minified.forEach { module ->
            val buildFile = File(root, "$module/build.gradle.kts")
            if (!buildFile.isFile) {
                failures += "$module/build.gradle.kts is missing"
            } else if (!buildFile.readText().contains("isMinifyEnabled = true")) {
                failures += "$module release build type does not set isMinifyEnabled = true"
            }
        }

        // 2. No manifest anywhere in the SDK hardcodes the flag that waives both security
        //    layers. `src/debug/` is exempt for the same reason the runtime waiver exists.
        root.walkTopDown()
            .filter { it.name == "AndroidManifest.xml" }
            .filterNot { it.invariantPath().contains("/build/") }
            .filterNot { it.invariantPath().contains("/src/debug/") }
            .forEach { manifest ->
                val text = manifest.readText()
                if (DEBUGGABLE_PATTERN.containsMatchIn(text)) {
                    failures += "${manifest.relativeTo(root)} hardcodes android:debuggable=\"true\""
                }
            }

        // 3. SDK logging must be off in release: the decision log is field-debugging
        //    material and includes positions.
        val coreBuild = File(root, "fieldtrack-core/build.gradle.kts")
        if (coreBuild.isFile &&
            !coreBuild.readText().contains("buildConfigField(\"boolean\", \"SDK_LOGGING_ENABLED\", \"false\")")
        ) {
            failures += "fieldtrack-core release build type does not disable SDK_LOGGING_ENABLED"
        }

        // 4. The shipped defaults of the integrity layer. Guards against a default flipped
        //    while debugging and committed — the exact mistake the lint checks catch in a
        //    host app, applied to the SDK's own source.
        val securityDefaults = File(
            root,
            "fieldtrack-core/src/main/kotlin/com/field360/tracker/TrackerConfig.kt",
        )
        if (!securityDefaults.isFile) {
            failures += "TrackerConfig.kt is missing"
        } else {
            val text = securityDefaults.readText()
            REQUIRED_SECURITY_DEFAULTS.forEach { required ->
                if (!text.contains(required)) {
                    failures += "SecurityConfig no longer declares `$required`"
                }
            }
        }

        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                failures.joinToString(
                    prefix = "Release integrity verification failed:\n  - ",
                    separator = "\n  - ",
                ),
            )
        }

        logger.lifecycle("Release integrity verification passed (${minified.size} modules audited).")
    }

    private companion object {
        /** Windows path separators normalised, so the source-set filters read the same everywhere. */
        fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

        val DEBUGGABLE_PATTERN = Regex("""android:debuggable\s*=\s*"true"""")

        val REQUIRED_SECURITY_DEFAULTS = listOf(
            "val enabled: Boolean = true",
            "val hooking: IntegrityPolicy = IntegrityPolicy.BLOCK",
            "val mockLocation: IntegrityPolicy = IntegrityPolicy.BLOCK",
        )
    }
}
