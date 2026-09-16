package com.field360.tracker.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises the three operations that bring the capture pipeline up or down:
 * [StartTrackingUseCase], [StopTrackingUseCase] and [ResumeCaptureUseCase].
 *
 * ### The race this closes
 *
 * `start()` issues the foreground-service start command *before* it writes the session row
 * and launches the pipeline — deliberately, to spend the platform's start-from-background
 * window on the one call that needs it. The service answers that command by running
 * [ResumeCaptureUseCase] on a background dispatcher, and that use case decides whether a
 * pipeline is missing by reading `FixIngestor.isRunning` — which stays false until the
 * ingestor's consumer coroutine exists, several Room reads into `CaptureLauncher.launch`.
 *
 * So for the duration of those reads, both `start()` and the service's resume path see an
 * open session with no pipeline behind it, and both call `CaptureLauncher.launch` — at the
 * same time, on different threads. `LocationStreamController.restart` then registers two
 * location requests (each caller cancels the *same* previous job), and `FixIngestor.start`
 * can leave two consumers draining one channel while sharing unsynchronised filter state.
 * The visible result was duplicate fixes, `OUT_OF_ORDER`/`Burst` rejections and corrupted
 * points on the very first session after a cold start.
 *
 * ### How it is used
 *
 * `start()` and `stop()` hold the lock across their whole transition through
 * [withTransition]: teardown, service command, session row, launch. Resume uses [ifIdle]
 * instead: if the lock is taken, a start or stop is already mid-flight, and either way there
 * is nothing for a revival to do — a start is about to launch the pipeline itself, and a
 * stop is about to close the session it would have resumed. Not waiting keeps the service's
 * supervision coroutine from blocking behind a host call.
 *
 * Non-reentrant, like the `Mutex` underneath. [SessionTeardown] therefore takes no lock of
 * its own: it is only ever called by a use case that already holds this one.
 */
internal class CaptureLifecycleLock {

    private val mutex = Mutex()

    /** Runs [block] with the lock held, waiting for any transition already in progress. */
    suspend fun <T> withTransition(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Runs [block] with the lock held only if nothing else holds it right now.
     *
     * @return the block's result, or `null` — without waiting — when a transition is in
     *   progress.
     */
    suspend fun <T> ifIdle(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
