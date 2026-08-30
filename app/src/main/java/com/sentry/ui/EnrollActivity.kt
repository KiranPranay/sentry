package com.sentry.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sentry.data.VoiceProfile
import com.sentry.ui.theme.SentryTheme

/**
 * "Teach Sentry my voice."
 *
 * The screen is careful about two things. It says plainly that this teaches Sentry
 * *who you are*, not *what you say* — those are different problems and only the
 * phrase teaching next door addresses the second. And it shows the measured spread of
 * your own recordings afterwards, because that number, not a threshold copied off the
 * internet, is what says whether this will work for you.
 */
class EnrollActivity : ComponentActivity() {

    private val viewModel: EnrollViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SentryTheme(forceDark = false, dynamicColor = true) {
                Scaffold { padding ->
                    EnrollScreen(
                        viewModel = viewModel,
                        onClose = { finish() },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.release()
    }
}

@Composable
private fun EnrollScreen(
    viewModel: EnrollViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Teach Sentry your voice",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Read ${VoiceProfile.ENROLL_TARGET} sentences. Sentry turns each into a " +
                "voiceprint — a description of how you sound, not of the words — and " +
                "can then tell your voice from someone else's.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "This teaches Sentry who you are, not what you say. If it mishears a " +
                "particular phrase, use “Teach a phrase” instead.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(22.dp))

        if (!state.saved) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(
                        progress = { state.samples.size / VoiceProfile.ENROLL_TARGET.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))

                    Text(
                        if (state.listening) "Listening — read it now" else "Read this out",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "“${viewModel.prompts[state.index]}”",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.partial.ifBlank { " " },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = CircleShape,
                        color = if (state.listening) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(70.dp),
                    ) {
                        IconButton(
                            onClick = {
                                if (state.listening) viewModel.stopListening()
                                else viewModel.record()
                            },
                        ) {
                            Icon(Icons.Rounded.Mic, contentDescription = "Record", tint = Color.White)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${state.samples.size} of ${VoiceProfile.ENROLL_TARGET} recorded",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF3FBF7F),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Voice learned — ${state.enrolledCount} samples",
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    // The honest part. A profile whose own samples disagree with each
                    // other cannot separate this person from anyone else, and no
                    // threshold will rescue it — so show the number rather than imply
                    // a confidence that was never measured.
                    state.spread?.let { (low, high) ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Your recordings match each other %.2f–%.2f."
                                .format(low, high),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                low >= 0.6f ->
                                    "Comfortably above the ${"%.2f".format(VoiceProfile.DEFAULT_THRESHOLD)} " +
                                        "match threshold — this should work well."
                                low >= VoiceProfile.DEFAULT_THRESHOLD ->
                                    "Above the ${"%.2f".format(VoiceProfile.DEFAULT_THRESHOLD)} threshold, " +
                                        "but not by much. Expect the occasional miss."
                                else ->
                                    "Below the ${"%.2f".format(VoiceProfile.DEFAULT_THRESHOLD)} threshold, " +
                                        "so even you don't reliably match yourself. Re-record " +
                                        "somewhere quieter before switching on the setting below."
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Only respond to my voice", fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Ignore commands from other people and from the TV. Off by " +
                                    "default: an assistant that wrongly ignores you is worse " +
                                    "than one that answers someone else. Short commands are " +
                                    "always let through, since they carry too little voice to judge.",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.enforce,
                            onCheckedChange = viewModel::setEnforce,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = viewModel::restart) { Text("Record again") }
                        TextButton(onClick = viewModel::clear) { Text("Delete profile") }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Your voiceprints never leave this phone. They are numbers describing " +
                "your voice, not recordings — the audio is not kept.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
