package pl.swit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Jedna seria na wykresie. type: line, dash, bar, area, band (lo w [values], hi w [hi]). axis: 0 lewa, 1 prawa (osobna skala). */
class Series(val name: String, val values: DoubleArray, val color: Int, val type: String = "line", val unit: String = "",
             val axis: Int = 0, val hi: DoubleArray? = null, val fmt: String = "%.0f")

/**
 * Wykres przewijany w poziomie: dowolna liczba serii, separatory dni, opcjonalnie ikony pogody i strzałki wiatru.
 * Stuknięcie/przeciągnięcie pokazuje wartości w danym punkcie.
 */
class ChartView(c: Context) : View(c) {
    var xs: LongArray = LongArray(0)
    var series: List<Series> = emptyList()
    var step = 14f                    // dp na punkt
    var icons: IntArray? = null       // kody pogody (ikony co [iconEvery] punktów)
    var iconDay: BooleanArray? = null
    var iconEvery = 3
    var arrows: DoubleArray? = null   // kierunek wiatru (stopnie) – strzałki pod wykresem
    var daily = false                 // oś X w dniach zamiast godzin
    var marker = -1                   // indeks pionowej linii (np. początek trendu)
    var markerLabel = ""
    var title = ""

    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sel = -1
    private val top get() = (if (title.isNotEmpty()) 22 else 6) * dp + (if (icons != null) 30 * dp else 0f)
    private val bottomPad get() = (if (arrows != null) 40 else 22) * dp
    private val left = 6 * dp

    override fun onMeasure(w: Int, h: Int) {
        val width = (left * 2 + xs.size * step * dp).toInt().coerceAtLeast(MeasureSpec.getSize(w))
        setMeasuredDimension(width, MeasureSpec.getSize(h).takeIf { it > 0 } ?: (200 * dp).toInt())
    }

    private fun x(i: Int) = left + (i + 0.5f) * step * dp

    private fun range(axis: Int): Pair<Double, Double> {
        val v = series.filter { it.axis == axis }.flatMap { s -> s.values.toList() + (s.hi?.toList() ?: emptyList()) }.filter { !it.isNaN() }
        if (v.isEmpty()) return 0.0 to 1.0
        val bars = series.any { it.axis == axis && (it.type == "bar" || it.type == "area") }
        var lo = if (bars) min(0.0, v.min()) else v.min(); var hi = v.max()
        if (hi - lo < 1e-6) { hi += 1; lo -= if (bars) 0.0 else 1.0 }
        val padv = (hi - lo) * 0.12
        return (if (bars && lo >= 0) 0.0 else lo - padv) to hi + padv
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(e.action == MotionEvent.ACTION_MOVE)
            sel = ((e.x - left) / (step * dp)).toInt().coerceIn(0, xs.size - 1); invalidate(); return true
        }
        if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) { parent?.requestDisallowInterceptTouchEvent(false); return true }
        return super.onTouchEvent(e)
    }

    override fun onDraw(cv: Canvas) {
        if (xs.isEmpty()) return
        val h = height.toFloat(); val w = width.toFloat()
        val plotTop = top; val plotBot = h - bottomPad
        p.textSize = 10 * dp
        if (title.isNotEmpty()) { p.color = Theme.fg; p.isFakeBoldText = true; p.textSize = 12 * dp; cv.drawText(title, left + 4 * dp, 15 * dp, p); p.isFakeBoldText = false; p.textSize = 10 * dp }
        // separatory dni i etykiety osi X
        val cal = Calendar.getInstance()
        val dn = SimpleDateFormat(if (daily) "d.MM" else "EEE d", Locale("pl"))
        for (i in xs.indices) {
            cal.timeInMillis = xs[i]
            val newDay = !daily && cal.get(Calendar.HOUR_OF_DAY) == 0
            if (newDay || (daily && cal.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY)) {
                p.color = Theme.withAlpha(Theme.fg, 0.18f); p.strokeWidth = 1f
                val xx = x(i) - step * dp / 2
                cv.drawLine(xx, plotTop, xx, plotBot, p)
                p.color = Theme.dim; p.textAlign = Paint.Align.LEFT
                cv.drawText(dn.format(Date(xs[i])), xx + 3 * dp, plotBot + 13 * dp, p)
            }
            if (!daily && step >= 10 && cal.get(Calendar.HOUR_OF_DAY) % (if (step >= 18) 3 else 6) == 0 && cal.get(Calendar.HOUR_OF_DAY) != 0) {
                p.color = Theme.withAlpha(Theme.dim, 0.7f); p.textAlign = Paint.Align.CENTER
                cv.drawText(cal.get(Calendar.HOUR_OF_DAY).toString(), x(i), plotBot + 13 * dp, p)
            }
            if (daily && step >= 22 && cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                p.color = Theme.withAlpha(Theme.dim, 0.7f); p.textAlign = Paint.Align.CENTER
                cv.drawText(cal.get(Calendar.DAY_OF_MONTH).toString(), x(i), plotBot + 13 * dp, p)
            }
        }
        // linie poziome
        p.color = Theme.withAlpha(Theme.fg, 0.07f)
        for (k in 0..3) { val y = plotTop + (plotBot - plotTop) * k / 3f; cv.drawLine(0f, y, w, y, p) }
        // znacznik (np. „od tego dnia trend”)
        if (marker in xs.indices) {
            val xx = x(marker) - step * dp / 2
            p.color = Theme.withAlpha(Theme.accent, 0.6f); p.strokeWidth = 2 * dp; p.pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
            cv.drawLine(xx, plotTop, xx, plotBot, p); p.pathEffect = null; p.strokeWidth = 1f
            p.color = Theme.accent; p.textAlign = Paint.Align.LEFT; cv.drawText(markerLabel, xx + 4 * dp, plotTop + 11 * dp, p)
        }
        val ranges = mapOf(0 to range(0), 1 to range(1))
        fun y(v: Double, axis: Int): Float { val (lo, hi) = ranges[axis]!!; return (plotBot - (v - lo) / (hi - lo) * (plotBot - plotTop)).toFloat() }
        // serie
        for (s in series) {
            p.color = s.color
            when (s.type) {
                "bar" -> {
                    val bw = max(2 * dp, step * dp * 0.62f)
                    for (i in xs.indices) { val v = s.values.getOrNull(i) ?: continue; if (v.isNaN() || v <= 0) continue
                        cv.drawRoundRect(RectF(x(i) - bw / 2, y(v, s.axis), x(i) + bw / 2, y(0.0, s.axis)), 2 * dp, 2 * dp, p) }
                }
                "area", "band" -> {
                    val path = Path(); var started = false; var lastX = 0f
                    for (i in xs.indices) { val v = if (s.type == "band") s.hi?.getOrNull(i) ?: Double.NaN else s.values.getOrNull(i) ?: Double.NaN; if (v.isNaN()) continue
                        if (!started) { path.moveTo(x(i), y(v, s.axis)); started = true } else path.lineTo(x(i), y(v, s.axis)); lastX = x(i) }
                    if (!started) continue
                    if (s.type == "band") { for (i in xs.indices.reversed()) { val v = s.values.getOrNull(i) ?: Double.NaN; if (!v.isNaN()) path.lineTo(x(i), y(v, s.axis)) } }
                    else { path.lineTo(lastX, plotBot); path.lineTo(x(s.values.indexOfFirst { !it.isNaN() }.coerceAtLeast(0)), plotBot) }
                    path.close(); p.style = Paint.Style.FILL; p.color = Theme.withAlpha(s.color, if (s.type == "band") 0.22f else 0.30f)
                    cv.drawPath(path, p)
                }
                else -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = (if (s.type == "dash") 1.6f else 2.4f) * dp; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                    if (s.type == "dash") p.pathEffect = DashPathEffect(floatArrayOf(5 * dp, 4 * dp), 0f)
                    val path = Path(); var started = false
                    for (i in xs.indices) { val v = s.values.getOrNull(i) ?: Double.NaN; if (v.isNaN()) { started = false; continue }
                        if (!started) { path.moveTo(x(i), y(v, s.axis)); started = true } else path.lineTo(x(i), y(v, s.axis)) }
                    cv.drawPath(path, p); p.pathEffect = null; p.style = Paint.Style.FILL
                    // wartości przy skrajnych punktach dnia (max/min)
                    if (s.axis == 0 && s.type == "line" && series.indexOf(s) == 0) {
                        p.textAlign = Paint.Align.CENTER; p.textSize = 10 * dp
                        val per = if (daily) 1 else 24
                        var i0 = 0
                        while (i0 < xs.size) {
                            val seg = (i0 until min(xs.size, i0 + per)).filter { !s.values[it].isNaN() }
                            if (seg.isNotEmpty() && (daily || seg.size > 6)) {
                                val mx = seg.maxBy { s.values[it] }
                                p.color = s.color; cv.drawText(String.format(Locale.US, s.fmt, s.values[mx]) + s.unit, x(mx), y(s.values[mx], 0) - 6 * dp, p)
                                if (!daily) { val mn = seg.minBy { s.values[it] }; p.color = Theme.withAlpha(s.color, 0.8f); cv.drawText(String.format(Locale.US, s.fmt, s.values[mn]) + s.unit, x(mn), y(s.values[mn], 0) + 14 * dp, p) }
                            }
                            i0 += per
                        }
                    }
                }
            }
        }
        // ikony pogody nad wykresem
        icons?.let { ic ->
            val sz = (24 * dp).toInt()
            for (i in ic.indices step iconEvery) cv.drawBitmap(Art.icon(ic[i], iconDay?.getOrNull(i) ?: true, sz, !Theme.dark), x(i) - sz / 2, top - 28 * dp, null)
        }
        // strzałki kierunku wiatru (w którą stronę wieje)
        arrows?.let { a ->
            p.color = Theme.fg; p.strokeWidth = 1.6f * dp; p.style = Paint.Style.STROKE
            val every = if (step >= 18) 2 else if (step >= 10) 3 else 6
            for (i in a.indices step every) {
                if (a[i].isNaN()) continue
                val ang = Math.toRadians(a[i] + 180.0)   // kierunek, DOKĄD wieje
                val cx = x(i); val cy = h - 12 * dp; val r = 7 * dp
                val dx = (sin(ang) * r).toFloat(); val dy = (-cos(ang) * r).toFloat()
                cv.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, p)
                val a1 = ang + Math.toRadians(150.0); val a2 = ang - Math.toRadians(150.0)
                cv.drawLine(cx + dx, cy + dy, cx + dx + (sin(a1) * r * 0.6).toFloat(), cy + dy - (cos(a1) * r * 0.6).toFloat(), p)
                cv.drawLine(cx + dx, cy + dy, cx + dx + (sin(a2) * r * 0.6).toFloat(), cy + dy - (cos(a2) * r * 0.6).toFloat(), p)
            }
            p.style = Paint.Style.FILL
        }
        // dymek z wartościami
        if (sel in xs.indices) {
            p.color = Theme.withAlpha(Theme.fg, 0.5f); p.strokeWidth = 1.2f * dp
            cv.drawLine(x(sel), plotTop, x(sel), plotBot, p)
            val lines = ArrayList<Pair<String, Int>>()
            lines += SimpleDateFormat(if (daily) "EEEE d.MM" else "EEE d.MM, H:mm", Locale("pl")).format(Date(xs[sel])) to Theme.fg
            for (s in series) { val v = s.values.getOrNull(sel) ?: continue; if (v.isNaN()) continue
                lines += (s.name + ": " + String.format(Locale.US, s.fmt, v) + s.unit + (s.hi?.getOrNull(sel)?.takeIf { !it.isNaN() }?.let { "–" + String.format(Locale.US, s.fmt, it) + s.unit } ?: "")) to s.color }
            p.textSize = 11 * dp; p.textAlign = Paint.Align.LEFT
            val bw = lines.maxOf { p.measureText(it.first) } + 16 * dp; val bh = lines.size * 15 * dp + 10 * dp
            var bx = x(sel) + 8 * dp; if (bx + bw > w - 4 * dp) bx = x(sel) - bw - 8 * dp
            p.color = Theme.withAlpha(Theme.surface, 0.95f); cv.drawRoundRect(RectF(bx, plotTop, bx + bw, plotTop + bh), 10 * dp, 10 * dp, p)
            for ((k, l) in lines.withIndex()) { p.color = if (k == 0) Theme.fg else l.second; p.isFakeBoldText = k == 0; cv.drawText(l.first, bx + 8 * dp, plotTop + 17 * dp + k * 15 * dp, p) }
            p.isFakeBoldText = false
        }
    }
}
