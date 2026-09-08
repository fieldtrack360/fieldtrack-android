# Tracker sync — build-time R8 configuration for the published release AAR.
#
# See fieldtrack-core/proguard-rules.pro for the consumer-rules distinction and the honest
# limits of obfuscation.

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The public surface: TrackerSync, SyncConfig, SyncTransport and its request/response
# family, SyncQueue.Result, OkHttpSyncTransport. A host implementing SyncTransport
# compiles against these names.
-keep public class com.field360.traker.sync.TrackerSync { public protected *; }
-keep public class com.field360.traker.sync.TrackerSync$* { public protected *; }
-keep public class com.field360.traker.sync.SyncConfig { public protected *; }
-keep public class com.field360.traker.sync.SyncConfig$Builder { public protected *; }
-keep public class com.field360.traker.sync.SyncConfig$Companion { public protected *; }
-keep public class com.field360.traker.sync.SyncTransport { public protected *; }
-keep public class com.field360.traker.sync.SyncRequest { public protected *; }
-keep public class com.field360.traker.sync.SyncResponse { public protected *; }
-keep public class com.field360.traker.sync.SyncResponse$* { public protected *; }
-keep public class com.field360.traker.sync.SyncPayload { public protected *; }
-keep public class com.field360.traker.sync.SyncPayload$Companion { public protected *; }
-keep public class com.field360.traker.sync.SyncPoint { public protected *; }
-keep public class com.field360.traker.sync.SyncPoint$Companion { public protected *; }
-keep public class com.field360.traker.sync.OkHttpSyncTransport { public protected *; }
-keep public class com.field360.traker.sync.OkHttpSyncTransport$Companion { public protected *; }
-keep public class com.field360.traker.sync.SyncQueue { public protected *; }
-keep public class com.field360.traker.sync.SyncQueue$* { public protected *; }
-keep public class com.field360.traker.sync.SyncTimeouts { public protected *; }
-keep public class com.field360.traker.sync.SyncTimeouts$Companion { public protected *; }
-keep public class com.field360.traker.sync.SyncEvent { public protected *; }
-keep public class com.field360.traker.sync.SyncEvent$* { public protected *; }

# The diagnostic channel's public surface (docs/APP-LOG-API.md). Every one of these is
# reachable from a kept TrackerSync member — `configureLogs(LogSyncConfig)`, `log(LogLevel,
# …)`, `getLogs(): List<LogRecord>`, `syncLogsNow(): LogSyncQueue.Result` — and being
# reachable is NOT being kept: R8 renamed them to a.class … g.class and, because they share
# a package with the pinned TrackerSync, could not repackage them either. The result was
# seven shortened classes sitting in the API package, which `verifyReleaseObfuscation`
# rejects, and a published AAR in which a host could not name `LogSyncConfig` at all.
-keep public class com.field360.traker.sync.LogSyncConfig { public protected *; }
-keep public class com.field360.traker.sync.LogSyncConfig$Builder { public protected *; }
-keep public class com.field360.traker.sync.LogSyncConfig$Companion { public protected *; }
-keep public class com.field360.traker.sync.LogSyncQueue { public protected *; }
-keep public class com.field360.traker.sync.LogSyncQueue$* { public protected *; }
-keep public class com.field360.traker.sync.LogRecord { public protected *; }
-keep public class com.field360.traker.sync.LogLevel { public protected *; }
-keep public class com.field360.traker.sync.LogType { public protected *; }
-keep public class com.field360.traker.sync.LifecyclePhase { public protected *; }

# The wire DTOs. Kept for the same reason SyncPayload and SyncPoint are: a host supplying
# its own SyncTransport decodes these. Generated `$$serializer` implementations are
# deliberately NOT kept — the companion `serializer()` is the stable entry point, and the
# JSON keys live as literals in the descriptor, so the body on the wire is unchanged.
-keep public class com.field360.traker.sync.LogPayload { public protected *; }
-keep public class com.field360.traker.sync.LogPayload$Companion { public protected *; }
-keep public class com.field360.traker.sync.LogAppInfo { public protected *; }
-keep public class com.field360.traker.sync.LogAppInfo$Companion { public protected *; }
-keep public class com.field360.traker.sync.LogDeviceInfo { public protected *; }
-keep public class com.field360.traker.sync.LogDeviceInfo$Companion { public protected *; }
-keep public class com.field360.traker.sync.LogEntryDto { public protected *; }
-keep public class com.field360.traker.sync.LogEntryDto$Companion { public protected *; }

# WorkManager instantiates SyncWorker reflectively; the dependency's own consumer rule
# does not run in this library-mode pass.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room resolves LogDatabase_Impl via Class.forName(database.name + "_Impl"), so both ends of
# that lookup must keep their names. The Room compiler emits an equivalent rule into this
# module's generated configuration, which is why the log database survived before this line
# existed — restated here so the file is correct on its own rather than correct because of a
# tool behaviour nobody remembers. Same reasoning as the worker rule above, and as
# fieldtrack-core's copy.
-keep class * extends androidx.room.RoomDatabase

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-assumenosideeffects class com.field360.traker.geo.port.TrackLogger {
    public void d(java.lang.String, java.lang.String);
    public void w(java.lang.String, java.lang.String);
}

-repackageclasses 'tr.dev.sync'
