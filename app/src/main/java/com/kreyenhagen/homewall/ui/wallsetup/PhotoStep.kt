package com.kreyenhagen.homewall.ui.wallsetup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme

private val PHOTO_TIPS = listOf(
    "Turn the LEDs off and light the wall evenly.",
    "Stand centered left to right, at about mid-height.",
    "The main panel leans toward you, so step back rather than tilting the tablet a lot.",
    "Use the 1× lens, not ultra-wide. Shoot in portrait.",
    "Get all six alignment points in frame: both ends of the floor line, both ends of the fold, and both top corners.",
)

/** Wall Setup → Photo (§8.6): take or choose the wall photo. */
@Composable
fun PhotoStep(
    photo: ImageBitmap?,
    hasPhoto: Boolean,
    working: Boolean,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val preview = @Composable { m: Modifier -> PhotoPreview(photo, hasPhoto, working, m) }
        val controls = @Composable { m: Modifier -> PhotoControls(hasPhoto, working, onTakePhoto, onChoosePhoto, m) }
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                preview(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.width(400.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                preview(Modifier.weight(1f).fillMaxWidth())
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PhotoPreview(photo: ImageBitmap?, hasPhoto: Boolean, working: Boolean, modifier: Modifier) {
    Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            photo != null -> Image(photo, contentDescription = "Wall photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            hasPhoto || working -> Unit
            else -> Text("No photo yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (working || (hasPhoto && photo == null)) CircularProgressIndicator()
    }
}

@Composable
private fun PhotoControls(
    hasPhoto: Boolean,
    working: Boolean,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onTakePhoto, enabled = !working) { Text(if (hasPhoto) "Retake photo" else "Take photo") }
            OutlinedButton(onClick = onChoosePhoto, enabled = !working) { Text("Choose photo") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Tips", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        PHOTO_TIPS.forEach { tip ->
            Text("•  $tip", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@TabletPreviews
@Composable
private fun PhotoStepPreview() {
    HomeWallTheme {
        PhotoStep(photo = null, hasPhoto = false, working = false, onTakePhoto = {}, onChoosePhoto = {})
    }
}
