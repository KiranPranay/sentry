package com.sentry.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sentry.sentry
import com.sentry.ui.theme.SentryTheme
import com.sentry.voice.HotwordService
import com.sentry.voice.SpeechPack
import kotlinx.coroutines.launch
import dev.taracore.client.TaraCore

/**
 * Setup and settings — everything that has to be true before the assistant works.
 *
 * Presented as a checklist rather than a settings list because that is what it
 * actually is: five things Android will not let an app do for itself, each with the
 * one button that fixes it.
 */
class HomeActivity : ComponentActivity() {

    private val required = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SentryTheme(forceDark = false, dynamicColor = true) {
                Scaffold { padding ->
                    HomeScreen(
                        modifier = Modifier.padding(padding),
                        permissions = required,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    permissions: Array<String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = context.sentry

    val speechReady by container.voice.ready.collectAsStateWithLifecycle()
    var speechPack by remember { mutableStateOf(container.prefs.speechPack) }
    val scope = rememberCoroutineScope()

    // Recomposition trigger: the checks below read system state that changes while
    // the user is away in Settings, so a returning user sees the truth.
    var refresh by remember { mutableStateOf(0) }
    var hotwordOn by remember { mutableStateOf(container.prefs.hotwordEnabled) }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    val missing = remember(refresh) {
        permissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    }
    val learnedCount = remember(refresh) { container.phrases.all().size }
    val taraInstalled = remember(refresh) { TaraCore.isInstalled(context) }
    val isDefaultAssistant = remember(refresh) { context.isDefaultAssistant() }
    val canScheduleAlarms = remember(refresh) { context.canScheduleExactAlarms() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            "Sentry",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "An assistant that runs on this phone, and nowhere else.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        SetupCard(
            title = "Tara Core",
            detail = if (taraInstalled) {
                "Installed. Sentry uses it for anything a pattern can't answer."
            } else {
                "Not installed. Sentry still handles alarms, calls and the torch " +
                    "without it, but cannot hold a conversation."
            },
            done = taraInstalled,
            action = if (taraInstalled) null else "Get Tara Core",
            onAction = {
                runCatching { context.startActivity(TaraCore.installIntent()) }
                    .onFailure { context.startActivity(TaraCore.installIntentFallback()) }
            },
        )

        SetupCard(
            title = "Permissions",
            detail = if (missing.isEmpty()) "All granted."
            else "${missing.size} still needed for calls, contacts and the microphone.",
            done = missing.isEmpty(),
            action = if (missing.isEmpty()) null else "Grant",
            onAction = { requestPermissions.launch(permissions) },
        )

        SetupCard(
            title = "Exact alarms",
            detail = if (canScheduleAlarms) "Allowed."
            else "Needed so \"set an alarm for 7\" actually sets one.",
            done = canScheduleAlarms,
            action = if (canScheduleAlarms) null else "Allow",
            onAction = { context.openExactAlarmSettings() },
        )

        SetupCard(
            title = "Speech model",
            detail = if (speechReady) "${speechPack.label} — recognition runs offline."
            else "Unpacking ${speechPack.label}…",
            done = speechReady,
        )

        // Accent, not size, is what decides whether recognition works. Nobody can
        // pick this correctly on the user's behalf, so it is a visible choice rather
        // than a build-time constant.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Recognise my accent as", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "If Sentry keeps mishearing you, change this. It matters more " +
                        "than model size.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                SpeechPack.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = option != speechPack) {
                                speechPack = option
                                container.prefs.speechPack = option
                                scope.launch { container.voice.use(option) }
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(
                            selected = option == speechPack,
                            onClick = {
                                speechPack = option
                                container.prefs.speechPack = option
                                scope.launch { container.voice.use(option) }
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(option.label)
                            Text(
                                option.detail,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        SetupCard(
            title = "Default assistant",
            detail = if (isDefaultAssistant) {
                "Sentry answers the assist gesture and the power button."
            } else {
                "Still Google. Change it under Default apps → Digital assistant app."
            },
            done = isDefaultAssistant,
            action = if (isDefaultAssistant) null else "Change",
            onAction = { context.openAssistantSettings() },
        )

        Spacer(Modifier.height(8.dp))

        // The one genuine toggle: everything else on this screen is a system setting.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Wake word", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Say \"Sentry\" any time. Keeps a quiet notification while " +
                            "listening — Android requires it. Audio never leaves the phone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = hotwordOn,
                    enabled = missing.none { it == Manifest.permission.RECORD_AUDIO },
                    onCheckedChange = { wanted ->
                        hotwordOn = wanted
                        container.prefs.hotwordEnabled = wanted
                        if (wanted) HotwordService.start(context) else HotwordService.stop(context)
                    },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { context.startActivity(Intent(context, TeachActivity::class.java)) },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Teach a phrase", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "If Sentry always mishears something — \"call maa\" as \"karma\" — " +
                        "say it a few times and it will learn what you mean." +
                        (if (learnedCount > 0) "  ($learnedCount learned)" else ""),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                context.startActivity(
                    Intent(context, AssistantActivity::class.java)
                        .putExtra(AssistantActivity.EXTRA_LISTEN_IMMEDIATELY, true)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = missing.none { it == Manifest.permission.RECORD_AUDIO },
        ) {
            Text("Talk to Sentry")
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
            Text("Re-check")
        }
    }
}

@Composable
private fun SetupCard(
    title: String,
    detail: String,
    done: Boolean,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = when {
                    done -> Icons.Default.CheckCircle
                    action != null -> Icons.Default.ErrorOutline
                    else -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (done) Color(0xFF3FBF7F) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (action != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onAction,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 18.dp,
                            vertical = 6.dp,
                        ),
                    ) {
                        Text(action)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun Context.isDefaultAssistant(): Boolean {
    val current = Settings.Secure.getString(contentResolver, "voice_interaction_service")
    return current?.startsWith(packageName) == true
}

private fun Context.canScheduleExactAlarms(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    } else {
        true
    }

private fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:$packageName"))
        )
    }
}

/**
 * There is no direct intent for the assistant picker, so this walks the user to the
 * screen that has it. Voice input settings is the closest reliable landing point
 * across OEM builds.
 */
private fun Context.openAssistantSettings() {
    val candidates = listOf(
        Intent("android.settings.VOICE_INPUT_SETTINGS"),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            return
        }
    }
}
