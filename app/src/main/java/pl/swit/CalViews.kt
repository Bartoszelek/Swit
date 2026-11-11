package pl.swit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private fun dayStart(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

/** Miesiąc jak w Kalendarzu Google: siatka 6×7, kolorowe paski wpisów, przesuwanie palcem między miesiącami. */
class MonthView(c: Context) : View(c) {
    var month: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    var selected = dayStart(System.currentTimeMillis())
    var occ: List<Occ> = emptyList()
    var onPick: (Long) -> Unit = {}
    var onLong: (Long) -> Unit = {}
    var onSwipe: (Int) -> Unit = {}

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density

    fun first(): Long {
        val c = (month.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        c.add(Calendar.DAY_OF_YEAR, -dow)
        return c.timeInMillis
    }

    private fun cellDay(i: Int): Long = Calendar.getInstance().apply { timeInMillis = first(); add(Calendar.DAY_OF_YEAR, i) }.timeInMillis

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { hit(e)?.let { selected = it; invalidate(); onPick(it) }; return true }
        override fun onLongPress(e: MotionEvent) { hit(e)?.let { onLong(it) } }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) && abs(vx) > 600) { onSwipe(if (vx < 0) 1 else -1); return true }
            return false
        }
    })

    private fun hit(e: MotionEvent): Long? {
        val top = 22 * dp
        if (e.y < top) return null
        val col = (e.x / (width / 7f)).toInt().coerceIn(0, 6)
        val row = ((e.y - top) / ((height - top) / 6f)).toInt().coerceIn(0, 5)
        return cellDay(row * 7 + col)
    }

    override fun onTouchEvent(e: MotionEvent) = gd.onTouchEvent(e)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = 22 * dp
        val cw = w / 7f; val ch = (h - top) / 6f
        p.textAlign = Paint.Align.CENTER
        p.textSize = 11 * dp; p.color = Theme.dim
        for ((i, n) in listOf("pn", "wt", "śr", "cz", "pt", "sb", "nd").withIndex()) cv.drawText(n, cw * i + cw / 2, 14 * dp, p)
        val today = dayStart(System.currentTimeMillis())
        val m = month.get(Calendar.MONTH)
        val cal = Calendar.getInstance()
        val texts = ArrayList<Triple<String, Float, Float>>()   // napisy rysowane na końcu, nad paskami
        for (i in 0 until 42) {
            val d = cellDay(i)
            cal.timeInMillis = d
            val x = (i % 7) * cw; val y = top + (i / 7) * ch
            val inMonth = cal.get(Calendar.MONTH) == m
            if (d == selected) { p.color = Theme.withAlpha(Theme.accent, 0.16f); cv.drawRoundRect(x + 2, y + 2, x + cw - 2, y + ch - 2, 10 * dp, 10 * dp, p) }
            p.color = Theme.line; p.strokeWidth = 1f
            cv.drawLine(x, y, x + cw, y, p)
            // numer dnia
            p.textSize = 12.5f * dp; p.isFakeBoldText = d == today
            if (d == today) {
                p.color = Theme.accent; cv.drawCircle(x + cw / 2, y + 12 * dp, 11 * dp, p); p.color = Theme.onAccent
            } else p.color = if (inMonth) Theme.fg else Theme.withAlpha(Theme.dim, 0.5f)
            val wknd = i % 7 >= 5
            if (d != today && inMonth && wknd) p.color = Theme.blend(Theme.fg, 0xFFFF453A.toInt(), 0.45f)
            cv.drawText(cal.get(Calendar.DAY_OF_MONTH).toString(), x + cw / 2, y + 16.5f * dp, p)
            p.isFakeBoldText = false
            // wpisy
            val dayOcc = occ.filter { it.start < d + 86_400_000L && maxOf(it.end, it.start + 1) > d }
                .sortedWith(compareByDescending<Occ> { it.end - it.start >= 86_400_000L }.thenBy { it.start }.thenBy { it.ev.title })
            var k = 0
            val lineH = 13.5f * dp
            for (o in dayOcc) {
                val yy = y + 26 * dp + k * lineH
                if (yy + lineH > y + ch - 2) {
                    p.color = Theme.dim; p.textSize = 9 * dp; p.textAlign = Paint.Align.CENTER
                    cv.drawText("+${dayOcc.size - k}", x + cw / 2, y + ch - 4 * dp, p); break
                }
                val col = Rem.color(o.ev)
                // wpis kilkudniowy: pasek ciągnie się przez kolejne dni, napis tylko na początku (lub w poniedziałek)
                val fromPrev = o.start < d
                val toNext = maxOf(o.end, o.start + 1) > d + 86_400_000L
                p.color = Theme.withAlpha(col, if (inMonth) 0.9f else 0.4f)
                val l0 = if (fromPrev) x else x + 2; val r0 = if (toNext) x + cw else x + cw - 2
                cv.drawRoundRect(l0, yy, r0, yy + lineH - 2, if (fromPrev && toNext) 0f else 4 * dp, 4 * dp, p)
                if (!fromPrev || i % 7 == 0) {
                    p.color = Color.WHITE; p.textSize = 9.5f * dp; p.textAlign = Paint.Align.LEFT
                    val t = o.ev.title
                    val span = if (toNext) cw * (7 - i % 7) else cw
                    val n = p.breakText(t, true, span - 8, null)
                    texts.add(Triple(t.substring(0, n), l0 + 4, yy + lineH - 5.5f * dp))
                }
                p.textAlign = Paint.Align.CENTER
                k++
            }
        }
        p.color = Color.WHITE; p.textSize = 9.5f * dp; p.textAlign = Paint.Align.LEFT
        for ((t, x, y) in texts) cv.drawText(t, x, y, p)
        p.textAlign = Paint.Align.CENTER
    }
}

/** Tydzień lub dzień: siatka godzin z kolorowymi blokami wpisów; stuknięcie w puste miejsce dodaje wpis o tej godzinie. */
class WeekView(c: Context) : View(c) {
    var start = dayStart(System.currentTimeMillis())
    var days = 7
    var occ: List<Occ> = emptyList()
    var onEmpty: (Long) -> Unit = {}
    var onOcc: (Occ) -> Unit = {}
    var onSwipe: (Int) -> Unit = {}

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density
    val hourH get() = 52 * dp
    private val left get() = 40 * dp
    private val head get() = 42 * dp
    private val blocks = ArrayList<Pair<RectF, Occ>>()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (head + hourH * 24 + 10 * dp).toInt())

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            blocks.lastOrNull { it.first.contains(e.x, e.y) }?.let { onOcc(it.second); return true }
            if (e.y < head || e.x < left) return true
            val col = ((e.x - left) / ((width - left) / days)).toInt().coerceIn(0, days - 1)
            val hour = ((e.y - head) / hourH).toInt().coerceIn(0, 23)
            onEmpty(Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_YEAR, col); set(Calendar.HOUR_OF_DAY, hour) }.timeInMillis)
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) * 1.5f && abs(vx) > 700) { onSwipe(if (vx < 0) 1 else -1); return true }
            return false
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean { gd.onTouchEvent(e); return true }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val colW = (w - left) / days
        val today = dayStart(System.currentTimeMillis())
        val cal = Calendar.getInstance()
        blocks.clear()
        // nagłówki dni
        p.textAlign = Paint.Align.CENTER
        for (d in 0 until days) {
            cal.timeInMillis = start; cal.add(Calendar.DAY_OF_YEAR, d)
            val x = left + d * colW + colW / 2
            val isToday = dayStart(cal.timeInMillis) == today
            p.textSize = 10 * dp; p.color = if (isToday) Theme.accent else Theme.dim
            cv.drawText(listOf("nd", "pn", "wt", "śr", "cz", "pt", "sb")[cal.get(Calendar.DAY_OF_WEEK) - 1], x, 13 * dp, p)
            if (isToday) { p.color = Theme.accent; cv.drawCircle(x, 28 * dp, 12 * dp, p) }
            p.textSize = 14 * dp; p.color = if (isToday) Theme.onAccent else Theme.fg; p.isFakeBoldText = true
            cv.drawText(cal.get(Calendar.DAY_OF_MONTH).toString(), x, 33 * dp, p); p.isFakeBoldText = false
        }
        // siatka godzin
        p.textAlign = Paint.Align.RIGHT; p.textSize = 10 * dp
        for (hh in 0..24) {
            val y = head + hh * hourH
            p.color = Theme.line; p.strokeWidth = 1f
            cv.drawLine(left, y, w, y, p)
            if (hh in 1..23) { p.color = Theme.dim; cv.drawText("$hh:00", left - 6 * dp, y + 4 * dp, p) }
        }
        for (d in 1 until days) { p.color = Theme.line; cv.drawLine(left + d * colW, head, left + d * colW, head + 24 * hourH, p) }
        // wpisy całodniowe – pasek u góry dnia
        for (o in occ) {
            for (d in 0 until days) {
                val ds = start + d * 86_400_000L
                if (!(o.start < ds + 86_400_000L && maxOf(o.end, o.start + 1) > ds)) continue
                val col = Rem.color(o.ev)
                if (o.ev.allDay || o.end - o.start >= 86_400_000L) {
                    val r = RectF(left + d * colW + 2, head + 2, left + (d + 1) * colW - 2, head + 18 * dp)
                    p.color = col; cv.drawRoundRect(r, 4 * dp, 4 * dp, p)
                    label(cv, o.ev.title, r, 9.5f)
                    blocks.add(r to o)
                } else {
                    val s = maxOf(o.start, ds); val e = minOf(maxOf(o.end, o.start + 30 * 60_000L), ds + 86_400_000L)
                    val y1 = head + (s - ds) / 3_600_000f * hourH; val y2 = head + (e - ds) / 3_600_000f * hourH
                    val r = RectF(left + d * colW + 2, y1 + 1, left + (d + 1) * colW - 2, maxOf(y2 - 1, y1 + 18 * dp))
                    p.color = Theme.withAlpha(col, 0.92f); cv.drawRoundRect(r, 5 * dp, 5 * dp, p)
                    label(cv, o.ev.title, r, 10f)
                    blocks.add(r to o)
                }
            }
        }
        // linia „teraz”
        val now = System.currentTimeMillis()
        if (now >= start && now < start + days * 86_400_000L) {
            val d = ((now - start) / 86_400_000L).toInt()
            val y = head + (now - start - d * 86_400_000L) / 3_600_000f * hourH
            p.color = 0xFFFF453A.toInt(); p.strokeWidth = 2 * dp
            cv.drawLine(left + d * colW, y, left + (d + 1) * colW, y, p)
            cv.drawCircle(left + d * colW, y, 4 * dp, p)
        }
    }

    private fun label(cv: Canvas, t: String, r: RectF, size: Float) {
        p.color = Color.WHITE; p.textSize = size * dp; p.textAlign = Paint.Align.LEFT
        val n = p.breakText(t, true, r.width() - 8, null)
        cv.drawText(t.substring(0, n), r.left + 4, r.top + size * dp + 2, p)
    }
}

/** Nocny krajobraz z górami i gwiazdami – nagłówek zakładki Budzik. */
class MountainView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stars = List(40) { floatArrayOf(Random.nextFloat(), Random.nextFloat() * 0.5f, 0.8f + Random.nextFloat() * 1.4f, Random.nextFloat() * 6f) }
    private val t0 = System.currentTimeMillis()

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = (System.currentTimeMillis() - t0) / 1000f
        p.shader = LinearGradient(0f, 0f, 0f, h, Theme.blend(0xFF0B2A4A.toInt(), Theme.accent, 0.25f), Theme.bg, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p); p.shader = null
        for (s in stars) { p.color = Color.argb((110 + 100 * sin(t * 1.2f + s[3])).toInt().coerceIn(20, 230), 255, 255, 255); cv.drawCircle(s[0] * w, s[1] * h, s[2], p) }
        p.color = 0x44FFFFFF; cv.drawCircle(w * 0.82f, h * 0.18f, h * 0.06f, p)
        fun ridge(pts: List<Pair<Float, Float>>, color: Int) {
            val path = Path().apply { moveTo(0f, h); for ((x, y) in pts) lineTo(x * w, y * h); lineTo(w, h); close() }
            p.color = color; cv.drawPath(path, p)
        }
        ridge(listOf(0f to 0.62f, 0.12f to 0.5f, 0.22f to 0.58f, 0.35f to 0.38f, 0.48f to 0.18f, 0.58f to 0.33f, 0.7f to 0.3f, 0.82f to 0.5f, 1f to 0.42f), Theme.blend(0xFF123250.toInt(), Theme.bg, 0.2f))
        ridge(listOf(0f to 0.78f, 0.15f to 0.7f, 0.3f to 0.76f, 0.5f to 0.62f, 0.66f to 0.72f, 0.85f to 0.64f, 1f to 0.74f), Theme.blend(0xFF0A1C30.toInt(), Theme.bg, 0.45f))
        postInvalidateDelayed(80)
    }
}
