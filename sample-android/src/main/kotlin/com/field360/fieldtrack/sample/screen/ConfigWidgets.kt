package com.field360.fieldtrack.sample.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The three widgets the config console is drawn from, plus the section that holds them.
 *
 * Kept apart from [HackerTheme]'s read-only pieces on purpose: everything here takes an
 * `onChange`, and that is the line worth being able to see. A row that only reports and a
 * row that writes to the SDK's configuration should not be one file apart from each other
 * by accident.
 *
 * All three share one geometry — name on the left, control on the right at a fixed width,
 * hint underneath — so a group of twenty fields scans as a column of values rather than as
 * twenty differently-shaped rows.
 */

/** The control column. Fixed, so every value in a group starts at the same x. */
private val ControlWidth = 132.dp

/**
 * A collapsible group of fields.
 *
 * Collapsed by default at the call site: eight groups expanded at once is 80 rows, and the
 * screen's job is to make one setting easy to find, not to display all of them.
 *
 * @param dirtyCount fields in this group differing from what the SDK was last given.
 *   Shown on the header so a collapsed group still says it is holding a change — the
 *   failure this exists to prevent is applying an edit made twenty minutes ago in a group
 *   that has since been scrolled past.
 */
@Composable
fun ConfigGroupCard(
    title: String,
    fieldCount: Int,
    dirtyCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Hack.Surface)
            .border(1.dp, if (dirtyCount > 0) Hack.Amber else Hack.Line),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (expanded) "▾ $title" else "▸ $title",
                style = MonoBody.copy(
                    color = Hack.Green,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                ),
            )
            Text(
                text = if (dirtyCount > 0) "$dirtyCount edited · $fieldCount" else "$fieldCount fields",
                style = MonoBody.copy(
                    color = if (dirtyCount > 0) Hack.Amber else Hack.Dim,
                    fontSize = 10.sp,
                ),
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * The shared row: name, optional hint, and whatever control the field needs.
 *
 * `dirty` marks a field the SDK has not been given yet — amber on the name, not on the
 * control, because the control already shows the new value and the question the reader is
 * asking is "which of these did I change".
 */
@Composable
private fun FieldRow(
    label: String,
    hint: String,
    dirty: Boolean,
    control: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (dirty) "* $label" else label,
                style = MonoBody.copy(
                    color = if (dirty) Hack.Amber else Hack.Text,
                    fontSize = 11.sp,
                ),
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            control()
        }
        if (hint.isNotBlank()) {
            Text(
                text = hint,
                style = MonoBody.copy(color = Hack.Dim, fontSize = 9.sp),
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
            )
        }
    }
}

/**
 * A boolean, as the two words a terminal would print.
 *
 * Not Material's `Switch`: at this row height it is the largest object on the screen and
 * it pulls the eye to whichever field happens to be boolean. `[ ON ]` sits in the same box
 * as every other control and reads at a glance in the same column.
 */
@Composable
fun ConfigToggleRow(
    label: String,
    hint: String,
    value: Boolean,
    dirty: Boolean,
    onChange: (Boolean) -> Unit,
) {
    FieldRow(label = label, hint = hint, dirty = dirty) {
        val accent = if (value) Hack.Green else Hack.Dim
        Box(
            Modifier
                .width(ControlWidth)
                .background(Hack.SurfaceHi)
                .border(1.dp, accent)
                .clickable { onChange(!value) }
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (value) "[ ON ]" else "[ OFF ]",
                style = MonoBody.copy(color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp),
            )
        }
    }
}

/** A closed set, as a dropdown anchored to the value it is currently showing. */
@Composable
fun ConfigChoiceRow(
    label: String,
    hint: String,
    value: String,
    options: List<String>,
    dirty: Boolean,
    onChange: (String) -> Unit,
) {
    FieldRow(label = label, hint = hint, dirty = dirty) {
        var open by remember { mutableStateOf(false) }
        Box {
            Box(
                Modifier
                    .width(ControlWidth)
                    .background(Hack.SurfaceHi)
                    .border(1.dp, Hack.Line)
                    .clickable { open = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "$value ▾",
                    style = MonoBody.copy(color = Hack.Cyan, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(Hack.Surface),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (option == value) "> $option" else "  $option",
                                style = MonoBody.copy(
                                    color = if (option == value) Hack.Green else Hack.Text,
                                    fontSize = 11.sp,
                                ),
                            )
                        },
                        onClick = {
                            open = false
                            onChange(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A typed value.
 *
 * @param invalid the text does not parse to this field's type. Bordered red and left
 *   alone — the string stays exactly as typed, because a box that erases what you wrote
 *   the moment it is momentarily invalid is unusable for numbers. Nothing reaches the
 *   config until it parses, and Apply refuses while any field is in this state.
 */
@Composable
fun ConfigTextRow(
    label: String,
    hint: String,
    value: String,
    keyboard: ConfigKeyboard,
    invalid: Boolean,
    dirty: Boolean,
    onChange: (String) -> Unit,
) {
    FieldRow(label = label, hint = hint, dirty = dirty) {
        val accent = if (invalid) Hack.Red else Hack.Line
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MonoBody.copy(
                color = if (invalid) Hack.Red else Hack.Text,
                fontSize = 11.sp,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(Hack.Green),
            keyboardOptions = KeyboardOptions(
                keyboardType = when (keyboard) {
                    ConfigKeyboard.NUMBER -> KeyboardType.Number
                    ConfigKeyboard.DECIMAL -> KeyboardType.Decimal
                    ConfigKeyboard.TEXT -> KeyboardType.Text
                },
            ),
            modifier = Modifier
                .width(ControlWidth)
                .background(Hack.SurfaceHi)
                .border(1.dp, accent)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) {
                        Text(
                            text = "null",
                            style = MonoBody.copy(color = Hack.Dim, fontSize = 11.sp),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * The validator's answer, verbatim.
 *
 * One line per error and never summarised: `validate()` returns the exact field and the
 * exact bound it wants, and a screen that renders "3 problems" instead has thrown away the
 * only part worth reading.
 */
@Composable
fun ValidationErrors(errors: List<String>, color: Color = Hack.Red) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        errors.forEach { error ->
            Text(
                text = "! $error",
                style = MonoBody.copy(color = color, fontSize = 10.sp),
            )
        }
    }
}
