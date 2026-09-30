package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.opslegal.tda.core.model.TaskKind

// Sober colours for the action buttons: navy, slate and bordeaux, like a law office.
val Navy = Color(0xFF1F2A44)
val Slate = Color(0xFF46505E)
val Bordeaux = Color(0xFF5C2A33)
val Pewter = Color(0xFF6B7280)

/** The mic while it listens: clearly "recording" without being loud. */
val Recording = Color(0xFF9B2C2C)

/** Text colour of a cell: task blue, meeting black, deadline or delivery red. */
@Composable
internal fun kindColor(kind: TaskKind, onYellow: Boolean = false): Color {
    val dark = !onYellow && MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (kind) {
        TaskKind.TASK -> if (dark) Color(0xFF9DB8E8) else Color(0xFF1E4E8C)
        TaskKind.MEETING -> if (dark) Color(0xFFF1F1F1) else Color(0xFF111111)
        TaskKind.DEADLINE -> if (dark) Color(0xFFF2A7A0) else Color(0xFFB3261E)
    }
}

/**
 * A big round button, easy to hit with a thumb while the other hand is busy.
 * The optional [label] is a one-word caption under it.
 */
@Composable
internal fun RoundAction(
    icon: ImageVector,
    description: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    size: Dp = 64.dp,
    label: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (enabled) color else color.copy(alpha = 0.4f),
            contentColor = contentColor,
            shadowElevation = 4.dp,
            modifier = Modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = description, modifier = Modifier.size(size * 0.45f))
            }
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * The app's dialogs: a bit narrower than the default and slightly see-through, with a light
 * dim, so the table stays visible behind.
 */
@Composable
internal fun SoftDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        modifier = Modifier.fillMaxWidth(0.88f).widthIn(max = 420.dp),
        title = {
            LightDim()
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.titleMedium,
            ) { title() }
        },
        text = text,
        containerColor = dialogColor(),
        tonalElevation = 0.dp,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

/** The dialogs' background: almost solid, so text stays easy to read. */
@Composable
internal fun dialogColor(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)

/** Lowers the dark veil behind the dialog window. */
@Composable
private fun LightDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(0.25f) }
}

/** A light "?" that shows or hides the explanation of a field. */
@Composable
internal fun HelpButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Box(
        modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = "Explain", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("?", color = color, fontSize = 16.sp, fontWeight = FontWeight.Light)
    }
}

/** The help text under a field, shown after a tap on its "?". */
@Composable
internal fun HelpText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, end = 32.dp),
    )
}

/**
 * A compact outlined field: its name always sits on the top line, and it is only a little
 * taller than a tag.
 */
@Composable
internal fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    var focused by remember { mutableStateOf(false) }
    val lineColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val labelColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.padding(top = 7.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = if (singleLine) 1 else 6,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 38.dp)
                .border(if (focused) 1.5.dp else 1.dp, lineColor, RoundedCornerShape(6.dp))
                .onFocusChanged { focused = it.isFocused }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            modifier = Modifier.offset(x = 8.dp, y = (-7).dp).background(dialogColor()).padding(horizontal = 4.dp),
        )
    }
}

/** A compact field with a light "?" next to it; the explanation only shows when asked. */
@Composable
internal fun HelpField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    help: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    var showHelp by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(value, onValueChange, label, Modifier.weight(1f), singleLine, minLines)
            HelpButton(showHelp, { showHelp = !showHelp }, Modifier.padding(top = 7.dp))
        }
        if (showHelp) HelpText(help)
    }
}

/** A group of tags with a small name above (same size as a field's name) and a light "?". */
@Composable
internal fun HelpLabel(label: String, help: String, content: @Composable () -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { content() }
            HelpButton(showHelp, { showHelp = !showHelp })
        }
        if (showHelp) HelpText(help)
    }
}

/** A tag: grey when not chosen, so it stands out from the background. */
@Composable
internal fun TagChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        ),
    )
}

private fun strokeIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

/** New task: a ticked box with a plus. */
internal val NewTaskIcon: ImageVector = strokeIcon("NewTask") {
    moveTo(3f, 3f); lineTo(15f, 3f); lineTo(15f, 15f); lineTo(3f, 15f); close()
    moveTo(6f, 9.5f); lineTo(8.5f, 12f); lineTo(12.5f, 6.5f)
    moveTo(19f, 14.5f); lineTo(19f, 22.5f)
    moveTo(15f, 18.5f); lineTo(23f, 18.5f)
}

/** New project: a folder with a plus. */
internal val NewProjectIcon: ImageVector = strokeIcon("NewProject") {
    moveTo(13f, 19f); lineTo(2.5f, 19f); lineTo(2.5f, 5f); lineTo(9f, 5f); lineTo(11f, 7.5f); lineTo(21f, 7.5f); lineTo(21f, 12.5f)
    moveTo(18.5f, 14.5f); lineTo(18.5f, 22.5f)
    moveTo(14.5f, 18.5f); lineTo(22.5f, 18.5f)
}

/** Push to a later day: an arrow jumping forward. */
internal val PushIcon: ImageVector = strokeIcon("Push") {
    moveTo(3f, 17f); curveTo(4f, 10f, 10f, 7f, 18f, 8.5f)
    moveTo(14.5f, 4.5f); lineTo(19f, 8.5f); lineTo(14.5f, 12.5f)
}
