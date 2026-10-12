package io.github.svyyyaaat.ankiwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Widget settings. Opened when a widget is added or reconfigured, and also from the launcher
 * (without a widget) to grant access to AnkiDroid.
 */
class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // Backing out of the first configuration cancels adding the widget.
        setResult(Activity.RESULT_CANCELED, result)

        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConfigScreen(
                        appWidgetId = appWidgetId.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID },
                        onSaved = {
                            setResult(Activity.RESULT_OK, result)
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun ConfigScreen(appWidgetId: Int?, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(AnkiDroid.isInstalled(context)) }
    var permitted by remember { mutableStateOf(AnkiDroid.hasPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    var decks by remember { mutableStateOf<List<AnkiDroid.Deck>?>(null) }
    var deckError by remember { mutableStateOf<String?>(null) }

    var deckId by remember { mutableStateOf(AnkiDroid.CURRENT_DECK) }
    var systemColors by remember { mutableStateOf(true) }
    var showCounts by remember { mutableStateOf(true) }
    var simpleControls by remember { mutableStateOf(false) }
    var pickingDeck by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        denied = !granted
    }

    // Re-check when coming back from AnkiDroid / the Play Store / system settings.
    LifecycleResumeEffect(Unit) {
        installed = AnkiDroid.isInstalled(context)
        permitted = AnkiDroid.hasPermission(context)
        onPauseOrDispose {}
    }

    LaunchedEffect(appWidgetId) {
        if (appWidgetId == null) return@LaunchedEffect
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        val prefs = getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, glanceId)
        deckId = prefs[Keys.DECK_ID] ?: AnkiDroid.CURRENT_DECK
        systemColors = prefs[Keys.SYSTEM_COLORS] ?: true
        showCounts = prefs[Keys.SHOW_COUNTS] ?: true
        simpleControls = prefs[Keys.SIMPLE_CONTROLS] ?: false
    }

    LaunchedEffect(installed, permitted) {
        if (!installed || !permitted) return@LaunchedEffect
        try {
            decks = withContext(Dispatchers.IO) { AnkiDroid.decks(context) }
            deckError = null
        } catch (e: Exception) {
            deckError = e.message ?: e.javaClass.simpleName
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (appWidgetId != null) "Widget settings" else "AnkiWidget", style = MaterialTheme.typography.headlineMedium)

        when {
            !installed -> Notice(
                "AnkiWidget reviews your AnkiDroid cards, so AnkiDroid needs to be installed.",
                "Get AnkiDroid",
            ) { context.startActivity(AnkiDroid.playStoreIntent()) }

            !permitted && denied -> Notice(
                "Access to AnkiDroid was denied. Allow it in this app's permissions to use the widget.",
                "Open app settings",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            !permitted -> Notice(
                "The widget reads due cards from AnkiDroid and records your answers there, just like reviewing in the app.",
                "Allow access to AnkiDroid",
            ) { permissionLauncher.launch(AnkiDroid.PERMISSION) }
        }

        if (appWidgetId == null) {
            Text(
                if (permitted) {
                    "All set. Add the widget from your home screen's widget list. Long-press the widget to change its settings."
                } else {
                    "Then add the widget from your home screen's widget list."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }

        SettingRow(
            title = "Deck",
            subtitle = when {
                deckId == AnkiDroid.CURRENT_DECK -> "AnkiDroid's current deck"
                else -> decks?.firstOrNull { it.id == deckId }?.name ?: deckError ?: "Loading…"
            },
            onClick = if (permitted) ({ pickingDeck = true }) else null,
        )
        SwitchRow("Use system colours", "Follow your wallpaper colours and light/dark theme. Off uses Anki's classic colours.", systemColors) {
            systemColors = it
        }
        SwitchRow("Show card counts", "New, learning and review cards left today.", showCounts) { showCounts = it }
        SwitchRow(
            "Simplified controls",
            "Instead of four buttons, tap the left half of the answer for Again and the right half for Good.",
            simpleControls,
        ) { simpleControls = it }

        Spacer(Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
                    // Write any held answer against the old deck before settings change.
                    WidgetController.perform(context, glanceId, Action.COMMIT)
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs[Keys.DECK_ID] = deckId
                        prefs[Keys.SYSTEM_COLORS] = systemColors
                        prefs[Keys.SHOW_COUNTS] = showCounts
                        prefs[Keys.SIMPLE_CONTROLS] = simpleControls
                    }
                    WidgetController.perform(context, glanceId, Action.REFRESH)
                    onSaved()
                }
            },
        ) { Text("Save") }
    }

    if (pickingDeck) {
        DeckPicker(
            decks = decks.orEmpty(),
            selected = deckId,
            onPick = {
                deckId = it
                pickingDeck = false
            },
            onDismiss = { pickingDeck = false },
        )
    }
}

@Composable
private fun Notice(text: String, button: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) OutlinedButton(onClick = onClick) { Text("Change") }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun DeckPicker(decks: List<AnkiDroid.Deck>, selected: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(AnkiDroid.Deck(AnkiDroid.CURRENT_DECK, "AnkiDroid's current deck")) + decks
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deck") },
        text = {
            LazyColumn {
                items(options, key = { it.id }) { deck ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(deck.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = deck.id == selected, onClick = { onPick(deck.id) })
                        // AnkiDroid uses "::" for subdecks; show them indented by depth.
                        val depth = deck.name.split("::").size - 1
                        Text(deck.name.substringAfterLast("::"), modifier = Modifier.padding(start = (depth * 16).dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
