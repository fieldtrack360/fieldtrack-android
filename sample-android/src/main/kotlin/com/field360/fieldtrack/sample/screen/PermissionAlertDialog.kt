package com.field360.fieldtrack.sample.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.field360.fieldtrack.sample.MissingPermission
import com.field360.fieldtrack.sample.PermissionAlert

/**
 * What Start shows when a grant the SDK needs is not held.
 *
 * Raised **before** `Tracker.start()` is called, so nothing has been opened yet — the
 * session id, the foreground service and the WorkManager entries all wait until the user
 * has answered. `PermissionManager` shows no UI by design and answers only in typed
 * errors (PERMISSIONS.md §5); this is the host's half of that contract.
 *
 * Each row states the consequence rather than the permission name, because a list of
 * `Manifest.permission` constants names things no Settings screen contains, and a prompt
 * with no stated cost is the one users deny out of hand.
 */
@Composable
fun PermissionAlertDialog(
    alert: PermissionAlert,
    onGrant: () -> Unit,
    onStartAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (alert.blocking) "Tracking cannot start yet" else "Tracking will be limited",
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (alert.blocking) {
                        "The SDK will refuse to open a session until this is granted:"
                    } else {
                        "You can start now, but the session will be missing this:"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                alert.missing.forEach { missing ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (missing.blocking) "✕" else "!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (missing.blocking) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(missing.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = missing.consequence,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (alert.missing.contains(MissingPermission.BACKGROUND_LOCATION)) {
                    Text(
                        text = "All-the-time location is a separate step — Android only " +
                            "offers it after basic location is granted, and from Android 11 " +
                            "it can only be turned on in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onGrant) { Text("Grant") } },
        dismissButton = {
            // Offered only when starting would actually work. On a blocking alert the SDK
            // would return the error the dialog has just explained, and that error would
            // then overwrite a message the user has already read and acted on.
            if (alert.blocking) {
                TextButton(onClick = onDismiss) { Text("Not now") }
            } else {
                TextButton(onClick = onStartAnyway) { Text("Start anyway") }
            }
        },
    )
}
