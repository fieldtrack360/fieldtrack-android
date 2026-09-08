package com.field360.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.repository.ConfigRepository
import com.field360.tracker.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Resumes an open session after a reboot or an app update.
 *
 * `MY_PACKAGE_REPLACED` matters as much as `BOOT_COMPLETED`: an update tears down the
 * service and clears alarms just as a reboot does, and handling only the latter leaves
 * tracking silently dead after every Play Store update (EC-65, EC-67).
 *
 * This cannot save a force-stopped app — nothing can, by policy. That case surfaces as
 * `SessionInterrupted` on next launch instead (EC-66).
 */
public class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        // Looked up after the action check, deliberately: this receiver is woken for
        // every boot of every install, and building the graph — which opens the database
        // on first touch — before knowing the broadcast is ours would put disk I/O on the
        // main thread of a boot storm.
        val graph = TrackerGraph.get(context)
        val sessions: SessionRepository = graph.sessions
        val config: ConfigRepository = graph.config

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val serviceConfig = config.load()?.service ?: return@launch
                if (!serviceConfig.startOnBoot) return@launch
                if (sessions.current() == null) return@launch
                // Checked before the start rather than inferred from its result: a host
                // running without a foreground service gets `false` back for a reason that
                // is not a refusal, and must not be handed to the retry path.
                if (!serviceConfig.foregroundService) return@launch

                // Alarms do not survive a reboot, so the heartbeat chain has to be
                // re-started here or the one revival layer that does not depend on
                // `JobScheduler` is gone for the rest of the session. Before the service
                // start, not after: the start is the thing that can be refused.
                ServiceHeartbeat.schedule(context, serviceConfig)

                // A fresh boot is a fresh chance, whatever the process that died before it
                // had spent.
                ServiceRestorer.reset()

                // On Android 15+ a location-typed foreground service may still be started
                // from `BOOT_COMPLETED`, but the platform is entitled to refuse it and the
                // throw used to propagate out of this `goAsync` block. Counted retry
                // instead — the alarm armed above is the backstop if even that fails.
                if (!TrackingService.start(context, serviceConfig)) {
                    ServiceRestorer.request(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
