package com.field360.fieldtrack.sample

import android.content.Context
import android.os.Build
import com.field360.tracker.RawFix
import com.field360.tracker.domain.model.LocationAccuracy
import com.field360.tracker.domain.model.PermissionTier
import com.field360.tracker.domain.model.ProviderState
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.motion.DeviceSensors
import com.field360.traker.geo.model.FixDecision
import com.field360.traker.geo.model.TrackFix
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.plot.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every capture, every rejection and every state change, appended to one plain-text file.
 *
 * This is a **testing instrument**, not a product feature, and it lives in the sample for
 * the same reason the permission dialogs do: the SDK does no I/O the host did not ask for.
 * `TrackLogger` inside `fieldtrack-core` writes to logcat, which is ring-buffered and gone
 * after a long drive — the whole point here is a file that survives the drive, the app
 * being killed, and the phone being plugged into a laptop hours later.
 *
 * Design constraints that shaped it:
 *  - **Never blocks the caller.** Lines go into a [Channel] and a single writer coroutine
 *    drains them. A slow SD card must not suspend the event collector, because
 *    `Tracker.events` is a `SharedFlow` and a suspended collector loses events.
 *  - **Flushes every line.** A tracking process gets killed by the OEM, by a crash, or by
 *    the user. A buffered tail that never reached disk is exactly the data you needed.
 *  - **One file, appended forever.** Runs are separated by a banner. Rotating files is
 *    worse for the actual job: `adb pull` one path, diff it, grep it.
 *  - **`Locale.US` everywhere.** A decimal comma from a device locale would silently
 *    corrupt every coordinate for anything that parses this file.
 */
class CaptureLog(context: Context) {

    private val context: Context = context.applicationContext

    /**
     * App-scoped external storage: no permission needed, visible over USB/MTP at
     * `Android/data/com.field360.fieldtrack.sample/files/`, and removed on uninstall.
     * Falls back to internal storage on a device with no external volume mounted.
     */
    val file: File by lazy {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, FILE_NAME)
    }

    val path: String get() = file.absolutePath

    fun sizeBytes(): Long = if (file.exists()) file.length() else 0L

    private val lines = Channel<String>(capacity = BUFFERED_LINES)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writer: BufferedWriter? = null

    init {
        scope.launch {
            for (line in lines) append(line)
        }
    }

    // ---------------------------------------------------------------- public writes

    /** Written once per process start, so a file spanning days can still be read. */
    fun runHeader(
        sensors: DeviceSensors?,
        tier: PermissionTier,
        accuracy: LocationAccuracy,
        provider: ProviderState?,
        mapsKeyPresent: Boolean,
        licensePresent: Boolean,
    ) {
        raw("")
        raw(RULE)
        raw("RUN      ${stamp(System.currentTimeMillis())}")
        raw("APP      ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        raw("DEVICE   ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        raw("ANDROID  ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}  fingerprint=${Build.FINGERPRINT}")
        raw("PERM     tier=$tier accuracy=$accuracy")
        raw(
            "PROVIDER " + if (provider == null) "unknown" else
                "gps=${provider.gpsEnabled} network=${provider.networkEnabled} " +
                    "fused=${provider.fusedAvailable} powerSave=${provider.powerSaveMode} " +
                    "tier=${provider.permission} accuracy=${provider.accuracyAuthorization}",
        )
        raw(
            "SENSORS  " + if (sensors == null) "unknown" else
                "accel=${sensors.accelerometer} gyro=${sensors.gyroscope} mag=${sensors.magnetometer} " +
                    "sigMotion=${sensors.significantMotion} stepDet=${sensors.stepDetector} " +
                    "stepCount=${sensors.stepCounter} baro=${sensors.barometer} " +
                    "rotVec=${sensors.rotationVector} quality=${sensors.motionQuality}",
        )
        // Never the key itself — this file gets mailed around.
        raw("MAPSKEY  ${if (mapsKeyPresent) "present" else "absent"}")
        raw("LICENSE  ${if (licensePresent) "present" else "absent"}")
        raw(RULE)
        raw("# columns: <wall clock> | <kind> | key=value ...")
        raw("#")
        raw("# HOW TO READ THIS FILE")
        raw("# Produced by the Tracker sample app. Line kinds:")
        raw("#   RAWFIX   a fix as the OS delivered it, before any filtering")
        raw("#   DECISION what the acceptance pipeline decided about one fix, and why")
        raw("#   POINT    a fix that survived and was stored — these draw the polyline")
        raw("#   ACCEPT / REJECT / SKIP  the same decision, live, as it happened")
        raw("#   HIST     verdict histogram for the session — START HERE")
        raw("#   JUMP     consecutive stored points far enough apart to draw a wrong line,")
        raw("#            each followed by the decisions made inside that gap")
        raw("#   CADENCE  seconds between stored points; the ceiling on turn fidelity")
        raw("#")
        raw("# Units: metres, m/s, degrees. Coordinates are decimal degrees, 7 dp,")
        raw("# Locale.US. `ert` is elapsedRealtimeNanos (monotonic); `t` is wall clock.")
        raw("# `sigma`/`threshold` are the 3-sigma gate: rejected when the distance from")
        raw("# the filter's predicted position exceeds the threshold.")
    }

    /** One line per event. Every field of every payload — nothing summarised away. */
    fun event(event: TrackerEvent) {
        val now = System.currentTimeMillis()
        when (event) {
            is TrackerEvent.Location -> line(now, "ACCEPT", point(event.point))
            is TrackerEvent.LocationRejected -> line(now, verdictKind(event.decision), decision(event.decision))
            is TrackerEvent.MotionChange ->
                line(now, "MOTION", "state=${event.state} atPoint=${event.point?.uuid ?: "-"}")
            is TrackerEvent.ActivityChange ->
                line(now, "ACTIVITY", "type=${event.activity} confidence=${event.confidence}")
            is TrackerEvent.EnabledChange -> line(now, "ENABLED", "enabled=${event.enabled}")
            is TrackerEvent.ProviderChange -> line(
                now,
                "PROVIDER",
                "gps=${event.state.gpsEnabled} network=${event.state.networkEnabled} " +
                    "fused=${event.state.fusedAvailable} powerSave=${event.state.powerSaveMode} " +
                    "tier=${event.state.permission} accuracy=${event.state.accuracyAuthorization}",
            )
            is TrackerEvent.Heartbeat -> line(now, "HEARTBEAT", "atMs=${event.atMs} at=${stamp(event.atMs)}")
            is TrackerEvent.PowerSaveChange -> line(now, "POWERSAVE", "enabled=${event.enabled}")
            // The three that explain a hole in the polyline. Read together with the
            // PROVIDER line either side of them: SUSPEND says capture stopped and why,
            // RESUME bounds the gap, and PERMISSION says what the user did to cause it.
            is TrackerEvent.PermissionChange -> line(
                now,
                "PERMISSION",
                "previous=${event.previous} current=${event.current} accuracy=${event.accuracy}",
            )
            is TrackerEvent.LocationServicesChange -> line(
                now,
                "LOCSERVICES",
                "enabled=${event.enabled} gps=${event.state.gpsEnabled} " +
                    "network=${event.state.networkEnabled} master=${event.state.locationServicesEnabled}",
            )
            is TrackerEvent.CaptureSuspended ->
                line(now, "SUSPEND", "reason=${event.reason} message=${event.message}")
            TrackerEvent.CaptureResumed -> line(now, "RESUME", "capture re-armed")
            is TrackerEvent.GeofenceAdded ->
                line(now, "GEOFENCE", "added id=${event.geofence.id} radius=${event.geofence.radiusM}")
            is TrackerEvent.GeofenceRemoved ->
                line(now, "GEOFENCE", "removed id=${event.geofenceId}")
            is TrackerEvent.GeofenceEntered ->
                line(now, "GEOFENCE", "enter id=${event.geofence.id} radius=${event.geofence.radiusM}")
            is TrackerEvent.GeofenceExited ->
                line(now, "GEOFENCE", "exit id=${event.geofence.id} radius=${event.geofence.radiusM}")
            is TrackerEvent.SessionInterrupted ->
                line(now, "INTERRUPT", "session=${event.session.id} tag=${event.session.tag ?: "-"}")
            is TrackerEvent.BatteryChange -> line(
                now,
                "BATTERY",
                "percent=${event.battery.percent} charging=${event.battery.isCharging} " +
                    "source=${event.battery.powerSource}",
            )
            // Every signal, not just the blocking ones: a capture taken on a device that
            // was merely warned about is exactly the file you want to read afterwards.
            is TrackerEvent.IntegrityChange -> line(
                now,
                "INTEGRITY",
                "waived=${event.report.waived} flags=${event.report.flags} " +
                    "signals=${event.report.findings.joinToString("|") {
                        "${it.signal}@${it.policy}(${it.confidence})"
                    }.ifEmpty { "-" }}",
            )
            // Logged on every check, `ACTIVE` included. A capture that only recorded
            // licence failures could not distinguish a healthy licence from a check that
            // never ran, which is exactly the question a support file has to answer.
            is TrackerEvent.LicenseChecked -> line(
                now,
                "LICENCE",
                "status=${event.info.status} valid=${event.info.valid} " +
                    "cached=${event.info.fromCache} ttl=${event.info.ttlSeconds}s " +
                    "checkedAt=${event.info.checkedAt} reason=${event.info.reason ?: "-"}",
            )
            is TrackerEvent.Diagnostic -> line(now, "DIAG", "message=${event.message}")
            is TrackerEvent.Error -> line(now, "ERROR", "code=${event.code} message=${event.message}")
        }
    }

    /** Host-side milestones the SDK cannot know about — button presses, results. */
    fun note(kind: String, detail: String) = line(System.currentTimeMillis(), kind, detail)

    /**
     * The full picture for one session, written on stop.
     *
     * Events only carry what happened live. This adds what the database actually holds:
     * every raw fix the provider delivered (including ones rejected before any event was
     * emitted), every stored decision, and every persisted point.
     */
    fun sessionDump(
        sessionId: String?,
        rawFixes: List<RawFix>,
        decisions: List<FixDecision>,
        points: List<TrackPoint>,
    ) {
        raw("")
        raw(RULE)
        raw("SESSION DUMP ${sessionId ?: "(none)"} at ${stamp(System.currentTimeMillis())}")
        raw("counts rawFixes=${rawFixes.size} decisions=${decisions.size} points=${points.size}")
        raw(RULE)

        raw("-- raw fixes (as delivered by the provider, pre-filter) --")
        rawFixes.forEach {
            raw(
                "RAWFIX   t=${it.timeMs} ${stamp(it.timeMs)} lat=${deg(it.latitude)} lng=${deg(it.longitude)} " +
                    "acc=${num(it.accuracy)} brg=${num(it.bearingDeg)} prov=${it.provider}",
            )
        }

        raw("-- decisions (every fix the pipeline judged) --")
        decisions.forEach { raw("DECISION ${verdictKind(it)} ${decision(it)}") }

        raw("-- stored points --")
        points.forEach { raw("POINT    ${point(it)}") }

        analysis(decisions, points)
        raw(RULE)
    }

    /**
     * The part an analyst — human or otherwise — reads first.
     *
     * The dumps above are complete but not *answerable*: three thousand `DECISION` lines
     * do not tell you that nine hundred of them said the same thing. Every block here
     * exists because a real question needed it:
     *
     *  - **Verdict histogram.** "Coordinates go missing" is always one reason dominating.
     *    A wall of `Drift Suppressed` is a departure-ladder problem; a wall of
     *    `Sigma Gate Outlier` is a filter-lag problem. They have opposite fixes, and this
     *    is the one line that tells them apart.
     *  - **Jumps.** The visible symptom is a straight line across a corner. This lists
     *    each oversized gap between consecutive stored points *with the decisions that
     *    fell inside it*, which converts "the polyline is wrong here" into "these eleven
     *    fixes were rejected for this reason".
     *  - **Cadence.** Sampling interval is the ceiling on turn fidelity. If the median
     *    gap is 60 s while driving, no filter change will help and the answer is upstream.
     */
    private fun analysis(decisions: List<FixDecision>, points: List<TrackPoint>) {
        raw("")
        raw("-- ANALYSIS ------------------------------------------------------------")
        raw("# Read this first. Everything below is derived from the dumps above.")

        if (decisions.isEmpty() && points.isEmpty()) {
            raw("ANALYSIS empty session — nothing was offered to the pipeline")
            return
        }

        raw("-- verdict histogram (why fixes did or did not become points) --")
        decisions
            .groupingBy { "${verdictKind(it)}/${it.reason}" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .forEach { (key, count) ->
                val share = 100.0 * count / decisions.size
                raw("HIST     ${"%-28s".format(key)} n=${"%-5d".format(count)} ${num(share)}%")
            }

        raw("-- accepted points by reason --")
        points.groupingBy { it.acceptReason }.eachCount()
            .entries.sortedByDescending { it.value }
            .forEach { (reason, count) -> raw("ACCEPTBY ${"%-24s".format(reason)} n=$count") }

        cadence(points)
        jumps(decisions, points)
    }

    /** Median and worst gap between stored points — the ceiling on turn fidelity. */
    private fun cadence(points: List<TrackPoint>) {
        if (points.size < 2) return
        val gaps = points.zipWithNext { a, b -> (b.timeMs - a.timeMs) / 1000.0 }.sorted()
        raw("-- cadence between STORED points, seconds --")
        raw(
            "CADENCE  median=${num(gaps[gaps.size / 2])} min=${num(gaps.first())} " +
                "max=${num(gaps.last())} n=${gaps.size}",
        )
    }

    /**
     * Gaps between consecutive stored points wide enough to draw a wrong line, each
     * annotated with the decisions that were made inside them.
     *
     * The threshold is distance, not time: a 300 m straight-line hop is a visible defect
     * whether it took twelve seconds or twelve minutes.
     */
    private fun jumps(decisions: List<FixDecision>, points: List<TrackPoint>) {
        if (points.size < 2) return

        raw("-- jumps: consecutive stored points more than ${JUMP_M.toInt()} m apart --")
        var found = 0

        points.zipWithNext().forEach { (from, to) ->
            val metres = haversine(from.latitude, from.longitude, to.latitude, to.longitude)
            if (metres < JUMP_M) return@forEach
            found++

            val seconds = (to.timeMs - from.timeMs) / 1000.0
            raw(
                "JUMP     ${stamp(from.timeMs)} -> ${stamp(to.timeMs)} " +
                    "gap=${num(metres)}m over ${num(seconds)}s " +
                    "impliedSpeed=${num(if (seconds > 0) metres / seconds else 0.0)}m/s " +
                    "from=${deg(from.latitude)},${deg(from.longitude)} " +
                    "to=${deg(to.latitude)},${deg(to.longitude)}",
            )

            // What the pipeline decided while the map was drawing a straight line.
            val inside = decisions.filter { it.fix.timeMs > from.timeMs && it.fix.timeMs < to.timeMs }
            if (inside.isEmpty()) {
                raw("  └─ no fixes were offered in this window — a capture gap, not a filter gap")
            } else {
                inside.groupingBy { "${verdictKind(it)}/${it.reason}" }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .forEach { (key, count) -> raw("  └─ $key n=$count") }
            }
        }

        if (found == 0) raw("JUMP     none — no stored gap exceeds ${JUMP_M.toInt()} m")
    }

    /** Local copy so the sample never reaches into the engine's internals for one formula. */
    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * What the plotting plane made of the stored points.
     *
     * The dumps say what was captured; this says what got *drawn*. When the two disagree
     * — points present but a segment missing, or a `snap_unavailable` warning — the fault
     * is in plotting, not capture, and that is a different file to go and read.
     */
    fun trackSummary(track: Track) {
        raw("-- built track --")
        raw(
            "TRACK    session=${track.sessionId ?: "-"} points=${track.stats.pointCount} " +
                "segments=${track.segments.size} stops=${track.stats.stopCount} " +
                "arrows=${track.arrows.size} distanceM=${num(track.stats.distanceMeters)} " +
                "movingSec=${track.stats.movingSec} stoppedSec=${track.stats.stoppedSec} " +
                "precision=${track.precision}",
        )
        track.segments.forEachIndexed { index, segment ->
            raw(
                "SEGMENT  #$index type=${segment.type} from=${segment.from} to=${segment.to} " +
                    "distanceM=${num(segment.distanceMeters)} durationSec=${segment.durationSec} " +
                    "avgSpd=${num(segment.avgSpeedMps)} maxSpd=${num(segment.maxSpeedMps)} " +
                    "activity=${segment.activity ?: "-"} band=${segment.speedBand ?: "-"}",
            )
        }
        raw("WARNINGS ${track.warnings.ifEmpty { listOf("none") }.joinToString(",")}")
    }

    /** Truncates the file. The next write re-creates it. */
    fun clear() {
        scope.launch {
            runCatching {
                closeWriter()
                file.writeText("")
            }
        }
    }

    // ---------------------------------------------------------------- formatting

    private fun point(p: TrackPoint): String = buildString {
        append("uuid=${p.uuid} session=${p.sessionId} ")
        append("t=${p.timeMs} at=${stamp(p.timeMs)} ert=${p.elapsedRealtimeNanos} ")
        append("date=${p.localDate} tz=${p.timezone} ")
        append("lat=${deg(p.latitude)} lng=${deg(p.longitude)} acc=${num(p.accuracy)} ")
        append("alt=${p.altitude?.let { num(it) } ?: "-"} ")
        append("spd=${num(p.speedMps)} brg=${num(p.bearingDeg)} ")
        append("hasSpd=${p.hasSpeed} hasBrg=${p.hasBearing} ")
        append("prov=${p.provider} mock=${p.isMock} ")
        append("move=${p.movementStatus} activity=${p.detectedActivity ?: "-"} ")
        append("activityStart=${p.activityStartTimeMs} odometer=${num(p.odometerMeters)} ")
        append("battery=${p.batteryPct ?: "-"} charging=${p.isCharging ?: "-"} ")
        append("extras=${p.extras ?: "-"} reason=${p.acceptReason}")
    }

    private fun decision(d: FixDecision): String = buildString {
        append("reason=${d.reason} verdict=${d.verdict::class.simpleName} ")
        append(fix(d.fix))
        append(" filterLat=${deg(d.filterLat)} filterLng=${deg(d.filterLng)} ")
        append("sigma=${num(d.sigma)} threshold=${num(d.threshold)} ")
        append("distanceM=${num(d.distanceMovedM)} effSpd=${num(d.effectiveSpeedMps)} motion=${d.motionState}")
    }

    private fun fix(f: TrackFix): String = buildString {
        append("fixT=${f.timeMs} fixAt=${stamp(f.timeMs)} ert=${f.elapsedRealtimeNanos} ")
        append("receivedErt=${f.receivedAtElapsedNanos} ")
        append("lat=${deg(f.latitude)} lng=${deg(f.longitude)} acc=${num(f.accuracy)} ")
        append("alt=${f.altitude?.let { num(it) } ?: "-"} vAcc=${f.verticalAccuracy?.let { num(it) } ?: "-"} ")
        append("spd=${num(f.speedMps)} brg=${num(f.bearingDeg)} ")
        append("hasSpd=${f.hasSpeed} hasBrg=${f.hasBearing} ")
        append("prov=${f.provider} mock=${f.isMock} sats=${f.satelliteCount ?: "-"} ")
        append("looksNlp=${f.looksLikeNetworkFix}")
    }

    private fun verdictKind(d: FixDecision): String =
        d.verdict::class.simpleName.orEmpty().uppercase(Locale.US).ifEmpty { "DECISION" }

    private fun line(atMs: Long, kind: String, detail: String) =
        raw("${stamp(atMs)} | ${kind.padEnd(KIND_WIDTH)} | $detail")

    private fun raw(text: String) {
        // Dropping is the right failure: 4096 queued lines means something is very wrong
        // with the disk, and blocking the event collector would lose events instead.
        lines.trySend(text)
    }

    private fun stamp(atMs: Long): String =
        TIMESTAMP.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

    private fun deg(value: Double): String = String.format(Locale.US, "%.7f", value)

    private fun num(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun num(value: Float): String = String.format(Locale.US, "%.2f", value)

    // ---------------------------------------------------------------- file I/O

    private fun append(text: String) {
        runCatching {
            val out = writer ?: BufferedWriter(FileWriter(file, true)).also { writer = it }
            out.write(text)
            out.newLine()
            // Flush per line on purpose: see the class KDoc. A tracking process dies
            // without warning and the unflushed tail is the interesting part.
            out.flush()
        }.onFailure {
            // Storage unmounted, file deleted under us, quota hit. Drop the handle so the
            // next line re-opens rather than failing forever against a dead stream.
            closeWriter()
        }
    }

    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
    }

    private companion object {
        const val FILE_NAME = "fieldtrack-capture.txt"
        const val BUFFERED_LINES = 4096
        const val KIND_WIDTH = 9

        /**
         * A gap this wide between stored points draws a visibly wrong line. Roughly two
         * legs at the 12 s vehicular cadence and 40 km/h, so ordinary sampling does not
         * trip it but a swallowed fix does.
         */
        const val JUMP_M = 250.0
        const val EARTH_RADIUS_M = 6_371_000.0
        const val RULE = "================================================================================"
        val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
