package com.backlogkiller

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Locale

// ---------------------------------------------------------------- State

class AppState(private val c: Context) {
    var lectures by mutableStateOf(Store.lectures(c))
        private set
    var priority by mutableStateOf(Store.priority(c))
        private set
    var slots by mutableStateOf(Store.slots(c))
        private set
    var active by mutableStateOf(Store.active(c))
        private set

    fun addLecture(subject: String, name: String, minutes: Int) {
        lectures = lectures + Lecture(subject = subject, name = name.trim(), minutes = minutes)
        Store.saveLectures(c, lectures)
    }

    fun deleteLecture(id: String) {
        slots.filter { it.lectureId == id }.forEach { removeSlot(it.id) }
        lectures = lectures.filter { it.id != id }
        Store.saveLectures(c, lectures)
        if (active?.lectureId == id) {
            active = null
            Store.saveActive(c, null)
        }
    }

    fun movePriority(i: Int, delta: Int) {
        val j = i + delta
        if (j !in priority.indices) return
        val l = priority.toMutableList()
        Collections.swap(l, i, j)
        priority = l
        Store.savePriority(c, l)
    }

    fun nextPending(subject: String): Lecture? {
        val taken = slots.map { it.lectureId }.toSet()
        return lectures.firstOrNull {
            it.subject == subject && !it.done && it.id !in taken && it.id != active?.lectureId
        }
    }

    fun addSlot(subject: String, timeMillis: Long): Boolean {
        val lec = nextPending(subject) ?: return false
        val slot = Slot(lectureId = lec.id, timeMillis = timeMillis)
        slots = slots + slot
        Store.saveSlots(c, slots)
        Scheduler.schedule(c, slot)
        return true
    }

    fun removeSlot(id: String) {
        slots.find { it.id == id }?.let { Scheduler.cancel(c, it) }
        slots = slots.filter { it.id != id }
        Store.saveSlots(c, slots)
    }

    fun startClass(lectureId: String): Boolean {
        val lec = lectures.find { it.id == lectureId } ?: return false
        if (lec.done) return false
        if (active?.lectureId == lectureId) return true // already running
        slots.filter { it.lectureId == lectureId }.forEach { removeSlot(it.id) }
        c.getSystemService(NotificationManager::class.java).cancel(lectureId.hashCode())
        active = ActiveClass(
            lec.id, lec.subject, lec.name,
            System.currentTimeMillis() + lec.minutes * 60_000L
        )
        Store.saveActive(c, active)
        return true
    }

    fun completeClass() {
        val a = active ?: return
        lectures = lectures.map { if (it.id == a.lectureId) it.copy(done = true) else it }
        Store.saveLectures(c, lectures)
        active = null
        Store.saveActive(c, null)
    }
}

// ---------------------------------------------------------------- Activity

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(applicationContext)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (Build.VERSION.SDK_INT in 31..32) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(state, ::joinClass) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == ACTION_JOIN) {
            val id = i.getStringExtra("lectureId")
            i.action = null
            if (id != null) joinClass(id)
        }
    }

    private fun joinClass(lectureId: String) {
        if (!state.startClass(lectureId)) {
            Toast.makeText(this, "Lecture not found or already completed", Toast.LENGTH_LONG).show()
            return
        }
        val pw = packageManager.getLaunchIntentForPackage(PW_PACKAGE)
        if (pw != null) startActivity(pw)
        else Toast.makeText(this, "PW app not found ($PW_PACKAGE)", Toast.LENGTH_LONG).show()
    }
}

// ---------------------------------------------------------------- UI

private fun fmtTime(ms: Long) =
    SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(ms)

private fun pickTime(ctx: Context, onPicked: (Long) -> Unit) {
    val now = Calendar.getInstance()
    TimePickerDialog(ctx, { _, h, m ->
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        onPicked(cal.timeInMillis)
    }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
}

@Composable
fun App(state: AppState, onJoin: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Backlog Killer",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)
        )
        TabRow(selectedTabIndex = tab) {
            listOf("Home", "Add", "Plan").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> HomeTab(state, onJoin)
            1 -> AddTab(state)
            else -> PlanTab(state)
        }
    }
}

@Composable
fun ActiveCard(state: AppState) {
    val a = state.active ?: return
    var now by remember(a.lectureId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(a.lectureId) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= a.endMillis) {
                state.completeClass()
                break
            }
            delay(1000)
        }
    }
    val left = ((a.endMillis - now) / 1000).coerceAtLeast(0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("馃敶 In class: ${a.subject}", fontWeight = FontWeight.Bold)
            Text(a.name)
            Text(
                String.format("%02d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = { state.completeClass() }) { Text("Complete Class") }
        }
    }
}

@Composable
fun HomeTab(state: AppState, onJoin: (String) -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ActiveCard(state)

        Text("Scheduled Live Classes", style = MaterialTheme.typography.titleMedium)
        if (state.slots.isEmpty()) Text("Nothing scheduled. Use the Plan tab.")
        state.slots.sortedBy { it.timeMillis }.forEach { s ->
            val lec = state.lectures.find { it.id == s.lectureId } ?: return@forEach
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${fmtTime(s.timeMillis)} 鈥� ${lec.subject}", fontWeight = FontWeight.Bold)
                    Text("${lec.name} (${lec.minutes} min)")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onJoin(lec.id) }) { Text("Join Class") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { state.removeSlot(s.id) }) { Text("Remove") }
                    }
                }
            }
        }

        Text("Backlog Progress", style = MaterialTheme.typography.titleMedium)
        state.priority.forEachIndexed { idx, sub ->
            val all = state.lectures.filter { it.subject == sub }
            val done = all.count { it.done }
            val minsLeft = all.filter { !it.done }.sumOf { it.minutes }
            Column {
                Text("#${idx + 1} $sub 鈥� $done/${all.size} done 鈥� ${minsLeft / 60}h ${minsLeft % 60}m left")
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (all.isEmpty()) 0f else done / all.size.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun AddTab(state: AppState) {
    val ctx = LocalContext.current
    var subject by remember { mutableStateOf(SUBJECTS[0]) }
    var name by remember { mutableStateOf("") }
    var mins by remember { mutableStateOf("") }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Add Backlog Lecture", style = MaterialTheme.typography.titleMedium)
        SUBJECTS.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { subject = s },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = subject == s, onClick = { subject = s })
                Text(s)
            }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Lecture name / topic") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = mins, onValueChange = { mins = it.filter(Char::isDigit) },
            label = { Text("Duration (minutes)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            val m = mins.toIntOrNull()
            if (name.isBlank() || m == null || m <= 0) {
                Toast.makeText(ctx, "Enter a name and duration", Toast.LENGTH_SHORT).show()
            } else {
                state.addLecture(subject, name, m)
                name = ""; mins = ""
            }
        }) { Text("Add Lecture") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("All Lectures (${state.lectures.size})", style = MaterialTheme.typography.titleMedium)
        state.lectures.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (l.done) "鉁� " else "猬� ") + "${l.subject} 路 ${l.name} 路 ${l.minutes}m",
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { state.deleteLecture(l.id) }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun PlanTab(state: AppState) {
    val ctx = LocalContext.current

    fun schedule(subject: String) {
        pickTime(ctx) { t ->
            if (state.addSlot(subject, t)) {
                Toast.makeText(ctx, "Live class set: ${fmtTime(t)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "No pending lecture in $subject", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Subject Priority (finish #1 first)", style = MaterialTheme.typography.titleMedium)
        state.priority.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. $s", modifier = Modifier.weight(1f))
                TextButton(onClick = { state.movePriority(i, -1) }, enabled = i > 0) { Text("鈻�") }
                TextButton(
                    onClick = { state.movePriority(i, 1) },
                    enabled = i < state.priority.lastIndex
                ) { Text("鈻�") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Today's Class Picker", style = MaterialTheme.typography.titleMedium)

        val top = state.priority.firstOrNull { state.nextPending(it) != null }
        Button(onClick = { top?.let { schedule(it) } }, enabled = top != null) {
            Text(if (top != null) "Schedule top priority: $top" else "No pending lectures")
        }

        state.priority.forEach { s ->
            val next = state.nextPending(s)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s, fontWeight = FontWeight.Bold)
                        Text(
                            if (next != null) "Next: ${next.name} (${next.minutes}m)"
                            else "No pending lectures"
                        )
                    }
                    Button(onClick = { schedule(s) }, enabled = next != null) { Text("Set time") }
                }
            }
        }
    }
}
