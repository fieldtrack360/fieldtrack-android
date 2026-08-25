package com.field360.fieldtrack.sample.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.field360.fieldtrack.sample.SyncAlert

/**
 * Why the upload half is not uploading.
 *
 * A developer's dialog, like [LicenseAlertDialog] and for the same reason: the person
 * running the sample is integrating, so it names the HTTP status, says what the SDK did
 * about it, and says what has happened to the rows. A shipping app would show a user
 * nothing at all for most of this — an offline queue is normal operation, not an error.
 *
 * The one line worth reading twice is the last: whether the data survived. A 401 clears
 * the queue and a 403 keeps it, which is the difference between "log back in" and "log
 * back in, you have lost the morning's work".
 */
@Composable
internal fun SyncAlertDialog(
    alert: SyncAlert,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alert.terminal) "Sync stopped" else "Sync problem") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(alert.headline, style = MaterialTheme.typography.bodyMedium)

                Text(
                    alert.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )

                Text(
                    "${alert.queued} row(s) queued",
                    style = MaterialTheme.typography.bodySmall,
                )

                if (alert.terminal) {
                    Text(
                        "Nothing will upload until this is fixed — the SDK is not " +
                            "retrying its way out of this one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    // Worth stating plainly: the common reason for this dialog is a
                    // device that is simply offline, and a reader who assumes otherwise
                    // will go hunting a fault that is not there.
                    Text(
                        "Rows stay queued and are retried automatically — on a 30 s " +
                            "backoff, and again as soon as the network returns. Capture " +
                            "is unaffected.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = {
            // Present even on a terminal alert: a 404 or a bad URL is usually fixed on
            // the server side, and the fastest way to confirm the fix is to drain again
            // without reinstalling. syncNow() reports the exact drain result.
            TextButton(onClick = onRetry) { Text("Retry now") }
        },
    )
}
