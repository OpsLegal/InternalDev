package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opslegal.tda.core.model.Value
import com.opslegal.tda.core.plan.Gbn

/**
 * The three categories in muted Ops Legal tones, distinct from the task text colours (blue, green, red, black):
 * Ground light pewter, Build slate-navy, Nourish bordeaux (lighter in dark mode).
 */
@Composable
internal fun bucketColor(bucket: String): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (bucket) {
        Gbn.BUILD -> if (dark) Color(0xFF8E9DBA) else Color(0xFF4A5A78)
        Gbn.NOURISH -> if (dark) Color(0xFFB98591) else Color(0xFF7A3E4A)
        else -> if (dark) Color(0xFF8D949E) else Color(0xFFB9BEC6)
    }
}

/** For words in a category's colour: Ground's pewter is too pale for text, so it gets a darker ink. */
@Composable
internal fun bucketInk(bucket: String): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (bucket == Gbn.GROUND || bucket.isBlank()) (if (dark) Color(0xFFA9AFB8) else Color(0xFF6B7280)) else bucketColor(bucket)
}

/**
 * All attributes in one row, grouped Ground · Build · Nourish, each a 3-block histogram; only the colour says the
 * category. [levels] given: a task's own levels (unrated attributes fade), and [share] is the task's split.
 * Otherwise the bars are the user's weights. [onTap] makes a block set its level (tap the top one again to lower it).
 */
@Composable
internal fun GbnStrip(
    values: List<Value>,
    share: Map<String, Int>,
    levels: Map<String, Int>? = null,
    small: Boolean = false,
    onTap: ((String, Int) -> Unit)? = null,
    onName: ((Value) -> Unit)? = null,
) {
    val off = MaterialTheme.colorScheme.surfaceVariant
    val groups = Gbn.buckets.map { b -> b to values.filter { it.bucket == b } }.filter { it.second.isNotEmpty() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            groups.forEach { (b, list) ->
                val c = bucketColor(b)
                Column(Modifier.weight(list.size.toFloat())) {
                    Row {
                        Text(Gbn.names.getValue(b), color = bucketInk(b), fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("${share[b] ?: 0}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(c))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            groups.forEach { (b, list) ->
                val c = bucketColor(b)
                list.forEach { v ->
                    val level = levels?.get(v.name) ?: if (levels == null) v.weight else 0
                    val faded = levels != null && level == 0
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        (3 downTo 1).forEach { n ->
                            Box(
                                Modifier.fillMaxWidth().height(if (small) 9.dp else 14.dp).clip(RoundedCornerShape(3.dp))
                                    .background(if (level >= n) c else off)
                                    .then(if (onTap != null) Modifier.clickable(onClickLabel = "${v.name}: $n of 3") { onTap(v.name, n) } else Modifier),
                            )
                        }
                        Text(
                            v.name, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant else if (levels != null) bucketInk(b) else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (levels != null && !faded) TextDecoration.Underline else null,
                            modifier = Modifier.fillMaxWidth().padding(top = 1.dp).then(if (onName != null) Modifier.clickable { onName(v) } else Modifier),
                        )
                    }
                }
            }
        }
    }
}

/** Tap logic shared by every strip: the same top block again lowers by one; 0 removes the attribute. */
internal fun Map<String, Int>.tapped(name: String, n: Int): Map<String, Int> {
    val now = this[name] ?: 0
    val next = if (now == n) n - 1 else n
    return if (next <= 0) this - name else this + (name to next)
}

/** "Value for you: ●●○ medium". */
internal fun valueWords(score: Int) = "●".repeat(score) + "○".repeat(3 - score) + " " + listOf("none", "low", "medium", "high")[score]

