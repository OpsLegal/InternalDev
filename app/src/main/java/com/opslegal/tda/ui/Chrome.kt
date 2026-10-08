package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The frame of every page: a navy header and a navy footer, far from the light middle, so the page reads as three
 * parts at a glance. In dark mode the bars are a lighter navy, still far from the near-black middle.
 */
@Composable
internal fun barColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF2B3A5C) else Color(0xFF2C3A4F)

/** The page's header: its name in white, its buttons (white too), and [below] (the table's values bar) on the same colour. */
@Composable
internal fun PageHeader(title: String, below: (@Composable () -> Unit)? = null, leading: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(Modifier.fillMaxWidth().background(barColor(), RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)).padding(bottom = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.let { Box(Modifier.padding(end = 10.dp)) { it() } }
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                actions()
            }
            below?.let { Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp)) { it() } }
        }
    }
}

/**
 * The footer: five tabs on navy. The selected one rises out of the bar in a yellow circle (the colour of a finished
 * cell), its label in yellow: one look tells where you are.
 */
@Composable
internal fun DocketNavBar(items: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp).background(barColor(), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .navigationBarsPadding().height(64.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.forEachIndexed { i, (label, icon) ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (on) {
                        Box(
                            Modifier.offset(y = (-14).dp).size(52.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(DoneYellow)
                                .border(3.dp, MaterialTheme.colorScheme.background, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(icon, contentDescription = label, tint = Navy, modifier = Modifier.size(26.dp)) }
                        Text(label, color = DoneYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = (-12).dp))
                    } else {
                        Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(24.dp))
                        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
