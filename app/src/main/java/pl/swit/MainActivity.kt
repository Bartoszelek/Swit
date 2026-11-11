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
    private lateinit var root: LinearLayout

    private fun applyTheme() {
        Theme.apply(this, pr().getString("theme", "ocean") ?: "ocean", pr().getString("mode", "system") ?: "system")
        window.statusBarColor = Theme.bg; window.navigationBarColor = Theme.surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (Theme.dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }
    private var forecast: Forecast? = null
    private var loading = false
    private var err = ""

    private val FG get() = Theme.fg
    private val DIM get() = Theme.dim
    private val SURF get() = Theme.surface
    private val LINE get() = Theme.line
    private val BG get() = Theme.bg

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
        setTextColor(if (primary) Theme.onAccent else FG); background = if (primary) Theme.gradient(dp(24).toFloat()) else round(SURF, 24, 1)
        setPadding(dp(18), dp(11), dp(18), dp(11)); setOnClickListener { f() }
    }
    private fun header(t: String) = col.addView(tv(t.uppercase(), 13f, true, DIM).apply { letterSpacing = 0.08f }, lp(top = 24))
    private fun line() = View(this).apply { setBackgroundColor(LINE) }
    private fun icon(res: Int, size: Int) = ImageView(this).apply { setImageResource(res); setColorFilter(Theme.fg) }.also { it.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }

    /** Kolorowa ikona pogody. */
    private fun wicon(code: Int, day: Boolean, size: Int) = ImageView(this).apply { setImageBitmap(Art.icon(code, day, dp(size))) }.also { it.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }
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
        applyTheme()
        // zapis każdej awarii – przy następnym uruchomieniu pokazujemy, co się stało
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e)) } catch (x: Exception) {}
            prev?.uncaughtException(t, e)
        }
        root = vbox().apply { setBackgroundColor(BG) }
        val root = this.root
        sv = ScrollView(this)
        col = vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(40)) }
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        tabs = hbox().apply { setBackgroundColor(SURF); setPadding(dp(6), dp(6), dp(6), dp(10)) }
        root.addView(tabs)
        setContentView(root)
        intent?.getStringExtra("tab")?.let { tab = it }
        intent?.getLongExtra("day", 0L)?.takeIf { it > 0 }?.let { calDay = it; calMonth.timeInMillis = it }
        if (intent?.getBooleanExtra("stopTimer", false) == true) Alarms.stopTimer(this)
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.ACCESS_COARSE_LOCATION
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.READ_CALENDAR
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        Notify.schedule(this)
        Alarms.scheduleAll(this)
        Rem.schedule(this)
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
        intent.getLongExtra("day", 0L).takeIf { it > 0 }?.let { calDay = it; calMonth.timeInMillis = it }
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
        for ((k, n) in listOf("weather" to "Pogoda", "alarms" to "Budzik", "cal" to "Kalendarz", "settings" to "Ustawienia")) {
            val on = k == tab
            tabs.addView(tv(n, 13f, on, if (on) Theme.onAccent else DIM).apply {
                gravity = Gravity.CENTER; background = if (on) Theme.gradient(dp(18).toFloat()) else null; setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { tab = k; render(); sv.scrollTo(0, 0) }
            }, lp(0, -2, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
    }

    private fun render() {
        h.removeCallbacks(tick)
        root.setBackgroundColor(BG); tabs.setBackgroundColor(SURF)
        col.removeAllViews()
        drawTabs()
        try {
            when (tab) {
                "alarms" -> renderAlarms()
                "timer" -> { tab = "alarms"; aSub = "timer"; renderAlarms() }
                "cal" -> renderCal()
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
        top.addView(tv("☰", 26f, true).apply { setPadding(dp(2), 0, dp(14), 0); setOnClickListener { drawer() } })
        val names = vbox()
        names.addView(tv((if (cur.gps) "📍 " else "") + (forecast?.place?.name?.takeIf { cur.gps } ?: cur.name), 19f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (places.size > 1) {
            // kropki jak w aplikacjach pogodowych – które miejsce z ilu
            val dots = hbox().apply { setPadding(0, dp(4), 0, 0) }
            val sel = pr().getInt("place", 0)
            for (i in places.indices) dots.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == sel) FG else Theme.withAlpha(FG, 0.3f)) } },
                LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(5) })
            names.addView(dots)
        }
        top.addView(names, lp(0, -2, 1f))
        top.addView(btn(if (loading) "…" else "↻") { load(true) })
        col.addView(top)
        val f = forecast
        if (f == null) {
            col.addView(tv(if (loading) "Pobieram prognozę…" else "Brak prognozy. $err", 15f, color = DIM), lp(top = 30))
            return
        }
        val df = SimpleDateFormat("HH:mm", Locale("pl"))
        col.addView(tv("Aktualizacja ${df.format(Date(f.at))}" + if (err.isNotEmpty()) " · offline" else "", 11f, color = DIM))
        // teraz – animowane niebo
        val hero = android.widget.FrameLayout(this).apply { clipToOutline = true; background = round(SURF, 28) }
        hero.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(28).toFloat()) }
        }
        hero.addView(SkyView(this).apply { phase = Art.phase(f); scene = Art.scene(f.code) }, android.widget.FrameLayout.LayoutParams(-1, -1))
        val hc = vbox().apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        val nowR = hbox()
        val tl = vbox()
        tl.addView(tv(Weather.deg(f.temp), 72f, true, Color.WHITE).apply { setShadowLayer(12f, 0f, 2f, 0x66000000) })
        tl.addView(tv(Weather.text(f.code), 18f, true, Color.WHITE).apply { setShadowLayer(8f, 0f, 1f, 0x66000000) })
        tl.addView(tv("Odczuwalna ${Weather.deg(f.feels)}" + (f.days.firstOrNull()?.let { " · ↑${Weather.deg(it.max)} ↓${Weather.deg(it.min)}" } ?: ""), 14f, color = 0xEEFFFFFF.toInt()).apply { setShadowLayer(6f, 0f, 1f, 0x66000000) })
        nowR.addView(tl, lp(0, -2, 1f))
        nowR.addView(wicon(f.code, f.day, 96))
        hc.addView(nowR)
        hero.addView(hc)
        // przesunięcie palcem po karcie pogody = następne / poprzednie miejsce
        val gd = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (Math.abs(vx) < Math.abs(vy) || Math.abs(vx) < 500 || places.size < 2) return false
                val n = (pr().getInt("place", 0) + (if (vx < 0) 1 else -1) + places.size) % places.size
                switchPlace(n); return true
            }
        })
        hero.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
        col.addView(hero, lp(-1, dp(230), top = 12))
        val nc = Weather.nowcast(f)
        if (nc.isNotEmpty()) col.addView(tv("☂ $nc", 15f, true), lp(top = 10))
        for ((_, w) in Weather.warnings(f)) col.addView(tv("⚠ $w", 14f, true, 0xFFFFB020.toInt()), lp(top = 6))

        // opady w najbliższych 3 godzinach (co 15 min)
        val q = f.q15.filter { it.first >= System.currentTimeMillis() - 15 * 60_000L }.take(12)
        if (q.any { it.second > 0 }) {
            header("Opady – najbliższe 3 godziny")
            val bars = hbox().apply { gravity = Gravity.BOTTOM }
            val mx = q.maxOf { it.second }.coerceAtLeast(0.5)
            for ((_, mm) in q) bars.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF4FA8FF.toInt(), 0xFF2A6FD6.toInt())).apply { cornerRadius = dp(3).toFloat() } }, LinearLayout.LayoutParams(0, dp((4 + 56 * mm / mx).toInt()), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
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
            val c = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(10), dp(10), dp(10), dp(10)); background = round(SURF, 18) }
            c.addView(tv(hf.format(Date(x.t)), 12f, color = DIM))
            c.addView(wicon(x.code, x.day, 32), LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(4) })
            c.addView(tv(Weather.deg(x.temp), 15f, true))
            c.addView(tv(if (x.pop >= 10) "${x.pop}%" else " ", 11f, color = DIM))
            c.addView(tv("${x.wind.ri()} km/h", 10f, color = DIM))
            hr.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
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
            r.addView(wicon(d.code, true, 30))
            r.addView(tv(if (d.pop >= 20) "${d.pop}%" else "", 11f, color = DIM).apply { gravity = Gravity.CENTER }, lp(dp(36), -2))
            r.addView(tv(Weather.deg(d.min), 14f, color = DIM).apply { gravity = Gravity.END }, lp(dp(36), -2))
            // pasek zakresu temperatur
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(LINE, 3) }
            val span = (gMax - gMin).coerceAtLeast(1.0)
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(5), (d.min - gMin).toFloat().coerceAtLeast(0.01f)))
            bar.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF5AB0FF.toInt(), 0xFFFFB347.toInt())).apply { cornerRadius = dp(3).toFloat() } },
                LinearLayout.LayoutParams(0, dp(5), (d.max - d.min).toFloat().coerceAtLeast(0.3f)))
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

    private fun switchPlace(i: Int) {
        Weather.select(this, i)
        forecast = null
        load(false)
        render()
    }

    /** Szuflada miejsc (☰): lista z ostatnią temperaturą, dodawanie, usuwanie, wybór. */
    private fun drawer() {
        val d = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val panel = vbox().apply { setBackgroundColor(SURF); setPadding(dp(18), dp(36), dp(18), dp(18)) }
        val w = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        val scroll = ScrollView(this).apply { addView(panel) }
        d.setContentView(scroll)
        d.window?.apply {
            setLayout(w, -1); setGravity(Gravity.START)
            setBackgroundDrawable(ColorDrawable(SURF))
            setWindowAnimations(android.R.style.Animation_Translucent)
            setDimAmount(0.5f); addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        fun fill() {
            panel.removeAllViews()
            panel.addView(tv("Miejsca", 24f, true))
            val l = Weather.places(this)
            val sel = pr().getInt("place", 0)
            for ((i, p) in l.withIndex()) {
                val r = hbox().apply {
                    setPadding(dp(12), dp(12), dp(8), dp(12))
                    background = if (i == sel) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.withAlpha(Theme.accent, 0.25f), Theme.withAlpha(Theme.accent2, 0.10f))).apply { cornerRadius = dp(16).toFloat() } else null
                }
                val mini = Weather.mini(this, p)
                if (mini != null) r.addView(ImageView(this).apply { setImageBitmap(Art.icon(mini[1].toIntOrNull() ?: 3, mini[2] == "true", dp(34))) }, LinearLayout.LayoutParams(dp(34), dp(34)))
                else r.addView(tv(if (p.gps) "📍" else "🏙", 22f), lp(dp(34), -2))
                val tl = vbox()
                tl.addView(tv(if (p.gps) "Moja lokalizacja" else p.name.substringBefore(" ("), 16f, i == sel).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                tl.addView(tv(if (p.gps) (mini?.getOrNull(3) ?: "według GPS") else p.name.substringAfter("(", "").removeSuffix(")"), 12f, color = DIM).apply { maxLines = 1 })
                r.addView(tl, lp(0, -2, 1f).apply { leftMargin = dp(12) })
                if (mini != null) r.addView(tv(Weather.deg(mini[0].toDoubleOrNull() ?: Double.NaN), 20f, true), lp(-2, -2).apply { rightMargin = dp(6) })
                if (!p.gps) r.addView(tv("✕", 16f, color = DIM).apply { setPadding(dp(10), dp(6), dp(4), dp(6)); setOnClickListener {
                    val nl = l.filterIndexed { k2, _ -> k2 != i }
                    Weather.savePlaces(this@MainActivity, nl)
                    Weather.select(this@MainActivity, if (sel >= nl.size) nl.size - 1 else if (i < sel) sel - 1 else sel)
                    fill()
                } })
                r.setOnClickListener { d.dismiss(); switchPlace(i) }
                panel.addView(r, lp(top = 4))
            }
            panel.addView(tv("Dodaj miejsce", 14f, true, DIM), lp(top = 22))
            val e = EditText(this).apply { hint = "Wpisz miejscowość, np. Zakopane"; setTextColor(FG); setHintTextColor(DIM); setSingleLine(); background = round(BG, 14); setPadding(dp(12), dp(10), dp(12), dp(10)); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
            panel.addView(e, lp(top = 6))
            val res = vbox()
            fun search() {
                val q = e.text.toString().trim(); if (q.length < 2) return
                res.removeAllViews(); res.addView(tv("Szukam…", 13f, color = DIM))
                Thread {
                    val found = try { Weather.search(q) } catch (x: Exception) { emptyList() }
                    h.post {
                        res.removeAllViews()
                        if (found.isEmpty()) res.addView(tv("Nic nie znaleziono", 13f, color = DIM))
                        for (p in found) res.addView(tv("＋  " + p.name, 15f).apply { setPadding(dp(6), dp(10), dp(6), dp(10)); setOnClickListener {
                            val nl = Weather.places(this@MainActivity).let { if (it.size == 1 && it[0].gps && Prefs.get(this@MainActivity).getString("places", null) == null) it else it } + p
                            Weather.savePlaces(this@MainActivity, nl)
                            d.dismiss(); switchPlace(nl.size - 1)
                        } })
                    }
                }.start()
            }
            e.setOnEditorActionListener { _, _, _ -> search(); true }
            panel.addView(btn("Szukaj", true) { search() }, lp(top = 8))
            panel.addView(res, lp(top = 6))
            if (l.none { it.gps }) panel.addView(btn("📍 Dodaj „Moja lokalizacja”") {
                Weather.savePlaces(this@MainActivity, listOf(Place("Moja lokalizacja", 0.0, 0.0, true)) + l); d.dismiss(); switchPlace(0)
            }, lp(top = 14))
            panel.addView(tv("Wskazówka: przesuń palcem po karcie pogody w lewo lub w prawo, żeby przełączać miejsca.", 12f, color = DIM), lp(top = 18))
        }
        fill()
        d.show()
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
            val e = EditText(this).apply { hint = "Szukaj miejscowości"; setTextColor(FG); setHintTextColor(DIM); setSingleLine(); background = round(BG, 14); setPadding(dp(12), dp(10), dp(12), dp(10)) }
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
    private var aSub = "alarms"
    private var fabOpen = false

    private fun challengeName(c: String) = when (c) { "math" -> "LICZBY"; "shake" -> "POTRZĄSANIE"; "captcha" -> "PRZEPISYWANIE"; "memory" -> "PAMIĘĆ"; else -> "PROSTY" }

    private fun renderAlarms() {
        // nagłówek: nocne góry, pora snu i najbliższy alarm
        val head = android.widget.FrameLayout(this)
        head.addView(MountainView(this), android.widget.FrameLayout.LayoutParams(-1, -1))
        val cards = hbox().apply { setPadding(dp(4), 0, dp(4), dp(10)); gravity = Gravity.BOTTOM }
        val df = SimpleDateFormat("H:mm", Locale("pl"))
        val bt = Rem.bedtime(this)
        val n = Alarms.nextAny(this)
        fun card(icon: String, txt: String, f: () -> Unit) {
            val b = hbox().apply { background = round(Theme.withAlpha(Color.BLACK, 0.35f), 18); setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { f() } }
            b.addView(tv(icon, 26f), lp(-2, -2).apply { rightMargin = dp(10) })
            b.addView(tv(txt, 13f, false, Color.WHITE), lp(0, -2, 1f))
            cards.addView(b, lp(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        card("🛏", if (bt != null && bt > System.currentTimeMillis() - 3600_000L) "Pora snu\n${df.format(Date(bt))}" else "Nie masz dziś\nustawionej pory snu") { sleepSheet() }
        card("⏰", if (n != null) { val m = (n.second - System.currentTimeMillis()) / 60_000; "Za ${m / 60} h ${m % 60} min\n${n.first.time()}" } else "Brak aktywnych\nalarmów") {}
        head.addView(cards, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        col.addView(head, lp(-1, dp(190)))

        // podzakładki: alarmy, minutnik, stoper
        val st = hbox().apply { background = round(SURF, 20); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        for ((k, nm) in listOf("alarms" to "Alarmy", "timer" to "Minutnik", "watch" to "Stoper")) {
            val on = aSub == k
            st.addView(tv(nm, 14f, on, if (on) Theme.onAccent else DIM).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9)); background = if (on) Theme.gradient(dp(16).toFloat()) else null
                setOnClickListener { aSub = k; render() }
            }, lp(0, -2, 1f))
        }
        col.addView(st, lp(top = 12))
        when (aSub) {
            "timer" -> return renderTimer()
            "watch" -> return renderWatch()
        }

        if (getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == false) col.addView(btn("⚠ Zezwól na dokładne budziki", true) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
        }, lp(top = 8))
        if (android.os.Build.VERSION.SDK_INT >= 34 && getSystemService(android.app.NotificationManager::class.java)?.canUseFullScreenIntent() == false) col.addView(btn("⚠ Zezwól na budzik na pełnym ekranie", true) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
        }, lp(top = 8))

        // przycisk „+” rozwijany jak w Alarm Clock Xtreme
        val fab = vbox().apply { gravity = Gravity.END }
        if (fabOpen) {
            fun opt(label: String, icon: String, f: () -> Unit) {
                val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(0, dp(4), 0, dp(4)) }
                r.addView(tv(label, 15f, true).apply { background = round(SURF, 10); setPadding(dp(14), dp(8), dp(14), dp(8)) }, lp(-2, -2).apply { rightMargin = dp(10) })
                r.addView(tv(icon, 22f, false, Theme.onAccent).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.blend(Theme.accent, Color.BLACK, 0.3f)) } }, LinearLayout.LayoutParams(dp(52), dp(52)))
                r.setOnClickListener { fabOpen = false; f() }
                fab.addView(r)
            }
            opt("Przypomnienie", "👆") { evEdit(Ev(start = System.currentTimeMillis() + 3600_000L, alerts = mutableListOf(0))) }
            opt("Szybki alarm", "⚡") { quickSheet() }
            opt("Alarm", "⏰") { edit(Alarm(0, 7, 0, 0x1f)) }
        }
        val plus = tv(if (fabOpen) "✕" else "＋", 30f, true, Theme.onAccent).apply {
            gravity = Gravity.CENTER; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Theme.accent, Theme.accent2)).apply { shape = GradientDrawable.OVAL }
            elevation = dp(6).toFloat(); setOnClickListener { fabOpen = !fabOpen; render() }
        }
        fab.addView(plus, LinearLayout.LayoutParams(dp(62), dp(62)).apply { topMargin = dp(6) })
        col.addView(fab, lp(top = 8))

        val list = Alarms.all(this)
        if (list.isEmpty()) col.addView(tv("Brak budzików – stuknij ＋", 15f, color = DIM).apply { gravity = Gravity.CENTER }, lp(top = 20))
        for (a in list) {
            val row = hbox().apply { setPadding(0, dp(12), 0, dp(12)) }
            row.addView(Switch(this).apply { isChecked = a.on; setOnCheckedChangeListener { _, b -> a.on = b; Alarms.put(this@MainActivity, a); render() } })
            val l = vbox()
            l.addView(tv(a.time(), 46f, true, if (a.on) FG else Theme.withAlpha(FG, 0.35f)))
            val nx = Alarms.next(a)
            val dayLabel = if (a.days == 0 && nx > 0) (if (dayKey(nx) == dayKey(System.currentTimeMillis())) "DZISIAJ" else "JUTRO") else a.daysText().uppercase()
            l.addView(tv(dayLabel + (if (a.label.isNotBlank()) " · " + a.label.uppercase() else "") + (if (a.skipUntil > System.currentTimeMillis()) " · POMINIĘTY" else ""), 12f, color = DIM))
            row.addView(l, lp(0, -2, 1f).apply { leftMargin = dp(14) })
            val rgt = vbox().apply { gravity = Gravity.END }
            rgt.addView(tv("⋮", 24f, true, DIM).apply { setPadding(dp(12), 0, dp(4), dp(4)); setOnClickListener { alarmMenu(a) } })
            rgt.addView(tv(challengeName(a.challenge), 11f, true, if (a.challenge == "none") DIM else ACC))
            row.addView(rgt)
            row.setOnClickListener { edit(a) }
            col.addView(row); col.addView(line(), lp(-1, 1))
        }
    }

    private val ACC get() = Theme.accent

    private fun dayKey(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    private fun alarmMenu(a: Alarm) {
        sheet(a.time()) { c, d ->
            c.addView(btn("Edytuj") { d.dismiss(); edit(a) }, lp(top = 10))
            c.addView(btn("Duplikuj") { d.dismiss(); Alarms.put(this, Alarm.of(a.json()).apply { id = 0 }); render() }, lp(top = 8))
            if (a.days != 0) c.addView(btn(if (a.skipUntil > System.currentTimeMillis()) "Cofnij pominięcie" else "Pomiń najbliższy") {
                d.dismiss(); a.skipUntil = if (a.skipUntil > System.currentTimeMillis()) 0 else Alarms.next(a) + 60_000L; Alarms.put(this, a); render()
            }, lp(top = 8))
            c.addView(btn("Test dzwonienia") { d.dismiss(); Ring.start(this, a.id, "alarm") }, lp(top = 8))
            c.addView(btn("Usuń") { d.dismiss(); Alarms.remove(this, a.id); render() }, lp(top = 8))
        }
    }

    /** Szybki alarm jak w Alarm Clock Xtreme: wpisujesz godziny i minuty z klawiatury, widać, o której zadzwoni. */
    private fun quickSheet() {
        val d = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        var digits = ""                                   // do 4 cyfr: GGMM, wpisywane od prawej
        fun hours() = digits.padStart(4, '0').take(2).toInt()
        fun mins() = digits.padStart(4, '0').takeLast(2).toInt()
        fun total() = hours() * 60 + mins()
        val panel = vbox().apply { setBackgroundColor(Theme.blend(SURF, Theme.accent, 0.08f)); setPadding(dp(18), dp(14), dp(18), dp(18)) }
        val top = hbox()
        top.addView(tv("✕", 26f).apply { setPadding(dp(6), 0, dp(6), 0); setOnClickListener { d.dismiss() } })
        top.addView(View(this), lp(0, 1, 1f))
        panel.addView(top)
        // wyświetlacz: 00 godz. 00 min
        val disp = hbox().apply { gravity = Gravity.CENTER or Gravity.BOTTOM }
        val hT = tv("00", 64f, true); val mT = tv("00", 64f, true)
        disp.addView(hT); disp.addView(tv(" godz.  ", 22f, color = DIM).apply { setPadding(0, 0, 0, dp(10)) })
        disp.addView(mT); disp.addView(tv(" min", 22f, color = DIM).apply { setPadding(0, 0, 0, dp(10)) })
        panel.addView(disp, lp(top = 6))
        val whenT = tv("", 20f, color = DIM).apply { gravity = Gravity.CENTER }
        panel.addView(whenT, lp(top = 4))
        val set = tv("USTAW SZYBKI ALARM", 16f, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.06f; setPadding(0, dp(16), 0, dp(16)) }
        fun refresh() {
            val empty = digits.isEmpty() || total() == 0
            hT.text = "%02d".format(hours()); mT.text = "%02d".format(mins())
            hT.setTextColor(if (empty) DIM else FG); mT.setTextColor(if (empty) DIM else FG)
            val t = System.currentTimeMillis() + total() * 60_000L
            val cal = Calendar.getInstance().apply { timeInMillis = t }
            val day = when (dayKey(t) - dayKey(System.currentTimeMillis())) { 0 -> "Dzisiaj"; 1 -> "Jutro"; else -> SimpleDateFormat("EEEE", Locale("pl")).format(Date(t)).replaceFirstChar { it.uppercase() } }
            whenT.text = "$day, %d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            set.setTextColor(if (empty) DIM else Theme.onAccent)
            set.background = if (empty) round(Theme.withAlpha(FG, 0.12f), 14) else Theme.gradient(dp(14).toFloat())
        }
        // klawiatura
        fun key(label: String, f: () -> Unit) = tv(label, 32f, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Theme.withAlpha(FG, 0.15f)), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            setOnClickListener { f(); refresh() }
        }
        fun add(x: String) { digits = (digits + x).trimStart('0').takeLast(4) }
        for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫"))) {
            val r = hbox()
            for (k in row) r.addView(key(k) {
                when (k) { "⌫" -> digits = digits.dropLast(1); "00" -> if (digits.isNotEmpty()) add("00"); else -> add(k) }
            }.apply { if (k == "⌫") setOnLongClickListener { digits = ""; refresh(); true } }, lp(0, -2, 1f))
            panel.addView(r, lp(top = 2))
        }
        // szybkie wybory
        val qr = hbox()
        for ((m, n) in listOf(30 to "30 min", 120 to "2 godz.", 480 to "8 godz.")) qr.addView(tv(n, 16f, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); background = round(Theme.withAlpha(FG, 0.12f), 12)
            setOnClickListener { digits = "%d%02d".format(m / 60, m % 60).trimStart('0'); refresh() }
        }, lp(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        panel.addView(qr, lp(top = 12))
        set.setOnClickListener {
            val m = total(); if (m <= 0) return@setOnClickListener
            Alarms.nap(this, m); d.dismiss()
            toast("Zadzwoni o " + whenT.text.toString().substringAfter(", ")); render()
        }
        panel.addView(set, lp(top = 12))
        refresh()
        d.setContentView(panel)
        d.window?.apply {
            setLayout(-1, -2); setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f); addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        // „Dzisiaj, 13:19” odświeżane co pół minuty, gdy okno jest otwarte
        val tick = object : Runnable { override fun run() { if (d.isShowing) { refresh(); h.postDelayed(this, 30_000) } } }
        d.setOnShowListener { h.postDelayed(tick, 30_000) }
        d.show()
    }

    /** Pora snu: ile chcesz spać i kiedy przypomnieć. */
    private fun sleepSheet() {
        sheet("Pora snu") { c, d ->
            c.addView(tv("Świt przypomni, kiedy iść spać, żeby wyspać się przed najbliższym budzikiem.", 13f, color = DIM), lp(top = 6))
            c.addView(tv("Ile chcesz spać", 14f, true), lp(top = 10))
            val r = hbox()
            for (m in listOf(360, 420, 450, 480, 540)) r.addView(btn("${m / 60}${if (m % 60 > 0) ",5" else ""} h", pr().getInt("sleepMin", 480) == m) {
                pr().edit().putInt("sleepMin", m).apply(); Rem.scheduleBedtime(this); d.dismiss(); sleepSheet(); render()
            }, lp(-2, -2).apply { rightMargin = dp(6) })
            c.addView(HorizontalScrollView(this).apply { addView(r) }, lp(top = 4))
            c.addView(tv("Przypomnij przed porą snu", 14f, true), lp(top = 12))
            val r2 = hbox()
            for (m in listOf(0, 15, 30, 60)) r2.addView(btn(if (m == 0) "wyłącz" else "$m min", if (m == 0) !pr().getBoolean("bedOn", true) else pr().getBoolean("bedOn", true) && pr().getInt("bedBefore", 30) == m) {
                pr().edit().putBoolean("bedOn", m > 0).putInt("bedBefore", m).apply(); Rem.scheduleBedtime(this); d.dismiss(); sleepSheet()
            }, lp(-2, -2).apply { rightMargin = dp(6) })
            c.addView(HorizontalScrollView(this).apply { addView(r2) }, lp(top = 4))
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
                    dr.addView(tv(names[i], 14f, on, if (on) Theme.onAccent else FG).apply {
                        gravity = Gravity.CENTER; background = if (on) Theme.gradient(dp(16).toFloat()) else round(BG, 16, 1); setPadding(0, dp(8), 0, dp(8))
                        setOnClickListener { a.days = a.days xor (1 shl i); drawDays() }
                    }, lp(0, -2, 1f).apply { rightMargin = dp(4) })
                }
            }
            drawDays()
            c.addView(dr, lp(top = 8))
            val lbl = EditText(this).apply { setText(a.label); hint = "Nazwa (np. Praca)"; setTextColor(FG); setHintTextColor(DIM); setSingleLine(); background = round(BG, 14); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            c.addView(lbl, lp(top = 10))

            fun choice(title: String, opts: List<Pair<String, String>>, cur: String, set: (String) -> Unit) {
                c.addView(tv(title, 13f, color = DIM), lp(top = 12))
                val r = hbox()
                val views = ArrayList<TextView>()
                for ((k, n) in opts) {
                    val v = tv(n, 13f, k == cur, if (k == cur) Theme.onAccent else FG).apply {
                        background = if (k == cur) Theme.gradient(dp(14).toFloat()) else round(BG, 14, 1); setPadding(dp(12), dp(7), dp(12), dp(7))
                    }
                    v.setOnClickListener {
                        set(k)
                        for ((j, x) in views.withIndex()) { val sel = opts[j].first == k; x.setTextColor(if (sel) Theme.onAccent else FG); x.background = if (sel) Theme.gradient(dp(14).toFloat()) else round(BG, 14, 1) }
                    }
                    views += v
                    r.addView(v, lp(-2, -2).apply { rightMargin = dp(6) })
                }
                c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 4))
            }
            choice("Wyłączanie", listOf("none" to "Prosty", "math" to "Liczby", "memory" to "Pamięć", "shake" to "Potrząsanie", "captcha" to "Przepisywanie"), a.challenge) { a.challenge = it }
            choice("Trudność / ile razy", listOf("1" to "Łatwo", "2" to "Średnio", "3" to "Trudno"), a.level.toString()) { a.level = it.toInt() }
            choice("Liczba zadań", listOf("1" to "1", "3" to "3", "5" to "5"), a.count.toString()) { a.count = it.toInt() }
            choice("Drzemka – pierwszy przycisk", listOf("3" to "3 min", "5" to "5 min", "9" to "9 min", "10" to "10 min", "15" to "15 min"), a.snooze.toString()) { a.snooze = it.toInt() }
            choice("Drzemka – drugi przycisk", listOf("10" to "10 min", "15" to "15 min", "20" to "20 min", "30" to "30 min"), a.snooze2.toString()) { a.snooze2 = it.toInt() }
            choice("Wyłączanie: przytrzymaj przycisk", listOf("0" to "bez", "3" to "3 s", "5" to "5 s", "10" to "10 s"), a.hold.toString()) { a.hold = it.toInt() }
            choice("Przyciski boczne podczas dzwonienia", listOf("none" to "Nic nie robią", "snooze" to "Drzemka"), a.keys) { a.keys = it }
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

    // ================================================================== KALENDARZ I PRZYPOMNIENIA
    private var calMode = "month"
    private val calMonth: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var calDay = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun startOfDay(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun weekStart(t: Long) = Calendar.getInstance().apply { timeInMillis = startOfDay(t); add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7)) }.timeInMillis

    private fun renderCal() {
        val mf = SimpleDateFormat("LLLL yyyy", Locale("pl"))
        val top = hbox()
        val title = when (calMode) {
            "month" -> mf.format(calMonth.time)
            "week" -> { val w = weekStart(calDay); SimpleDateFormat("d MMM", Locale("pl")).format(Date(w)) + " – " + SimpleDateFormat("d MMM", Locale("pl")).format(Date(w + 6 * 86_400_000L)) }
            "day" -> SimpleDateFormat("EEEE, d MMMM", Locale("pl")).format(Date(calDay))
            else -> "Najbliższe wpisy"
        }.replaceFirstChar { it.uppercase() }
        fun chip(t: String, on: Boolean, size: Float = 13f, f: () -> Unit) = tv(t, size, on, if (on) Theme.onAccent else FG).apply {
            maxLines = 1; gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7))
            background = if (on) Theme.gradient(dp(14).toFloat()) else null; setOnClickListener { f() }
        }
        top.addView(tv("‹", 24f, true).apply { setPadding(dp(4), 0, dp(10), 0); setOnClickListener { calMove(-1) } })
        top.addView(tv(title, 19f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, -2, 1f))
        top.addView(chip("Dziś", false) { calDay = startOfDay(System.currentTimeMillis()); calMonth.timeInMillis = calDay; calMonth.set(Calendar.DAY_OF_MONTH, 1); render() }.apply { background = round(SURF, 14, 1) })
        top.addView(tv("›", 24f, true).apply { setPadding(dp(10), 0, dp(4), 0); setOnClickListener { calMove(1) } })
        col.addView(top)
        // jeden pasek przełącznika widoków (bez łamania napisów)
        val mr = hbox().apply { background = round(SURF, 16); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        for ((k, n) in listOf("month" to "Miesiąc", "week" to "Tydzień", "day" to "Dzień", "list" to "Lista"))
            mr.addView(chip(n, calMode == k) { calMode = k; render() }, lp(0, -2, 1f))
        col.addView(mr, lp(top = 8))
        val add = hbox()
        for ((t, n) in Rem.TYPES) add.addView(chip("＋ $n", false, 12.5f) { newEv(t, if (calMode == "list") System.currentTimeMillis() + 3600_000L else calDay + 9 * 3600_000L) }
            .apply { background = round(SURF, 14, 1); if (t == "reminder") { background = Theme.gradient(dp(14).toFloat()); setTextColor(Theme.onAccent) } }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(add) }, lp(top = 8))

        when (calMode) {
            "month" -> {
                val mv = MonthView(this)
                mv.month = calMonth.clone() as Calendar
                mv.selected = calDay
                mv.occ = Rem.range(this, mv.first(), mv.first() + 42 * 86_400_000L)
                mv.onPick = { d -> calDay = d; render() }
                mv.onLong = { d -> calDay = d; newEv("reminder", d + 9 * 3600_000L) }
                mv.onSwipe = { calMove(it) }
                col.addView(mv, lp(-1, dp(470), top = 10))
                dayList(calDay)
            }
            "week", "day" -> {
                val wv = WeekView(this)
                wv.days = if (calMode == "week") 7 else 1
                wv.start = if (calMode == "week") weekStart(calDay) else startOfDay(calDay)
                wv.occ = Rem.range(this, wv.start, wv.start + wv.days * 86_400_000L)
                wv.onEmpty = { t -> newEv("event", t) }
                wv.onOcc = { o -> occSheet(o) }
                wv.onSwipe = { calMove(it) }
                col.addView(wv, lp(top = 10))
                sv.post { sv.scrollTo(0, (wv.hourH * 7).toInt()) }
            }
            else -> agenda()
        }
    }

    private fun calMove(d: Int) {
        when (calMode) {
            "month" -> { calMonth.add(Calendar.MONTH, d); calDay = startOfDay(calMonth.timeInMillis) }
            "week" -> calDay += d * 7 * 86_400_000L
            "day" -> calDay += d * 86_400_000L
        }
        if (calMode != "month") { calMonth.timeInMillis = calDay; calMonth.set(Calendar.DAY_OF_MONTH, 1) }
        render()
    }

    private fun occRow(o: Occ): View {
        val hf = SimpleDateFormat("H:mm", Locale("pl"))
        val r = hbox().apply { setPadding(0, dp(9), 0, dp(9)) }
        r.addView(View(this).apply { background = round(Rem.color(o.ev), 3) }, LinearLayout.LayoutParams(dp(5), dp(40)).apply { rightMargin = dp(12) })
        val l = vbox()
        l.addView(tv(Rem.typeIcon(o.ev.type) + " " + o.ev.title + (if (o.start in o.ev.done) "  ✓" else ""), 16f, true))
        val sub = ArrayList<String>()
        sub += if (o.ev.allDay) (if (o.end - o.start > 86_400_000L) "do " + SimpleDateFormat("d MMM", Locale("pl")).format(Date(o.end)) else "cały dzień") else hf.format(Date(o.start)) + if (o.end > o.start) "–" + hf.format(Date(o.end)) else ""
        if (o.ev.type == "birthday") Rem.ageAt(o.ev, o.start).takeIf { it > 0 }?.let { sub += "kończy $it lat" }
        Rem.repeatText(o.ev).takeIf { it.isNotEmpty() }?.let { sub += it }
        if (o.ev.external) sub += "Kalendarz Google"
        l.addView(tv(sub.joinToString(" · "), 12f, color = DIM))
        r.addView(l, lp(0, -2, 1f))
        r.setOnClickListener { occSheet(o) }
        return r
    }

    private fun dayList(d: Long) {
        header(SimpleDateFormat("EEEE, d MMMM", Locale("pl")).format(Date(d)))
        val l = Rem.range(this, d, d + 86_400_000L)
        if (l.isEmpty()) col.addView(tv("Nic zaplanowanego. Przytrzymaj dzień w kalendarzu, żeby szybko dodać.", 13f, color = DIM), lp(top = 4))
        for (o in l) col.addView(occRow(o))
    }

    private fun agenda() {
        val now = startOfDay(System.currentTimeMillis())
        val l = Rem.range(this, now, now + 90L * 86_400_000L)
        if (l.isEmpty()) col.addView(tv("Brak wpisów w najbliższych 90 dniach.", 14f, color = DIM), lp(top = 16))
        val df = SimpleDateFormat("EEEE, d MMMM", Locale("pl"))
        var last = -1L
        for (o in l.sortedBy { it.start }.take(300)) {
            val d = startOfDay(maxOf(o.start, now))
            if (d != last) { header(if (d == now) "Dziś" else if (d == now + 86_400_000L) "Jutro" else df.format(Date(d))); last = d }
            col.addView(occRow(o))
        }
    }

    private fun occSheet(o: Occ) {
        val e = o.ev
        sheet(Rem.typeIcon(e.type) + " " + e.title) { c, d ->
            val df = SimpleDateFormat(if (e.allDay) "EEEE, d MMMM yyyy" else "EEEE, d MMMM yyyy, H:mm", Locale("pl"))
            c.addView(tv(df.format(Date(o.start)), 15f), lp(top = 6))
            Rem.repeatText(e).takeIf { it.isNotEmpty() }?.let { c.addView(tv("Powtarza się: $it", 14f, color = DIM), lp(top = 4)) }
            if (e.note.isNotBlank()) c.addView(tv(e.note, 14f), lp(top = 8))
            if (e.external) {
                c.addView(btn("Otwórz w Kalendarzu Google", true) {
                    d.dismiss()
                    try { startActivity(Intent(Intent.ACTION_VIEW).setData(android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, -e.id))) } catch (x: Exception) {}
                }, lp(top = 12))
                return@sheet
            }
            if (e.type == "reminder" && o.start !in e.done) c.addView(btn("✓ Zrobione", true) { Rem.done(this, e.id, o.start); d.dismiss(); render() }, lp(top = 12))
            c.addView(btn("Edytuj") { d.dismiss(); evEdit(e) }, lp(top = 8))
            c.addView(btn("Usuń") {
                d.dismiss()
                android.app.AlertDialog.Builder(this).setTitle("Usunąć „${e.title}”?").setMessage(if (e.repeat != "none") "Usunięte zostaną wszystkie powtórzenia." else null)
                    .setPositiveButton("Usuń") { _, _ -> Rem.remove(this, e.id); render() }.setNegativeButton("Anuluj", null).show()
            }, lp(top = 8))
        }
    }

    private fun newEv(type: String, at: Long) {
        val e = when (type) {
            "birthday" -> Ev(type = "birthday", level = 2, start = startOfDay(at), end = startOfDay(at), allDay = true, repeat = "yearly", alerts = mutableListOf(0, 1440))
            "trip" -> Ev(type = "trip", level = 2, start = startOfDay(at), end = startOfDay(at) + 3 * 86_400_000L, allDay = true, alerts = mutableListOf(1440, 10080))
            "event" -> Ev(type = "event", start = at, end = at + 3600_000L, alerts = mutableListOf(30))
            else -> Ev(type = "reminder", start = at, alerts = mutableListOf(0))
        }
        evEdit(e)
    }

    /** Edytor wpisu: rodzaj, ważność (kolor), data i godzina, powtarzanie, powiadomienia. */
    private fun evEdit(e0: Ev) {
        val e = Ev.of(e0.json())
        sheet(if (e.id == 0L) "Nowy wpis" else "Edycja") { c, d ->
            val title = EditText(this).apply { setText(e.title); hint = "Tytuł (np. Urodziny mamy, Leki, Wyjazd do Zakopanego)"; setTextColor(FG); setHintTextColor(DIM); background = round(BG, 14); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            c.addView(title, lp(top = 8))
            fun chips(label: String, opts: List<Pair<String, String>>, cur: () -> String, colorOf: (String) -> Int? = { null }, set: (String) -> Unit): LinearLayout {
                c.addView(tv(label, 13f, color = DIM), lp(top = 12))
                val r = hbox()
                fun draw() {
                    r.removeAllViews()
                    for ((k, n) in opts) {
                        val on = cur() == k
                        val col = colorOf(k)
                        r.addView(tv(n, 13f, on, if (on) Theme.onAccent else FG).apply {
                            background = if (on) (if (col != null) round(col, 14) else Theme.gradient(dp(14).toFloat())) else round(BG, 14, 1)
                            if (!on && col != null) setTextColor(col)
                            setPadding(dp(12), dp(7), dp(12), dp(7)); setOnClickListener { set(k); draw() }
                        }, lp(-2, -2).apply { rightMargin = dp(6) })
                    }
                }
                draw()
                c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 4))
                return r
            }
            chips("Rodzaj", Rem.TYPES, { e.type }) { e.type = it; if (it == "birthday") { e.allDay = true; e.repeat = "yearly" }; if (it == "trip") e.allDay = true }
            chips("Ważność", Rem.LEVELS.mapIndexed { i, p -> i.toString() to p.first }, { e.level.toString() }, { Rem.LEVELS[it.toInt()].second }) { e.level = it.toInt() }

            // data i godzina
            val df = SimpleDateFormat("EEE, d MMM yyyy", Locale("pl")); val hf = SimpleDateFormat("H:mm", Locale("pl"))
            val whenR = hbox()
            val dBtn = btn(df.format(Date(e.start))) {}
            val tBtn = btn(hf.format(Date(e.start))) {}
            val eBtn = btn("do " + df.format(Date(maxOf(e.end, e.start)))) {}
            fun refresh() {
                dBtn.text = df.format(Date(e.start)); tBtn.text = hf.format(Date(e.start)); eBtn.text = "do " + df.format(Date(maxOf(e.end, e.start)))
                tBtn.visibility = if (e.allDay) View.GONE else View.VISIBLE
                eBtn.visibility = if (e.type == "trip" || e.type == "event") View.VISIBLE else View.GONE
            }
            dBtn.setOnClickListener {
                val cal = Calendar.getInstance().apply { timeInMillis = e.start }
                android.app.DatePickerDialog(this, { _, y, m, dd ->
                    val dur = e.duration
                    cal.set(y, m, dd); e.start = cal.timeInMillis; e.end = e.start + dur; refresh()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
            tBtn.setOnClickListener {
                val cal = Calendar.getInstance().apply { timeInMillis = e.start }
                android.app.TimePickerDialog(this, { _, hh, mm ->
                    val dur = e.duration
                    cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, mm); e.start = cal.timeInMillis; e.end = e.start + dur; refresh()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
            }
            eBtn.setOnClickListener {
                val cal = Calendar.getInstance().apply { timeInMillis = maxOf(e.end, e.start) }
                android.app.DatePickerDialog(this, { _, y, m, dd ->
                    cal.set(y, m, dd)
                    if (!e.allDay) { val s0 = Calendar.getInstance().apply { timeInMillis = e.start }; cal.set(Calendar.HOUR_OF_DAY, s0.get(Calendar.HOUR_OF_DAY) + 1) }
                    e.end = maxOf(cal.timeInMillis, e.start); refresh()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
            whenR.addView(dBtn, lp(-2, -2).apply { rightMargin = dp(6) }); whenR.addView(tBtn, lp(-2, -2).apply { rightMargin = dp(6) }); whenR.addView(eBtn)
            c.addView(tv("Kiedy", 13f, color = DIM), lp(top = 12))
            c.addView(HorizontalScrollView(this).apply { addView(whenR) }, lp(top = 4))
            val adR = hbox().apply { setPadding(0, dp(6), 0, dp(6)) }
            adR.addView(tv("Cały dzień", 15f), lp(0, -2, 1f))
            adR.addView(Switch(this).apply { isChecked = e.allDay; setOnCheckedChangeListener { _, b -> e.allDay = b; refresh() } })
            c.addView(adR)
            refresh()

            // powtarzanie
            val params = vbox()
            fun drawParams() {
                params.removeAllViews()
                fun stepper(label: String, v: () -> Int, min: Int, max: Int, set: (Int) -> Unit, unit: String) {
                    val r = hbox().apply { setPadding(0, dp(4), 0, dp(4)) }
                    r.addView(tv(label, 14f), lp(0, -2, 1f))
                    val vt = tv("${v()} $unit", 14f, true)
                    r.addView(btn("−") { set((v() - 1).coerceAtLeast(min)); vt.text = "${v()} $unit" }, lp(-2, -2).apply { rightMargin = dp(6) })
                    r.addView(vt, lp(-2, -2).apply { rightMargin = dp(6) })
                    r.addView(btn("+") { set((v() + 1).coerceAtMost(max)); vt.text = "${v()} $unit" })
                    params.addView(r)
                }
                when (e.repeat) {
                    "hourly" -> {
                        stepper("Co ile godzin", { e.every }, 1, 12, { e.every = it }, "h")
                        stepper("Od godziny", { e.fromH }, 0, 23, { e.fromH = it }, ":00")
                        stepper("Do godziny", { e.toH }, 0, 23, { e.toH = it }, ":00")
                    }
                    "daily" -> stepper("Co ile dni", { e.every }, 1, 30, { e.every = it }, "dni")
                    "monthly" -> stepper("Co ile miesięcy", { e.every }, 1, 12, { e.every = it }, "mies.")
                    "weekly" -> stepper("Co ile tygodni", { e.every }, 1, 8, { e.every = it }, "tyg.")
                }
                if (e.repeat == "weekly" || e.repeat == "hourly") {
                    val dr = hbox()
                    val names = listOf("pn", "wt", "śr", "cz", "pt", "sb", "nd")
                    fun drawDays() {
                        dr.removeAllViews()
                        for (i in 0..6) {
                            val on = e.days and (1 shl i) != 0
                            dr.addView(tv(names[i], 13f, on, if (on) Theme.onAccent else FG).apply {
                                gravity = Gravity.CENTER; background = if (on) Theme.gradient(dp(14).toFloat()) else round(BG, 14, 1); setPadding(0, dp(7), 0, dp(7))
                                setOnClickListener { e.days = e.days xor (1 shl i); drawDays() }
                            }, lp(0, -2, 1f).apply { rightMargin = dp(3) })
                        }
                    }
                    drawDays()
                    params.addView(tv(if (e.repeat == "hourly") "W które dni (puste = codziennie)" else "W które dni", 12f, color = DIM), lp(top = 4))
                    params.addView(dr, lp(top = 4))
                }
            }
            chips("Powtarzaj", listOf("none" to "Nie", "hourly" to "Co kilka godzin", "daily" to "Codziennie", "weekly" to "Co tydzień", "monthly" to "Co miesiąc", "yearly" to "Co rok"), { e.repeat }) {
                e.repeat = it; if (e.every < 1) e.every = 1; drawParams()
            }
            c.addView(params)
            drawParams()

            // powiadomienia (kilka naraz)
            c.addView(tv("Powiadom", 13f, color = DIM), lp(top = 12))
            val ar = hbox()
            fun drawAl() {
                ar.removeAllViews()
                for ((m, n) in Rem.ALERTS) {
                    val on = m in e.alerts
                    ar.addView(tv(if (m == 0) n else "$n przed", 13f, on, if (on) Theme.onAccent else FG).apply {
                        background = if (on) Theme.gradient(dp(14).toFloat()) else round(BG, 14, 1); setPadding(dp(12), dp(7), dp(12), dp(7))
                        setOnClickListener { if (on) e.alerts.remove(m) else e.alerts.add(m); drawAl() }
                    }, lp(-2, -2).apply { rightMargin = dp(6) })
                }
            }
            drawAl()
            c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ar) }, lp(top = 4))

            val note = EditText(this).apply { setText(e.note); hint = "Notatka (miejsce, szczegóły)"; setTextColor(FG); setHintTextColor(DIM); background = round(BG, 14); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            c.addView(note, lp(top = 12))
            val br = hbox()
            br.addView(btn("Zapisz", true) {
                e.title = title.text.toString().trim().ifEmpty { Rem.TYPES.first { it.first == e.type }.second }
                e.note = note.text.toString().trim()
                if (e.allDay) { e.start = startOfDay(e.start); if (e.end < e.start) e.end = e.start }
                Rem.put(this, e); d.dismiss()
                calDay = startOfDay(e.start); calMonth.timeInMillis = calDay; calMonth.set(Calendar.DAY_OF_MONTH, 1)
                if (tab != "cal") toast("Zapisano w kalendarzu") else render()
            }, lp(0, -2, 1f).apply { rightMargin = dp(8) })
            if (e.id != 0L) br.addView(btn("Usuń") { Rem.remove(this, e.id); d.dismiss(); render() }, lp(0, -2, 1f))
            c.addView(br, lp(top = 14))
        }
    }

    // ================================================================== MINUTNIK I STOPER
    private var swStart = 0L
    private var swAcc = 0L
    private val laps = ArrayList<Long>()
    private var timerLabel: TextView? = null
    private var timerRing: ProgressRing? = null
    private var swLabel: TextView? = null
    private var timerSet = 5 * 60

    private val tick = object : Runnable {
        override fun run() {
            val end = pr().getLong("timerEnd", 0)
            timerLabel?.text = if (end > System.currentTimeMillis()) fmt((end - System.currentTimeMillis()) / 1000) else fmt(timerSet.toLong())
            if (end > System.currentTimeMillis()) timerRing?.let { r -> val v = (end - System.currentTimeMillis()) / 1000f / pr().getInt("timerTotal", timerSet).coerceAtLeast(1); r.big = ""; r.invalidate(); if (Math.abs(r.value - v) > 0.02f) r.value = v }
            swLabel?.text = swFmt(swAcc + if (swStart > 0) System.currentTimeMillis() - swStart else 0)
            h.postDelayed(this, 100)
        }
    }

    private fun fmt(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    private fun swFmt(ms: Long) = "%d:%02d,%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)

    private fun renderTimer() {
        val end = pr().getLong("timerEnd", 0)
        val running = end > System.currentTimeMillis()
        val tl = tv(fmt(if (running) (end - System.currentTimeMillis()) / 1000 else timerSet.toLong()), 56f, true).apply { gravity = Gravity.CENTER }
        timerLabel = tl
        val ringBox = android.widget.FrameLayout(this)
        val ring = ProgressRing(this)
        timerRing = ring
        ringBox.addView(ring, android.widget.FrameLayout.LayoutParams(-1, -1))
        ringBox.addView(tl, android.widget.FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.CENTER })
        tl.gravity = Gravity.CENTER
        col.addView(ringBox, lp(-1, dp(260), top = 10))
        ring.value = if (running) ((end - System.currentTimeMillis()) / 1000f / pr().getInt("timerTotal", timerSet).coerceAtLeast(1)) else 1f
        if (!running) {
            val r = hbox()
            for ((s, n) in listOf(60 to "1 min", 180 to "3 min", 300 to "5 min", 600 to "10 min", 900 to "15 min", 1800 to "30 min", 3600 to "1 h"))
                r.addView(btn(n, s == timerSet) { timerSet = s; render() }, lp(-2, -2).apply { rightMargin = dp(6) })
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 8))
            val adj = hbox()
            for ((d, n) in listOf(-60 to "−1 min", -10 to "−10 s", 10 to "+10 s", 60 to "+1 min")) adj.addView(btn(n) { timerSet = (timerSet + d).coerceAtLeast(10); render() }, lp(0, -2, 1f).apply { rightMargin = dp(6) })
            col.addView(adj, lp(top = 8))
            col.addView(btn("Start", true) { pr().edit().putInt("timerTotal", timerSet).apply(); Alarms.startTimer(this, timerSet); render() }, lp(top = 10))
        } else col.addView(btn("Zatrzymaj") { Alarms.stopTimer(this); render() }, lp(top = 10))
        h.post(tick)
    }

    private fun renderWatch() {
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

        header("Wygląd")
        col.addView(tv("Motyw", 14f, color = DIM), lp(top = 4))
        val tr = hbox()
        for (pz in Theme.PRESETS) {
            val (c1, c2) = Theme.colorsOf(this, pz)
            val on = (pr().getString("theme", "ocean") ?: "ocean") == pz.id
            val chip = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            chip.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { shape = GradientDrawable.OVAL; if (on) setStroke(dp(3), FG) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            chip.addView(tv(pz.name, 11f, on, if (on) FG else DIM))
            chip.setOnClickListener { pr().edit().putString("theme", pz.id).apply(); applyTheme(); render() }
            tr.addView(chip, LinearLayout.LayoutParams(dp(78), -2))
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tr) }, lp(top = 4))
        col.addView(tv("Tryb", 14f, color = DIM), lp(top = 10))
        val mr2 = hbox()
        for ((k, n) in Theme.MODES) mr2.addView(btn(n, (pr().getString("mode", "system") ?: "system") == k) { pr().edit().putString("mode", k).apply(); applyTheme(); render() }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(mr2) }, lp(top = 4))

        header("Kalendarz i przypomnienia")
        toggle("Pokazuj Kalendarz Google", "Wydarzenia z kalendarzy na telefonie (tylko do odczytu)", pr().getBoolean("sysCal", true)) {
            pr().edit().putBoolean("sysCal", it).apply()
            if (it && checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
        }
        col.addView(btn("Importuj urodziny z kontaktów") {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 3); return@btn }
            Thread { val n = Rem.importBirthdays(this); h.post { toast(if (n > 0) "Dodano urodziny: $n" else "Brak nowych urodzin w kontaktach") } }.start()
        }, lp(-2, -2, top = 6))
        val adr = hbox()
        adr.addView(tv("Wpisy całodniowe przypominaj o", 14f), lp(0, -2, 1f))
        for (hh in listOf(7, 8, 9, 10)) adr.addView(btn("$hh:00", pr().getInt("allDayAt", 9) == hh) { pr().edit().putInt("allDayAt", hh).apply(); Rem.schedule(this); render() }, lp(-2, -2).apply { leftMargin = dp(4) })
        col.addView(adr, lp(top = 8))
        toggle("„Mój dzień” po wyłączeniu budzika", "Pogoda, dzisiejsze wpisy i urodziny zaraz po wstaniu", pr().getBoolean("myDay", true)) { pr().edit().putBoolean("myDay", it).apply() }
        col.addView(btn("Pora snu…") { sleepSheet() }, lp(-2, -2, top = 6))

        header("Widżety")
        val ws = pr().getString("widgetStyle", if (pr().getBoolean("widgetLight", false)) "light" else "weather") ?: "weather"
        val wr = hbox()
        for ((k, n) in listOf("weather" to "Niebo wg pogody", "glass" to "Szkło", "dark" to "Ciemne", "light" to "Jasne"))
            wr.addView(btn(n, ws == k) { pr().edit().putString("widgetStyle", k).apply(); Widgets.updateAll(this); render() }, lp(-2, -2).apply { rightMargin = dp(6) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(wr) }, lp(top = 4))
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
