package pl.swit

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** „Zaawansowana pogoda”: mapy jak dla żeglarzy, wykresy godzinowe i prognoza na 40+ dni. Ładuje się dopiero po otwarciu. */
class AdvActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var body: FrameLayout
    private lateinit var tabs: LinearLayout
    private var tab = "map"
    private var data: Adv? = null
    private var err = ""
    private var model = "best_match"
    private var hoursRange = 168
    private lateinit var place: Place

    // mapa
    private var web: WebView? = null
    private var overlay = "rain"
    private var product = "ecmwf"
    private var zoom = 7

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int = -1, hh: Int = -2, weight: Float = 0f, top: Int = 0) = LinearLayout.LayoutParams(w, hh, weight).apply { topMargin = dp(top) }
    private fun tv(t: String, s: Float = 14f, bold: Boolean = false, c: Int = Theme.fg) = TextView(this).apply { text = t; textSize = s; setTextColor(c); if (bold) typeface = Typeface.DEFAULT_BOLD }
    private fun round(c: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat(); if (stroke > 0) setStroke(dp(stroke), Theme.line) }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun chip(t: String, on: Boolean, f: () -> Unit) = tv(t, 13f, on, if (on) Theme.onAccent else Theme.fg).apply {
        maxLines = 1; setPadding(dp(12), dp(7), dp(12), dp(7)); background = if (on) Theme.gradient(dp(14).toFloat()) else round(Theme.surface, 14, 1); setOnClickListener { f() }
    }
    private fun chipRow(items: List<Pair<String, String>>, cur: String, f: (String) -> Unit): View {
        val r = hbox().apply { setPadding(dp(10), dp(6), dp(10), dp(6)) }
        for ((k, n) in items) r.addView(chip(n, k == cur) { f(k) }, lp(-2, -2).apply { rightMargin = dp(6) })
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.apply(this, Prefs.get(this).getString("theme", "ocean") ?: "ocean", Prefs.get(this).getString("mode", "system") ?: "system")
        window.statusBarColor = Theme.bg; window.navigationBarColor = Theme.surface
        val f = Weather.cached(this)
        place = f?.place ?: Weather.current(this)
        model = Prefs.get(this).getString("advModel", "best_match") ?: "best_match"
        overlay = Prefs.get(this).getString("mapOverlay", "rain") ?: "rain"
        val root = vbox().apply { setBackgroundColor(Theme.bg) }
        val top = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(6)) }
        top.addView(tv("‹", 28f, true).apply { setPadding(0, 0, dp(14), 0); setOnClickListener { finish() } })
        val tl = vbox()
        tl.addView(tv("Zaawansowana pogoda", 19f, true))
        tl.addView(tv("📍 " + place.name, 12f, c = Theme.dim))
        top.addView(tl, lp(0, -2, 1f))
        root.addView(top)
        tabs = hbox().apply { setPadding(dp(10), dp(2), dp(10), dp(6)) }
        root.addView(tabs)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
        load(false)
    }

    private fun load(force: Boolean) {
        err = ""
        Thread {
            val d = try { AdvWeather.load(this, place, model, force) } catch (e: Exception) { err = e.message ?: "brak połączenia"; null }
            h.post { data = d ?: data; if (tab != "map") render() }
        }.start()
    }

    private fun render() {
        tabs.removeAllViews()
        for ((k, n) in listOf("map" to "Mapa", "hours" to "Godziny", "days" to "40 dni")) tabs.addView(tv(n, 14f, k == tab, if (k == tab) Theme.onAccent else Theme.dim).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9)); background = if (k == tab) Theme.gradient(dp(16).toFloat()) else null
            setOnClickListener { tab = k; render() }
        }, lp(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        if (tab == "map") { showMap(); return }
        web?.visibility = View.GONE
        body.findViewWithTag<View>("content")?.let { body.removeView(it) }
        val sv = ScrollView(this).apply { tag = "content" }
        val col = vbox().apply { setPadding(dp(12), dp(4), dp(12), dp(30)) }
        sv.addView(col)
        body.addView(sv, FrameLayout.LayoutParams(-1, -1))
        val d = data
        if (d == null) {
            col.addView(if (err.isEmpty()) ProgressBar(this) else tv("Nie udało się pobrać: $err", 15f), lp(-2, -2, top = 40).apply { gravity = Gravity.CENTER_HORIZONTAL })
            if (err.isEmpty()) col.addView(tv("Pobieram szczegółową prognozę…", 14f, c = Theme.dim).apply { gravity = Gravity.CENTER }, lp(top = 10))
            return
        }
        col.addView(chipRow(AdvWeather.MODELS.map { it.key to it.value }, model) { m ->
            model = m; Prefs.get(this).edit().putString("advModel", m).apply(); data = null; render(); load(false)
        })
        if (tab == "hours") hours(col, d) else days(col, d)
        col.addView(tv("Dane: Open-Meteo (${AdvWeather.MODELS[model]})" + (if (d.xSource.isNotEmpty()) ", trend: ${d.xSource}" else "") +
            " · aktualizacja " + SimpleDateFormat("H:mm", Locale("pl")).format(Date(d.at)), 11f, c = Theme.dim), lp(top = 14))
    }

    private fun card(col: LinearLayout, v: View, height: Int) {
        val box = FrameLayout(this).apply { background = round(Theme.surface, 18); setPadding(0, dp(6), 0, dp(4)) }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(v, FrameLayout.LayoutParams(-2, dp(height))) })
        col.addView(box, lp(top = 10))
    }

    // ------------------------------------------------------------ godziny
    private fun hours(col: LinearLayout, d: Adv) {
        col.addView(chipRow(listOf("48" to "48 godzin", "168" to "7 dni", "384" to "16 dni"), hoursRange.toString()) { hoursRange = it.toInt(); render() })
        val now = System.currentTimeMillis() - 3600_000L
        val from = d.ht.indexOfFirst { it >= now }.coerceAtLeast(0)
        val to = (from + hoursRange).coerceAtMost(d.ht.size)
        fun cut(a: DoubleArray) = a.copyOfRange(from, to)
        val xs = d.ht.copyOfRange(from, to)
        val step = when (hoursRange) { 48 -> 22f; 168 -> 10f; else -> 5f }
        fun chart(title: String, s: List<Series>, hgt: Int, setup: ChartView.() -> Unit = {}) {
            val c = ChartView(this).apply { this.xs = xs; this.series = s; this.step = step; this.title = title; setup() }
            card(col, c, hgt)
        }
        chart("Temperatura (°C)", listOf(Series("Temperatura", cut(d.temp), 0xFFFF9F0A.toInt(), unit = "°"),
            Series("Odczuwalna", cut(d.feels), 0xFFFFD60A.toInt(), "dash", "°"), Series("Punkt rosy", cut(d.dew), 0xFF30D158.toInt(), "dash", "°")), 210) {
            icons = d.code.copyOfRange(from, to); iconDay = d.day.copyOfRange(from, to); iconEvery = if (step >= 18) 2 else if (step >= 10) 4 else 8
        }
        chart("Opady (mm) i szansa (%)", listOf(Series("Opady", cut(d.rain), 0xFF0A84FF.toInt(), "bar", " mm", fmt = "%.1f"),
            Series("Śnieg", cut(d.snow), 0xFFE5E5EA.toInt(), "bar", " cm", fmt = "%.1f"), Series("Szansa", cut(d.prob), 0xFF64D2FF.toInt(), "dash", "%", axis = 1)), 170)
        chart("Wiatr i porywy (km/h)", listOf(Series("Wiatr", cut(d.wind), 0xFF5AC8FA.toInt(), unit = " km/h"),
            Series("Porywy", cut(d.gust), 0xFFFF453A.toInt(), "dash", " km/h")), 190) { arrows = cut(d.dir) }
        chart("Ciśnienie (hPa)", listOf(Series("Ciśnienie", cut(d.press), 0xFFBF5AF2.toInt(), unit = " hPa")), 150)
        chart("Zachmurzenie (%)", listOf(Series("Niskie", cut(d.cLow), 0xFF8E8E93.toInt(), "area", "%"), Series("Średnie", cut(d.cMid), 0xFFAEAEB2.toInt(), "area", "%"),
            Series("Wysokie", cut(d.cHigh), 0xFFD1D1D6.toInt(), "area", "%")), 150)
        chart("Wilgotność (%) i UV", listOf(Series("Wilgotność", cut(d.rh), 0xFF64D2FF.toInt(), unit = "%"), Series("UV", cut(d.uv), 0xFFFFD60A.toInt(), "bar", axis = 1, fmt = "%.1f")), 150)
        chart("Burze: energia CAPE (J/kg) i widoczność (km)", listOf(Series("CAPE", cut(d.cape), 0xFFFF375F.toInt(), "bar", " J/kg"),
            Series("Widoczność", DoubleArray(to - from) { d.vis[from + it] / 1000.0 }, 0xFF98989D.toInt(), "line", " km", axis = 1, fmt = "%.1f")), 150)
        col.addView(tv("Wskazówka: przesuń palcem po wykresie, żeby zobaczyć dokładne wartości. CAPE powyżej 1000 J/kg oznacza warunki do burz.", 11f, c = Theme.dim), lp(top = 8))

        // tabela godzinowa (najbliższe 48 h)
        col.addView(tv("GODZINA PO GODZINIE", 13f, true, Theme.dim).apply { letterSpacing = 0.08f }, lp(top = 18))
        val hf = SimpleDateFormat("EEE H:mm", Locale("pl"))
        for (i in from until (from + 48).coerceAtMost(d.ht.size)) {
            val r = hbox().apply { setPadding(0, dp(6), 0, dp(6)) }
            r.addView(tv(hf.format(Date(d.ht[i])), 12f, c = Theme.dim), lp(dp(70), -2))
            r.addView(ImageView(this).apply { setImageBitmap(Art.icon(d.code[i], d.day[i], dp(26), !Theme.dark)) }, LinearLayout.LayoutParams(dp(26), dp(26)))
            r.addView(tv(Weather.deg(d.temp[i]), 15f, true), lp(dp(44), -2).apply { leftMargin = dp(6) })
            r.addView(tv((if (d.rain[i] > 0) String.format(Locale.US, "%.1f mm", d.rain[i]) else "—") + " · ${d.prob[i].ri()}%", 12f, c = 0xFF64D2FF.toInt()), lp(dp(92), -2))
            r.addView(tv("${d.wind[i].ri()}/${d.gust[i].ri()} km/h ${Weather.windDir(d.dir[i].ri())}", 12f, c = Theme.dim), lp(0, -2, 1f))
            r.addView(tv("${d.press[i].ri()}", 11f, c = Theme.dim))
            col.addView(r)
        }
    }

    // ------------------------------------------------------------ 40 dni
    private fun days(col: LinearLayout, d: Adv) {
        // 1–16 dni: dokładna prognoza; dalej trend z ensemble (średnia + zakres 80% scenariuszy)
        val lastMain = d.dt.lastOrNull() ?: 0L
        val ext = d.xt.indices.filter { d.xt[it] > lastMain }
        val n = d.dt.size + ext.size
        val xs = LongArray(n) { if (it < d.dt.size) d.dt[it] else d.xt[ext[it - d.dt.size]] }
        val dayT = DoubleArray(n) { if (it < d.dt.size) d.dMax[it] else d.xMax[ext[it - d.dt.size]] }
        val nightT = DoubleArray(n) { if (it < d.dt.size) d.night(it).takeIf { v -> !v.isNaN() } ?: d.dMin[it] else d.xMin[ext[it - d.dt.size]] }
        val lo = DoubleArray(n) { if (it < d.dt.size) Double.NaN else d.xMaxLo[ext[it - d.dt.size]] }
        val hi = DoubleArray(n) { if (it < d.dt.size) Double.NaN else d.xMaxHi[ext[it - d.dt.size]] }
        val sum = DoubleArray(n) { if (it < d.dt.size) d.dSum[it] else d.xSum[ext[it - d.dt.size]] }
        col.addView(tv("${n} dni: dokładna prognoza na ${d.dt.size} dni, dalej trend z ${d.xSource.ifEmpty { "ensemble" }}", 12f, c = Theme.dim), lp(top = 6))
        val c1 = ChartView(this).apply {
            this.xs = xs; daily = true; step = 30f; title = "Temperatura w dzień i w nocy (°C)"
            series = listOf(Series("Dzień", dayT, 0xFFFF9F0A.toInt(), unit = "°"), Series("Noc", nightT, 0xFF0A84FF.toInt(), unit = "°"),
                Series("Zakres dnia (80% scenariuszy)", lo, 0xFFFF9F0A.toInt(), "band", "°", hi = hi))
            icons = IntArray(n) { if (it < d.dt.size) d.dCode[it] else 3 }.also { a -> for (k in d.dt.size until n) a[k] = if (sum[k] > 3) 61 else if (sum[k] > 0.5) 2 else 1 }
            iconEvery = 1; marker = if (ext.isNotEmpty()) d.dt.size else -1; markerLabel = "trend"
        }
        card(col, c1, 230)
        val c2 = ChartView(this).apply {
            this.xs = xs; daily = true; step = 30f; title = "Opady w ciągu doby (mm)"
            series = listOf(Series("Opady", sum, 0xFF0A84FF.toInt(), "bar", " mm", fmt = "%.1f"),
                Series("Szansa", DoubleArray(n) { if (it < d.dt.size) d.dProb[it] else Double.NaN }, 0xFF64D2FF.toInt(), "dash", "%", axis = 1))
            marker = if (ext.isNotEmpty()) d.dt.size else -1; markerLabel = "trend"
        }
        card(col, c2, 170)
        val c3 = ChartView(this).apply {
            this.xs = d.dt; daily = true; step = 30f; title = "Wiatr maks. i porywy (km/h)"
            series = listOf(Series("Wiatr", d.dWind, 0xFF5AC8FA.toInt(), unit = " km/h"), Series("Porywy", d.dGust, 0xFFFF453A.toInt(), "dash", " km/h"))
            arrows = d.dDir
        }
        card(col, c3, 170)

        // lista dni
        col.addView(tv("DZIEŃ PO DNIU", 13f, true, Theme.dim).apply { letterSpacing = 0.08f }, lp(top = 18))
        val df = SimpleDateFormat("EEE d.MM", Locale("pl")); val sf = SimpleDateFormat("H:mm", Locale("pl"))
        for (i in d.dt.indices) {
            val box = vbox().apply { setPadding(dp(12), dp(10), dp(12), dp(10)); background = round(Theme.surface, 14) }
            val r = hbox()
            r.addView(tv(if (i == 0) "Dziś" else if (i == 1) "Jutro" else df.format(Date(d.dt[i])), 15f, true), lp(dp(78), -2))
            r.addView(ImageView(this).apply { setImageBitmap(Art.icon(d.dCode[i], true, dp(32), !Theme.dark)) }, LinearLayout.LayoutParams(dp(32), dp(32)))
            r.addView(tv(Weather.text(d.dCode[i]), 13f), lp(0, -2, 1f).apply { leftMargin = dp(8) })
            r.addView(tv("${Weather.deg(d.dMax[i])} / ${Weather.deg(nightT[i])}", 16f, true))
            box.addView(r)
            box.addView(tv(listOfNotNull(
                "☂ " + String.format(Locale.US, "%.1f mm", d.dSum[i]) + (if (d.dHours[i] > 0) " (${d.dHours[i].ri()} h)" else "") + " · ${d.dProb[i].ri()}%",
                if (d.dSnow[i] > 0) String.format(Locale.US, "❄ %.1f cm", d.dSnow[i]) else null,
                "💨 ${d.dWind[i].ri()}/${d.dGust[i].ri()} km/h ${Weather.windDir(d.dDir[i].ri())}",
                "☀ ${(d.dSun[i] / 3600).ri()} h słońca", "UV ${d.dUv[i].ri()}",
                "🌅 ${sf.format(Date(d.dRise[i]))}–${sf.format(Date(d.dSet[i]))}",
                "odczuwalna ${Weather.deg(d.dFMax[i])}/${Weather.deg(d.dFMin[i])}").joinToString("  ·  "), 12f, c = Theme.dim), lp(top = 4))
            col.addView(box, lp(top = 6))
        }
        if (ext.isNotEmpty()) {
            col.addView(tv("TREND (MNIEJ PEWNY)", 13f, true, Theme.dim).apply { letterSpacing = 0.08f }, lp(top = 16))
            col.addView(tv("Średnia z wielu przebiegów modelu. Pokazuje kierunek (cieplej/zimniej, mokro/sucho), nie dokładną pogodę danego dnia.", 11f, c = Theme.dim), lp(top = 2))
            for (k in ext) {
                val r = hbox().apply { setPadding(dp(4), dp(7), dp(4), dp(7)) }
                r.addView(tv(df.format(Date(d.xt[k])), 13f), lp(dp(78), -2))
                r.addView(tv("${Weather.deg(d.xMax[k])} / ${Weather.deg(d.xMin[k])}", 14f, true), lp(dp(80), -2))
                r.addView(tv("(${Weather.deg(d.xMaxLo[k])}…${Weather.deg(d.xMaxHi[k])})", 11f, c = Theme.dim), lp(0, -2, 1f))
                r.addView(tv(String.format(Locale.US, "☂ %.1f mm", d.xSum[k]), 12f, c = 0xFF64D2FF.toInt()))
                col.addView(r)
            }
        }
    }

    // ------------------------------------------------------------ mapa (Windy – jak na jachtach) i radar
    private fun mapUrl(): String {
        if (overlay == "radar") return "https://www.rainviewer.com/map.html?loc=${place.lat},${place.lon},$zoom&oFa=1&oC=1&oU=0&oCS=1&oF=0&oAP=1&c=3&o=83&lm=1&layer=radar&sm=1&sn=1"
        return "https://embed.windy.com/embed.html?type=map&location=coordinates&metricRain=mm&metricTemp=%C2%B0C&metricWind=km%2Fh" +
            "&zoom=$zoom&overlay=$overlay&product=$product&level=surface&lat=${place.lat}&lon=${place.lon}&detailLat=${place.lat}&detailLon=${place.lon}" +
            "&marker=true&message=true&pressure=${if (overlay == "pressure") "true" else "false"}&detail=false"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showMap() {
        body.findViewWithTag<View>("content")?.let { body.removeView(it) }
        val wrap = vbox().apply { tag = "content" }
        wrap.addView(chipRow(listOf("rain" to "☂ Opady", "wind" to "💨 Wiatr", "gust" to "Porywy", "clouds" to "☁ Chmury", "temp" to "🌡 Temperatura",
            "pressure" to "Ciśnienie", "thunder" to "⚡ Burze", "waves" to "🌊 Fale", "radar" to "📡 Radar na żywo"), overlay) {
            overlay = it; Prefs.get(this).edit().putString("mapOverlay", it).apply(); render(); reloadMap()
        })
        val zr = hbox().apply { setPadding(dp(10), 0, dp(10), dp(4)) }
        for ((z, n) in listOf(10 to "Okolica", 8 to "Region", 6 to "Kraj", 4 to "Europa")) zr.addView(chip(n, zoom == z) { zoom = z; render(); reloadMap() }, lp(-2, -2).apply { rightMargin = dp(6) })
        if (overlay != "radar") for ((p, n) in listOf("ecmwf" to "ECMWF", "gfs" to "GFS", "iconEu" to "ICON")) zr.addView(chip(n, product == p) { product = p; render(); reloadMap() }, lp(-2, -2).apply { rightMargin = dp(6) })
        wrap.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(zr) })
        wrap.addView(tv("Przybliżaj dwoma palcami. Na dole mapy jest oś czasu – przesuń ją lub stuknij ▶, żeby zobaczyć godzinę po godzinie.", 11f, c = Theme.dim).apply { setPadding(dp(12), 0, dp(12), dp(4)) })
        body.addView(wrap, FrameLayout.LayoutParams(-1, -2))
        // mapa tworzona dopiero przy pierwszym wejściu
        val w = web ?: WebView(this).also { wv ->
            wv.settings.javaScriptEnabled = true; wv.settings.domStorageEnabled = true
            wv.settings.loadWithOverviewMode = true; wv.settings.useWideViewPort = true
            wv.settings.builtInZoomControls = false
            wv.webViewClient = WebViewClient(); wv.webChromeClient = WebChromeClient()
            wv.setBackgroundColor(Color.BLACK)
            web = wv
            body.addView(wv, 0, FrameLayout.LayoutParams(-1, -1))
            wv.loadUrl(mapUrl())
        }
        w.visibility = View.VISIBLE
        wrap.post { (w.layoutParams as FrameLayout.LayoutParams).topMargin = wrap.height; w.requestLayout() }
    }

    private fun reloadMap() { web?.loadUrl(mapUrl()) }

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }
}
