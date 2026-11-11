package pl.swit

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Powiadomienia: dzwonienie, poranna prognoza, deszcz za chwilę, ostrzeżenia, temperatura na pasku, minutnik. */
object Notify {
    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)!!

    private fun channels(c: Context) {
        val m = nm(c)
        m.createNotificationChannel(NotificationChannel("ring", "Dzwoniący budzik", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
        m.createNotificationChannel(NotificationChannel("weather", "Prognoza i ostrzeżenia", NotificationManager.IMPORTANCE_DEFAULT))
        m.createNotificationChannel(NotificationChannel("rain", "Deszcz za chwilę", NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel("temp", "Temperatura na pasku", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        m.createNotificationChannel(NotificationChannel("timer", "Minutnik", NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel("info", "Budziki – informacje", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(c: Context, tab: String, code: Int) = PendingIntent.getActivity(c, code, Intent(c, MainActivity::class.java).putExtra("tab", tab),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun act(c: Context, action: String, id: Int, code: Int, kind: String = "alarm") = PendingIntent.getBroadcast(c, code,
        Intent(c, AlarmReceiver::class.java).setAction(action).putExtra("id", id).putExtra("kind", kind), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun post(c: Context, id: Int, b: Notification.Builder) = try { nm(c).notify(id, b.build()) } catch (e: SecurityException) {}

    // ------------------------------------------------------------ budzik
    fun ringing(c: Context, id: Int, kind: String, a: Alarm?): Notification {
        channels(c)
        val full = PendingIntent.getActivity(c, 6000 + id, Intent(c, AlarmActivity::class.java).putExtra("id", id).putExtra("kind", kind)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (kind) { "timer" -> "Minutnik – koniec"; "pre" -> "Za chwilę budzik ${a?.time() ?: ""}"; else -> a?.label?.ifBlank { null } ?: "Budzik ${a?.time() ?: ""}" }
        val b = Notification.Builder(c, "ring").setSmallIcon(Icon.createWithResource(c, R.drawable.ic_alarm)).setContentTitle(title)
            .setContentText(if (kind == "alarm" && a?.challenge != "none") "Wyłączenie wymaga zadania" else "Stuknij, żeby wyłączyć")
            .setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(full, true).setContentIntent(full).setOngoing(true)
        if (kind != "alarm" || a?.challenge == "none") b.addAction(Notification.Action.Builder(null, "Wyłącz", act(c, "DISMISS", id, 6100 + id, kind)).build())
        if (kind == "alarm") b.addAction(Notification.Action.Builder(null, "Drzemka ${a?.snooze ?: 9} min", act(c, "SNOOZE", id, 6200 + id)).build())
        return b.build()
    }

    fun missed(c: Context, a: Alarm?) {
        channels(c)
        post(c, 7100, Notification.Builder(c, "info").setSmallIcon(Icon.createWithResource(c, R.drawable.ic_alarm))
            .setContentTitle("Nieodebrany budzik ${a?.time() ?: ""}").setContentText("Dzwonił ${a?.autoStop ?: 10} min bez reakcji").setAutoCancel(true)
            .setContentIntent(open(c, "alarms", 7100)))
    }

    /** Po wyłączeniu: „Nie śpisz?” – bez reakcji w 2 minuty budzik dzwoni znowu. */
    fun wakeCheck(c: Context, id: Int) {
        channels(c)
        post(c, 9000 + id, Notification.Builder(c, "ring").setSmallIcon(Icon.createWithResource(c, R.drawable.ic_alarm))
            .setContentTitle("Nie śpisz?").setContentText("Stuknij „Nie śpię” w ciągu 2 minut, inaczej budzik zadzwoni znowu.")
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Nie śpię", act(c, "WAKEOK", id, 9100 + id)).build()))
        c.getSystemService(AlarmManager::class.java)?.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 2 * 60_000L, open(c, "alarms", 1)),
            PendingIntent.getBroadcast(c, 400_000 + id, Intent(c, AlarmReceiver::class.java).setAction("WAKEFAIL").putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    fun timer(c: Context, end: Long) {
        channels(c)
        post(c, 8000, Notification.Builder(c, "timer").setSmallIcon(Icon.createWithResource(c, R.drawable.ic_alarm)).setContentTitle("Minutnik")
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(end).setShowWhen(true).setOngoing(true)
            .setContentIntent(open(c, "timer", 8000))
            .addAction(Notification.Action.Builder(null, "Zatrzymaj", PendingIntent.getActivity(c, 8001, Intent(c, MainActivity::class.java).putExtra("tab", "timer").putExtra("stopTimer", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()))
    }

    // ------------------------------------------------------------ pogoda
    private fun p(c: Context) = Prefs.get(c)

    /** Planowanie: odświeżanie co 30 min i poranna prognoza o wybranej godzinie. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val tick = PendingIntent.getBroadcast(c, 700_001, Intent(c, AlarmReceiver::class.java).setAction("TICK"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 60_000L, 30 * 60_000L, tick)
    }

    /** Co 30 min: odśwież pogodę, deszcz za chwilę, ostrzeżenia, temperatura na pasku, poranna prognoza. */
    fun tick(c: Context) {
        val f = try { Weather.refresh(c) } catch (e: Exception) { Weather.cached(c) } ?: return
        val pr = p(c)
        if (pr.getBoolean("nRain", true)) {
            val nc = Weather.nowcast(f)
            val starts = nc.startsWith("Za ok.")
            val was = pr.getBoolean("rainWarned", false)
            if (starts && !was) {
                channels(c)
                post(c, 7300, Notification.Builder(c, "rain").setSmallIcon(Icon.createWithResource(c, R.drawable.ic_drop))
                    .setContentTitle(nc).setContentText(f.place.name).setAutoCancel(true).setTimeoutAfter(90 * 60_000L).setContentIntent(open(c, "weather", 7300)))
            }
            pr.edit().putBoolean("rainWarned", starts || nc.startsWith("Pada")).apply()
        }
        if (pr.getBoolean("nWarn", true)) {
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            for ((k, txt) in Weather.warnings(f)) {
                val key = "warn_${k}_$today"
                if (pr.getBoolean(key, false)) continue
                pr.edit().putBoolean(key, true).apply()
                channels(c)
                post(c, 7400 + k.hashCode() % 50, Notification.Builder(c, "weather").setSmallIcon(Icon.createWithResource(c, Weather.icon(f.code, f.day)))
                    .setContentTitle("⚠ $txt").setContentText(f.place.name).setAutoCancel(true).setContentIntent(open(c, "weather", 7400)))
            }
        }
        if (pr.getBoolean("nMorning", true)) {
            val cal = Calendar.getInstance()
            val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val at = pr.getInt("morningAt", 7 * 60)
            val day = cal.get(Calendar.DAY_OF_YEAR)
            if (mins in at..(at + 45) && pr.getInt("morningDay", -1) != day) morning(c)
        }
        if (pr.getBoolean("nEvening", false)) {
            val cal = Calendar.getInstance()
            val day = cal.get(Calendar.DAY_OF_YEAR)
            if (cal.get(Calendar.HOUR_OF_DAY) == 20 && pr.getInt("eveningDay", -1) != day) { pr.edit().putInt("eveningDay", day).apply(); tomorrow(c, f) }
        }
        statusTemp(c, f)
    }

    fun morning(c: Context) {
        val f = Weather.fresh(c, 30) ?: return
        p(c).edit().putInt("morningDay", Calendar.getInstance().get(Calendar.DAY_OF_YEAR)).apply()
        channels(c)
        val w = Weather.warnings(f).joinToString("\n") { "⚠ " + it.second }
        val txt = Weather.summary(f) + (Weather.nowcast(f).takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "") + if (w.isNotEmpty()) "\n$w" else ""
        post(c, 7200, Notification.Builder(c, "weather").setSmallIcon(Icon.createWithResource(c, Weather.icon(f.code, f.day)))
            .setContentTitle("Dzień dobry · ${f.place.name}").setContentText(Weather.summary(f)).setStyle(Notification.BigTextStyle().bigText(txt))
            .setAutoCancel(true).setContentIntent(open(c, "weather", 7200)))
    }

    private fun tomorrow(c: Context, f: Forecast) {
        val d = f.days.getOrNull(1) ?: return
        channels(c)
        post(c, 7250, Notification.Builder(c, "weather").setSmallIcon(Icon.createWithResource(c, Weather.icon(d.code)))
            .setContentTitle("Jutro: ${Weather.text(d.code).lowercase()}, ${Weather.deg(d.min)} – ${Weather.deg(d.max)}")
            .setContentText(if (d.pop >= 30) "Opady ${d.pop}% · ${"%.1f".format(d.rain)} mm" else "Bez większych opadów").setAutoCancel(true).setContentIntent(open(c, "weather", 7250)))
    }

    /** Stałe powiadomienie z temperaturą jako ikoną na pasku stanu. */
    fun statusTemp(c: Context, f: Forecast?) {
        if (!p(c).getBoolean("nTemp", false) || f == null) { nm(c).cancel(7500); return }
        channels(c)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val txt = f.temp.roundToInt().toString() + "°"
        val pnt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = if (txt.length > 3) 52f else 64f }
        Canvas(bmp).drawText(txt, 48f, 48f - (pnt.descent() + pnt.ascent()) / 2, pnt)
        post(c, 7500, Notification.Builder(c, "temp").setSmallIcon(Icon.createWithBitmap(bmp)).setOngoing(true).setShowWhen(false)
            .setContentTitle("${Weather.deg(f.temp)} · ${Weather.text(f.code)}").setContentText("${f.place.name} · odczuwalna ${Weather.deg(f.feels)}")
            .setContentIntent(open(c, "weather", 7500)))
    }
}

/** Ekran dzwoniącego budzika (także na zablokowanym telefonie) z zadaniami do wyłączenia. */
class AlarmActivity : Activity(), SensorEventListener {
    companion object { const val CLOSE = "pl.swit.CLOSE_ALARM" }

    private lateinit var root: LinearLayout
    private var id = 0
    private var kind = "alarm"
    private var alarm: Alarm? = null
    private var solved = 0
    private var shakes = 0
    private var lastShake = 0L
    private var sm: SensorManager? = null

    private val closeRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { finish() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, s: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = t; textSize = s; setTextColor(color); gravity = Gravity.CENTER; if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun btn(t: String, primary: Boolean, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 22f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply { setColor(if (primary) Color.WHITE else Color.BLACK); setStroke(dp(2), Color.WHITE); cornerRadius = dp(40).toFloat() }
        setPadding(dp(20), dp(22), dp(20), dp(22)); setOnClickListener { f() }
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        id = intent.getIntExtra("id", 0); kind = intent.getStringExtra("kind") ?: "alarm"
        alarm = if (kind == "timer") null else Alarms.get(this, id)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(28), dp(60), dp(28), dp(40)); gravity = Gravity.CENTER_HORIZONTAL }
        setContentView(root)
        registerReceiver(closeRx, IntentFilter(CLOSE), RECEIVER_NOT_EXPORTED)
        main()
    }

    override fun onDestroy() {
        try { unregisterReceiver(closeRx) } catch (e: Exception) {}
        sm?.unregisterListener(this)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* nie pozwalamy uciec przyciskiem wstecz */ }

    private fun main() {
        root.removeAllViews()
        val cal = Calendar.getInstance()
        root.addView(tv("%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), 86f, true))
        root.addView(tv(when (kind) { "timer" -> "Minutnik – koniec"; "pre" -> "Za ${alarm?.pre ?: 0} min budzik ${alarm?.time() ?: ""}"; else -> alarm?.label?.ifBlank { null } ?: "Budzik" }, 22f), lp(6))
        Weather.cached(this)?.let { f ->
            root.addView(tv("${Weather.deg(f.temp)} · ${Weather.text(f.code)} · ${f.place.name}", 17f, color = 0xFFBBBBBB.toInt()), lp(14))
            f.days.firstOrNull()?.let { d -> root.addView(tv("Dziś ${Weather.deg(d.min)} – ${Weather.deg(d.max)}" + if (d.pop >= 30) " · opady ${d.pop}%" else "", 15f, color = 0xFF999999.toInt()), lp(2)) }
        }
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val r = Ring.active
        if (kind == "alarm" && (r?.snoozesLeft() ?: 1) > 0) root.addView(btn("Drzemka ${alarm?.snooze ?: 9} min" + (r?.let { " (zostało ${it.snoozesLeft()})" } ?: ""), true) {
            Ring.active?.doSnooze() ?: Alarms.snooze(this, id, alarm?.snooze ?: 9); finish()
        }, lp(0))
        root.addView(btn(if (kind == "alarm" && alarm?.challenge != "none") "Wyłącz (zadanie)" else "Wyłącz", kind != "alarm") {
            if (kind == "alarm" && alarm?.challenge != "none") challenge() else dismiss()
        }, lp(14))
    }

    private fun dismiss() {
        Ring.active?.doDismiss() ?: run { if (kind == "alarm") Alarms.dismissed(this, id); stopService(Intent(this, Ring::class.java)) }
        finish()
    }

    // ------------------------------------------------------------ zadania
    private fun challenge() {
        when (alarm?.challenge) {
            "math" -> math()
            "shake" -> shake()
            "captcha" -> captcha()
            else -> dismiss()
        }
    }

    private fun backRow() = btn("‹ Wróć", false) { sm?.unregisterListener(this); main() }

    private fun math() {
        root.removeAllViews()
        val lv = alarm?.level ?: 1
        val (q, ans) = when (lv) {
            1 -> { val a = Random.nextInt(11, 60); val b = Random.nextInt(11, 40); "$a + $b" to a + b }
            2 -> { val a = Random.nextInt(6, 15); val b = Random.nextInt(6, 13); val c = Random.nextInt(10, 90); "$a × $b + $c" to a * b + c }
            else -> { val a = Random.nextInt(12, 30); val b = Random.nextInt(12, 25); val c = Random.nextInt(100, 400); "$a × $b − $c" to a * b - c }
        }
        val need = alarm?.count ?: 3
        root.addView(tv("Zadanie ${solved + 1} z $need", 18f, color = 0xFFAAAAAA.toInt()))
        root.addView(tv("$q = ?", 48f, true), lp(30))
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setStroke(dp(2), Color.WHITE); cornerRadius = dp(16).toFloat() }; setPadding(dp(10), dp(14), dp(10), dp(14))
        }
        root.addView(e, lp(24))
        root.addView(btn("Sprawdź", true) {
            if (e.text.toString().trim().toIntOrNull() == ans) { solved++; if (solved >= need) dismiss() else math() }
            else { e.setText(""); e.hint = "Źle – spróbuj jeszcze raz"; e.setHintTextColor(0xFFFF6666.toInt()) }
        }, lp(20))
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(backRow())
        e.requestFocus()
    }

    private lateinit var shakeLabel: TextView

    private fun shake() {
        root.removeAllViews()
        val need = (alarm?.count ?: 3) * 10
        shakes = 0
        root.addView(tv("Potrząśnij telefonem", 28f, true), lp(20))
        shakeLabel = tv("0 / $need", 56f, true)
        root.addView(shakeLabel, lp(30))
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(backRow())
        sm = getSystemService(SensorManager::class.java)
        sm?.registerListener(this, sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (g > 2.2f && now - lastShake > 250) {
            lastShake = now; shakes++
            val need = (alarm?.count ?: 3) * 10
            shakeLabel.text = "$shakes / $need"
            if (shakes >= need) { sm?.unregisterListener(this); dismiss() }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun captcha() {
        root.removeAllViews()
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val len = 4 + (alarm?.level ?: 1) * 2
        val code = (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        val need = alarm?.count ?: 3
        root.addView(tv("Przepisz dokładnie (${solved + 1} z $need)", 18f, color = 0xFFAAAAAA.toInt()))
        root.addView(tv(code, 40f, true).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.15f }, lp(30))
        val e = EditText(this).apply {
            textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            background = GradientDrawable().apply { setStroke(dp(2), Color.WHITE); cornerRadius = dp(16).toFloat() }; setPadding(dp(10), dp(14), dp(10), dp(14))
        }
        root.addView(e, lp(24))
        root.addView(btn("Sprawdź", true) {
            if (e.text.toString().trim() == code) { solved++; if (solved >= need) dismiss() else captcha() }
            else { e.setText(""); e.hint = "Źle – wielkość liter ma znaczenie"; e.setHintTextColor(0xFFFF6666.toInt()) }
        }, lp(20))
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(backRow())
        e.requestFocus()
    }
}
