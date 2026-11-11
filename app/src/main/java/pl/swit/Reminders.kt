package pl.swit

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Wpis w kalendarzu: przypomnienie, wydarzenie, urodziny albo wyjazd.
 * level: 0 niska (zielony), 1 normalna (niebieski), 2 ważna (pomarańczowy), 3 pilna (czerwony).
 * repeat: none, hourly (co [every] h między [fromH] a [toH]), daily, weekly ([days] pn..nd), monthly, yearly.
 */
class Ev(
    var id: Long = 0, var title: String = "", var note: String = "", var type: String = "reminder", var level: Int = 1,
    var start: Long = 0, var end: Long = 0, var allDay: Boolean = false,
    var repeat: String = "none", var every: Int = 1, var days: Int = 0, var fromH: Int = 8, var toH: Int = 20, var until: Long = 0,
    var alerts: MutableList<Int> = mutableListOf(0), var done: MutableSet<Long> = mutableSetOf(),
    var external: Boolean = false, var color: Int = 0
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("t", title).put("n", note).put("ty", type).put("lv", level).put("s", start).put("e", end)
        .put("ad", allDay).put("r", repeat).put("ev", every).put("d", days).put("fh", fromH).put("th", toH).put("u", until)
        .put("al", JSONArray(alerts)).put("dn", JSONArray(done.toList()))

    companion object {
        fun of(o: JSONObject) = Ev(o.optLong("id"), o.optString("t"), o.optString("n"), o.optString("ty", "reminder"), o.optInt("lv", 1),
            o.optLong("s"), o.optLong("e"), o.optBoolean("ad"), o.optString("r", "none"), o.optInt("ev", 1), o.optInt("d"), o.optInt("fh", 8),
            o.optInt("th", 20), o.optLong("u"),
            o.optJSONArray("al")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toMutableList() } ?: mutableListOf(0),
            o.optJSONArray("dn")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toMutableSet() } ?: mutableSetOf())
    }

    val duration get() = if (end > start) end - start else 0L
}

/** Pojedyncze wystąpienie wpisu w danym czasie. */
class Occ(val ev: Ev, val start: Long, val end: Long)

object Rem {
    val LEVELS = listOf("Niska" to 0xFF34C759.toInt(), "Normalna" to 0xFF0A84FF.toInt(), "Ważna" to 0xFFFF9F0A.toInt(), "Pilna" to 0xFFFF453A.toInt())
    val TYPES = listOf("reminder" to "Przypomnienie", "event" to "Wydarzenie", "birthday" to "Urodziny", "trip" to "Wyjazd")
    val ALERTS = listOf(0 to "W chwili", 5 to "5 min", 10 to "10 min", 30 to "30 min", 60 to "1 h", 120 to "2 h", 1440 to "1 dzień", 2880 to "2 dni", 10080 to "tydzień")

    fun color(e: Ev) = if (e.external && e.color != 0) e.color else LEVELS[e.level.coerceIn(0, 3)].second

    fun typeIcon(t: String) = when (t) { "birthday" -> "🎂"; "trip" -> "🧳"; "event" -> "📅"; else -> "🔔" }

    // ------------------------------------------------------------ zapis
    fun all(c: Context): MutableList<Ev> {
        val a = try { JSONArray(Prefs.get(c).getString("events", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { Ev.of(a.getJSONObject(it)) }.toMutableList()
    }

    fun save(c: Context, l: List<Ev>) {
        Prefs.get(c).edit().putString("events", JSONArray(l.map { it.json() }).toString()).apply()
        schedule(c)
    }

    fun put(c: Context, e: Ev) {
        if (e.id == 0L) e.id = System.currentTimeMillis()
        save(c, all(c).filter { it.id != e.id } + e)
    }

    fun remove(c: Context, id: Long) = save(c, all(c).filter { it.id != id })

    fun get(c: Context, id: Long) = all(c).firstOrNull { it.id == id }

    // ------------------------------------------------------------ powtórzenia
    /** Wystąpienia wpisu, które nachodzą na przedział [from, to). */
    fun occurrences(e: Ev, from: Long, to: Long): List<Occ> {
        val out = ArrayList<Occ>()
        val dur = e.duration
        if (e.repeat == "none") {
            if (e.start < to && maxOf(e.start + dur, e.start + 1) > from) out.add(Occ(e, e.start, e.start + dur))
            return out
        }
        val cal = Calendar.getInstance().apply { timeInMillis = e.start }
        val limit = if (e.until > 0) minOf(e.until + 86_400_000L, to) else to
        var guard = 0
        if (e.repeat == "hourly") {
            // co [every] godzin między fromH a toH, każdego dnia (lub w wybrane dni)
            val day = Calendar.getInstance().apply { timeInMillis = maxOf(from - 86_400_000L, e.start); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val minute = Calendar.getInstance().apply { timeInMillis = e.start }.get(Calendar.MINUTE)
            while (day.timeInMillis < limit && guard++ < 800) {
                val dow = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7
                if (e.days == 0 || e.days and (1 shl dow) != 0) {
                    var hh = e.fromH
                    while (hh <= e.toH) {
                        val t = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, minute) }.timeInMillis
                        if (t >= e.start && t < limit && t + dur > from - 1) out.add(Occ(e, t, t + dur))
                        hh += e.every.coerceAtLeast(1)
                    }
                }
                day.add(Calendar.DAY_OF_YEAR, 1)
            }
            return out
        }
        // przewiń bliżej początku przedziału
        if (e.repeat == "daily" || e.repeat == "weekly") {
            val skipDays = ((from - e.start - dur) / 86_400_000L - 1).coerceAtLeast(0)
            if (skipDays > 0) { val step = if (e.repeat == "daily") e.every.coerceAtLeast(1) else 7 * e.every.coerceAtLeast(1); cal.add(Calendar.DAY_OF_YEAR, ((skipDays / step) * step).toInt()) }
        }
        while (cal.timeInMillis < limit && guard++ < 3000) {
            val t = cal.timeInMillis
            when (e.repeat) {
                "weekly" -> {
                    // wybrane dni tygodnia (albo dzień startu)
                    val mask = if (e.days == 0) 1 shl ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) else e.days
                    val week = (cal.clone() as Calendar)
                    for (k in 0..6) {
                        val dow = (week.get(Calendar.DAY_OF_WEEK) + 5) % 7
                        val wt = week.timeInMillis
                        if (mask and (1 shl dow) != 0 && wt >= e.start && wt < limit && wt + dur > from - 1) out.add(Occ(e, wt, wt + dur))
                        week.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    cal.add(Calendar.DAY_OF_YEAR, 7 * e.every.coerceAtLeast(1))
                    continue
                }
                else -> if (t + dur > from - 1) out.add(Occ(e, t, t + dur))
            }
            when (e.repeat) {
                "daily" -> cal.add(Calendar.DAY_OF_YEAR, e.every.coerceAtLeast(1))
                "monthly" -> cal.add(Calendar.MONTH, e.every.coerceAtLeast(1))
                "yearly" -> cal.add(Calendar.YEAR, e.every.coerceAtLeast(1))
                else -> break
            }
        }
        return out.filter { it.start < to }
    }

    /** Wszystkie wystąpienia w przedziale (własne + kalendarz telefonu), posortowane. */
    fun range(c: Context, from: Long, to: Long): List<Occ> {
        val l = ArrayList<Occ>()
        for (e in all(c)) l.addAll(occurrences(e, from, to))
        if (Prefs.get(c).getBoolean("sysCal", true)) l.addAll(system(c, from, to))
        return l.sortedWith(compareBy<Occ> { !it.ev.allDay }.thenBy { it.start })
    }

    fun repeatText(e: Ev): String = when (e.repeat) {
        "hourly" -> "co ${e.every} h, ${e.fromH}:00–${e.toH}:00" + if (e.days != 0) " (" + daysText(e.days) + ")" else ""
        "daily" -> if (e.every == 1) "codziennie" else "co ${e.every} dni"
        "weekly" -> (if (e.every == 1) "co tydzień" else "co ${e.every} tyg.") + if (e.days != 0) " (" + daysText(e.days) + ")" else ""
        "monthly" -> if (e.every == 1) "co miesiąc" else "co ${e.every} mies."
        "yearly" -> "co rok"
        else -> ""
    }

    fun daysText(d: Int): String {
        if (d == 0x1f) return "pn–pt"
        if (d == 0x7f) return "codziennie"
        val n = listOf("pn", "wt", "śr", "cz", "pt", "sb", "nd")
        return (0..6).filter { d and (1 shl it) != 0 }.joinToString(" ") { n[it] }
    }

    // ------------------------------------------------------------ planowanie powiadomień
    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 800_000, Intent(c, AlarmReceiver::class.java).setAction("REM"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Najbliższy moment powiadomienia: (czas powiadomienia, wystąpienie). */
    private fun alertsBetween(c: Context, from: Long, to: Long): List<Pair<Long, Occ>> {
        val out = ArrayList<Pair<Long, Occ>>()
        val maxAhead = 10080 * 60_000L
        for (e in all(c)) for (o in occurrences(e, from, to + maxAhead)) {
            if (o.start in o.ev.done) continue
            for (m in e.alerts) {
                val base = if (e.allDay) Calendar.getInstance().apply { timeInMillis = o.start; set(Calendar.HOUR_OF_DAY, Prefs.get(c).getInt("allDayAt", 9)); set(Calendar.MINUTE, 0) }.timeInMillis else o.start
                val t = base - m * 60_000L
                if (t in from until to) out.add(Pair(t, o))
            }
        }
        return out.sortedBy { it.first }
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val next = alertsBetween(c, now + 1000, now + 400L * 86_400_000L).firstOrNull()
        am.cancel(pi(c))
        if (next != null) {
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.first, pi(c)) }
            catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.first, pi(c)) }
        }
        scheduleBedtime(c)
    }

    /** Uruchamiane przez alarm: pokazuje wszystkie powiadomienia, które właśnie wypadły. */
    fun fire(c: Context) {
        val pr = Prefs.get(c)
        val last = pr.getLong("remLast", System.currentTimeMillis() - 5 * 60_000L)
        val now = System.currentTimeMillis()
        for ((t, o) in alertsBetween(c, last, now + 30_000L)) notify(c, o, t)
        pr.edit().putLong("remLast", now + 30_000L).apply()
        schedule(c)
    }

    private fun channel(c: Context, important: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("rem", "Przypomnienia", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("rem_low", "Przypomnienia (ciche)", NotificationManager.IMPORTANCE_DEFAULT))
        if (important) Unit
    }

    fun notify(c: Context, o: Occ, at: Long) {
        val e = o.ev
        channel(c, e.level >= 2)
        val df = SimpleDateFormat(if (e.allDay) "EEEE, d MMMM" else "EEEE, d MMMM, H:mm", Locale("pl"))
        val before = ((if (e.allDay) at else o.start) - at) / 60_000L
        val whenTxt = when {
            e.type == "birthday" -> { val age = ageAt(e, o.start); if (age > 0) "kończy $age lat" else "urodziny" }
            before <= 1 -> "teraz"
            before < 60 -> "za $before min"
            before < 1440 -> "za ${before / 60} h"
            else -> df.format(Date(o.start))
        }
        val id = (e.id xor o.start).toInt()
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(c, id + code, Intent(c, AlarmReceiver::class.java).setAction(a).putExtra("ev", e.id).putExtra("occ", o.start).putExtra("nid", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java).putExtra("tab", "cal").putExtra("day", o.start), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, if (e.level >= 1) "rem" else "rem_low")
            .setSmallIcon(Icon.createWithResource(c, R.drawable.ic_alarm))
            .setContentTitle(Rem.typeIcon(e.type) + " " + e.title)
            .setContentText(whenTxt + if (e.note.isNotBlank()) " · " + e.note else "")
            .setStyle(Notification.BigTextStyle().bigText(whenTxt + if (e.note.isNotBlank()) "\n" + e.note else ""))
            .setColor(color(e)).setColorized(e.level >= 3)
            .setCategory(Notification.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true)
        if (e.type == "reminder") {
            b.addAction(Notification.Action.Builder(null, "Zrobione", act("REMDONE", 1)).build())
            b.addAction(Notification.Action.Builder(null, "Za 10 min", act("REMSNOOZE", 2)).build())
            b.addAction(Notification.Action.Builder(null, "Za godzinę", act("REMSNOOZE60", 3)).build())
        }
        try { c.getSystemService(NotificationManager::class.java)?.notify(id, b.build()) } catch (x: SecurityException) {}
    }

    fun ageAt(e: Ev, occ: Long): Int {
        val y0 = Calendar.getInstance().apply { timeInMillis = e.start }.get(Calendar.YEAR)
        val y = Calendar.getInstance().apply { timeInMillis = occ }.get(Calendar.YEAR)
        return if (y0 < 1900) 0 else y - y0
    }

    fun done(c: Context, evId: Long, occ: Long) {
        val l = all(c)
        l.firstOrNull { it.id == evId }?.done?.add(occ)
        save(c, l)
    }

    /** Odłożenie: jednorazowe przypomnienie za X minut. */
    fun snooze(c: Context, evId: Long, minutes: Int) {
        val e = get(c, evId) ?: return
        put(c, Ev(0, e.title, e.note, "reminder", e.level, System.currentTimeMillis() + minutes * 60_000L, alerts = mutableListOf(0)))
    }

    // ------------------------------------------------------------ pora snu
    private fun bedPi(c: Context) = PendingIntent.getBroadcast(c, 800_001, Intent(c, AlarmReceiver::class.java).setAction("BEDTIME"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Kiedy iść spać przed najbliższym budzikiem (null, gdy brak budzika). */
    fun bedtime(c: Context): Long? {
        val n = Alarms.nextAny(c) ?: return null
        val sleep = Prefs.get(c).getInt("sleepMin", 480)
        return n.second - sleep * 60_000L
    }

    fun scheduleBedtime(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(bedPi(c))
        if (!Prefs.get(c).getBoolean("bedOn", true)) return
        val bt = bedtime(c) ?: return
        val t = bt - Prefs.get(c).getInt("bedBefore", 30) * 60_000L
        if (t > System.currentTimeMillis()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, bedPi(c))
    }

    fun bedNotify(c: Context) {
        val bt = bedtime(c) ?: return
        val n = Alarms.nextAny(c) ?: return
        channel(c, false)
        val df = SimpleDateFormat("H:mm", Locale("pl"))
        val h = Prefs.get(c).getInt("sleepMin", 480)
        try {
            c.getSystemService(NotificationManager::class.java)?.notify(8100, Notification.Builder(c, "rem_low").setSmallIcon(Icon.createWithResource(c, R.drawable.ic_alarm))
                .setContentTitle("🌙 Pora snu o ${df.format(Date(bt))}")
                .setContentText("Budzik o ${n.first.time()} – tak wyśpisz się ${h / 60} h${if (h % 60 > 0) " ${h % 60} min" else ""}.").setAutoCancel(true).build())
        } catch (e: SecurityException) {}
    }

    // ------------------------------------------------------------ kalendarz telefonu (Google) i urodziny z kontaktów
    fun system(c: Context, from: Long, to: Long): List<Occ> {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = ArrayList<Occ>()
        try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_LOCATION),
                null, null, CalendarContract.Instances.BEGIN)?.use { q ->
                while (q.moveToNext()) {
                    val allDay = q.getInt(4) == 1
                    // wydarzenia całodniowe są zapisane w UTC – przesuwamy na czas lokalny
                    val off = if (allDay) java.util.TimeZone.getDefault().getOffset(q.getLong(2)).toLong() else 0L
                    val e = Ev(-q.getLong(0), q.getString(1) ?: "(bez tytułu)", q.getString(6) ?: "", "event", 1, q.getLong(2) - off, q.getLong(3) - off - (if (allDay) 1 else 0), allDay,
                        external = true, color = q.getInt(5))
                    out.add(Occ(e, e.start, e.end))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Dodaje urodziny zapisane w kontaktach jako coroczne wpisy. Zwraca liczbę nowych. */
    fun importBirthdays(c: Context): Int {
        val l = all(c)
        var n = 0
        try {
            c.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Event.TYPE}=?",
                arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null)?.use { q ->
                while (q.moveToNext()) {
                    val name = q.getString(0) ?: continue
                    val d = q.getString(1) ?: continue
                    // formaty: 1985-03-14 albo --03-14
                    val m = Regex("""(\d{4}|-)-?(\d{2})-(\d{2})""").find(d) ?: continue
                    val year = m.groupValues[1].toIntOrNull() ?: 1800
                    val cal = Calendar.getInstance().apply { clear(); set(year, m.groupValues[2].toInt() - 1, m.groupValues[3].toInt()) }
                    if (l.any { it.type == "birthday" && it.title == name }) continue
                    l.add(Ev(System.currentTimeMillis() + n, name, "", "birthday", 2, cal.timeInMillis, cal.timeInMillis, true, "yearly", alerts = mutableListOf(0, 1440)))
                    n++
                }
            }
        } catch (e: Exception) {
        }
        if (n > 0) save(c, l)
        return n
    }
}
