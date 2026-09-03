# Tracker core — rules a consuming app inherits.
#
# These are *consumer* rules: they are merged into the host's R8 configuration, so every
# line here constrains somebody else's build. That is the reason this file is short and
# specific rather than a `-keep class com.field360.tracker.** { *; }`. A blanket keep on
# an SDK this size would add several hundred KB to every host's APK to protect against a
# handful of reflective lookups, and it would hide the next one rather than document it.
#
# Most of what an SDK like this needs is already shipped by the libraries themselves, and
# duplicating their rules here would mean maintaining a stale copy:
#
#   kotlinx-serialization  keeps `Companion`, `serializer()`, `INSTANCE` and
#                          `$$serializer.descriptor` for every `@Serializable` class,
#                          and keeps the annotations. Field *names* need no keep: the
#                          generated descriptor carries them as string literals, so the
#                          wire format survives obfuscation by construction.
#   room-runtime           `-keep class * extends androidx.room.RoomDatabase`, which
#                          covers TrackerDatabase and the generated `_Impl` it loads.
#                          Entities and DAOs are referenced statically by generated code.
#   work-runtime           `-keepnames class * extends androidx.work.ListenableWorker`
#                          plus their constructors, which covers BackstopWorker,
#                          RestoreWorker and PruneWorker.
#   AGP                    generates keeps from the merged manifest, which covers
#                          TrackingService, BootReceiver, ActivityTransitionReceiver and
#                          StationaryFenceReceiver.
#
# What is left is the one thing nothing else covers.

# ── enum constant names ─────────────────────────────────────────────────────
#
# THE rule in this file, and the only one whose absence is a silent data bug rather than
# a crash.
#
# Stored points persist `movementStatus`, `detectedActivity` and `motionState` as their
# enum *name*, and read them back with `valueOf` (data/db/Mappers.kt, Repositories.kt).
# Those call sites are `runCatching { … }.getOrDefault(…)` — deliberately, because a row
# written by a newer version must not crash an older one. The consequence under
# obfuscation is that a renamed constant does not throw: it falls through to the default.
#
# So without this rule, a host's *release* build reads every stored point back as
# STEADY / UNKNOWN / STOPPED. No exception, no log, no failed request. Motion history,
# activity segments and the debug overlay are all quietly wrong, in the one build
# configuration that ships and the one nobody runs the test suite against.
#
# `<fields>` is what preserves the constant names; `values()`/`valueOf()` keep the
# accessors a host may call on a name it persisted itself.
-keepclassmembers,allowoptimization enum com.field360.tracker.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# `IntegritySignal` is covered by the rule above and depends on it for the same reason,
# one step further out: its constant names are uploaded verbatim in `integrity_signals`
# and a backend rule matches on them. A renamed constant there is a security signal that
# silently stops matching in release builds only.

# `fieldtrack-geo` ships the equivalent rule in its own AAR. This duplicate remains because
# core persists geo enum names directly and must protect them even if dependency rule
# aggregation changes. The rule is idempotent in a host R8 configuration.

# ── licence wire fields ─────────────────────────────────────────────────────
#
# The second silent-data-bug rule, and it fails the same way the enum one does: quietly,
# in release only.
#
# `VerifyRequestDto` is serialised by Gson, which maps by reflected field name. R8 renames
# those fields, so without this the revocation request goes out as `{"a":…,"b":…}`, the
# server answers 400, and the check fails open — which is indistinguishable from a licence
# that is fine. `@SerializedName` carries the wire names, so keeping the annotated fields
# is enough; the class itself may be renamed and flattened.
# Matches on the annotation, not the package, so moving a DTO cannot silently
# un-keep it — which is what the package-scoped version of this rule allowed.
-keepclassmembers,allowobfuscation class com.field360.tracker.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

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

# ── deliberately NOT here: anything about OkHttp ─────────────────────────────
#
# Recorded because the question comes back every time a host hits
#
#     java.lang.NoClassDefFoundError: Failed resolution of: Lokhttp3/internal/Util;
#         at okhttp3.JavaNetCookieJar.decodeHeaderAsJavaNetCookies(JavaNetCookieJar.kt:81)
#
# and reaches for a keep rule. No rule fixes it. `-keep` preserves classes that exist,
# and OkHttp 5 deleted `okhttp3.internal.Util` outright — it is in no artifact on the
# classpath, so the rule matches nothing. The cause is a split OkHttp family: this module
# links OkHttp 5 for the licence check, Gradle's conflict resolution raises the `okhttp`
# module alone, and a sibling left at 4.x (React Native ships `okhttp-urlconnection`
# there for its cookie jar) calls into the removed class. The fix is the version
# constraint in build.gradle.kts, not R8 configuration.
#
# `-dontwarn okhttp3.**` is the actively wrong answer and worth naming as such. R8 fails
# the build on a missing class referenced from kept code, which is the check that catches
# this split at compile time. OkHttp's own AAR narrows its suppression to
# `-dontwarn okhttp3.internal.platform.**` precisely so the rest stays loud. Widening it
# in a host turns a build error into a production crash on the first cookie-bearing
# request — which is exactly how this one shipped.
