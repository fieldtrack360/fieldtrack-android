package com.field360.fieldtrack.sample.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One tab in [HackerBottomBar].
 *
 * @property code the two-digit index shown before the label. A terminal menu is chosen by
 *   number, and the number is also what makes the four items the same width regardless of
 *   how long their names are.
 * @property badge a live count or flag for this tab, or null. The bar is on screen on
 *   every tab, so it is the one place a fault on a screen the tester is *not* looking at
 *   can announce itself.
 */
data class HackerTab(
    val code: String,
    val label: String,
    val badge: String? = null,
    val badgeColor: Color = Hack.Amber,
)

/**
 * The bottom menu as a terminal's mode line: a hairline rule, four numbered slots, and
 * the selected one inverted the way a TUI marks focus.
 *
 * Material's `NavigationBar` was doing none of that work here — it wants an icon per
 * item, its selection indicator is a pill, and its container colour is derived from a
 * tonal elevation that has no meaning against a near-black ground. This is a Row.
 */
@Composable
fun HackerBottomBar(
    tabs: List<HackerTab>,
    selected: HackerTab,
    onSelect: (HackerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Hack.Bg),
    ) {
        // The rule, not a shadow: elevation reads as nothing on this ground.
        Row(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Hack.Green.copy(alpha = RULE_ALPHA)),
        ) {}
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEach { tab ->
                val active = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        // Inverted when focused — solid accent, black text — so the
                        // current tab is legible in peripheral vision.
                        .background(if (active) Hack.Green else Hack.Surface)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = tab.code,
                        style = MonoBody.copy(
                            color = if (active) Color.Black.copy(alpha = CODE_ALPHA) else Hack.Dim,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp,
                        ),
                    )
                    Text(
                        text = tab.label.uppercase(),
                        style = MonoBody.copy(
                            color = if (active) Color.Black else Hack.Text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        ),
                    )
                    // Drawn in the slot whether or not there is a badge, so selecting a
                    // tab never shifts the row's height by a text line.
                    Text(
                        text = tab.badge ?: " ",
                        style = MonoBody.copy(
                            color = if (active) Color.Black else tab.badgeColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                        ),
                    )
                }
            }
        }
    }
}

private const val RULE_ALPHA = 0.5f
private const val CODE_ALPHA = 0.6f
