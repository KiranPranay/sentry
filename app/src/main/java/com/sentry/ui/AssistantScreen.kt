package com.sentry.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sentry.core.Agent
import com.sentry.core.Chip
import com.sentry.core.ChipIcon
import com.sentry.core.Party
import com.sentry.core.Turn

/**
 * The assistant surface, drawn over whatever the user was doing.
 *
 * Layout follows the eye: the orb is the thing that moves and it sits where the
 * user's attention already is, the live transcription sits directly under it so the
 * two read as one object, and history scrolls away above. The keyboard is present
 * but deliberately small — this is a voice assistant, and typing is the fallback.
 */
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    onDismiss: () -> Unit,
) {
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val partial by viewModel.partial.collectAsStateWithLifecycle()
    val amplitude by viewModel.amplitude.collectAsStateWithLifecycle()
    val speechReady by viewModel.speechReady.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    // The scrim darkens as the assistant becomes active, so the app underneath
    // recedes without ever fully disappearing.
    val scrimAlpha by animateFloatAsState(
        targetValue = if (status == Agent.Status.IDLE) 0.92f else 0.97f,
        animationSpec = tween(400),
        label = "scrim",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    // Never below ~0.82: the transcript and the live transcription sit
                    // on top of this, and at the alpha that merely "dims" the app
                    // behind, both are still legible enough to compete for the eye.
                    0f to Color.Black.copy(alpha = scrimAlpha * 0.88f),
                    0.4f to Color.Black.copy(alpha = scrimAlpha * 0.96f),
                    1f to Color.Black.copy(alpha = scrimAlpha),
                )
            )
            // Tapping the backdrop dismisses. No ripple: this is empty space, not a
            // button, and a ripple out here would look like a mistake.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White.copy(alpha = 0.55f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // History, oldest at the top, pushed up as the conversation grows.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                // Top padding clears the close button, which floats above this list;
                // without it a long first message slides underneath the X.
                contentPadding = PaddingValues(top = 52.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            ) {
                items(transcript, key = { it.id }) { turn -> TurnRow(turn) }
            }

            Orb(
                status = status,
                amplitude = amplitude,
                modifier = Modifier
                    .padding(vertical = 18.dp)
                    .size(132.dp),
            )

            StatusLine(
                status = status,
                partial = partial,
                speechReady = speechReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 28.dp),
            )

            InputBar(
                listening = status == Agent.Status.LISTENING,
                onMic = viewModel::toggleMic,
                onSubmit = viewModel::submit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * What Sentry is doing, in words.
 *
 * The live transcription lives here rather than in the history, so the user sees
 * their sentence forming in one place instead of watching a bubble rewrite itself.
 */
@Composable
private fun StatusLine(
    status: Agent.Status,
    partial: String,
    speechReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = when {
        partial.isNotBlank() -> partial
        !speechReady -> "Getting speech ready…"
        status == Agent.Status.LISTENING -> "Listening"
        status == Agent.Status.THINKING -> "Thinking"
        status == Agent.Status.SPEAKING -> ""
        else -> "Tap the mic, or say \"Sentry\""
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            color = if (partial.isNotBlank()) Color.White else Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            fontWeight = if (partial.isNotBlank()) FontWeight.Normal else FontWeight.Light,
        )
    }
}

@Composable
private fun TurnRow(turn: Turn) {
    val isUser = turn.party == Party.USER

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 },
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Column(
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                turn.chip?.let { chip ->
                    ChipBadge(chip)
                    Spacer(Modifier.height(6.dp))
                }

                if (turn.text.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = if (isUser) 20.dp else 6.dp,
                            bottomEnd = if (isUser) 6.dp else 20.dp,
                        ),
                        color = when {
                            turn.isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                            isUser -> Color.White.copy(alpha = 0.14f)
                            else -> Color.White.copy(alpha = 0.07f)
                        },
                    ) {
                        Text(
                            text = turn.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = when {
                                turn.isError -> MaterialTheme.colorScheme.error
                                isUser -> Color.White.copy(alpha = 0.92f)
                                else -> Color.White
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The badge that says what actually happened — "Alarm · 7:00 AM". */
@Composable
private fun ChipBadge(chip: Chip) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = iconFor(chip.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = chip.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun iconFor(icon: ChipIcon): ImageVector = when (icon) {
    ChipIcon.ALARM -> Icons.Default.Alarm
    ChipIcon.TIMER -> Icons.Default.HourglassBottom
    ChipIcon.CALL -> Icons.Default.Call
    ChipIcon.MESSAGE -> Icons.Default.Message
    ChipIcon.MUSIC -> Icons.Default.MusicNote
    ChipIcon.TORCH -> Icons.Default.FlashlightOn
    ChipIcon.VOLUME -> Icons.Default.VolumeUp
    ChipIcon.APP -> Icons.Default.Apps
    ChipIcon.SEARCH -> Icons.Default.Search
    ChipIcon.NAVIGATION -> Icons.Default.Navigation
    ChipIcon.CAMERA -> Icons.Default.CameraAlt
    ChipIcon.BATTERY -> Icons.Default.BatteryFull
    ChipIcon.SETTINGS -> Icons.Default.Settings
    ChipIcon.CLOCK -> Icons.Default.Schedule
    ChipIcon.DND -> Icons.Default.DoNotDisturbOn
}

@Composable
private fun InputBar(
    listening: Boolean,
    onMic: () -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }

    fun send() {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            draft = ""
            onSubmit(text)
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text("Type instead", color = Color.White.copy(alpha = 0.4f))
            },
            shape = CircleShape,
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.09f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.09f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                if (draft.isNotBlank()) {
                    IconButton(onClick = ::send) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
        )

        Surface(
            shape = CircleShape,
            color = if (listening) MaterialTheme.colorScheme.primary
            else Color.White.copy(alpha = 0.12f),
            modifier = Modifier.size(52.dp),
        ) {
            IconButton(onClick = onMic) {
                Icon(
                    imageVector = if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = if (listening) "Stop listening" else "Speak",
                    tint = if (listening) MaterialTheme.colorScheme.onPrimary else Color.White,
                )
            }
        }
    }
}
