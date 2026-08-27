package com.field360.fieldtrack.sample.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.field360.fieldtrack.sample.TrackerViewModel
import com.field360.tracker.TrackerConfig

/**
 * The configuration console: every option in [TrackerConfig], editable, over a status
 * strip that says whether anything is recording.
 *
 * Home is this rather than the status console it used to be — that moved to [StatusScreen]
 * — because of what this app is for. The SDK's behaviour is almost entirely a function of
 * its config, and a sample that hardcodes one config can demonstrate exactly one of them.
 * Reproducing a field report meant editing `SampleApplication`, rebuilding and reinstalling;
 * it is now three taps, in the field, on the device that showed the problem.
 *
 * The layout is fixed at both ends and scrolls in the middle, and both pins are load-bearing:
 *
 *  - **The header** stays because every value below it changes what capture does, and a
 *    tester editing `intervalMs` needs to see whether a session is running while they do
 *    it. The strip is deliberately narrow — the full picture is one tab away.
 *  - **The apply bar** stays because a change that has not been applied does nothing at
 *    all, and eighty rows is far enough to scroll to forget that. It states what it is
 *    about to do, including that it will restart a live session.
 *
 * Groups are collapsed by default. Expanding everything is 80 rows, and the job is to make
 * one setting easy to find, not to show all of them at once.
 */
@Composable
fun HomeScreen(
    state: TrackerViewModel.UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEditConfig: ((TrackerConfig) -> TrackerConfig) -> Unit = {},
    onEditConfigText: (String, String, (TrackerConfig, String) -> TrackerConfig?) -> Unit =
        { _, _, _ -> },
    onApplyConfig: () -> Unit = {},
    onResetConfig: () -> Unit = {},
    onResetToSdkDefaults: () -> Unit = {},
) {
    val groups = remember { configGroups() }

    // Which groups are open, as one delimited string rather than a `Set`.
    //
    // `rememberSaveable` writes through a Bundle, and a `Set` has no default saver — it
    // would need one written by hand for a value that is genuinely a list of names with
    // no commas in them. Survives rotation, dies with the process, which is the right
    // lifetime for it.
    var expandedCsv by rememberSaveable { mutableStateOf("") }
    val expanded = expandedCsv.split(',').filter(String::isNotEmpty).toSet()

    // Counted here, once, rather than per group inside the loop: the apply bar needs the
    // total and each header needs its own share, and deriving both from one pass keeps
    // them from ever disagreeing about what "edited" means.
    val dirtyByGroup = groups.associate { group ->
        group.title to group.fields.count { field ->
            currentValue(field, state.configDraft) != currentValue(field, state.configApplied)
        }
    }
    val dirtyTotal = dirtyByGroup.values.sum()

    Column(Modifier.fillMaxSize().background(Hack.Bg)) {
        ConsoleHeader(state = state, dirtyCount = dirtyTotal, onStart = onStart, onStop = onStop)

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "every field below is a TrackerConfig option. edits apply on the " +
                        "button at the bottom, not as you type.",
                    style = MonoBody.copy(color = Hack.Dim, fontSize = 10.sp),
                )
            }

            groups.forEach { group ->
                item(key = group.title) {
                    val isOpen = group.title in expanded
                    ConfigGroupCard(
                        title = group.title,
                        fieldCount = group.fields.size,
                        dirtyCount = dirtyByGroup.getValue(group.title),
                        expanded = isOpen,
                        onToggle = {
                            val next = if (isOpen) expanded - group.title else expanded + group.title
                            expandedCsv = next.joinToString(",")
                        },
                    ) {
                        group.fields.forEach { field ->
                            ConfigRow(
                                field = field,
                                state = state,
                                onEditConfig = onEditConfig,
                                onEditConfigText = onEditConfigText,
                            )
                        }
                    }
                }
            }
        }

        ApplyBar(
            state = state,
            dirtyCount = dirtyTotal,
            onApply = onApplyConfig,
            onReset = onResetConfig,
            onResetToSdkDefaults = onResetToSdkDefaults,
        )
    }
}

/**
 * One field, dispatched to its widget.
 *
 * The whole reason [ConfigField] is data: this function is the entire rendering of the
 * configuration surface, so a field cannot be drawn inconsistently with its neighbours
 * even by accident.
 */
@Composable
private fun ConfigRow(
    field: ConfigField,
    state: TrackerViewModel.UiState,
    onEditConfig: ((TrackerConfig) -> TrackerConfig) -> Unit,
    onEditConfigText: (String, String, (TrackerConfig, String) -> TrackerConfig?) -> Unit,
) {
    // A field is "edited" when the draft and the last applied config disagree about it —
    // not when it has been touched. Typing a value and typing the old one back leaves
    // nothing to apply, and the screen should say so.
    val dirty = currentValue(field, state.configDraft) != currentValue(field, state.configApplied)

    when (field) {
        is BoolField -> ConfigToggleRow(
            label = field.label,
            hint = field.hint,
            value = field.get(state.configDraft),
            dirty = dirty,
            onChange = { value -> onEditConfig { config -> field.set(config, value) } },
        )

        is ChoiceField -> ConfigChoiceRow(
            label = field.label,
            hint = field.hint,
            value = field.get(state.configDraft),
            options = field.options,
            dirty = dirty,
            onChange = { value -> onEditConfig { config -> field.set(config, value) } },
        )

        is TextField -> ConfigTextRow(
            label = field.label,
            hint = field.hint,
            // The typed string wins while one exists. Falling back to the draft would
            // rewrite the box out from under a half-typed number the moment it stopped
            // parsing.
            value = state.configText[field.key] ?: field.get(state.configDraft),
            keyboard = field.keyboard,
            invalid = field.key in state.configInvalid,
            dirty = dirty,
            onChange = { raw -> onEditConfigText(field.key, raw, field.parse) },
        )
    }
}

/** What this field currently reads as, for the draft-versus-applied comparison. */
private fun currentValue(field: ConfigField, config: TrackerConfig): String = when (field) {
    is BoolField -> field.get(config).toString()
    is ChoiceField -> field.get(config)
    is TextField -> field.get(config)
}

/**
 * `root@fieldtrack`, whether anything is recording, and the two buttons that decide it.
 *
 * Narrow on purpose: this screen is for setting values, and the full diagnostic picture is
 * one tab away. What survives the trim is what a config edit can be wrong about — is a
 * session open, is it actually capturing, and how many points has it stored.
 */
@Composable
private fun ConsoleHeader(
    state: TrackerViewModel.UiState,
    dirtyCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val statusColor = statusColorOf(state)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Hack.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "root@fieldtrack",
                style = MonoBody.copy(
                    color = Hack.Green,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
            )
            Text(":~$ ", style = MonoBody.copy(color = Hack.Dim, fontSize = 14.sp))
            Text(
                statusLabelOf(state).uppercase(),
                style = MonoBody.copy(
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
            )
            BlinkingCursor(statusColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge("pts ${state.pointCount}", Hack.Green)
            Badge(state.permissionTier.name, tierColor(state))
            FlagBadge("capture", state.isCapturing)
            FlagBadge("gps", state.providerState.gpsEnabled)
            if (dirtyCount > 0) Badge("$dirtyCount edited", Hack.Amber)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HackButton(
                text = "▶ start",
                onClick = onStart,
                enabled = !state.isTracking && !state.configApplying,
                modifier = Modifier.weight(1f),
            )
            HackButton(
                text = "■ stop",
                onClick = onStop,
                enabled = state.isTracking && !state.configApplying,
                accent = Hack.Red,
                modifier = Modifier.weight(1f),
            )
        }
        // Why capture stopped, on the screen where someone is changing the settings that
        // could have caused it. The full reason lives on the Status tab; this is the one
        // line that says to go and read it.
        state.captureSuspendedReason?.let { Alert(it, Hack.Amber) }
    }
}

/**
 * The pinned footer: what applying will do, what refused, and the two resets.
 *
 * The Apply button states its own consequence rather than leaving it to a note elsewhere.
 * "restarts session" is not a warning here — it is what the button does, and a tester who
 * reads it after the fact has already lost a run.
 */
@Composable
private fun ApplyBar(
    state: TrackerViewModel.UiState,
    dirtyCount: Int,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onResetToSdkDefaults: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Hack.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The validator's own words, or the name of a box that does not parse. Above the
        // button rather than below it, because it is the reason the button did nothing.
        if (state.configErrors.isNotEmpty()) ValidationErrors(state.configErrors)

        state.configNotice?.let { notice ->
            Text(
                text = "· $notice",
                style = MonoBody.copy(color = Hack.Cyan, fontSize = 10.sp),
            )
        }

        HackButton(
            text = when {
                state.configApplying -> "applying…"
                state.configInvalid.isNotEmpty() -> "fix ${state.configInvalid.size} invalid field(s)"
                state.isTracking -> "apply · restarts session"
                else -> "apply"
            },
            onClick = onApply,
            // Deliberately NOT disabled on `dirtyCount == 0`: re-applying an unchanged
            // config is how a tester re-runs `ready()` after granting a permission or
            // clearing an integrity finding, and it is the cheapest way to see what the
            // validator says about the config that is already running.
            enabled = !state.configApplying && state.configInvalid.isEmpty(),
            accent = if (dirtyCount > 0) Hack.Amber else Hack.Green,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "reset · sample",
                onClick = onReset,
                enabled = !state.configApplying,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = "reset · sdk defaults",
                onClick = onResetToSdkDefaults,
                enabled = !state.configApplying,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
@Preview(showBackground = true, backgroundColor = 0xFF060A06)
fun HomeScreenPreview() {
    HomeScreen(
        state = TrackerViewModel.UiState(
            isTracking = true,
            isCapturing = true,
            pointCount = 128,
            sessionId = "8f2c41ab-77de-4f01-9c11-0d2a55b6e900",
            configDraft = TrackerConfig(),
            configApplied = TrackerConfig(),
        ),
        onStart = {},
        onStop = {},
    )
}
