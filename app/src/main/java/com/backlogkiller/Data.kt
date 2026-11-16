package com.backlogkiller

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

val SUBJECTS = listOf(
    "Physics", "Maths", "Physical Chemistry", "Organic Chemistry", "Inorganic Chemistry"
)
const val PW_PACKAGE = "xyz.penpencil.physicswala"
const val ACTION_JOIN = "com.backlogkiller.JOIN"

data class Lecture(
    val id: String = UUID.randomUUID().toString(),
    val subject: String = "",
    val name: String = "",
    val minutes: Int = 0,
    val done: Boolean = false
)

data class Slot(
    val id: String = UUID.randomUUID().toString(),
    val lectureId: String = "",
    val timeMillis: Long = 0L
)

data class ActiveClass(
    val lectureId: String = "",
    val subject: String = "",
    val name: String = "",
    val endMillis: Long = 0L
)

object Store {
    private val gson = Gson()
    private fun prefs(c: Context) =
        c.getSharedPreferences("backlog_killer", Context.MODE_PRIVATE)

    private inline fun <reified T> get(c: Context, key: String): T? {
        val json = prefs(c).getString(key, null) ?: return null
        return try {
            gson.fromJson<T>(json, object : TypeToken<T>() {}.type)
        } catch (e: Exception) {
            null
        }
    }

    private fun put(c: Context, key: String, v: Any?) {
        prefs(c).edit().putString(key, gson.toJson(v)).apply()
    }

    fun lectures(c: Context): List<Lecture> = get<List<Lecture>>(c, "lectures") ?: emptyList()
    fun saveLectures(c: Context, v: List<Lecture>) = put(c, "lectures", v)

    fun priority(c: Context): List<String> {
        val p = get<List<String>>(c, "priority") ?: return SUBJECTS
        return if (p.toSet() == SUBJECTS.toSet() && p.size == SUBJECTS.size) p else SUBJECTS
    }
    fun savePriority(c: Context, v: List<String>) = put(c, "priority", v)

    fun slots(c: Context): List<Slot> = get<List<Slot>>(c, "slots") ?: emptyList()
    fun saveSlots(c: Context, v: List<Slot>) = put(c, "slots", v)

    fun active(c: Context): ActiveClass? = get<ActiveClass>(c, "active")
    fun saveActive(c: Context, v: ActiveClass?) = put(c, "active", v)
}

object Scheduler {
    private fun pending(c: Context, slotId: String): PendingIntent =
        PendingIntent.getBroadcast(
            c,
            slotId.hashCode(),
            Intent(c, AlarmReceiver::class.java).putExtra("slotId", slotId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(c: Context, slot: Slot) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = pending(c, slot.id)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.timeMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.timeMillis, pi)
        }
    }

    fun cancel(c: Context, slot: Slot) {
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, slot.id))
    }
}
