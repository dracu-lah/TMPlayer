package com.tmplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.cast.TranscodeProgress
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

/** The progress line of a running transcode: how far it has got, of how much, and how fast. */
fun transcodeLine(p: TranscodeProgress, s: Messages): String {
    val speed = s.formatter.decimal(p.speed.toDouble().coerceIn(0.0, 99.0), 1)
    val position = s.formatter.clock(p.positionMs)
    return if (p.durationMs > 0) s.castTranscodeProgress(position, s.formatter.clock(p.durationMs), speed)
    else s.castTranscodeProgressOpen(position, speed)
}

/** A transcode that is running: neither done nor stopped. */
val TranscodeProgress.running: Boolean get() = !done && stop == null

/**
 * The card a cast that is converted shows (the phone's remote, the computer's casting panel):
 * "Converting for the TV", how far it has got and the speed, a bar of the whole video, a word when
 * it runs slower than the video plays, and Stop converting. Nothing while no transcode runs.
 */
@Composable
fun CastConvertingCard(progress: TranscodeProgress?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val p = progress?.takeIf { it.running } ?: return
    val s = LocalStrings.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Tone.surfaceHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(s.castTranscodeTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Tone.text)
        Spacer(Modifier.height(4.dp))
        Text(transcodeLine(p, s), style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        val fraction = p.fraction
        val bar = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = bar, color = Tone.accent, trackColor = Tone.surface)
        } else {
            LinearProgressIndicator(modifier = bar, color = Tone.accent, trackColor = Tone.surface)
        }
        if (p.slow) {
            Spacer(Modifier.height(8.dp))
            Text(s.castTranscodeSlow, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 3)
        }
        Spacer(Modifier.height(10.dp))
        val interactions = remember { MutableInteractionSource() }
        Box(
            Modifier
                .pressScale(interactions, depth = 0.97f)
                .clip(CircleShape)
                .background(Tone.surface)
                .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onCancel)
                .semantics { contentDescription = s.castTranscodeCancel }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(s.castTranscodeCancel, style = MaterialTheme.typography.labelLarge, color = Tone.text, maxLines = 1)
        }
    }
}
