package pl.swit

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var sv: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var tabs: LinearLayout
    private var tab = "weather"
    private var forecast: Forecast? = null
    private var loading = false
    private var err = ""

    private val FG = Color.WHITE
    private val DIM = 0xFF9A9A9E.toInt()
    private val SURF = 0xFF1A1A1A.toInt()
    private val LINE = 0xFF2C2C2E.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, size: Float = 15f, bold: Boolean = false, color: Int = FG) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun lp(w: Int = -1, hh: Int = -2, weight: Float = 0f, top: Int = 0) = LinearLayout.LayoutParams(w, hh, weight).apply { topMargin = dp(top) }
    private fun round(c: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat(); if (stroke > 0) setStroke(dp(stroke), LINE) }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun btn(t: String, primary: Boolean = false, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER
        setTextColor(if (primary) Color.BLACK else FG); background = if (primary) round(FG, 24) else round(SURF, 24, 1)
        setPadding(dp(18), dp(11), dp(18), dp(11)); setOnClickListener { f() }
    }
    private fun header(t: String) = col.addView(tv(t.uppercase(), 13f, true, DIM).apply { letterSpacing = 0.08f }, lp(top = 24))
    private fun line() = View(this).apply { setBackgroundColor(LINE) }
    private fun icon(res: Int, size: Int) = ImageView(this).apply { setImageResource(res) }.also { it.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun pr() = Prefs.get(this)

    private fun toggle(title: String, sub: String, on: Boolean, f: (Boolean) -> Unit) {
        val r = hbox().apply { setPadding(0, dp(12), 0, dp(12)) }
        val l = vbox()
        l.addView(tv(title, 16f)); if (sub.isNotEmpty()) l.addView(tv(sub, 12f, color = DIM))
        r.addView(l, lp(0, -2, 1f))
        val sw = Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, b -> f(b) } }
        r.addView(sw); r.setOnClickListener { sw.isChecked = !sw.isChecked }
        col.addView(r)
    }

    private fun sheet(title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val c = vbox().apply { setPadding(dp(20), dp(18), dp(20), dp(18)); background = round(SURF, 22) }
        c.addView(tv(title, 19f, true))
        build(c, d)
        d.setContentView(ScrollView(this).apply { addView(c) })
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        return d
    }

    // ------------------------------------------------------------ start
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // zapis każdej awarii – przy następnym uruchomieniu pokazujemy, co się stało
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e)) } catch (x: Exception) {}
            prev?.uncaughtException(t, e)
        }
        val root = vbox().apply { setBackgroundColor(Color.BLACK) }
        sv = ScrollView(this)
        col = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(40)) }
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        tabs = hbox().apply { setBackgroundColor(SURF); setPadding(dp(6), dp(6), dp(6), dp(10)) }
        root.addView(tabs)
        setContentView(root)
        intent?.getStringExtra("tab")?.let { tab = it }
        if (intent?.getBooleanExtra("stopTimer", false) == true) Alarms.stopTimer(this)
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.ACCESS_COARSE_LOCATION
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        Notify.schedule(this)
        Alarms.scheduleAll(this)
        forecast = try { Weather.cached(this) } catch (e: Exception) { null }
        load(false)
        render()
        val cf = File(filesDir, "crash.txt")
        if (cf.exists()) {
            val txt = cf.readText(); cf.delete()
            sheet("Ostatnio aplikacja się zamknęła") { c, _ ->
                c.addView(tv("Zrób zrzut ekranu albo skopiuj i wyślij – naprawię przyczynę.", 13f, color = DIM), lp(top = 6))
                c.addView(tv(txt.take(1500), 11f).apply { setTextIsSelectable(true) }, lp(top = 8))
                c.addView(btn("Kopiuj", true) {
                    getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("błąd", txt)); toast("Skopiowano")
                }, lp(top = 10))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("tab")?.let { tab = it }
        if (intent.getBooleanExtra("stopTimer", false)) Alarms.stopTimer(this)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        load(true)
    }

    private fun load(force: Boolean) {
        if (loading) return
        loading = true; err = ""
        Thread {
            val f = try { if (force) Weather.refresh(this) else Weather.fresh(this) } catch (e: Exception) { err = e.message ?: "brak połączenia"; Weather.cached(this) }
            h.post {
                loading = false; forecast = f
                try { Widgets.updateAll(this); Notify.statusTemp(this, f) } catch (e: Exception) {}
                if (tab == "weather") render()
            }
        }.start()
    }

    private fun drawTabs() {
        tabs.removeAllViews()
        for ((k, n) in listOf("weather" to "Pogoda", "alarms" to "Budziki", "timer" to "Minutnik", "settings" to "Ustawienia")) {
            val on = k == tab
            tabs.addView(tv(n, 13f, on, if (on) Color.BLACK else DIM).apply {
                gravity = Gravity.CENTER; background = if (on) round(FG, 18) else null; setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { tab = k; render(); sv.scrollTo(0, 0) }
            }, lp(0, -2, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
    }

    private fun render() {
        h.removeCallbacks(tick)
        col.removeAllViews()
        drawTabs()
        try {
            when (tab) {
                "alarms" -> renderAlarms()
                "timer" -> renderTimer()
                "settings" -> renderSettings()
                else -> renderWeather()
            }
        } catch (e: Exception) {
            col.addView(tv("Coś poszło nie tak przy wyświetlaniu tej karty:", 15f, true), lp(top = 16))
            col.addView(tv(android.util.Log.getStackTraceString(e).take(1200), 11f, color = DIM).apply { setTextIsSelectable(true) }, lp(top = 6))
        }
    }

    // ================================================================== POGODA
    private fun renderWeather() {
        val places = Weather.places(this)
        val cur = Weather.current(this)
        val top = hbox()
        top.addView(tv("📍 " + (forecast?.place?.name?.takeIf { cur.gps } ?: cur.name), 17f, true).apply { setOnClickListener { placesSheet() } }, lp(0, -2, 1f))
        top.addView(btn(if (loading) "…" else "↻") { load(true) })
        col.addView(top)
        val f = forecast
        if (f == null) {
            col.addView(tv(if (loading) "Pobieram prognozę…" else "Brak prognozy. $err", 15f, color = DIM), lp(top = 30))
            return
        }
        val df = SimpleDateFormat("HH:mm", Locale("pl"))
        col.addView(tv("Aktualizacja ${df.format(Date(f.at))}" + if (err.isNotEmpty()) " · offline" else "", 11f, color = DIM))
        // teraz
        val nowR = hbox().apply { setPadding(0, dp(14), 0, 0) }
        nowR.addView(icon(Weather.icon(f.code, f.day), 84))
        val nl = vbox()
        nl.addView(tv(Weather.deg(f.temp), 64f, true))
        nl.addView(tv(Weather.text(f.code), 17f))
        nl.addView(tv("Odczuwalna ${Weather.deg(f.feels)}", 13f, color = DIM))
        nowR.addView(nl, lp(0, -2, 1f).apply { leftMargin = dp(16) })
        col.addView(nowR)
        val nc = Weather.nowcast(f)
        if (nc.isNotEmpty()) col.addView(tv("☂ $nc", 15f, true), lp(top = 10))
        for ((_, w) in Weather.warnings(f)) col.addView(tv("⚠ $w", 14f, true, 0xFFFFB020.toInt()), lp(top = 6))

        // opady w najbliższych 3 godzinach (co 15 min)
        val q = f.q15.filter { it.first >= System.currentTimeMillis() - 15 * 60_000L }.take(12)
        if (q.any { it.second > 0 }) {
            header("Opady – najbliższe 3 godziny")
            val bars = hbox().apply { gravity = Gravity.BOTTOM }
            val mx = q.maxOf { it.second }.coerceAtLeast(0.5)
            for ((_, mm) in q) bars.addView(View(this).apply { background = round(FG, 3) }, LinearLayout.LayoutParams(0, dp((4 + 56 * mm / mx).toInt()), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
            col.addView(bars, lp(-1, dp(64), top = 6))
            val lbl = hbox()
            lbl.addView(tv("teraz", 11f, color = DIM), lp(0, -2, 1f)); lbl.addView(tv("+3 h", 11f, color = DIM))
            col.addView(lbl)
        }

        // godzinowa
        header("Godzina po godzinie")
        val hr = hbox()
        val hf = SimpleDateFormat("H:mm", Locale("pl"))
        for (x in f.hours.take(48)) {
            val c = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            c.addView(tv(hf.format(Date(x.t)), 12f, color = DIM))
            c.addView(icon(Weather.icon(x.code, x.day), 28), LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(4) })
            c.addView(tv(Weather.deg(x.temp), 15f, true))
            c.addView(tv(if (x.pop >= 10) "${x.pop}%" else " ", 11f, color = DIM))
            c.addView(tv("${x.wind.ri()} km/h", 10f, color = DIM))
            hr.addView(c)
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(hr) }, lp(top = 4))

        // 16 dni
        header("Prognoza na ${f.days.size} dni")
        val dn = SimpleDateFormat("EEE d.MM", Locale("pl"))
        val okDays = f.days.filter { !it.min.isNaN() && !it.max.isNaN() }
        val gMin = okDays.minOfOrNull { it.min } ?: 0.0; val gMax = okDays.maxOfOrNull { it.max } ?: 1.0
        for ((i, d) in okDays.withIndex()) {
            val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            r.addView(tv(if (i == 0) "Dziś" else if (i == 1) "Jutro" else dn.format(Date(d.t)), 14f), lp(dp(78), -2))
            r.addView(icon(Weather.icon(d.code), 26))
            r.addView(tv(if (d.pop >= 20) "${d.pop}%" else "", 11f, color = DIM).apply { gravity = Gravity.CENTER }, lp(dp(36), -2))
            r.addView(tv(Weather.deg(d.min), 14f, color = DIM).apply { gravity = Gravity.END }, lp(dp(36), -2))
            // pasek zakresu temperatur
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(LINE, 3) }
            val span = (gMax - gMin).coerceAtLeast(1.0)
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(5), (d.min - gMin).toFloat().coerceAtLeast(0.01f)))
            bar.addView(View(this).apply { background = round(FG, 3) }, LinearLayout.LayoutParams(0, dp(5), (d.max - d.min).toFloat().coerceAtLeast(0.3f)))
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(5), (gMax - d.max).toFloat().coerceAtLeast(0.01f)))
            r.addView(bar, lp(0, dp(5), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
            r.addView(tv(Weather.deg(d.max), 14f, true), lp(dp(36), -2))
            r.setOnClickListener { daySheet(d) }
            col.addView(r)
            if (span < 0) break
        }

        // szczegóły
        header("Szczegóły")
        val d0 = f.days.firstOrNull()
        val sun = SimpleDateFormat("H:mm", Locale("pl"))
        val grid = listOf(
            "Wiatr" to "${f.wind.ri()} km/h ${Weather.windDir(f.windDir)}", "Porywy" to "${f.gusts.ri()} km/h",
            "Wilgotność" to "${f.humidity}%", "Ciśnienie" to "${f.pressure.ri()} hPa",
            "Zachmurzenie" to "${f.clouds}%", "Indeks UV" to (d0?.uv?.ri()?.toString() ?: "—"),
            "Wschód" to (d0?.let { sun.format(Date(it.sunrise)) } ?: "—"), "Zachód" to (d0?.let { sun.format(Date(it.sunset)) } ?: "—"))
        for (pair in grid.chunked(2)) {
            val r = hbox()
            for ((k, v) in pair) {
                val b = vbox().apply { background = round(SURF, 16); setPadding(dp(14), dp(10), dp(14), dp(10)) }
                b.addView(tv(k, 12f, color = DIM)); b.addView(tv(v, 17f, true))
                r.addView(b, lp(0, -2, 1f).apply { rightMargin = dp(8) })
            }
            col.addView(r, lp(top = 8))
        }

        header("Powietrze i pyłki")
        col.addView(tv("Jakość powietrza: ${Weather.aqiText(f.aqi)}" + (if (f.aqi >= 0) " (indeks ${f.aqi})" else ""), 15f, true), lp(top = 4))
        if (f.pm25 >= 0) col.addView(tv("PM2,5: ${f.pm25.ri()} µg/m³ · PM10: ${f.pm10.ri()} µg/m³", 13f, color = DIM))
        for ((n, v) in f.pollen) col.addView(tv("$n: ${Weather.pollenText(v)}", 14f), lp(top = 2))

        header("Radar opadów")
        col.addView(btn("Otwórz radar") { radar(f) }, lp(-2, -2, top = 6))
        col.addView(tv("Dane: Open-Meteo, radar: RainViewer – bez kont i kluczy.", 11f, color = DIM), lp(top = 16))
    }

    private fun daySheet(d: Day) {
        val dn = SimpleDateFormat("EEEE, d MMMM", Locale("pl"))
        val sun = SimpleDateFormat("H:mm", Locale("pl"))
        sheet(dn.format(Date(d.t))) { c, _ ->
            c.addView(tv(Weather.text(d.code), 17f), lp(top = 8))
            c.addView(tv("Od ${Weather.deg(d.min)} do ${Weather.deg(d.max)}", 15f), lp(top = 6))
            c.addView(tv("Opady: ${"%.1f".format(d.rain)} mm (szansa ${d.pop}%)", 15f), lp(top = 4))
            c.addView(tv("Porywy wiatru: ${d.gusts.ri()} km/h", 15f), lp(top = 4))
            c.addView(tv("Indeks UV: ${d.uv.ri()}", 15f), lp(top = 4))
            c.addView(tv("Wschód ${sun.format(Date(d.sunrise))} · zachód ${sun.format(Date(d.sunset))}", 15f), lp(top = 4))
        }
    }

    private fun radar(f: Forecast) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val w = WebView(this)
        w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
        w.webViewClient = WebViewClient()
        w.loadUrl("https://www.rainviewer.com/map.html?loc=${f.place.lat},${f.place.lon},8&oFa=0&oC=1&oU=0&oCS=1&oF=0&oAP=1&c=3&o=83&lm=1&layer=radar&sm=1&sn=1")
        val box = vbox()
        box.addView(btn("Zamknij") { d.dismiss() })
        box.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(box); d.show()
    }

    private fun placesSheet() {
        sheet("Miejsca") { c, d ->
            val l = Weather.places(this)
            for ((i, p) in l.withIndex()) {
                val r = hbox().apply { setPadding(0, dp(10), 0, dp(10)) }
                r.addView(tv((if (i == pr().getInt("place", 0)) "● " else "  ") + p.name, 16f).apply {
                    setOnClickListener { Weather.select(this@MainActivity, i); d.dismiss(); forecast = null; load(true); render() }
                }, lp(0, -2, 1f))
                if (!p.gps) r.addView(tv("✕", 16f, color = DIM).apply { setPadding(dp(12), 0, dp(4), 0); setOnClickListener {
                    Weather.savePlaces(this@MainActivity, l.filterIndexed { k, _ -> k != i }); Weather.select(this@MainActivity, 0); d.dismiss(); placesSheet()
                } })
                c.addView(r)
            }
            val e = EditText(this).apply { hint = "Szukaj miejscowości"; setTextColor(FG); setHintTextColor(DIM); setSingleLine(); background = round(Color.BLACK, 14); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            c.addView(e, lp(top = 12))
            val res = vbox()
            c.addView(btn("Szukaj", true) {
                val q = e.text.toString().trim(); if (q.length < 2) return@btn
                Thread {
                    val found = try { Weather.search(q) } catch (x: Exception) { emptyList() }
                    h.post {
                        res.removeAllViews()
                        if (found.isEmpty()) res.addView(tv("Nic nie znaleziono", 14f, color = DIM))
                        for (p in found) res.addView(tv(p.name, 15f).apply { setPadding(0, dp(10), 0, dp(10)); setOnClickListener {
                            val nl = Weather.places(this@MainActivity) + p
                            Weather.savePlaces(this@MainActivity, nl); Weather.select(this@MainActivity, nl.size - 1); d.dismiss(); forecast = null; load(true); render()
                        } })
                    }
                }.start()
            }, lp(top = 8))
            c.addView(res, lp(top = 6))
        }
    }

    // ================================================================== BUDZIKI
    private fun renderAlarms() {
        col.addView(tv("Budziki", 32f, true))
        val n = Alarms.nextAny(this)
        if (n != null) {
            val mins = (n.second - System.currentTimeMillis()) / 60_000
            col.addView(tv("Najbliższy za ${mins / 60} h ${mins % 60} min", 14f, color = DIM), lp(top = 2))
        }
        if (getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == false) col.addView(btn("⚠ Zezwól na dokładne budziki", true) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
        }, lp(top = 8))
        if (android.os.Build.VERSION.SDK_INT >= 34 && getSystemService(android.app.NotificationManager::class.java)?.canUseFullScreenIntent() == false) col.addView(btn("⚠ Zezwól na budzik na pełnym ekranie", true) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
        }, lp(top = 8))
        val nap = hbox()
        nap.addView(tv("Drzemka:", 14f, color = DIM), lp(-2, -2).apply { rightMargin = dp(8) })
        for (m in listOf(10, 20, 30, 45, 90)) nap.addView(btn("$m min") { Alarms.nap(this, m); toast("Budzik za $m min"); render() }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(nap) }, lp(top = 12))
        col.addView(btn("＋ Nowy budzik", true) { edit(Alarm(0, 7, 0, 0x1f)) }, lp(top = 12))
        val df = SimpleDateFormat("EEE d.MM HH:mm", Locale("pl"))
        for (a in Alarms.all(this)) {
            val box = vbox().apply { background = round(SURF, 18); setPadding(dp(16), dp(12), dp(14), dp(12)) }
            val r = hbox()
            val l = vbox()
            l.addView(tv(a.time(), 40f, true, if (a.on) FG else DIM))
            l.addView(tv(listOf(a.daysText(), a.label).filter { it.isNotBlank() }.joinToString(" · "), 13f, color = DIM))
            val extra = listOfNotNull(when (a.challenge) { "math" -> "zadania"; "shake" -> "potrząsanie"; "captcha" -> "przepisywanie"; else -> null },
                if (a.pre > 0) "przedbudzik ${a.pre} min" else null, if (a.holidays) "bez świąt" else null, if (a.wakeCheck) "sprawdzanie" else null)
            if (extra.isNotEmpty()) l.addView(tv(extra.joinToString(" · "), 12f, color = DIM))
            val nx = Alarms.next(a)
            if (a.on && nx > 0) l.addView(tv("Następny: " + df.format(Date(nx)), 12f, color = DIM))
            r.addView(l, lp(0, -2, 1f))
            r.addView(Switch(this).apply { isChecked = a.on; setOnCheckedChangeListener { _, b -> a.on = b; Alarms.put(this@MainActivity, a); render() } })
            box.addView(r)
            box.setOnClickListener { edit(a) }
            col.addView(box, lp(top = 10))
        }
    }

    private var pickedSound: ((String) -> Unit)? = null

    private fun edit(a0: Alarm) {
        val a = Alarm.of(a0.json())
        sheet(if (a.id == 0) "Nowy budzik" else "Budzik") { c, d ->
            val tp = TimePicker(this).apply { setIs24HourView(true); hour = a.hour; minute = a.min }
            c.addView(tp)
            // dni
            val dr = hbox()
            val names = listOf("pn", "wt", "śr", "cz", "pt", "sb", "nd")
            fun drawDays() {
                dr.removeAllViews()
                for (i in 0..6) {
                    val on = a.days and (1 shl i) != 0
                    dr.addView(tv(names[i], 14f, on, if (on) Color.BLACK else FG).apply {
                        gravity = Gravity.CENTER; background = if (on) round(FG, 16) else round(Color.BLACK, 16, 1); setPadding(0, dp(8), 0, dp(8))
                        setOnClickListener { a.days = a.days xor (1 shl i); drawDays() }
                    }, lp(0, -2, 1f).apply { rightMargin = dp(4) })
                }
            }
            drawDays()
            c.addView(dr, lp(top = 8))
            val lbl = EditText(this).apply { setText(a.label); hint = "Nazwa (np. Praca)"; setTextColor(FG); setHintTextColor(DIM); setSingleLine(); background = round(Color.BLACK, 14); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            c.addView(lbl, lp(top = 10))

            fun choice(title: String, opts: List<Pair<String, String>>, cur: String, set: (String) -> Unit) {
                c.addView(tv(title, 13f, color = DIM), lp(top = 12))
                val r = hbox()
                val views = ArrayList<TextView>()
                for ((k, n) in opts) {
                    val v = tv(n, 13f, k == cur, if (k == cur) Color.BLACK else FG).apply {
                        background = if (k == cur) round(FG, 14) else round(Color.BLACK, 14, 1); setPadding(dp(12), dp(7), dp(12), dp(7))
                    }
                    v.setOnClickListener {
                        set(k)
                        for ((j, x) in views.withIndex()) { val sel = opts[j].first == k; x.setTextColor(if (sel) Color.BLACK else FG); x.background = if (sel) round(FG, 14) else round(Color.BLACK, 14, 1) }
                    }
                    views += v
                    r.addView(v, lp(-2, -2).apply { rightMargin = dp(6) })
                }
                c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 4))
            }
            choice("Wyłączanie", listOf("none" to "Zwykłe", "math" to "Zadania matematyczne", "shake" to "Potrząsanie", "captcha" to "Przepisywanie"), a.challenge) { a.challenge = it }
            choice("Trudność / ile razy", listOf("1" to "Łatwo", "2" to "Średnio", "3" to "Trudno"), a.level.toString()) { a.level = it.toInt() }
            choice("Liczba zadań", listOf("1" to "1", "3" to "3", "5" to "5"), a.count.toString()) { a.count = it.toInt() }
            choice("Drzemka", listOf("5" to "5 min", "9" to "9 min", "10" to "10 min", "15" to "15 min", "20" to "20 min"), a.snooze.toString()) { a.snooze = it.toInt() }
            choice("Maks. drzemek", listOf("0" to "bez", "1" to "1", "3" to "3", "5" to "5", "99" to "bez limitu"), a.maxSnooze.toString()) { a.maxSnooze = it.toInt() }
            choice("Narastanie głośności", listOf("0" to "od razu", "30" to "30 s", "60" to "1 min", "120" to "2 min"), a.ramp.toString()) { a.ramp = it.toInt() }
            choice("Głośność", listOf("40" to "40%", "70" to "70%", "100" to "100%"), a.volume.toString()) { a.volume = it.toInt() }
            choice("Przedbudzik (cichy, wcześniej)", listOf("0" to "bez", "5" to "5 min", "10" to "10 min", "15" to "15 min"), a.pre.toString()) { a.pre = it.toInt() }
            choice("Wycisz po", listOf("5" to "5 min", "10" to "10 min", "20" to "20 min"), a.autoStop.toString()) { a.autoStop = it.toInt() }
            fun sw(t: String, on: Boolean, f: (Boolean) -> Unit) {
                val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
                r.addView(tv(t, 15f), lp(0, -2, 1f)); r.addView(Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, b -> f(b) } })
                c.addView(r)
            }
            sw("Wibracje", a.vibrate) { a.vibrate = it }
            sw("Przeczytaj godzinę i pogodę", a.speak) { a.speak = it }
            sw("Pomijaj święta", a.holidays) { a.holidays = it }
            sw("Sprawdź, czy nie śpisz (po 3 min)", a.wakeCheck) { a.wakeCheck = it }
            val snd = tv("Dźwięk: " + (if (a.sound.isBlank()) "domyślny alarm" else (RingtoneManager.getRingtone(this, Uri.parse(a.sound))?.getTitle(this) ?: "wybrany plik")), 15f)
            snd.setPadding(0, dp(10), 0, dp(10))
            snd.setOnClickListener {
                pickedSound = { u -> a.sound = u; snd.text = "Dźwięk: " + (RingtoneManager.getRingtone(this, Uri.parse(u))?.getTitle(this) ?: "wybrany plik") }
                sheet("Dźwięk") { cc, dd ->
                    cc.addView(btn("Dzwonki alarmowe telefonu") {
                        dd.dismiss()
                        startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (a.sound.isBlank()) null else Uri.parse(a.sound)), 11)
                    }, lp(top = 10))
                    cc.addView(btn("Plik muzyczny / piosenka") {
                        dd.dismiss(); startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), 12)
                    }, lp(top = 8))
                }
            }
            c.addView(snd)
            if (a.id != 0 && a.days != 0) c.addView(btn(if (a.skipUntil > System.currentTimeMillis()) "Pominięcie aktywne – cofnij" else "Pomiń najbliższy") {
                a.skipUntil = if (a.skipUntil > System.currentTimeMillis()) 0 else Alarms.next(a) + 60_000L
                a.hour = tp.hour; a.min = tp.minute; a.label = lbl.text.toString().trim()
                Alarms.put(this, a); d.dismiss(); render()
            }, lp(top = 8))
            val br = hbox()
            br.addView(btn("Zapisz", true) {
                a.hour = tp.hour; a.min = tp.minute; a.label = lbl.text.toString().trim(); a.on = true
                Alarms.put(this, a); d.dismiss(); render()
                val nx = Alarms.next(a); if (nx > 0) { val m = (nx - System.currentTimeMillis()) / 60_000; toast("Zadzwoni za ${m / 60} h ${m % 60} min") }
            }, lp(0, -2, 1f).apply { rightMargin = dp(8) })
            if (a.id != 0) br.addView(btn("Usuń") { Alarms.remove(this, a.id); d.dismiss(); render() }, lp(0, -2, 1f).apply { rightMargin = dp(8) })
            br.addView(btn("Test") { a.hour = tp.hour; a.min = tp.minute; if (a.id == 0) toast("Najpierw zapisz") else { Alarms.put(this, a); Ring.start(this, a.id, "alarm") } }, lp(0, -2, 1f))
            c.addView(br, lp(top = 14))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val u: Uri? = if (requestCode == 11) {
            @Suppress("DEPRECATION")
            data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        } else if (requestCode == 12) data?.data?.also { try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {} } else null
        if (u != null) pickedSound?.invoke(u.toString())
    }

    // ================================================================== MINUTNIK I STOPER
    private var swStart = 0L
    private var swAcc = 0L
    private val laps = ArrayList<Long>()
    private var timerLabel: TextView? = null
    private var swLabel: TextView? = null
    private var timerSet = 5 * 60

    private val tick = object : Runnable {
        override fun run() {
            val end = pr().getLong("timerEnd", 0)
            timerLabel?.text = if (end > System.currentTimeMillis()) fmt((end - System.currentTimeMillis()) / 1000) else fmt(timerSet.toLong())
            swLabel?.text = swFmt(swAcc + if (swStart > 0) System.currentTimeMillis() - swStart else 0)
            h.postDelayed(this, 100)
        }
    }

    private fun fmt(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    private fun swFmt(ms: Long) = "%d:%02d,%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)

    private fun renderTimer() {
        col.addView(tv("Minutnik", 32f, true))
        val end = pr().getLong("timerEnd", 0)
        val running = end > System.currentTimeMillis()
        val tl = tv(fmt(if (running) (end - System.currentTimeMillis()) / 1000 else timerSet.toLong()), 64f, true).apply { gravity = Gravity.CENTER }
        timerLabel = tl
        col.addView(tl, lp(top = 10))
        if (!running) {
            val r = hbox()
            for ((s, n) in listOf(60 to "1 min", 180 to "3 min", 300 to "5 min", 600 to "10 min", 900 to "15 min", 1800 to "30 min", 3600 to "1 h"))
                r.addView(btn(n, s == timerSet) { timerSet = s; render() }, lp(-2, -2).apply { rightMargin = dp(6) })
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 8))
            val adj = hbox()
            for ((d, n) in listOf(-60 to "−1 min", -10 to "−10 s", 10 to "+10 s", 60 to "+1 min")) adj.addView(btn(n) { timerSet = (timerSet + d).coerceAtLeast(10); render() }, lp(0, -2, 1f).apply { rightMargin = dp(6) })
            col.addView(adj, lp(top = 8))
            col.addView(btn("Start", true) { Alarms.startTimer(this, timerSet); render() }, lp(top = 10))
        } else col.addView(btn("Zatrzymaj") { Alarms.stopTimer(this); render() }, lp(top = 10))

        header("Stoper")
        val sl = tv(swFmt(swAcc), 52f, true).apply { gravity = Gravity.CENTER }
        swLabel = sl
        col.addView(sl, lp(top = 6))
        val sr = hbox()
        sr.addView(btn(if (swStart > 0) "Pauza" else "Start", swStart == 0L) {
            if (swStart > 0) { swAcc += System.currentTimeMillis() - swStart; swStart = 0 } else swStart = System.currentTimeMillis(); render()
        }, lp(0, -2, 1f).apply { rightMargin = dp(8) })
        sr.addView(btn("Okrążenie") { if (swStart > 0) { laps.add(0, swAcc + System.currentTimeMillis() - swStart); render() } }, lp(0, -2, 1f).apply { rightMargin = dp(8) })
        sr.addView(btn("Zeruj") { swStart = 0; swAcc = 0; laps.clear(); render() }, lp(0, -2, 1f))
        col.addView(sr, lp(top = 8))
        for ((i, l) in laps.withIndex()) col.addView(tv("Okrążenie ${laps.size - i}: ${swFmt(l)}", 15f), lp(top = 4))
        h.post(tick)
    }

    // ================================================================== USTAWIENIA
    private var update: Updater.Info? = null
    private var updMsg = ""

    private fun renderSettings() {
        col.addView(tv("Ustawienia", 32f, true))
        header("Powiadomienia")
        toggle("Poranna prognoza", "Codziennie o ${pr().getInt("morningAt", 420) / 60}:${"%02d".format(pr().getInt("morningAt", 420) % 60)} – i po wyłączeniu budzika", pr().getBoolean("nMorning", true)) { pr().edit().putBoolean("nMorning", it).apply() }
        val mr = hbox()
        for (m in listOf(360, 390, 420, 450, 480)) mr.addView(btn("${m / 60}:${"%02d".format(m % 60)}", pr().getInt("morningAt", 420) == m) { pr().edit().putInt("morningAt", m).apply(); render() }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(mr) })
        toggle("Prognoza po wyłączeniu budzika", "Zaraz po wstaniu – dzisiejsza pogoda i ostrzeżenia", pr().getBoolean("briefAfterAlarm", true)) { pr().edit().putBoolean("briefAfterAlarm", it).apply() }
        toggle("Deszcz za chwilę", "Gdy w ciągu godziny ma zacząć padać", pr().getBoolean("nRain", true)) { pr().edit().putBoolean("nRain", it).apply() }
        toggle("Ostrzeżenia", "Burza, silny wiatr, ulewa, gołoledź, mróz, przymrozek, upał, wysoki UV, złe powietrze", pr().getBoolean("nWarn", true)) { pr().edit().putBoolean("nWarn", it).apply() }
        toggle("Wieczorem prognoza na jutro", "O 20:00", pr().getBoolean("nEvening", false)) { pr().edit().putBoolean("nEvening", it).apply() }
        toggle("Temperatura na pasku stanu", "Stała ikona z aktualną temperaturą", pr().getBoolean("nTemp", false)) { pr().edit().putBoolean("nTemp", it).apply(); Notify.statusTemp(this, forecast) }

        header("Widżety")
        toggle("Jasne widżety", "Czarny tekst na białym tle zamiast białego na czarnym", pr().getBoolean("widgetLight", false)) { pr().edit().putBoolean("widgetLight", it).apply(); Widgets.updateAll(this) }
        col.addView(tv("Dostępne: Pogoda 1×1, 2×1, 2×2 · Zegar i pogoda 4×1 · Prognoza 4×2 i 4×3 · Budzik 1×1 i 2×1. Każdy sam dopasowuje układ po zmianie rozmiaru. Dodasz je, przytrzymując pusty ekran → Widżety → Świt.", 12f, color = DIM), lp(top = 4))

        header("Aktualizacje")
        col.addView(tv("Wersja: " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }), 14f, color = DIM))
        if (updMsg.isNotEmpty()) col.addView(tv(updMsg, 14f), lp(top = 4))
        col.addView(btn(update?.let { "Pobierz i zainstaluj 1.${it.number}" } ?: "Sprawdź aktualizacje", update != null) {
            val u = update
            if (u == null) Thread {
                val r = try { Updater.check(Updater.token(this)) } catch (e: Exception) { null }
                val cur = try { packageManager.getPackageInfo(packageName, 0).longVersionCode } catch (e: Exception) { 0L }
                h.post { update = r?.takeIf { it.number > cur }; updMsg = if (update != null) "Dostępna wersja 1.${r!!.number}" else "Masz najnowszą wersję"; render() }
            }.start()
            else install(u)
        }, lp(-2, -2, top = 6))
        col.addView(tv("Aktualizuje też Bastion → Moje aplikacje (bez okna instalatora).", 11f, color = DIM), lp(top = 6))
    }

    private fun install(u: Updater.Info) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Zezwól na instalowanie, potem stuknij ponownie")
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
            return
        }
        updMsg = "Pobieram…"; render()
        Thread {
            try {
                val f = File(cacheDir, "update.apk")
                Updater.download(Updater.token(this), u, f) { p -> h.post { updMsg = "Pobieram… $p%" } }
                val pi = packageManager.packageInstaller
                val sid = pi.createSession(android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(sid).use { s ->
                    s.openWrite("swit", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val intent = android.app.PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java).setAction("pl.swit.INSTALLED"),
                        android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    s.commit(intent.intentSender)
                }
                h.post { updMsg = "Potwierdź instalację" ; render() }
            } catch (e: Exception) {
                h.post { updMsg = "Błąd: ${e.message}"; render() }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (intent?.action == "pl.swit.INSTALLED") {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { startActivity(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }
}
