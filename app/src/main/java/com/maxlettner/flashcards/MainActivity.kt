package com.maxlettner.flashcards

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Screen { Cards, Study, Settings }

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_STUDY = "study" }

    private val startStudy = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Reminder.createChannel(this)
        Reminder.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startStudy.value = intent.getBooleanExtra(EXTRA_STUDY, false)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { App(startStudy) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_STUDY, false)) startStudy.value = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(startStudy: MutableState<Boolean>) {
    val context = LocalContext.current
    val store = remember { CardStore(context) }
    var cards by remember { mutableStateOf(store.load()) }
    var screen by remember { mutableStateOf(Screen.Cards) }
    fun update(new: List<Card>) { cards = new; store.save(new) }

    LaunchedEffect(startStudy.value) {
        if (startStudy.value) { screen = Screen.Study; startStudy.value = false }
    }
    BackHandler(screen != Screen.Cards) { screen = Screen.Cards }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when (screen) {
                    Screen.Cards -> "Flashcards"; Screen.Study -> "Study"; Screen.Settings -> "Settings"
                }) },
                navigationIcon = {
                    if (screen != Screen.Cards) IconButton({ screen = Screen.Cards }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (screen == Screen.Cards) IconButton({ screen = Screen.Settings }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                Screen.Cards -> CardsScreen(cards, ::update) { screen = Screen.Study }
                Screen.Study -> StudyScreen(cards, ::update) { screen = Screen.Cards }
                Screen.Settings -> SettingsScreen()
            }
        }
    }
}

@Composable
fun CardsScreen(cards: List<Card>, update: (List<Card>) -> Unit, study: () -> Unit) {
    var editing by remember { mutableStateOf<Card?>(null) }
    var adding by remember { mutableStateOf(false) }
    val due = cards.count { it.isDue }

    Column(Modifier.fillMaxSize()) {
        OutlinedCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$due due today", style = MaterialTheme.typography.titleLarge)
                    Text("${cards.size} cards total", style = MaterialTheme.typography.bodyMedium)
                }
                Button(study, enabled = due > 0) { Text("Study") }
            }
        }
        if (cards.isEmpty()) {
            Text("No cards yet. Tap + to add one.", Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(cards, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.front, maxLines = 2) },
                    supportingContent = { Text(c.back, maxLines = 2) },
                    trailingContent = {
                        IconButton({ update(cards - c) }) { Icon(Icons.Default.Delete, "Delete") }
                    },
                    modifier = Modifier.clickable { editing = c },
                )
                HorizontalDivider()
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        FloatingActionButton({ adding = true }) { Icon(Icons.Default.Add, "Add card") }
    }

    if (adding) EditDialog(null, { adding = false }) { f, b ->
        update(cards + Card(front = f, back = b)); adding = false
    }
    editing?.let { c ->
        EditDialog(c, { editing = null }) { f, b ->
            update(cards.map { if (it.id == c.id) it.copy(front = f, back = b) else it }); editing = null
        }
    }
}

@Composable
fun EditDialog(card: Card?, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var front by remember { mutableStateOf(card?.front ?: "") }
    var back by remember { mutableStateOf(card?.back ?: "") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (card == null) "New card" else "Edit card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(front, { front = it }, label = { Text("Front") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(back, { back = it }, label = { Text("Back") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton({ save(front.trim(), back.trim()) }, enabled = front.isNotBlank() && back.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

@Composable
fun StudyScreen(cards: List<Card>, update: (List<Card>) -> Unit, done: () -> Unit) {
    val current = cards.firstOrNull { it.isDue }
    var revealed by remember(current?.id, current?.box) { mutableStateOf(false) }
    val remaining = cards.count { it.isDue }

    if (current == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("All done for today! 🎉", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Button(done) { Text("Back to cards") }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$remaining remaining", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(16.dp))
        ElevatedCard(Modifier.fillMaxWidth().weight(1f).clickable { revealed = true }) {
            Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(current.front, fontSize = 26.sp, textAlign = TextAlign.Center)
                if (revealed) {
                    HorizontalDivider(Modifier.padding(vertical = 24.dp))
                    Text(current.back, fontSize = 22.sp, textAlign = TextAlign.Center)
                } else {
                    Spacer(Modifier.height(24.dp))
                    Text("Tap to reveal", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (revealed) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                fun answer(ok: Boolean) {
                    val reviewed = current.reviewed(ok)
                    // Move the card to the end so wrong answers come back after the others.
                    update(cards.filter { it.id != current.id } + reviewed)
                }
                OutlinedButton({ answer(false) }, Modifier.weight(1f)) { Text("Again") }
                Button({ answer(true) }, Modifier.weight(1f)) { Text("Got it") }
            }
        } else {
            Button({ revealed = true }, Modifier.fillMaxWidth()) { Text("Show answer") }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { ReminderSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var hour by remember { mutableIntStateOf(settings.hour) }
    var minute by remember { mutableIntStateOf(settings.minute) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListItem(
            headlineContent = { Text("Daily reminder") },
            supportingContent = { Text("Notify me when cards are due") },
            trailingContent = {
                Switch(enabled, {
                    enabled = it; settings.enabled = it; Reminder.schedule(context)
                })
            },
        )
        ListItem(
            headlineContent = { Text("Reminder time") },
            supportingContent = { Text("%02d:%02d".format(hour, minute)) },
            modifier = Modifier.clickable(enabled = enabled) {
                TimePickerDialog(context, { _, h, m ->
                    hour = h; minute = m
                    settings.hour = h; settings.minute = m
                    Reminder.schedule(context)
                }, hour, minute, android.text.format.DateFormat.is24HourFormat(context)).show()
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            ListItem(
                headlineContent = { Text("Allow exact alarms") },
                supportingContent = { Text("Without this, the reminder may arrive a few minutes late.") },
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                },
            )
        }
    }
}
