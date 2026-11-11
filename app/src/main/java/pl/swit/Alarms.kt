package pl.swit

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

object Prefs {
    fun get(c: Context): SharedPreferences = c.getSharedPreferences("swit", 0)
}

/** Budzik. days: bity 0..6 = pon..niedz; 0 = jednorazowy. */
class Alarm(
    var id: Int, var hour: Int, var min: Int, var days: Int = 0, var on: Boolean = true, var label: String = "",
    var sound: String = "", var vibrate: Boolean = true, var ramp: Int = 30, var snooze: Int = 9, var maxSnooze: Int = 3,
    var challenge: String = "none", var level: Int = 1, var count: Int = 3, var autoStop: Int = 10, var pre: Int = 0,
    var speak: Boolean = true, var holidays: Boolean = false, var skipUntil: Long = 0, var wakeCheck: Boolean = false, var volume: Int = 100
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("h", hour).put("m", min).put("d", days).put("on", on).put("l", label).put("s", sound)
        .put("v", vibrate).put("r", ramp).put("sn", snooze).put("ms", maxSnooze).put("ch", challenge).put("lv", level).put("cn", count)
        .put("as", autoStop).put("pre", pre).put("sp", speak).put("hol", holidays).put("sk", skipUntil).put("wc", wakeCheck).put("vol", volume)

    companion object {
        fun of(o: JSONObject) = Alarm(o.optInt("id"), o.optInt("h"), o.optInt("m"), o.optInt("d"), o.optBoolean("on", true), o.optString("l"), o.optString("s"),
            o.optBoolean("v", true), o.optInt("r", 30), o.optInt("sn", 9), o.optInt("ms", 3), o.optString("ch", "none"), o.optInt("lv", 1), o.optInt("cn", 3),
            o.optInt("as", 10), o.optInt("pre"), o.optBoolean("sp", true), o.optBoolean("hol"), o.optLong("sk"), o.optBoolean("wc"), o.optInt("vol", 100))
    }

    fun time() = "%d:%02d".format(hour, min)

    fun daysText(): String {
        if (days == 0) return "jednorazowo"
        if (days == 0x7f) return "codziennie"
        if (days == 0x1f) return "pon–pt"
        if (days == 0x60) return "weekend"
        val n = listOf("pn", "wt", "śr", "cz", "pt", "sb", "nd")
        return (0..6).filter { days and (1 shl it) != 0 }.joinToString(" ") { n[it] }
    }
}

object Alarms {
    fun all(c: Context): MutableList<Alarm> {
        val a = try { JSONArray(Prefs.get(c).getString("alarms", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { Alarm.of(a.getJSONObject(it)) }.sortedBy { it.hour * 60 + it.min }.toMutableList()
    }

    fun save(c: Context, l: List<Alarm>) {
        Prefs.get(c).edit().putString("alarms", JSONArray(l.map { it.json() }).toString()).apply()
        scheduleAll(c)
        Widgets.updateAll(c)
    }

    fun put(c: Context, a: Alarm) {
        val l = all(c).filter { it.id != a.id }.toMutableList()
        if (a.id == 0) a.id = (l.maxOfOrNull { it.id } ?: 0) + 1
        l.add(a); save(c, l)
    }

    fun remove(c: Context, id: Int) { cancel(c, id); save(c, all(c).filter { it.id != id }) }

    fun get(c: Context, id: Int) = all(c).firstOrNull { it.id == id }

    // ------------------------------------------------------------ święta w Polsce (dni wolne)
    private fun easter(y: Int): Calendar {
        val a = y % 19; val b = y / 100; val cc = y % 100; val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = cc / 4; val k = cc % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = ((h + l - 7 * m + 114) % 31) + 1
        return Calendar.getInstance().apply { clear(); set(y, month - 1, day) }
    }

    fun holiday(cal: Calendar): String? {
        val d = cal.get(Calendar.DAY_OF_MONTH); val m = cal.get(Calendar.MONTH) + 1; val y = cal.get(Calendar.YEAR)
        val fixed = mapOf("1.1" to "Nowy Rok", "6.1" to "Trzech Króli", "1.5" to "Święto Pracy", "3.5" to "Święto Konstytucji", "15.8" to "Wniebowzięcie",
            "1.11" to "Wszystkich Świętych", "11.11" to "Święto Niepodległości", "24.12" to "Wigilia", "25.12" to "Boże Narodzenie", "26.12" to "Drugi dzień świąt")
        fixed["$d.$m"]?.let { return it }
        val e = easter(y)
        fun plus(n: Int) = (e.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, n) }
        for ((off, name) in listOf(0 to "Wielkanoc", 1 to "Poniedziałek Wielkanocny", 49 to "Zielone Świątki", 60 to "Boże Ciało")) {
            val x = plus(off)
            if (x.get(Calendar.DAY_OF_MONTH) == d && x.get(Calendar.MONTH) + 1 == m) return name
        }
        return null
    }

    // ------------------------------------------------------------ następne dzwonienie
    fun next(a: Alarm, from: Long = System.currentTimeMillis()): Long {
        if (!a.on) return -1
        val c = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        if (a.days == 0) return c.timeInMillis
        repeat(400) {
            val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7  // pon=0
            val ok = a.days and (1 shl dow) != 0 && c.timeInMillis > a.skipUntil && !(a.holidays && holiday(c) != null)
            if (ok) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return -1
    }

    fun nextAny(c: Context): Pair<Alarm, Long>? = all(c).mapNotNull { a -> next(a).takeIf { it > 0 }?.let { a to it } }.minByOrNull { it.second }

    private fun pi(c: Context, action: String, id: Int, code: Int) = PendingIntent.getBroadcast(c, code,
        Intent(c, AlarmReceiver::class.java).setAction(action).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun show(c: Context) = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java).putExtra("tab", "alarms"), PendingIntent.FLAG_IMMUTABLE)

    fun cancel(c: Context, id: Int) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(c, "FIRE", id, id)); am.cancel(pi(c, "PRE", id, 100_000 + id))
    }

    fun scheduleAll(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        for (a in all(c)) {
            cancel(c, a.id)
            val t = next(a)
            if (t <= 0) continue
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show(c)), pi(c, "FIRE", a.id, a.id))
            if (a.pre > 0 && t - a.pre * 60_000L > System.currentTimeMillis())
                am.setAlarmClock(AlarmManager.AlarmClockInfo(t - a.pre * 60_000L, show(c)), pi(c, "PRE", a.id, 100_000 + a.id))
        }
    }

    fun snooze(c: Context, id: Int, minutes: Int) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + minutes * 60_000L, show(c)), pi(c, "FIRE", id, 200_000 + id))
    }

    fun cancelSnooze(c: Context, id: Int) { c.getSystemService(AlarmManager::class.java)?.cancel(pi(c, "FIRE", id, 200_000 + id)) }

    /** Szybka drzemka (jednorazowy budzik za X minut). */
    fun nap(c: Context, minutes: Int) {
        val t = System.currentTimeMillis() + minutes * 60_000L
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        put(c, Alarm(0, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), 0, true, "Drzemka $minutes min", challenge = "none", pre = 0, speak = false))
    }

    /** Po wyłączeniu: jednorazowy – wyłącz, powtarzalny – zaplanuj następny; ewentualnie sprawdź, czy nie śpisz. */
    fun dismissed(c: Context, id: Int) {
        cancelSnooze(c, id)
        Prefs.get(c).edit().putInt("snoozes_$id", 0).apply()
        val a = get(c, id)
        if (a != null) {
            if (a.days == 0) a.on = false
            a.skipUntil = 0
            put(c, a)
            if (a.wakeCheck) {
                val am = c.getSystemService(AlarmManager::class.java)
                am?.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 3 * 60_000L, show(c)), pi(c, "CHECK", id, 300_000 + id))
            }
        } else scheduleAll(c)
        if (Prefs.get(c).getBoolean("briefAfterAlarm", true)) Thread { try { Notify.morning(c) } catch (e: Exception) {} }.start()
    }

    fun wakeOk(c: Context, id: Int) {
        c.getSystemService(AlarmManager::class.java)?.cancel(pi(c, "WAKEFAIL", id, 400_000 + id))
        c.getSystemService(NotificationManager::class.java)?.cancel(9000 + id)
    }

    // ------------------------------------------------------------ minutnik
    fun startTimer(c: Context, seconds: Int) {
        val end = System.currentTimeMillis() + seconds * 1000L
        Prefs.get(c).edit().putLong("timerEnd", end).apply()
        c.getSystemService(AlarmManager::class.java)?.setAlarmClock(AlarmManager.AlarmClockInfo(end, show(c)), pi(c, "TIMER", 0, 500_000))
        Notify.timer(c, end)
    }

    fun stopTimer(c: Context) {
        Prefs.get(c).edit().remove("timerEnd").apply()
        c.getSystemService(AlarmManager::class.java)?.cancel(pi(c, "TIMER", 0, 500_000))
        c.getSystemService(NotificationManager::class.java)?.cancel(8000)
    }
}

/** Zdarzenia budzika: dzwonienie, przedbudzik, sprawdzenie czy nie śpisz, minutnik, restart telefonu. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Alarms.scheduleAll(c); Notify.schedule(c); Widgets.updateAll(c)
            }
            "FIRE" -> Ring.start(c, id, "alarm")
            "PRE" -> Ring.start(c, id, "pre")
            "TIMER" -> { Prefs.get(c).edit().remove("timerEnd").apply(); Ring.start(c, 0, "timer") }
            "CHECK" -> Notify.wakeCheck(c, id)
            "WAKEOK" -> Alarms.wakeOk(c, id)
            "WAKEFAIL" -> Ring.start(c, id, "alarm")
            "DISMISS" -> { c.stopService(Intent(c, Ring::class.java)); if (i.getStringExtra("kind") == "alarm") Alarms.dismissed(c, id) }
            "SNOOZE" -> { c.stopService(Intent(c, Ring::class.java)); val a = Alarms.get(c, id); Alarms.snooze(c, id, a?.snooze ?: 9) }
            "TICK" -> { val pr = goAsync(); Thread { try { Notify.tick(c); Widgets.updateAll(c) } catch (e: Exception) {}; pr.finish() }.start() }
            "NAP" -> { Alarms.nap(c, i.getIntExtra("min", 20)); android.widget.Toast.makeText(c, "Drzemka: budzik za ${i.getIntExtra("min", 20)} min", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
}

/** Dzwonienie: dźwięk alarmu z narastającą głośnością, wibracje, odczyt godziny i pogody, ekran na blokadzie. */
class Ring : Service(), TextToSpeech.OnInitListener {
    companion object {
        @Volatile var active: Ring? = null

        fun start(c: Context, id: Int, kind: String) {
            val i = Intent(c, Ring::class.java).putExtra("id", id).putExtra("kind", kind)
            try { c.startForegroundService(i) } catch (e: Exception) {}
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var wl: PowerManager.WakeLock? = null
    var id = 0
    var kind = "alarm"
    var alarm: Alarm? = null
    private var startAt = 0L
    private var savedVol = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        id = intent?.getIntExtra("id", 0) ?: 0
        kind = intent?.getStringExtra("kind") ?: "alarm"
        alarm = if (kind == "timer") null else Alarms.get(this, id)
        startAt = System.currentTimeMillis()
        active = this
        wl = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swit:ring")?.apply { acquire(20 * 60_000L) }
        val n = Notify.ringing(this, id, kind, alarm)
        if (Build.VERSION.SDK_INT >= 29) startForeground(7000, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(7000, n)
        stopSound()
        play()
        if (kind == "alarm") {
            val a = alarm
            if (a?.speak == true) tts = TextToSpeech(this, this)
            val auto = (a?.autoStop ?: 10).coerceAtLeast(1)
            h.postDelayed({ autoStop() }, auto * 60_000L)
        } else h.postDelayed({ stopSelf() }, (if (kind == "pre") 2 else 5) * 60_000L)
        try { startActivity(Intent(this, AlarmActivity::class.java).putExtra("id", id).putExtra("kind", kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)) } catch (e: Exception) {}
        return START_NOT_STICKY
    }

    private fun play() {
        val a = alarm
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = if (kind == "pre") (max * 0.35).toInt().coerceAtLeast(1) else (max * (a?.volume ?: 100) / 100).coerceAtLeast(1)
        savedVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        val uri = a?.sound?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        mp = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@Ring, uri)
                isLooping = true
                prepare()
                setVolume(0f, 0f)
                start()
            }
        } catch (e: Exception) {
            try { MediaPlayer.create(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply { isLooping = true; start() } } catch (x: Exception) { null }
        }
        // narastająca głośność
        val ramp = if (kind == "pre") 60 else (a?.ramp ?: 30)
        if (ramp <= 0) mp?.setVolume(1f, 1f) else {
            val steps = 30
            for (k in 1..steps) h.postDelayed({ val v = k / steps.toFloat(); try { mp?.setVolume(v, v) } catch (e: Exception) {} }, ramp * 1000L * k / steps)
        }
        if (kind != "pre" && (a?.vibrate != false)) {
            val vib = getSystemService(Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        t.language = Locale("pl", "PL")
        h.postDelayed({
            val cal = Calendar.getInstance()
            val sb = StringBuilder("Dzień dobry. Jest godzina ${cal.get(Calendar.HOUR_OF_DAY)} ${"%02d".format(cal.get(Calendar.MINUTE))}. ")
            Weather.cached(this)?.let { sb.append(Weather.speech(it)) }
            try { mp?.setVolume(0.15f, 0.15f) } catch (e: Exception) {}
            t.speak(sb.toString(), TextToSpeech.QUEUE_FLUSH, null, "swit")
            h.postDelayed({ try { mp?.setVolume(1f, 1f) } catch (e: Exception) {} }, 14_000)
        }, 12_000)
    }

    /** Po X minutach bez reakcji: drzemka (jeśli wolno) albo wyciszenie. */
    private fun autoStop() {
        val a = alarm
        val n = Prefs.get(this).getInt("snoozes_$id", 0)
        if (a != null && n < a.maxSnooze) { Prefs.get(this).edit().putInt("snoozes_$id", n + 1).apply(); Alarms.snooze(this, id, a.snooze) }
        Notify.missed(this, a)
        stopSelf()
    }

    fun snoozesLeft(): Int { val a = alarm ?: return 0; return a.maxSnooze - Prefs.get(this).getInt("snoozes_$id", 0) }

    fun doSnooze() {
        val a = alarm ?: return
        val n = Prefs.get(this).getInt("snoozes_$id", 0)
        Prefs.get(this).edit().putInt("snoozes_$id", n + 1).apply()
        Alarms.snooze(this, id, a.snooze)
        stopSelf()
    }

    fun doDismiss() {
        if (kind == "alarm") Alarms.dismissed(this, id)
        stopSelf()
    }

    private fun stopSound() {
        try { mp?.stop(); mp?.release() } catch (e: Exception) {}
        mp = null
        getSystemService(Vibrator::class.java)?.cancel()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        stopSound()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        if (savedVol >= 0) try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0) } catch (e: Exception) {}
        try { wl?.release() } catch (e: Exception) {}
        active = null
        sendBroadcast(Intent(AlarmActivity.CLOSE).setPackage(packageName))
        Widgets.updateAll(this)
        super.onDestroy()
    }
}
