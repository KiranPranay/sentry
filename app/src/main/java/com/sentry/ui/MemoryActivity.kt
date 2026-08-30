package com.sentry.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sentry.data.Fact
import com.sentry.data.Remembered
import com.sentry.sentry
import com.sentry.ui.theme.SentryTheme

/**
 * Everything Sentry has picked up about the person using it.
 *
 * The point of the screen is that memory should never be a black box. Every row shows
 * the sentence the fact came from, because a fact whose provenance is hidden is one
 * the user cannot judge — and an assistant quietly holding a wrong belief about your
 * family is worse than one holding none.
 */
class MemoryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SentryTheme(forceDark = false, dynamicColor = true) {
                Scaffold { padding ->
                    MemoryScreen(
                        onClose = { finish() },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val memory = LocalContext.current.sentry.memory

    var rows by remember { mutableStateOf(memory.all()) }
    var editing by remember { mutableStateOf<Fact?>(null) }
    var draft by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    fun refresh() {
        rows = memory.all()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "What Sentry knows",
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
            "Picked up while you were talking. Sentry never asks for these and never " +
                "sends them anywhere — they stay on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        if (rows.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Nothing yet", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tell Sentry something — “my mother's name is Rani”, " +
                            "“my blood group is B positive” — and it will appear here.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        rows.forEach { row ->
            FactRow(
                row = row,
                onEdit = {
                    editing = row.fact
                    draft = row.value
                },
                onForget = {
                    memory.forget(row.fact)
                    refresh()
                },
            )
        }

        // Adding by hand matters as much as the automatic path: it is how someone
        // fixes a fact Sentry never picked up, or got subtly wrong.
        val missing = Fact.entries.filter { fact -> rows.none { it.fact == fact } }
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Not known yet", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            missing.forEach { fact ->
                TextButton(
                    onClick = {
                        editing = fact
                        draft = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Text("Add ${fact.label.lowercase()}", Modifier.align(Alignment.CenterStart))
                    }
                }
            }
        }

        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Forget everything", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    editing?.let { fact ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(fact.label) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(fact.question) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    memory.remember(fact, draft, source = "you typed it")
                    refresh()
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Forget everything?") },
            text = { Text("Sentry will lose every fact it has learned about you.") },
            confirmButton = {
                TextButton(onClick = {
                    memory.clear()
                    refresh()
                    confirmClear = false
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun FactRow(row: Remembered, onEdit: () -> Unit, onForget: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.fact.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(row.value, fontWeight = FontWeight.Medium)

                // Where it came from. Without this the user has no way to tell a fact
                // they stated from one Sentry mangled out of a passing remark.
                if (row.source.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "from “${row.source}”",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onEdit) { Text("Edit") }
            IconButton(onClick = onForget) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Forget",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
