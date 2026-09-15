# Tracker core — build-time R8 configuration for the published release AAR.
#
# Distinct from consumer-rules.pro, and the two must not be confused: that file is merged
# into the HOST's build and constrains somebody else's R8 pass; this one runs when the
# release AAR itself is assembled. Its job is IP protection — the capture pipeline, the
# graph, the service plumbing and every internal class are renamed and flattened into
# `tr.dev.core`, so the shipped artifact exposes the public contract
# and nothing else, even to a host that never enables minification of its own.
#
# What this does NOT do, stated so nobody relies on it: obfuscation renames symbols. It
# does not encrypt strings, does not hide the tuning constants (they must exist at
# runtime), and does not obscure control flow. The `Reasons` vocabulary and error
# messages survive verbatim because they are API. A determined reader with a decompiler
# still sees the algorithm's shape — renaming raises the effort, it does not make the
# code secret.

# ── attributes the public API needs to stay usable ──────────────────────────
#
# Signature keeps generics on the kept surface (a host sees Flow<TrackerEvent>, not raw
# Flow). InnerClasses/EnclosingMethod keep the nested-class structure of Builder and the
# sealed hierarchies. Runtime annotations carry kotlin.Metadata, which R8 rewrites for
# kept classes — without it a Kotlin host loses named arguments, default values and
# null-safety on the whole API.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,AnnotationDefault

# Stack traces from the field must remain mappable (retrace against the mapping file
# under build/outputs/mapping/release/), but real source file names need not ship.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── the public contract ─────────────────────────────────────────────────────
#
# Hosts and the sibling artifacts (fieldtrack-bridge, fieldtrack-sync, fieldtrack-maps) are
# compiled separately against these names, so the names are load-bearing. Kept by
# explicit package/class rather than by visibility on purpose: Kotlin `internal`
# compiles to public bytecode, so "keep everything public" would keep the internals
# this file exists to hide.

# Root API types are listed explicitly so generated BuildConfig, logging helpers,
# serializers and enum switch tables are not accidentally treated as host API.
-keep public class com.field360.tracker.AccuracyConfig { public protected *; }
-keep public class com.field360.tracker.AccuracyConfig$Companion { public protected *; }
-keep public class com.field360.tracker.AccuracyProfile { public protected *; }
-keep public class com.field360.tracker.DesiredAccuracy { public protected *; }
-keep public class com.field360.tracker.GeolocationConfig { public protected *; }
-keep public class com.field360.tracker.GeolocationConfig$Companion { public protected *; }
-keep public class com.field360.tracker.LocationProviderType { public protected *; }
-keep public class com.field360.tracker.MotionConfig { public protected *; }
-keep public class com.field360.tracker.MotionConfig$Companion { public protected *; }
-keep public class com.field360.tracker.PersistenceConfig { public protected *; }
-keep public class com.field360.tracker.PersistenceConfig$Companion { public protected *; }
-keep public class com.field360.tracker.RawFix { public protected *; }
-keep public class com.field360.tracker.RawPoint { public protected *; }
-keep public class com.field360.tracker.SensorConfig { public protected *; }
-keep public class com.field360.tracker.SensorConfig$Companion { public protected *; }
-keep public class com.field360.tracker.ServiceConfig { public protected *; }
-keep public class com.field360.tracker.ServiceConfig$Companion { public protected *; }
-keep public class com.field360.tracker.Tracker { public protected *; }
-keep public class com.field360.tracker.Tracker$Companion { public protected *; }
-keep public class com.field360.tracker.TrackerArtifacts { public protected *; }
-keep public class com.field360.tracker.TrackerArtifacts$Companion { public protected *; }
-keep public class com.field360.tracker.TrackerConfig { public protected *; }
-keep public class com.field360.tracker.TrackerConfig$Builder { public protected *; }
-keep public class com.field360.tracker.TrackerConfig$Companion { public protected *; }
-keep public class com.field360.tracker.TrackingMode { public protected *; }
# ServiceConfig.wakeLockPolicy's type. Added with that property and missed here, so R8
# stripped it from the AAR while TrackerConfig kept referencing it — every host minifying
# its own build then failed with "Missing class com.field360.tracker.WakeLockPolicy", which
# is a link error in the SDK, not in theirs. A public type reachable from the public API has
# to be on this list; the list is explicit precisely so that adding one is a decision.
-keep public class com.field360.tracker.WakeLockPolicy { public protected *; }
-keep public class com.field360.tracker.SecurityConfig { public protected *; }
-keep public class com.field360.tracker.SecurityConfig$Companion { public protected *; }

# The device-integrity model, and ONLY the model. The probes, the evaluator and the
# monitor are internal and stay renamed and repackaged — an anti-tamper layer whose class
# names survive obfuscation is a map for the person it exists to stop.
#
# Those internals live in `integrity.internal`, not in this package, and must stay there:
# R8 renames but refuses to REPACKAGE a class that shares a package with a pinned class
# and is reachable from a pinned member's signature (Tracker's constructor takes the
# monitor). The result was `com/field360/tracker/integrity/a.class` shipping in the API
# package, which verifyReleaseObfuscation rejects.
-keep public class com.field360.tracker.integrity.IntegritySignal { public protected *; }
-keep public class com.field360.tracker.integrity.IntegrityPolicy { public protected *; }
-keep public class com.field360.tracker.integrity.IntegrityFinding { public protected *; }
-keep public class com.field360.tracker.integrity.IntegrityFinding$Companion { public protected *; }
-keep public class com.field360.tracker.integrity.IntegrityReport { public protected *; }
-keep public class com.field360.tracker.integrity.IntegrityReport$Companion { public protected *; }

# The model and repository seams hosts and siblings consume. Generated serializers are
# deliberately omitted: companion serializer() methods remain stable, implementations
# are renamed and repackaged.
-keep public class com.field360.tracker.domain.model.ErrorCode { public protected *; }
-keep public class com.field360.tracker.domain.model.GeofenceTransition { public protected *; }
-keep public class com.field360.tracker.domain.model.LicenseInfo { public protected *; }
-keep public class com.field360.tracker.domain.model.LicenseStatus { public protected *; }
-keep public class com.field360.tracker.domain.model.LocationAccuracy { public protected *; }
-keep public class com.field360.tracker.domain.model.PermissionTier { public protected *; }
-keep public class com.field360.tracker.domain.model.PointQuery { public protected *; }
-keep public class com.field360.tracker.domain.model.PointQuery$Companion { public protected *; }
-keep public class com.field360.tracker.domain.model.TrackerGeofence { public protected *; }
-keep public class com.field360.tracker.domain.model.TrackerGeofence$Companion { public protected *; }
-keep public class com.field360.tracker.domain.model.TrackerGeofenceEvent { public protected *; }
-keep public class com.field360.tracker.domain.model.ProviderState { public protected *; }
-keep public class com.field360.tracker.domain.model.BatteryInfo { public protected *; }
-keep public class com.field360.tracker.domain.model.BatteryInfo$Companion { public protected *; }
-keep public class com.field360.tracker.domain.model.PowerSource { public protected *; }
-keep public class com.field360.tracker.domain.model.TrackerEvent { public protected *; }
-keep public class com.field360.tracker.domain.model.TrackerEvent$* { public protected *; }
-keep public class com.field360.tracker.domain.model.TrackerResult { public protected *; }
-keep public class com.field360.tracker.domain.model.TrackerResult$* { public protected *; }
-keep public class com.field360.tracker.domain.model.TrackerState { public protected *; }
-keep public class com.field360.tracker.domain.model.TrackSession { public protected *; }
-keep public class com.field360.tracker.domain.repository.** { public protected *; }

# The permission ladder — PermissionManager and its nested BackgroundRequest hierarchy.
# The package also holds the internal ProviderStateMonitor, which is why this is a class
# glob and not a package glob.
-keep public class com.field360.tracker.permission.PermissionManager { public protected *; }
-keep public class com.field360.tracker.permission.PermissionManager$* { public protected *; }
# The background-restriction snapshot `PermissionManager.backgroundRestrictions()` returns.
# Reachable from the public API, so it has to survive R8 for the same reason
# WakeLockPolicy did — a stripped return type is a link error in the host's build, not ours.
-keep public class com.field360.tracker.permission.BackgroundRestrictions { public protected *; }

# The two motion types on the public surface. Everything else in the package —
# controllers, wake sources, the sensor probe — is wiring.
-keep public class com.field360.tracker.motion.DeviceSensors { public protected *; }
-keep public class com.field360.tracker.motion.MotionQuality { public protected *; }

# The WorkManager seam the sibling artifacts link against.
#
# Not host API — @RestrictTo(LIBRARY_GROUP) — but `fieldtrack-sync` compiles against this
# module's UNMINIFIED jar and links against the MINIFIED one, so the name is load-bearing
# for exactly the same reason every entry above is. Without this rule R8 renamed it into
# `tr.dev.core` while sync kept emitting `com.field360.tracker.work.WorkManagerAccess`, and
# sync's own R8 pass failed the build with
#
#     Missing class com.field360.tracker.work.WorkManagerAccess
#         (referenced from: void ...LogSyncWorker$Companion.cancel(android.content.Context))
#
# `-dontwarn` is the wrong answer here and worth naming as such, for the reason
# consumer-rules.pro spells out about OkHttp: silencing the warning ships an AAR whose
# workers throw NoClassDefFoundError on the first enqueue, in release only. A cross-module
# call needs the callee's name to survive, not the warning to stop.
#
# Members are kept because the call is `WorkManagerAccess.get(context)` — INSTANCE and
# get(Context) are both named at the sync call site. Everything else in this package
# (SyncScheduler, Watchdog, the worker bodies) stays renamed: nothing in this object's
# signature reaches them, so R8 still repackages them.
-keep public class com.field360.tracker.work.WorkManagerAccess { public protected *; }

# ── reflective entry points ─────────────────────────────────────────────────
#
# Instantiated by name by the framework, so the names must survive whatever the keep
# rules above decided. AGP derives some of these from the manifest; they are restated
# here so this file is correct on its own rather than correct because of a tool
# behaviour nobody remembers.
-keep class com.field360.tracker.service.TrackingService { <init>(); }
-keep class com.field360.tracker.service.BootReceiver { <init>(); }
-keep class com.field360.tracker.motion.ActivityTransitionReceiver { <init>(); }
-keep class com.field360.tracker.motion.StationaryFenceReceiver { <init>(); }

# WorkManager's default factory instantiates workers reflectively by class name
# (BackstopWorker, RestoreWorker, PruneWorker). The dependency ships an equivalent
# consumer rule, but dependency consumer rules apply to APP builds — not to this
# library-mode pass — so it is needed here in its own right.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room resolves TrackerDatabase_Impl via Class.forName(database.name + "_Impl"); both
# ends of that lookup must keep their names. Same reasoning as the worker rule: Room's
# own consumer rule does not run in this pass.
-keep class * extends androidx.room.RoomDatabase

# ── shipped-artifact hygiene ────────────────────────────────────────────────
#
# Logging is useful in development but exposes control-flow and failure details in a
# published SDK. Strip every android.util.Log level from release bytecode.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Most SDK logging goes through the TrackLogger port rather than android.util.Log
# directly. Mark both methods side-effect-free so R8 also removes the string construction
# feeding those calls. The sample still receives structured TrackerEvent diagnostics.
-assumenosideeffects class com.field360.traker.geo.port.TrackLogger {
    public void d(java.lang.String, java.lang.String);
    public void w(java.lang.String, java.lang.String);
}

# ── licence wire fields ─────────────────────────────────────────────────────
#
# Gson maps by reflected field name and this AAR ships obfuscated, so the licence request
# would go out with renamed keys and the server would answer 400 — which fails open, and
# therefore looks exactly like a licence that is fine. `@SerializedName` carries the wire
# names, so only the annotated fields need keeping; the class is still renamed and
# repackaged by the rule below.
# Matches on the annotation, not the package, so moving a DTO cannot silently
# un-keep it — which is what the package-scoped version of this rule allowed.
-keepclassmembers,allowobfuscation class com.field360.tracker.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Everything not kept above is renamed AND moved here, so the package tree itself stops
# describing the architecture (no more capture/, di/, data/db/ to read like a map).
-repackageclasses 'tr.dev.core'

# ── Retrofit service interfaces ─────────────────────────────────────────────
#
# Retrofit builds its calls by reading the annotations off an interface's methods at
# runtime. R8 keeps the interface (it is referenced) but strips annotations from members
# it considers unused, and a service whose @POST is gone fails at `create()` with
# "Method must have a valid HTTP annotation" — at the first licence check, in release
# only. Retrofit ships consumer rules covering its own classes; the service interfaces
# are ours.
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
