package com.field360.tracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        TrackSessionEntity::class,
        TrackPointEntity::class,
        RawFixEntity::class,
        FixDecisionEntity::class,
        ActivitySegmentEntity::class,
        FilterStateEntity::class,
        RawPointEntity::class,
    ],
    version = 9,
    // EC-83: schemas are committed and migrations are explicit. A library that
    // silently wipes its host's data on upgrade is not shippable, so
    // fallbackToDestructiveMigration() is never called anywhere in this module.
    exportSchema = true,
)
internal abstract class TrackerDatabase : RoomDatabase() {
    abstract fun points(): TrackPointDao
    abstract fun sessions(): TrackSessionDao
    abstract fun decisions(): FixDecisionDao
    abstract fun filterState(): FilterStateDao
    abstract fun activity(): ActivitySegmentDao
    abstract fun rawFixes(): RawFixDao
    abstract fun rawPoints(): RawPointDao

    companion object {
        /** Package-scoped so a host app's own Room database can never collide (EC-84). */
        fun nameFor(context: Context): String = "fieldtrack-${context.packageName}.db"

        /**
         * v1 → v2: bearing persisted alongside accuracy on the two capture tables.
         *
         * `track_point` already carried `bearingDeg`; `raw_fix` and `fix_decision` kept
         * only the `hasBearing` flag, which made a stored decision impossible to replay
         * against the heading the device actually reported.
         *
         * Additive and non-destructive, per EC-83. `0` is the correct default because it
         * is what `TrackFix.bearingDeg` already defaults to when the provider reports no
         * bearing — existing rows read back exactly as they behaved before.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE raw_fix ADD COLUMN bearingDeg REAL NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE fix_decision ADD COLUMN bearingDeg REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 → v3: the filter remembers the heading at the last stored point, so a turn
         * can force a capture (EC-45).
         *
         * Additive and non-destructive, per EC-83. `-1` is the correct default because it
         * is `FilterState.BEARING_UNSET`: a database written before this column existed
         * restores as "no heading captured yet", and the first stored point after the
         * upgrade seeds it. The alternative — defaulting to `0` — would claim the user
         * was last seen heading due north and fabricate a turn on the very next fix.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE filter_state ADD COLUMN lastCapturedBearingDeg REAL NOT NULL DEFAULT -1",
                )
            }
        }

        /**
         * v3 → v4: the filter carries a velocity estimate and its covariance (EC-44a).
         *
         * Additive and non-destructive, per EC-83. The defaults are the seed values, so a
         * database written by the scalar filter restores as "position known, velocity
         * unknown" — which is exactly true, and is what the filter would hold after a
         * reseed. `varianceVel` defaults to `FilterState.VELOCITY_VARIANCE_SEED`; a `0`
         * there would claim the old filter had been certain the user was stationary, and
         * the first fix after the upgrade would be gated against a prediction that says
         * they never moved.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE filter_state ADD COLUMN velocityNorthMps REAL NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE filter_state ADD COLUMN velocityEastMps REAL NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE filter_state ADD COLUMN covPosVel REAL NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE filter_state ADD COLUMN varianceVel REAL NOT NULL DEFAULT 25")
            }
        }

        /**
         * v4 → v5: the hardware's own confidence in its Doppler speed and bearing,
         * captured on the raw-fix layer so downstream weighting is possible at all
         * (SMOOTH-NAV-PLAN Phase 1).
         *
         * Additive and non-destructive, per EC-83. Nullable with no default because
         * `null` is the honest value for every existing row — "the platform did not
         * report one". A numeric default would fabricate a confidence the hardware
         * never claimed, exactly the assumption A8 exists to forbid.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE raw_fix ADD COLUMN speedAccuracyMps REAL")
                connection.execSQL("ALTER TABLE raw_fix ADD COLUMN bearingAccuracyDeg REAL")
            }
        }

        /**
         * v5 → v6: `raw_points` — every fix in point form, whatever the verdict.
         *
         * Additive and non-destructive, per EC-83: a new table touches nothing that
         * exists, and a database upgraded into v6 simply starts empty here. The DDL is
         * hand-written to match what Room generates for [RawPointEntity] exactly —
         * column order, `NOT NULL`s, the `CASCADE` foreign key and all three indices —
         * because Room validates the schema on first open and a single missing index
         * name is a fatal `IllegalStateException` at startup, not a silent difference.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `raw_points` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `uuid` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `timeMs` INTEGER NOT NULL,
                        `elapsedRealtimeNanos` INTEGER NOT NULL,
                        `localDate` TEXT NOT NULL,
                        `timezone` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `accuracy` REAL NOT NULL,
                        `altitude` REAL,
                        `speedMps` REAL NOT NULL,
                        `bearingDeg` REAL NOT NULL,
                        `hasSpeed` INTEGER NOT NULL,
                        `hasBearing` INTEGER NOT NULL,
                        `provider` TEXT NOT NULL,
                        `isMock` INTEGER NOT NULL,
                        `movementStatus` TEXT NOT NULL,
                        `detectedActivity` TEXT,
                        `activityStartTimeMs` INTEGER NOT NULL,
                        `odometerMeters` REAL NOT NULL,
                        `batteryPct` INTEGER,
                        `isCharging` INTEGER,
                        `extras` TEXT,
                        `verdict` TEXT NOT NULL,
                        `reason` TEXT NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `track_session`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_raw_points_sessionId_timeMs` " +
                        "ON `raw_points` (`sessionId`, `timeMs`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_raw_points_timeMs` ON `raw_points` (`timeMs`)",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_points_uuid` ON `raw_points` (`uuid`)",
                )
            }
        }

        /**
         * v6 → v7: the device-integrity bitmask, on all three point-shaped tables.
         *
         * Additive and non-destructive, per EC-83. `0` is the correct default for every
         * existing row and is *not* a claim of cleanliness — it is `IntegrityReport.flags`
         * for "nothing observed", which is also what a debuggable build and a host with
         * the layer disabled produce. A backend telling "clean" from "not evaluated" reads
         * the SDK version, not this column.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE track_point ADD COLUMN integrityFlags INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE raw_fix ADD COLUMN integrityFlags INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE raw_points ADD COLUMN integrityFlags INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v7 → v8: the location-subsystem snapshot, on the two point-shaped tables.
         *
         * Additive and non-destructive, per EC-83. `0` is `ProviderSnapshot.NOT_RECORDED`
         * and is the correct default for every existing row: it says "no snapshot was
         * taken", which is exactly true of a point captured before this column existed. It
         * is deliberately **not** a snapshot with every field off — a row claiming location
         * services were disabled and permission denied, on points that plainly were
         * captured, would be worse than no column at all. The `recorded` bit is what keeps
         * those two apart.
         *
         * `raw_fix` is left alone. It is hardware-shaped — what the chip reported — and the
         * provider state is a fact about the device, not about the fix; `raw_points` already
         * carries it for every judged fix, accepted or not.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE track_point ADD COLUMN providerFlags INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE raw_points ADD COLUMN providerFlags INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v8 → v9: two fields the filter needed and did not have — delivery liveness, and
         * the length of the current run of unconditional accuracy rejections.
         *
         * Additive and non-destructive, per EC-83. Both defaults are the honest reading of
         * a row written before the columns existed, and both reproduce the pre-upgrade
         * behaviour exactly on the first fix after the upgrade:
         *
         *  - `lastSeenElapsedNanos = 0` is `FilterState`'s own "nothing seen yet" sentinel.
         *    The pipeline reads it as an unbounded delivery gap, which is what it assumed
         *    before the column existed, so the first post-upgrade fix takes the same
         *    recovery branch it would have taken on v8. A wall-clock-shaped default would
         *    be far worse than useless here: the column holds `elapsedRealtimeNanos`, which
         *    restarts at every reboot, and any non-zero guess would claim a delivery that
         *    belongs to a timeline that no longer exists.
         *  - `hardRejectRun = 0` says no run is in progress, which is true of a database
         *    written by a build that could not count one. It costs at most one extra
         *    rejection before a bridge can be earned, on the session that spans the
         *    upgrade.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE filter_state ADD COLUMN lastSeenElapsedNanos INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE filter_state ADD COLUMN hardRejectRun INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun build(context: Context): TrackerDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TrackerDatabase::class.java,
                nameFor(context),
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                )
                // WAL so host reads never block the ingestor's writes (EC-85).
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
