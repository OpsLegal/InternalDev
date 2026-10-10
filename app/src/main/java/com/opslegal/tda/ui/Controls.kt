package com.opslegal.tda.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

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

/**
 * Text colour of a cell. Red (deadline) wins over black (meeting), which wins over green (project step),
 * which wins over blue (one-cell task).
 */
@Composable
internal fun kindColor(kind: TaskKind, onYellow: Boolean = false, inProject: Boolean = false): Color {
    val dark = !onYellow && MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when {
        kind == TaskKind.DEADLINE -> if (dark) Color(0xFFF2A7A0) else Color(0xFFB3261E)
        kind == TaskKind.MEETING -> if (dark) Color(0xFFF1F1F1) else Color(0xFF111111)
        inProject -> if (dark) Color(0xFF86D3A8) else Color(0xFF1B6B43)
        else -> if (dark) Color(0xFF9DB8E8) else Color(0xFF1E4E8C)
    }
}

/** The thin bar on the left of every project cell, whatever its colour. */
@Composable
internal fun projectBarColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF5BBF8A) else Color(0xFF2E8B57)

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
    /** A form the user types in: a tap outside or the back gesture never closes it (only its buttons do), so nothing typed is lost. */
    keepOpen: Boolean = false,
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
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !keepOpen, dismissOnBackPress = !keepOpen),
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
    onFocus: (Boolean) -> Unit = {},
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
                .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
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

/** A date shown like a compact field; a tap opens the phone's date picker. [value] is an ISO date or empty. */
@Composable
internal fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shown = runCatching { java.time.LocalDate.parse(value) }.getOrNull()
    Box(modifier.padding(top = 7.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 38.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .clickable {
                    val d = shown ?: java.time.LocalDate.now()
                    android.app.DatePickerDialog(context, { _, y, m, day -> onChange(java.time.LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shown?.let { "${it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} ${it}" } ?: "Choose a date",
                style = MaterialTheme.typography.bodyMedium,
                color = if (shown == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (shown != null) Text("×", modifier = Modifier.clickable { onChange("") }.padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onFocus: (Boolean) -> Unit = {},
) {
    var showHelp by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(value, onValueChange, label, Modifier.weight(1f), singleLine, minLines, onFocus)
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
        // The "?" sits on the name's line, so the tags below get the full width.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            HelpButton(showHelp, { showHelp = !showHelp }, Modifier.size(24.dp))
        }
        content()
        if (showHelp) HelpText(help)
    }
}

/**
 * One choice among a few, as a row of equal tags that always stays on one line: a long word gets a
 * slightly smaller font instead of wrapping. [color] tints a tag's text (e.g. the cell colours).
 */
@Composable
internal fun <T> Tags(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    color: @Composable (T) -> Color? = { null },
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val tint = color(value)
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(8.dp),
                color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.weight(1f).heightIn(min = 40.dp).semantics { this.selected = on },
            ) {
                Box(Modifier.padding(horizontal = 6.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                    BasicText(
                        label,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 14.sp),
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = tint ?: if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (tint != null || on) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }
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

internal val FolderIcon: ImageVector = strokeIcon("Folder") {
    moveTo(3f, 19f); lineTo(3f, 5f); lineTo(9.5f, 5f); lineTo(11.5f, 7.5f); lineTo(21f, 7.5f); lineTo(21f, 19f); close()
}

internal val TaskBoxIcon: ImageVector = strokeIcon("TaskBox") {
    moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
    moveTo(8f, 12.5f); lineTo(11f, 15.5f); lineTo(16.5f, 8.5f)
}

/** Create a new: a plus. */
internal val PlusIcon: ImageVector = strokeIcon("Plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }

/** An expense: a bill with a coin. */
internal val MoneyIcon: ImageVector = strokeIcon("Money") {
    moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
    moveTo(14.5f, 12f); arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 12f); arcTo(2.5f, 2.5f, 0f, true, true, 14.5f, 12f)
    moveTo(6f, 9.5f); lineTo(6f, 14.5f); moveTo(18f, 9.5f); lineTo(18f, 14.5f)
}

/** A document to fill: a page with lines. */
internal val DocIcon: ImageVector = strokeIcon("Doc") {
    moveTo(6f, 3f); lineTo(14f, 3f); lineTo(18f, 7f); lineTo(18f, 21f); lineTo(6f, 21f); close()
    moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f); moveTo(9f, 12f); lineTo(15f, 12f); moveTo(9f, 16f); lineTo(15f, 16f)
}

/** Documents kept with a cell: a paper clip. */
internal val ClipIcon: ImageVector = strokeIcon("Clip") {
    moveTo(20f, 11.5f); lineTo(12.5f, 19f); arcTo(5f, 5f, 0f, false, true, 5.5f, 12f); lineTo(13.5f, 4f)
    arcTo(3.3f, 3.3f, 0f, false, true, 18.2f, 8.7f); lineTo(10.2f, 16.7f); arcTo(1.7f, 1.7f, 0f, false, true, 7.8f, 14.3f); lineTo(15f, 7f)
}

/** Extend: a cell with a second, dashed cell attached. */
internal val ExtendIcon: ImageVector = strokeIcon("Extend") {
    moveTo(2f, 7f); lineTo(11f, 7f); lineTo(11f, 17f); lineTo(2f, 17f); close()
    moveTo(13f, 7f); lineTo(15f, 7f); moveTo(17f, 7f); lineTo(19f, 7f); moveTo(21f, 7f); lineTo(22f, 7f); lineTo(22f, 9f)
    moveTo(22f, 11f); lineTo(22f, 13f); moveTo(22f, 15f); lineTo(22f, 17f); lineTo(21f, 17f); moveTo(19f, 17f); lineTo(17f, 17f)
    moveTo(15f, 17f); lineTo(13f, 17f); lineTo(13f, 15f); moveTo(13f, 13f); lineTo(13f, 11f); moveTo(13f, 9f); lineTo(13f, 7f)
    moveTo(17.5f, 10f); lineTo(17.5f, 14f); moveTo(15.5f, 12f); lineTo(19.5f, 12f)
}

/** More effort: an arrow into the next cell. */
internal val MoreEffortIcon: ImageVector = strokeIcon("MoreEffort") {
    moveTo(13f, 6f); lineTo(22f, 6f); lineTo(22f, 18f); lineTo(13f, 18f); close()
    moveTo(2f, 12f); lineTo(10f, 12f); moveTo(7f, 9f); lineTo(10f, 12f); lineTo(7f, 15f)
}

/** Related task: a new cell before this one. */
internal val RelatedIcon: ImageVector = strokeIcon("Related") {
    moveTo(13f, 6f); lineTo(22f, 6f); lineTo(22f, 18f); lineTo(13f, 18f); close()
    moveTo(2f, 6f); lineTo(11f, 6f); lineTo(11f, 18f); lineTo(2f, 18f); close()
    moveTo(6.5f, 10f); lineTo(6.5f, 14f); moveTo(4.5f, 12f); lineTo(8.5f, 12f)
}

internal val MinusIcon: ImageVector = strokeIcon("Minus") { moveTo(6f, 12f); lineTo(18f, 12f) }

internal val BellIcon: ImageVector = strokeIcon("Bell") {
    moveTo(6f, 17f); lineTo(6f, 11f); curveTo(6f, 7.7f, 8.7f, 5f, 12f, 5f); curveTo(15.3f, 5f, 18f, 7.7f, 18f, 11f); lineTo(18f, 17f)
    lineTo(19.5f, 19f); lineTo(4.5f, 19f); close(); moveTo(10f, 21.5f); lineTo(14f, 21.5f)
}

internal val ProgressIcon: ImageVector = strokeIcon("Progress") {
    moveTo(4f, 7f); lineTo(14f, 7f); moveTo(4f, 12f); lineTo(18f, 12f); moveTo(4f, 17f); lineTo(11f, 17f)
}

internal val CompassIcon: ImageVector = strokeIcon("Compass") {
    moveTo(21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f); curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f)
    curveTo(17f, 3f, 21f, 7f, 21f, 12f); close()
    moveTo(15.5f, 8.5f); lineTo(13.5f, 13.5f); lineTo(8.5f, 15.5f); lineTo(10.5f, 10.5f); close()
}

internal val UndoIcon: ImageVector = strokeIcon("Undo") {
    moveTo(20f, 11f); curveTo(19.4f, 7f, 16f, 4f, 12f, 4f); curveTo(7.6f, 4f, 4f, 7.6f, 4f, 12f); curveTo(4f, 16.4f, 7.6f, 20f, 12f, 20f)
    curveTo(14.4f, 20f, 16.6f, 19f, 18f, 17.3f); moveTo(20f, 4f); lineTo(20f, 11f); lineTo(13f, 11f)
}


/** A speech bubble: an AI to talk to. */
internal val ChatIcon: ImageVector = strokeIcon("Chat") {
    moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(10f, 16f); lineTo(6f, 20f); lineTo(6f, 16f); lineTo(4f, 16f); close()
}

/**
 * The round buttons float: drag them anywhere so they never hide what you're reading. Taps still work;
 * the place is remembered and shared by every screen.
 */
@Composable
internal fun FloatingButtons(vm: MainViewModel, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    var offset by remember(settings.fabX, settings.fabY) {
        mutableStateOf(Offset(settings.fabX * density.density, settings.fabY * density.density))
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val margin = with(density) { 12.dp.toPx() }
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        fun clamp(o: Offset) = Offset(
            o.x.coerceIn(-(maxW - size.width - 2 * margin).coerceAtLeast(0f), 0f),
            o.y.coerceIn(-(maxH - size.height - 2 * margin).coerceAtLeast(0f), 0f),
        )
        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp)
                .offset { clamp(offset).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                .onSizeChanged { size = it }
                .pointerInput(maxW, maxH) {
                    detectDragGestures(
                        onDragEnd = { clamp(offset).let { vm.saveButtonsPosition(it.x / density.density, it.y / density.density) } },
                    ) { change, drag ->
                        change.consume()
                        offset = clamp(offset + drag)
                    }
                }
                .semantics { stateDescription = "Drag to move these buttons" },
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
            content = content,
        )
    }
}

/** A shopping cart: the To buy list. */
internal val CartIcon: ImageVector = strokeIcon("Cart") {
    moveTo(3f, 4f); lineTo(5.5f, 4f); lineTo(7.7f, 14.5f); lineTo(18.3f, 14.5f); lineTo(20.5f, 7f); lineTo(7f, 7f)
    moveTo(10.9f, 19f); curveTo(10.9f, 19.8f, 10.3f, 20.4f, 9.5f, 20.4f); curveTo(8.7f, 20.4f, 8.1f, 19.8f, 8.1f, 19f); curveTo(8.1f, 18.2f, 8.7f, 17.6f, 9.5f, 17.6f); curveTo(10.3f, 17.6f, 10.9f, 18.2f, 10.9f, 19f); close()
    moveTo(18.4f, 19f); curveTo(18.4f, 19.8f, 17.8f, 20.4f, 17f, 20.4f); curveTo(16.2f, 20.4f, 15.6f, 19.8f, 15.6f, 19f); curveTo(15.6f, 18.2f, 16.2f, 17.6f, 17f, 17.6f); curveTo(17.8f, 17.6f, 18.4f, 18.2f, 18.4f, 19f); close()
}

/** An envelope moving forward: replies ready for the user to send. */
internal val EnvelopeIcon: ImageVector = strokeIcon("Envelope") {
    moveTo(1.5f, 5.5f); lineTo(15.5f, 5.5f); lineTo(15.5f, 17.5f); lineTo(1.5f, 17.5f); close()
    moveTo(2f, 6.5f); lineTo(8.5f, 11.5f); lineTo(15f, 6.5f)
    moveTo(17f, 13f); lineTo(22.5f, 13f); moveTo(20f, 10.5f); lineTo(22.5f, 13f); lineTo(20f, 15.5f)
}
