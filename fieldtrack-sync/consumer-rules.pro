# Tracker sync — rules a consuming app inherits.
#
# Nothing. The previous rule here was:
#
#     -keep class com.field360.traker.sync.** { *; }
#
# which kept every class and every member in the module, including `SyncQueue`'s internals
# and the OkHttp transport a host may not even be using. It was written for
# `SyncPayload` and `SyncPoint`, which are `@Serializable` — and those are already covered
# by `kotlinx-serialization-core`'s own consumer rules, which keep `Companion`,
# `serializer()` and the generated descriptor. The JSON keys are string literals in that
# descriptor, so the uploaded payload survives obfuscation unchanged.
#
# `SyncWorker` is covered by `work-runtime`'s `-keepnames class * extends
# androidx.work.ListenableWorker`, and the class itself survives shrinking because
# `OneTimeWorkRequestBuilder<SyncWorker>()` is reified into a real class reference.
#
# `SyncTransport` is an interface a host implements, so the host's own code keeps it.
#
# The diagnostic channel (docs/APP-LOG-API.md) adds nothing here either, for the same
# reasons one at a time:
#
#   * `LogPayload`, `LogAppInfo`, `LogDeviceInfo` and `LogEntryDto` are `@Serializable`, so
#     `kotlinx-serialization-core`'s consumer rules already keep `Companion`, `serializer()`
#     and the descriptor the JSON keys live in.
#   * `LogSyncWorker` is covered by `work-runtime`'s `-keepnames class * extends
#     androidx.work.ListenableWorker`, and survives shrinking because
#     `PeriodicWorkRequestBuilder<LogSyncWorker>()` reifies into a real class reference.
#   * `LogDatabase` is covered by `room-runtime`'s own consumer rules, which keep every
#     `RoomDatabase` subclass so `Class.forName(name + "_Impl")` still resolves.
#   * `LogSyncConfig`, `LogLevel`, `LogType`, `LifecyclePhase`, `LogRecord` and
#     `LogSyncQueue.Result` are ordinary API. A host that calls them keeps them by
#     referencing them; a host that never calls `configureLogs()` should have them shrunk
#     away, which is the entire point of not writing a rule here.
#
# Those names ARE pinned in `proguard-rules.pro`, which is a different file with a different
# job: that pass builds the published AAR, and a renamed class is one a host cannot name.
#
# A blanket keep on a module is the easiest rule to write and the hardest to remove later,
# because nobody can tell afterwards which line it was protecting. Removing it costs the
# host a smaller APK and costs us nothing that is not already covered.
