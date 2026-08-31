package com.sentry.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.sentry.ui.theme.SentryTheme

/**
 * "Sentry keeps mishearing me" — the screen that fixes it.
 *
 * Presented as teaching Sentry a phrase, which is what it is from the user's side.
 * What it is not, and the screen says so, is retraining the recogniser: the copy is
 * honest that Sentry is learning *its own mistake*, because a user who thinks they
 * are improving general accuracy will be disappointed by the next new word.
 */
class TeachActivity : ComponentActivity() {

    private val viewModel: TeachViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SentryTheme(forceDark = false, dynamicColor = true) {
                Scaffold { padding ->
                    TeachScreen(
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
private fun TeachScreen(
    viewModel: TeachViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val learned by viewModel.learned.collectAsStateWithLifecycle()
    val learnedNames by viewModel.learnedNames.collectAsStateWithLifecycle()
    val learnedVerbs by viewModel.learnedVerbs.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Teach a phrase",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "If Sentry always mishears something — \"call maa\" coming out as " +
                "\"karma\" — say it a few times here. Sentry writes down what it " +
                "actually hears and remembers that those sounds mean this phrase.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "This does not retrain the recogniser — that isn't possible on a phone, " +
                "and it isn't what \"Ok Google\" enrollment does either. It learns a " +
                "consistent mistake so it stops mattering.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = state.phrase,
            onValueChange = viewModel::setPhrase,
            label = { Text("What you want to say") },
            placeholder = { Text("call maa") },
            singleLine = true,
            enabled = !state.listening,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(18.dp))

        if (state.phrase.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when {
                            state.listening -> "Say it now"
                            state.complete -> "That's enough"
                            else -> "Say \"${state.phrase}\" " +
                                "(${state.remaining} more time${if (state.remaining == 1) "" else "s"})"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.partial.ifBlank { " " },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(14.dp))

                    Surface(
                        shape = CircleShape,
                        color = if (state.listening) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(66.dp),
                    ) {
                        IconButton(
                            onClick = {
                                if (state.listening) viewModel.stopListening()
                                else viewModel.record()
                            },
                            enabled = !state.complete,
                        ) {
                            Icon(
                                Icons.Rounded.Mic,
                                contentDescription = "Record",
                                tint = Color.White,
                            )
                        }
                    }

                    if (state.attempts.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        state.attempts.forEachIndexed { index, attempt ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${index + 1}.",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "“${attempt.heard}”",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (!attempt.usable) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "already correct",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    state.error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (state.saved) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Learned. Try saying it to Sentry.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (state.complete && !state.saved) {
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                            Text("Teach it")
                        }
                    }

                    if (state.saved || state.complete) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                            Text("Teach another")
                        }
                    }
                }
            }
        }

        if (learned.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("Already learned", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))

            learned.forEach { (heard, meant) ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("“$heard”  →  “$meant”")
                        }
                        TextButton(onClick = { viewModel.forget(heard) }) { Text("Forget") }
                    }
                }
            }
        }

        if (learnedNames.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("Names picked up on their own", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Learned when you chose a contact from a list. Forget one if it " +
                    "starts sending you to the wrong person.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            learnedNames.forEach { (spoken, contact) ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("“$spoken”  →  $contact", Modifier.weight(1f))
                        TextButton(onClick = { viewModel.forgetName(spoken) }) { Text("Forget") }
                    }
                }
            }
        }

        if (learnedVerbs.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("Words you told Sentry the meaning of", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "When Sentry hears a volume or brightness command but not which way, " +
                    "it asks once and remembers your answer.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            learnedVerbs.forEach { (spoken, verb) ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("\u201C$spoken\u201D  \u2192  $verb", Modifier.weight(1f))
                        TextButton(onClick = { viewModel.forgetVerb(spoken) }) { Text("Forget") }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Everything here stays on this phone.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
