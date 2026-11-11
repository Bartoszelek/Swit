package pl.swit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Wspólna logika widżetów: każdy widżet ma 6 układów i sam dobiera właściwy do swojego rozmiaru. */
object Widgets {
    private val WEATHER = listOf(WWeather1::class.java, WWeather2::class.java, WWeather::class.java, WClock::class.java, WForecast::class.java, WForecast3::class.java, WForecast4::class.java)
    private val ALARM = listOf(WAlarm1::class.java, WAlarm2::class.java)

    /** Styl widżetów: weather (kolor nieba wg pogody), dark, light, glass. */
    private fun style(c: Context) = Prefs.get(c).getString("widgetStyle", if (Prefs.get(c).getBoolean("widgetLight", false)) "light" else "weather") ?: "weather"
    private fun light(c: Context) = style(c) == "light"
    private fun fg(c: Context) = if (light(c)) Color.BLACK else Color.WHITE

    private fun open(c: Context, tab: String) = PendingIntent.getActivity(c, tab.hashCode(), Intent(c, MainActivity::class.java).putExtra("tab", tab)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        for (cls in WEATHER + ALARM + listOf(WMinimal::class.java)) {
            val ids = m.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isNotEmpty()) update(c, m, ids, false)
        }
    }

    /** Każdy widżet ma własny projekt (Ustawienia widżetu) – elementy, tło, przezroczystość, rozmiary. */
    fun update(c: Context, m: AppWidgetManager, ids: IntArray, @Suppress("UNUSED_PARAMETER") alarm: Boolean) {
        for (id in ids) try {
            val o = m.getAppWidgetOptions(id)
            m.updateAppWidget(id, WD.render(c, WD.get(c, id), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)))
        } catch (e: Exception) {}
    }

    /** Wszystkie widżety Świtu na ekranie: (id, nazwa rodzaju). */
    fun placed(c: Context): List<Pair<Int, String>> {
        val m = AppWidgetManager.getInstance(c)
        val names = mapOf(WWeather1::class.java to "Pogoda 1×1", WWeather2::class.java to "Pogoda 2×1", WWeather::class.java to "Pogoda 2×2",
            WClock::class.java to "Zegar i pogoda 4×1", WForecast::class.java to "Prognoza 4×2", WForecast3::class.java to "Prognoza 4×3",
            WForecast4::class.java to "Prognoza 4×4", WMinimal::class.java to "Zegar minimalistyczny", WAlarm1::class.java to "Budzik 1×1", WAlarm2::class.java to "Budzik 2×1")
        // pomijamy „duchy”: widżety, których dodanie się nie udało, a system dalej je pamięta (brak rozmiaru)
        return names.flatMap { (cls, n) -> m.getAppWidgetIds(ComponentName(c, cls)).filter { id ->
            m.getAppWidgetInfo(id) != null && m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) > 0
        }.map { id -> id to n } }
    }

    private fun text(rv: RemoteViews, c: Context, id: Int, s: String) { rv.setTextViewText(id, s); rv.setTextColor(id, fg(c)) }

    private fun icon(rv: RemoteViews, c: Context, id: Int, res: Int) {
        rv.setImageViewResource(id, res)
        if (light(c)) rv.setInt(id, "setColorFilter", Color.BLACK)
    }

    /** Kolorowa ikona pogody (rysowana – ostra w każdym rozmiarze). */
    private fun wicon(rv: RemoteViews, id: Int, code: Int, day: Boolean, px: Int) = rv.setImageViewBitmap(id, Art.icon(code, day, px))

    private fun base(c: Context, layout: Int, tab: String, f: Forecast? = null): RemoteViews {
        val rv = RemoteViews(c.packageName, layout)
        val bg = when (style(c)) {
            "light" -> R.drawable.widget_bg_light
            "dark" -> R.drawable.widget_bg
            "glass" -> R.drawable.widget_glass
            else -> if (f != null) Art.widgetBg(f.code, Art.phase(f)) else R.drawable.sky_night
        }
        rv.setInt(R.id.w_root, "setBackgroundResource", bg)
        rv.setOnClickPendingIntent(R.id.w_root, open(c, tab))
        return rv
    }

    private fun alarmLine(c: Context): String {
        val n = Alarms.nextAny(c) ?: return "Brak budzików"
        val cal = Calendar.getInstance().apply { timeInMillis = n.second }
        val today = Calendar.getInstance()
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val day = when {
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "dziś"
            cal.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR) -> "jutro"
            else -> SimpleDateFormat("EEE", Locale("pl")).format(Date(n.second))
        }
        return "⏰ ${n.first.time()} $day"
    }

    private fun weatherView(c: Context, layout: Int, f: Forecast?): RemoteViews {
        val rv = base(c, layout, "weather", f)
        val lt = light(c)
        val big = layout in listOf(R.layout.wg_4x2, R.layout.wg_4x3, R.layout.wg_4x4)
        // zegar i data są we wszystkich układach
        rv.setTextColor(R.id.w_clock, fg(c))
        if (layout != R.layout.wg_1x1) rv.setTextColor(R.id.w_date, fg(c))
        if (f == null) {
            text(rv, c, R.id.w_temp, "--°")
            rv.setImageViewBitmap(R.id.w_icon, Art.icon(3, true, 160, lt))
            if (layout != R.layout.wg_1x1 && layout != R.layout.wg_2x1) text(rv, c, R.id.w_desc, "Otwórz Świt")
            return rv
        }
        rv.setImageViewBitmap(R.id.w_icon, Art.icon(f.code, f.day, if (big) 200 else 150, lt))
        text(rv, c, R.id.w_temp, Weather.deg(f.temp))
        val d = f.days.firstOrNull()
        if (layout == R.layout.wg_2x2) {
            text(rv, c, R.id.w_desc, Weather.text(f.code))
            text(rv, c, R.id.w_hilo, if (d != null) "↑${Weather.deg(d.max)}  ↓${Weather.deg(d.min)}" else "")
        }
        if (layout == R.layout.wg_4x1) text(rv, c, R.id.w_desc, Weather.text(f.code) + if (d != null) " · ${Weather.deg(d.max)}/${Weather.deg(d.min)}" else "")
        if (big) {
            text(rv, c, R.id.w_desc, Weather.text(f.code))
            text(rv, c, R.id.w_place, f.place.name)
            text(rv, c, R.id.w_alarm, alarmLine(c))
            val nc = Weather.nowcast(f)
            text(rv, c, R.id.w_now, listOfNotNull(
                d?.let { "↑${Weather.deg(it.max)} ↓${Weather.deg(it.min)}" }, "odczuwalna ${Weather.deg(f.feels)}",
                if (nc.startsWith("Za ok.") || nc.startsWith("Pada") || nc.startsWith("Przestanie")) "☂ " + nc.lowercase() else d?.takeIf { it.pop >= 20 }?.let { "☂ ${it.pop}%" }).joinToString("  ·  "))
        }
        if (layout == R.layout.wg_4x3 || layout == R.layout.wg_4x4) {
            rv.removeAllViews(R.id.w_hours)
            val hf = SimpleDateFormat("H:mm", Locale("pl"))
            for ((i, h) in f.hours.drop(1).take(6).withIndex()) {
                val cell = RemoteViews(c.packageName, R.layout.wg_cell)
                text(cell, c, R.id.c_top, if (i == 0) "za 1 h" else hf.format(Date(h.t)))
                cell.setImageViewBitmap(R.id.c_icon, Art.icon(h.code, h.day, 84, lt))
                text(cell, c, R.id.c_bot, Weather.deg(h.temp))
                rv.addView(R.id.w_hours, cell)
            }
        }
        if (layout == R.layout.wg_4x4) {
            rv.removeAllViews(R.id.w_days)
            rv.setInt(R.id.w_sep, "setBackgroundColor", if (lt) 0x22000000 else 0x33FFFFFF)
            val dn = SimpleDateFormat("EEE", Locale("pl"))
            for ((i, day) in f.days.take(5).withIndex()) {
                val cell = RemoteViews(c.packageName, R.layout.wg_cell)
                text(cell, c, R.id.c_top, if (i == 0) "dziś" else dn.format(Date(day.t)))
                cell.setImageViewBitmap(R.id.c_icon, Art.icon(day.code, true, 84, lt))
                text(cell, c, R.id.c_bot, "${Weather.deg(day.max)} ${Weather.deg(day.min)}")
                rv.addView(R.id.w_days, cell)
            }
        }
        return rv
    }

    /** Minimalistyczny: przezroczyste tło, duży zegar, pod nim data i pogoda. */
    fun minimal(c: Context, m: AppWidgetManager, ids: IntArray) {
        val f = Weather.cached(c)
        for (id in ids) {
            val rv = RemoteViews(c.packageName, R.layout.wg_mini)
            rv.setOnClickPendingIntent(R.id.w_root, open(c, "weather"))
            if (f != null) {
                rv.setImageViewBitmap(R.id.w_icon, Art.icon(f.code, f.day, 110))
                rv.setTextViewText(R.id.w_temp, Weather.deg(f.temp))
                rv.setTextViewText(R.id.w_desc, Weather.text(f.code))
                val d = f.days.firstOrNull()
                val nc = Weather.nowcast(f)
                rv.setTextViewText(R.id.w_now, listOfNotNull(d?.let { "↑${Weather.deg(it.max)}  ↓${Weather.deg(it.min)}" },
                    if (nc.startsWith("Za ok.") || nc.startsWith("Przestanie")) "☂ " + nc.lowercase() else null,
                    Alarms.nextAny(c)?.let { "⏰ ${it.first.time()}" }).joinToString("   ·   "))
            } else rv.setTextViewText(R.id.w_temp, "--°")
            m.updateAppWidget(id, rv)
        }
    }

    private fun alarmView(c: Context, layout: Int): RemoteViews {
        val rv = base(c, layout, "alarms", Weather.cached(c))
        icon(rv, c, R.id.a_icon, R.drawable.ic_alarm)
        val n = Alarms.nextAny(c)
        if (n == null) { text(rv, c, R.id.a_time, "—"); text(rv, c, R.id.a_day, "brak budzików") }
        else {
            text(rv, c, R.id.a_time, n.first.time())
            text(rv, c, R.id.a_day, alarmLine(c).substringAfter(n.first.time()).trim() + if (layout == R.layout.wa_2x1 && n.first.label.isNotBlank()) " · ${n.first.label}" else "")
        }
        if (layout == R.layout.wa_2x1) {
            rv.setTextColor(R.id.a_nap, fg(c))
            rv.setOnClickPendingIntent(R.id.a_nap, PendingIntent.getBroadcast(c, 9500, Intent(c, AlarmReceiver::class.java).setAction("NAP").putExtra("min", 20),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return rv
    }
}

abstract class BaseWidget(private val alarm: Boolean) : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        Widgets.update(c, m, ids, alarm)
        if (!alarm && Weather.cached(c) == null) Thread { try { Weather.refresh(c); Widgets.updateAll(c) } catch (e: Exception) {} }.start()
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: android.os.Bundle) = Widgets.update(c, m, intArrayOf(id), alarm)

    override fun onEnabled(c: Context) { Notify.schedule(c) }

    override fun onDeleted(c: Context, ids: IntArray) { val e = Prefs.get(c).edit(); for (id in ids) e.remove("wd_$id"); e.apply() }
}

class WWeather1 : BaseWidget(false)
class WWeather2 : BaseWidget(false)
class WWeather : BaseWidget(false)
class WClock : BaseWidget(false)
class WForecast : BaseWidget(false)
class WForecast3 : BaseWidget(false)
class WForecast4 : BaseWidget(false)
class WAlarm1 : BaseWidget(true)
class WAlarm2 : BaseWidget(true)

class WMinimal : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        Widgets.update(c, m, ids, false)
        if (Weather.cached(c) == null) Thread { try { Weather.refresh(c); Widgets.updateAll(c) } catch (e: Exception) {} }.start()
    }

    override fun onEnabled(c: Context) { Notify.schedule(c) }

    override fun onDeleted(c: Context, ids: IntArray) { val e = Prefs.get(c).edit(); for (id in ids) e.remove("wd_$id"); e.apply() }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: android.os.Bundle) = Widgets.update(c, m, intArrayOf(id), false)
}
