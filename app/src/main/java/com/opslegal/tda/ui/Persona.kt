package com.opslegal.tda.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opslegal.tda.core.model.Persona
import com.opslegal.tda.persona.Faces

/** Bumped when the user picks a new photo, so every face on screen redraws. */
internal object AvatarVersion { var v by mutableIntStateOf(0) }

/** The assistant's face: drawn, the user's photo, or a plain spark before it has a name. */
@Composable
internal fun Avatar(persona: Persona, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(8)
    val v = AvatarVersion.v
    val bmp = remember(persona, px, v) { Faces.bitmap(context, persona, px).asImageBitmap() }
    Image(bmp, null, modifier.size(size).clip(CircleShape))
}

/** Someone who wrote: their first letter on a soft colour. */
@Composable
internal fun PersonAvatar(name: String, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(8)
    val bmp = remember(name, px) { Faces.initial(name, px).asImageBitmap() }
    Image(bmp, null, Modifier.size(size).clip(CircleShape))
}

/** "What do you want to call me?": a name, one of six faces, or a photo from the phone. */
@Composable
internal fun PersonaPicker(vm: MainViewModel, name: String, onName: (String) -> Unit, face: Int, photo: Boolean, onFace: (Int, Boolean) -> Unit) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && Faces.savePhoto(context, uri)) { AvatarVersion.v++; onFace(face, true) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(Persona(name.ifBlank { "?" }, face, photo), 84.dp)
        OutlinedTextField(name, { onName(it.take(24)) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("My name") },
            supportingText = { Text("People can write to me too: a message with my name comes straight to me.") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until Faces.COUNT).forEach { i ->
                val on = !photo && face == i
                Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).border(3.dp, if (on) Color(0xFFF5C842) else Color.Transparent, CircleShape)
                    .clickable { onFace(i, false) }) { Avatar(Persona("x", i), 40.dp) }
            }
            Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .border(3.dp, if (photo) Color(0xFFF5C842) else Color.Transparent, CircleShape)
                .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center) {
                if (photo) Avatar(Persona("x", face, true), 40.dp) else Text("📷", fontSize = 18.sp)
            }
        }
        Text("Pick a face, or 📷 a photo from your phone (kept on this phone only).", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

/** Settings → Your assistant: change the name or the face anytime. */
@Composable
internal fun PersonaDialog(vm: MainViewModel, current: Persona, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current.name.ifBlank { "Jimmy" }) }
    var face by remember { mutableIntStateOf(current.face) }
    var photo by remember { mutableStateOf(current.photo) }
    SoftDialog(
        keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text("Your assistant") },
        text = { PersonaPicker(vm, name, { name = it }, face, photo) { f, p -> face = f; photo = p } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.setPersona(name, face, photo); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
