package pl.swit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Wygląd jednego widżetu – każdy widżet na ekranie ma własny. */
class Design(
    var items: MutableList<String> = mutableListOf(),   // kolejność elementów
    var off: MutableSet<String> = mutableSetOf(),        // elementy wyłączone
    var bg: String = "sky",                              // sky, theme, dark, light, color, none
    var color: Int = 0xFF1E293B.toInt(),
    var alpha: Int = 90,                                  // nieprzezroczystość tła 0–100
    var radius: Int = 24,                                 // 0, 12, 24, 36
    var text: String = "auto",                            // auto, white, black
    var scale: Int = 100,                                 // wielkość tekstu w %
    var clock: Int = 56,                                  // wielkość zegara (sp)
    var icon: Int = 48,                                   // wielkość ikony pogody (dp)
    var align: String = "start",                          // start, center, end
    var valign: String = "center",                        // top, center, bottom
    var pad: Int = 14,                                    // odstęp od krawędzi (dp)
    var hours: Int = 6, var days: Int = 5, var events: Int = 3,
    var shadow: Boolean = true,
    var seconds: Boolean = false,
    var free: Boolean = false,                            // układ swobodny: elementy w dowolnym miejscu
    var pos: MutableMap<String, FloatArray> = mutableMapOf()  // klucz → [x 0–1, y 0–1, skala]
) {
    fun json(): JSONObject = JSONObject().put("items", JSONArray(items)).put("off", JSONArray(off.toList())).put("bg", bg).put("color", color)
        .put("alpha", alpha).put("radius", radius).put("text", text).put("scale", scale).put("clock", clock).put("icon", icon)
        .put("align", align).put("valign", valign).put("pad", pad).put("hours", hours).put("days", days).put("events", events)
        .put("shadow", shadow).put("seconds", seconds).put("free", free)
        .put("pos", JSONObject().also { o -> for ((k, v) in pos) o.put(k, JSONArray(v.map { it.toDouble() })) })

    fun copy() = of(json())

    val shown get() = items.filter { it !in off }

    companion object {
        fun of(o: JSONObject): Design {
            val d = Design()
            o.optJSONArray("items")?.let { a -> d.items = (0 until a.length()).map { a.getString(it) }.toMutableList() }
            o.optJSONArray("off")?.let { a -> d.off = (0 until a.length()).map { a.getString(it) }.toMutableSet() }
            d.bg = o.optString("bg", d.bg); d.color = o.optInt("color", d.color); d.alpha = o.optInt("alpha", d.alpha); d.radius = o.optInt("radius", d.radius)
            d.text = o.optString("text", d.text); d.scale = o.optInt("scale", d.scale); d.clock = o.optInt("clock", d.clock); d.icon = o.optInt("icon", d.icon)
            d.align = o.optString("align", d.align); d.valign = o.optString("valign", d.valign); d.pad = o.optInt("pad", d.pad)
            d.hours = o.optInt("hours", d.hours); d.days = o.optInt("days", d.days); d.events = o.optInt("events", d.events)
            d.shadow = o.optBoolean("shadow", true); d.seconds = o.optBoolean("seconds"); d.free = o.optBoolean("free")
            o.optJSONObject("pos")?.let { po -> for (k in po.keys()) po.optJSONArray(k)?.let { a -> d.pos[k] = floatArrayOf(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2, 1.0).toFloat()) } }
            return d
        }
    }
}

object WD {
    /** Wszystkie elementy, które można pokazać na widżecie. */
    val ELEMENTS = linkedMapOf(
        "clockwx" to "Zegar i pogoda obok siebie", "clock" to "Zegar", "date" to "Data", "now" to "Pogoda teraz (ikona, temperatura, opis)",
        "hilo" to "Najwyżej / najniżej i odczuwalna", "place" to "Miejsce", "rain" to "Opady za chwilę", "warn" to "Ostrzeżenia pogodowe",
        "hours" to "Prognoza godzinowa", "days" to "Prognoza na dni", "alarm" to "Najbliższy budzik", "alarms" to "Budziki na dziś i jutro",
        "events" to "Kalendarz: najbliższe wpisy", "bed" to "Pora snu", "nap" to "Przycisk: drzemka 20 min"
    )

    /** Gotowe układy. */
    val PRESETS = linkedMapOf(
        "mini" to Design(mutableListOf("clock", "date", "now", "hilo", "alarm"), bg = "none", alpha = 0, align = "center", clock = 88, icon = 34, shadow = true),
        "glance" to Design(mutableListOf("clockwx", "date", "hilo", "rain", "alarm")),
        "full" to Design(mutableListOf("clockwx", "date", "hilo", "rain", "hours", "days")),
        "day" to Design(mutableListOf("clock", "date", "now", "alarms", "events", "warn"), bg = "dark", alpha = 70),
        "small" to Design(mutableListOf("now"), align = "center", icon = 34),
        "alarm" to Design(mutableListOf("alarm", "alarms", "nap"), bg = "dark", alpha = 75, align = "center")
    )
    val PRESET_NAMES = linkedMapOf("mini" to "Minimalistyczny", "glance" to "Zegar i pogoda", "full" to "Pełna prognoza", "day" to "Mój dzień",
        "small" to "Mała pogoda", "alarm" to "Budzik")

    /** Domyślny wygląd zależny od rodzaju widżetu, który dodano. */
    fun defaultFor(cls: String): Design {
        val key = when (cls.substringAfterLast('.')) {
            "WWeather1" -> "small"; "WWeather2" -> "small"; "WWeather" -> "glance"; "WClock" -> "glance"
            "WForecast" -> "glance"; "WForecast3", "WForecast4" -> "full"; "WMinimal" -> "mini"; "WAlarm1", "WAlarm2" -> "alarm"
            else -> "glance"
        }
        return PRESETS[key]!!.copy().also { d ->
            // pozostałe elementy dopisane na końcu jako wyłączone – łatwo je włączyć w ustawieniach
            for (k in ELEMENTS.keys) if (k !in d.items) { d.items.add(k); d.off.add(k) }
            if (cls.endsWith("WWeather2")) { d.items.remove("clockwx"); d.items.add(0, "clockwx"); d.off.remove("clockwx"); d.off.add("now"); d.clock = 34; d.icon = 36 }
            if (cls.endsWith("WClock")) { d.off.addAll(listOf("hilo", "rain", "alarm")); d.clock = 40; d.icon = 40 }
        }
    }

    fun get(c: Context, id: Int): Design {
        Prefs.get(c).getString("wd_$id", null)?.let { return try { Design.of(JSONObject(it)) } catch (e: Exception) { defaultFor("") } }
        val cls = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className ?: ""
        return defaultFor(cls)
    }

    fun save(c: Context, id: Int, d: Design) = Prefs.get(c).edit().putString("wd_$id", d.json().toString()).apply()

    // ------------------------------------------------------------ rysowanie
    private fun bgRes(d: Design, f: Forecast?): Int {
        val r = listOf(0, 12, 24, 36).minByOrNull { Math.abs(it - d.radius) } ?: 24
        val sky = if (f == null) "night" else when (Art.phase(f)) {
            0 -> "night"; 1 -> "dusk"
            else -> when (Art.scene(f.code)) { Scene.CLEAR -> "clear"; Scene.PARTLY -> "partly"; Scene.CLOUDY, Scene.FOG -> "cloudy"; Scene.SNOW -> "snow"; Scene.STORM -> "storm"; else -> "rain" }
        }
        val name = if (d.bg == "sky") "wb_${sky}_r$r" else "wb_solid_r$r"
        return when (name) {
            "wb_clear_r0" -> R.drawable.wb_clear_r0; "wb_clear_r12" -> R.drawable.wb_clear_r12; "wb_clear_r24" -> R.drawable.wb_clear_r24; "wb_clear_r36" -> R.drawable.wb_clear_r36
            "wb_partly_r0" -> R.drawable.wb_partly_r0; "wb_partly_r12" -> R.drawable.wb_partly_r12; "wb_partly_r24" -> R.drawable.wb_partly_r24; "wb_partly_r36" -> R.drawable.wb_partly_r36
            "wb_cloudy_r0" -> R.drawable.wb_cloudy_r0; "wb_cloudy_r12" -> R.drawable.wb_cloudy_r12; "wb_cloudy_r24" -> R.drawable.wb_cloudy_r24; "wb_cloudy_r36" -> R.drawable.wb_cloudy_r36
            "wb_rain_r0" -> R.drawable.wb_rain_r0; "wb_rain_r12" -> R.drawable.wb_rain_r12; "wb_rain_r24" -> R.drawable.wb_rain_r24; "wb_rain_r36" -> R.drawable.wb_rain_r36
            "wb_snow_r0" -> R.drawable.wb_snow_r0; "wb_snow_r12" -> R.drawable.wb_snow_r12; "wb_snow_r24" -> R.drawable.wb_snow_r24; "wb_snow_r36" -> R.drawable.wb_snow_r36
            "wb_storm_r0" -> R.drawable.wb_storm_r0; "wb_storm_r12" -> R.drawable.wb_storm_r12; "wb_storm_r24" -> R.drawable.wb_storm_r24; "wb_storm_r36" -> R.drawable.wb_storm_r36
            "wb_night_r0" -> R.drawable.wb_night_r0; "wb_night_r12" -> R.drawable.wb_night_r12; "wb_night_r24" -> R.drawable.wb_night_r24; "wb_night_r36" -> R.drawable.wb_night_r36
            "wb_dusk_r0" -> R.drawable.wb_dusk_r0; "wb_dusk_r12" -> R.drawable.wb_dusk_r12; "wb_dusk_r24" -> R.drawable.wb_dusk_r24; "wb_dusk_r36" -> R.drawable.wb_dusk_r36
            "wb_solid_r0" -> R.drawable.wb_solid_r0; "wb_solid_r12" -> R.drawable.wb_solid_r12; "wb_solid_r36" -> R.drawable.wb_solid_r36
            else -> R.drawable.wb_solid_r24
        }
    }

    private fun solid(c: Context, d: Design): Int = when (d.bg) {
        "dark" -> 0xFF0E0F12.toInt(); "light" -> Color.WHITE; "color" -> d.color
        "theme" -> {
            // kolor motywu bez przełączania motywu aplikacji (widżet działa w tym samym procesie)
            val id = Prefs.get(c).getString("theme", "ocean") ?: "ocean"
            Theme.PRESETS.firstOrNull { it.id == id }?.let { Theme.colorsOf(c, it).first } ?: 0xFF0A84FF.toInt()
        }
        else -> Color.WHITE
    }

    fun fg(c: Context, d: Design): Int = when (d.text) {
        "white" -> Color.WHITE; "black" -> 0xFF111111.toInt()
        else -> if (d.bg == "light" || (d.bg == "color" && luminance(d.color) > 0.6 && d.alpha > 40)) 0xFF111111.toInt() else Color.WHITE
    }

    private fun luminance(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255

    private fun open(c: Context, tab: String) = PendingIntent.getActivity(c, ("w" + tab).hashCode(), Intent(c, MainActivity::class.java).putExtra("tab", tab)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Buduje widżet według projektu. */
    fun render(c: Context, d: Design, wDp: Int = 0, hDp: Int = 0): RemoteViews {
        val f = Weather.cached(c)
        val fg = fg(c, d)
        val onLight = Color.red(fg) < 100
        val k = d.scale / 100f
        val freeMode = d.free && Build.VERSION.SDK_INT >= 31
        val rv = RemoteViews(c.packageName, if (freeMode) R.layout.wg_free else R.layout.wg_custom)
        rv.setOnClickPendingIntent(R.id.w_root, open(c, "weather"))
        // tło
        if (d.bg == "none" || d.alpha == 0) rv.setViewVisibility(R.id.w_bg, View.GONE)
        else {
            rv.setImageViewResource(R.id.w_bg, bgRes(d, f))
            if (d.bg != "sky") rv.setInt(R.id.w_bg, "setColorFilter", solid(c, d))
            rv.setInt(R.id.w_bg, "setImageAlpha", d.alpha * 255 / 100)
        }
        val hg = when (d.align) { "center" -> Gravity.CENTER_HORIZONTAL; "end" -> Gravity.END; else -> Gravity.START }
        val vg = when (d.valign) { "top" -> Gravity.TOP; "bottom" -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }
        val px = (d.pad * c.resources.displayMetrics.density).toInt()
        if (!freeMode) { rv.setInt(R.id.w_body, "setGravity", hg or vg); rv.setViewPadding(R.id.w_body, px, px, px, px) }
        var block = RemoteViews(c.packageName, R.layout.wr_col)
        var ks = 1f                                       // skala bieżącego elementu (układ swobodny)

        // cień pod tekstem tylko na ciemnym tle i gdy włączony – osobne układy bez cienia
        val sh = d.shadow && !onLight
        fun L(withShadow: Int, without: Int) = if (sh) withShadow else without
        fun txt(v: RemoteViews, id: Int, s: String, size: Float) {
            v.setTextViewText(id, s); v.setTextColor(id, fg); v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size * k * ks)
        }
        fun line(s: String, size: Float = 13f, tab: String = "weather") {
            if (s.isBlank()) return
            val v = RemoteViews(c.packageName, L(R.layout.wr_text, R.layout.wr_text_ns))
            txt(v, R.id.r_text, s, size)
            v.setOnClickPendingIntent(R.id.r_row, open(c, tab))
            block.addView(R.id.r_block, v)
        }
        fun clockStyle(v: RemoteViews) {
            v.setTextColor(R.id.r_clock, fg); v.setTextViewTextSize(R.id.r_clock, TypedValue.COMPLEX_UNIT_SP, d.clock * ks)
            if (d.seconds) { v.setCharSequence(R.id.r_clock, "setFormat24Hour", "H:mm:ss"); v.setCharSequence(R.id.r_clock, "setFormat12Hour", "h:mm:ss") }
            v.setOnClickPendingIntent(R.id.r_clock, open(c, "alarms"))
        }
        fun iconSize(v: RemoteViews, id: Int, dp: Int) {
            if (Build.VERSION.SDK_INT >= 31) { v.setViewLayoutWidth(id, dp * ks, TypedValue.COMPLEX_UNIT_DIP); v.setViewLayoutHeight(id, dp * ks, TypedValue.COMPLEX_UNIT_DIP) }
        }
        val dd = f?.days?.firstOrNull()
        for ((idx, key) in d.shown.withIndex()) {
        ks = if (freeMode) (d.pos[key]?.getOrNull(2) ?: 1f) else 1f
        block = RemoteViews(c.packageName, if (freeMode && key != "hours" && key != "days") R.layout.wr_colw else R.layout.wr_col)
        if (!freeMode) block.setInt(R.id.r_block, "setGravity", hg)
        when (key) {
            "clock" -> { val v = RemoteViews(c.packageName, L(R.layout.wr_clock, R.layout.wr_clock_ns)); clockStyle(v); block.addView(R.id.r_block, v) }
            "date" -> { val v = RemoteViews(c.packageName, L(R.layout.wr_date, R.layout.wr_date_ns)); v.setTextColor(R.id.r_date, fg); v.setTextViewTextSize(R.id.r_date, TypedValue.COMPLEX_UNIT_SP, 14f * k); block.addView(R.id.r_block, v) }
            "clockwx" -> {
                val v = RemoteViews(c.packageName, L(R.layout.wr_clockwx, R.layout.wr_clockwx_ns)); clockStyle(v)
                if (f != null) { v.setImageViewBitmap(R.id.r_icon, Art.icon(f.code, f.day, 180, onLight)); txt(v, R.id.r_temp, Weather.deg(f.temp), d.clock * 0.7f / k) }
                else txt(v, R.id.r_temp, "--°", d.clock * 0.7f / k)
                iconSize(v, R.id.r_icon, d.icon)
                v.setOnClickPendingIntent(R.id.r_icon, open(c, "weather")); v.setOnClickPendingIntent(R.id.r_temp, open(c, "weather"))
                block.addView(R.id.r_block, v)
            }
            "now" -> {
                val v = RemoteViews(c.packageName, L(R.layout.wr_now, R.layout.wr_now_ns))
                if (f != null) {
                    v.setImageViewBitmap(R.id.r_icon, Art.icon(f.code, f.day, 160, onLight))
                    txt(v, R.id.r_temp, Weather.deg(f.temp), 30f); txt(v, R.id.r_desc, Weather.text(f.code), 14f)
                } else { txt(v, R.id.r_temp, "--°", 30f); txt(v, R.id.r_desc, "Otwórz Świt", 14f) }
                iconSize(v, R.id.r_icon, d.icon)
                v.setOnClickPendingIntent(R.id.r_row, open(c, "weather"))
                block.addView(R.id.r_block, v)
            }
            "hilo" -> if (dd != null && f != null) line("↑${Weather.deg(dd.max)}  ↓${Weather.deg(dd.min)}  ·  odczuwalna ${Weather.deg(f.feels)}")
            "place" -> if (f != null) line("📍 " + f.place.name, 12f)
            "rain" -> if (f != null) Weather.nowcast(f).takeIf { it.startsWith("Za ok.") || it.startsWith("Pada") || it.startsWith("Przestanie") }
                ?.let { line("☂ $it") } ?: dd?.takeIf { it.pop >= 20 }?.let { line("☂ szansa opadów ${it.pop}%") }
            "warn" -> if (f != null) Weather.warnings(f).take(2).forEach { line("⚠ " + it.second, 12f) }
            "hours", "days" -> if (f != null) {
                val strip = RemoteViews(c.packageName, R.layout.wr_strip)
                val hf = SimpleDateFormat("H:mm", Locale("pl")); val dn = SimpleDateFormat("EEE", Locale("pl"))
                if (key == "hours") for ((i, h) in f.hours.drop(1).take(d.hours.coerceIn(2, 12)).withIndex()) {
                    val cell = RemoteViews(c.packageName, L(R.layout.wg_cell, R.layout.wg_cell_ns))
                    txt(cell, R.id.c_top, if (i == 0) "za 1 h" else hf.format(Date(h.t)), 11f)
                    cell.setImageViewBitmap(R.id.c_icon, Art.icon(h.code, h.day, 84, onLight)); txt(cell, R.id.c_bot, Weather.deg(h.temp), 13f)
                    strip.addView(R.id.r_strip, cell)
                } else for ((i, day) in f.days.take(d.days.coerceIn(2, 10)).withIndex()) {
                    val cell = RemoteViews(c.packageName, L(R.layout.wg_cell, R.layout.wg_cell_ns))
                    txt(cell, R.id.c_top, if (i == 0) "dziś" else dn.format(Date(day.t)), 11f)
                    cell.setImageViewBitmap(R.id.c_icon, Art.icon(day.code, true, 84, onLight)); txt(cell, R.id.c_bot, "${Weather.deg(day.max)} ${Weather.deg(day.min)}", 12f)
                    strip.addView(R.id.r_strip, cell)
                }
                block.addView(R.id.r_block, strip)
            }
            "alarm" -> line(Alarms.nextAny(c)?.let { (a, t) ->
                val m = (t - System.currentTimeMillis()) / 60_000
                "⏰ ${a.time()} ${dayWord(t)}" + (if (a.label.isNotBlank()) " · ${a.label}" else "") + " · za ${m / 60} h ${m % 60} min"
            } ?: "⏰ Brak budzików", 13f, "alarms")
            "alarms" -> {
                val end = System.currentTimeMillis() + 36 * 3600_000L
                val l = Alarms.all(c).mapNotNull { a -> Alarms.next(a).takeIf { it in 1..end }?.let { a to it } }.sortedBy { it.second }
                if (l.isEmpty()) line("Brak budzików do jutra", 12f, "alarms")
                for ((a, t) in l.take(4)) line("⏰ ${a.time()} ${dayWord(t)}" + if (a.label.isNotBlank()) " · ${a.label}" else "", 12f, "alarms")
            }
            "events" -> {
                val now = System.currentTimeMillis()
                val l = try { Rem.range(c, now, now + 7 * 86_400_000L).filter { it.end >= now || it.start >= now } } catch (e: Exception) { emptyList() }
                val hf = SimpleDateFormat("H:mm", Locale("pl"))
                if (l.isEmpty()) line("📅 Nic w kalendarzu na 7 dni", 12f, "cal")
                for (o in l.take(d.events.coerceIn(1, 8))) line(Rem.typeIcon(o.ev.type) + " " + dayWord(o.start) + (if (o.ev.allDay) "" else " " + hf.format(Date(o.start))) + " · " + o.ev.title, 12f, "cal")
            }
            "bed" -> Rem.bedtime(c)?.takeIf { it > System.currentTimeMillis() - 3600_000L }?.let { line("🌙 Pora snu " + SimpleDateFormat("H:mm", Locale("pl")).format(Date(it)), 12f, "alarms") }
            "nap" -> {
                val v = RemoteViews(c.packageName, R.layout.wr_btn)
                txt(v, R.id.r_btn, "💤 Drzemka 20 min", 13f)
                v.setOnClickPendingIntent(R.id.r_btn, PendingIntent.getBroadcast(c, 9500, Intent(c, AlarmReceiver::class.java).setAction("NAP").putExtra("min", 20),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                block.addView(R.id.r_block, v)
            }
        }
        if (freeMode) {
            val p = d.pos[key] ?: floatArrayOf(0.05f, 0.05f + 0.15f * idx, 1f)
            val w = if (wDp > 0) wDp else 300; val hh = if (hDp > 0) hDp else 200
            block.setViewLayoutMargin(R.id.r_block, RemoteViews.MARGIN_LEFT, p[0] * w, TypedValue.COMPLEX_UNIT_DIP)
            block.setViewLayoutMargin(R.id.r_block, RemoteViews.MARGIN_TOP, p[1] * hh, TypedValue.COMPLEX_UNIT_DIP)
            rv.addView(R.id.w_free, block)
        } else rv.addView(R.id.w_body, block)
        }
        return rv
    }

    private fun dayWord(t: Long): String {
        val a = Calendar.getInstance(); val b = Calendar.getInstance().apply { timeInMillis = t }
        val diff = (b.get(Calendar.YEAR) * 400 + b.get(Calendar.DAY_OF_YEAR)) - (a.get(Calendar.YEAR) * 400 + a.get(Calendar.DAY_OF_YEAR))
        return when (diff) { 0 -> "dziś"; 1 -> "jutro"; else -> SimpleDateFormat("EEE d.MM", Locale("pl")).format(Date(t)) }
    }
}
