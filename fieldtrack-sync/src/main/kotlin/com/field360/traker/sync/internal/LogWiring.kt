package com.field360.traker.sync.internal

import com.field360.tracker.Tracker
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger
import com.field360.traker.sync.LogAppInfo
import com.field360.traker.sync.LogDeviceInfo
import com.field360.traker.sync.LogRecorder
import com.field360.traker.sync.LogSyncQueue
import com.field360.traker.sync.SyncEvent
import com.field360.traker.sync.data.db.LogDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import com.field360.tracker.domain.model.TrackerEvent

/**
 * Builds the two halves of the diagnostic channel, **outside the API package**.
 *
 * Not a convenience. `LogRecorder` and `LogSyncQueue` take their view of the tracker as
 * lambdas, and a lambda compiles to a class in the file that writes it. Written inline in
 * `TrackerSync.Companion.build()` they became
 * `TrackerSync$Companion$build$logQueue$1` and `$2` — synthetic classes R8 renames (they are
 * not public, so no keep rule matches them) but will not repackage out of a package holding
 * pinned classes. The release AAR therefore shipped `com/field360/traker/sync/a.class` and
 * `b.class` sitting in the published API package, which `verifyReleaseObfuscation` rejects.
 *
 * Declared here, the same lambdas are ordinary internals with nothing pinned beside them, so
 * R8 renames *and* repackages them into `tr.dev.sync` like everything else in this package.
 * `fieldtrack-core` keeps its integrity internals in `integrity.internal` for exactly this
 * reason — see its `proguard-rules.pro`.
 */
internal fun newLogRecorder(
    dao: LogDao,
    clock: Clock,
    scope: CoroutineScope,
    logger: TrackLogger,
    tracker: Tracker,
    events: SharedFlow<TrackerEvent>,
): LogRecorder = LogRecorder(
    dao = dao,
    clock = clock,
    scope = scope,
    logger = logger,
    sessionId = { tracker.state.value.currentSessionId },
    events = events,
    sensors = { runCatching { tracker.getSensors() }.getOrNull() },
    // Read separately from the probe above, which folds this grant into its two step
    // fields: a `false` there could mean no sensor or no permission, and the remedies are
    // a different phone and a prompt.
    activityRecognitionGranted = {
        runCatching { tracker.permissions().hasActivityRecognition() }.getOrDefault(false)
    },
    // `Tracker.state` is published by a second collector of the same event flow the
    // recorder attaches to, so at the instant `EnabledChange(true)` reaches the recorder
    // the id there may still be the previous session's — or null. The session store is the
    // authority, and the cached state is the fallback for a read that fails.
    openSessionId = {
        runCatching { tracker.currentSession()?.id }.getOrNull()
            ?: tracker.state.value.currentSessionId
    },
)

/** @see newLogRecorder — the same packaging reason applies to both suspend lambdas here. */
internal fun newLogSyncQueue(
    dao: LogDao,
    recorder: LogRecorder,
    tracker: Tracker,
    clock: Clock,
    logger: TrackLogger,
    appInfo: LogAppInfo,
    deviceInfo: LogDeviceInfo,
    onEvent: (SyncEvent) -> Unit,
): LogSyncQueue = LogSyncQueue(
    dao = dao,
    recorder = recorder,
    // The open session, or the newest the device has. `getDecisions` can only attribute a
    // row to a session when it is asked for one, and that is also the session anybody is
    // diagnosing.
    activeSession = {
        tracker.state.value.currentSessionId ?: tracker.getSessions().firstOrNull()?.id
    },
    decisions = { session, limit, offset -> tracker.getDecisions(session, limit, offset) },
    clock = clock,
    logger = logger,
    appInfo = appInfo,
    deviceInfo = deviceInfo,
    onEvent = onEvent,
)
