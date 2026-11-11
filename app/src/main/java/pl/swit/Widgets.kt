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
    private val WEATHER = listOf(WWeather1::class.java, WWeather2::class.java, WWeather::class.java, WClock::class.java, WForecast::class.java, WForecast3::class.java)
    private val ALARM = listOf(WAlarm1::class.java, WAlarm2::class.java)

    /** Styl widżetów: weather (kolor nieba wg pogody), dark, light, glass. */
    private fun style(c: Context) = Prefs.get(c).getString("widgetStyle", if (Prefs.get(c).getBoolean("widgetLight", false)) "light" else "weather") ?: "weather"
    private fun light(c: Context) = style(c) == "light"
    private fun fg(c: Context) = if (light(c)) Color.BLACK else Color.WHITE

    private fun open(c: Context, tab: String) = PendingIntent.getActivity(c, tab.hashCode(), Intent(c, MainActivity::class.java).putExtra("tab", tab)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        for (cls in WEATHER + ALARM) {
            val ids = m.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isNotEmpty()) update(c, m, ids, cls in ALARM)
        }
    }

    fun update(c: Context, m: AppWidgetManager, ids: IntArray, alarm: Boolean) {
        val f = Weather.cached(c)
        for (id in ids) {
            val rv = if (Build.VERSION.SDK_INT >= 31) {
                if (alarm) RemoteViews(mapOf(SizeF(40f, 40f) to alarmView(c, R.layout.wa_1x1), SizeF(110f, 40f) to alarmView(c, R.layout.wa_2x1)))
                else RemoteViews(mapOf(
                    SizeF(40f, 40f) to weatherView(c, R.layout.wg_1x1, f),
                    SizeF(110f, 40f) to weatherView(c, R.layout.wg_2x1, f),
                    SizeF(110f, 100f) to weatherView(c, R.layout.wg_2x2, f),
                    SizeF(240f, 40f) to weatherView(c, R.layout.wg_4x1, f),
                    SizeF(240f, 100f) to weatherView(c, R.layout.wg_4x2, f),
                    SizeF(240f, 170f) to weatherView(c, R.layout.wg_4x3, f)))
            } else if (alarm) alarmView(c, R.layout.wa_2x1) else weatherView(c, R.layout.wg_2x2, f)
            m.updateAppWidget(id, rv)
        }
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
        if (f == null) {
            text(rv, c, R.id.w_temp, "--°")
            if (layout != R.layout.wg_1x1) try { text(rv, c, R.id.w_desc, "Otwórz Świt") } catch (e: Exception) {}
            icon(rv, c, R.id.w_icon, R.drawable.w_cloud)
            return rv
        }
        wicon(rv, R.id.w_icon, f.code, f.day, 160)
        text(rv, c, R.id.w_temp, Weather.deg(f.temp))
        val d = f.days.firstOrNull()
        when (layout) {
            R.layout.wg_2x1 -> { text(rv, c, R.id.w_desc, Weather.text(f.code)); text(rv, c, R.id.w_place, f.place.name) }
            R.layout.wg_2x2 -> {
                text(rv, c, R.id.w_place, f.place.name); text(rv, c, R.id.w_desc, Weather.text(f.code))
                text(rv, c, R.id.w_hilo, if (d != null) "↑${Weather.deg(d.max)} ↓${Weather.deg(d.min)}" + (if (d.pop >= 20) " · ☂${d.pop}%" else "") else "")
            }
            R.layout.wg_4x1 -> {
                text(rv, c, R.id.w_desc, Weather.text(f.code)); text(rv, c, R.id.w_alarm, alarmLine(c))
                rv.setTextColor(R.id.w_clock, fg(c)); rv.setTextColor(R.id.w_date, fg(c))
            }
            R.layout.wg_4x2, R.layout.wg_4x3 -> {
                text(rv, c, R.id.w_desc, Weather.text(f.code)); text(rv, c, R.id.w_place, f.place.name); text(rv, c, R.id.w_alarm, alarmLine(c))
                rv.setTextColor(R.id.w_clock, fg(c)); rv.setTextColor(R.id.w_date, fg(c))
                rv.removeAllViews(R.id.w_days)
                val dn = SimpleDateFormat("EEE", Locale("pl"))
                for ((i, day) in f.days.take(5).withIndex()) {
                    val cell = RemoteViews(c.packageName, R.layout.wg_cell)
                    text(cell, c, R.id.c_top, if (i == 0) "dziś" else dn.format(Date(day.t)))
                    wicon(cell, R.id.c_icon, day.code, true, 72)
                    text(cell, c, R.id.c_bot, "${Weather.deg(day.max)} ${Weather.deg(day.min)}")
                    rv.addView(R.id.w_days, cell)
                }
                if (layout == R.layout.wg_4x3) {
                    text(rv, c, R.id.w_now, Weather.nowcast(f).ifEmpty { Weather.summary(f) })
                    rv.removeAllViews(R.id.w_hours)
                    val hf = SimpleDateFormat("H:mm", Locale("pl"))
                    for (h in f.hours.filterIndexed { i, _ -> i % 2 == 1 }.take(6)) {
                        val cell = RemoteViews(c.packageName, R.layout.wg_cell)
                        text(cell, c, R.id.c_top, hf.format(Date(h.t)))
                        wicon(cell, R.id.c_icon, h.code, h.day, 72)
                        text(cell, c, R.id.c_bot, Weather.deg(h.temp) + if (h.pop >= 30) " ${h.pop}%" else "")
                        rv.addView(R.id.w_hours, cell)
                    }
                }
            }
        }
        return rv
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
}

class WWeather1 : BaseWidget(false)
class WWeather2 : BaseWidget(false)
class WWeather : BaseWidget(false)
class WClock : BaseWidget(false)
class WForecast : BaseWidget(false)
class WForecast3 : BaseWidget(false)
class WAlarm1 : BaseWidget(true)
class WAlarm2 : BaseWidget(true)
