package pl.swit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Rodzaj sceny pogodowej. */
enum class Scene { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

object Art {
    fun scene(code: Int): Scene = when (code) {
        0, 1 -> Scene.CLEAR; 2 -> Scene.PARTLY; 3 -> Scene.CLOUDY; 45, 48 -> Scene.FOG
        51, 53, 55, 56, 57 -> Scene.DRIZZLE; 61, 63, 65, 66, 67, 80, 81, 82 -> Scene.RAIN
        71, 73, 75, 77, 85, 86 -> Scene.SNOW; 95, 96, 99 -> Scene.STORM; else -> Scene.CLOUDY
    }

    /** Kolory nieba: (góra, dół) wg pogody i pory dnia. phase: 0 noc, 1 świt/zmierzch, 2 dzień. */
    fun sky(s: Scene, phase: Int): IntArray = when (phase) {
        0 -> when (s) {
            Scene.CLEAR, Scene.PARTLY -> intArrayOf(0xFF0B1026.toInt(), 0xFF1E2A5A.toInt())
            Scene.STORM -> intArrayOf(0xFF0A0A12.toInt(), 0xFF262338.toInt())
            Scene.SNOW -> intArrayOf(0xFF1B2333.toInt(), 0xFF3A4660.toInt())
            else -> intArrayOf(0xFF12151E.toInt(), 0xFF2A3040.toInt())
        }
        1 -> when (s) {
            Scene.CLEAR, Scene.PARTLY -> intArrayOf(0xFF3B4A8C.toInt(), 0xFFF59E6B.toInt())
            Scene.STORM, Scene.RAIN -> intArrayOf(0xFF2B2F45.toInt(), 0xFF7A6A80.toInt())
            else -> intArrayOf(0xFF4A5578.toInt(), 0xFFC9A0A0.toInt())
        }
        else -> when (s) {
            Scene.CLEAR -> intArrayOf(0xFF1E7FE0.toInt(), 0xFF7CC4FF.toInt())
            Scene.PARTLY -> intArrayOf(0xFF3A86D0.toInt(), 0xFFA9D2F5.toInt())
            Scene.CLOUDY -> intArrayOf(0xFF6B7B8E.toInt(), 0xFFAEB9C6.toInt())
            Scene.FOG -> intArrayOf(0xFF8E98A3.toInt(), 0xFFC9CFD5.toInt())
            Scene.DRIZZLE, Scene.RAIN -> intArrayOf(0xFF3E4C5E.toInt(), 0xFF7D8B9C.toInt())
            Scene.SNOW -> intArrayOf(0xFF8FA7C2.toInt(), 0xFFDCE6F1.toInt())
            Scene.STORM -> intArrayOf(0xFF23283A.toInt(), 0xFF4D5266.toInt())
        }
    }

    fun phase(f: Forecast?): Int {
        val d = f?.days?.firstOrNull() ?: return if (f?.day == false) 0 else 2
        val now = System.currentTimeMillis()
        return when {
            now < d.sunrise - 40 * 60_000L || now > d.sunset + 40 * 60_000L -> 0
            now < d.sunrise + 50 * 60_000L || now > d.sunset - 50 * 60_000L -> 1
            else -> 2
        }
    }

    /** Tło widżetu wg pogody i pory dnia. */
    fun widgetBg(code: Int, phase: Int): Int = when {
        phase == 0 -> R.drawable.sky_night
        phase == 1 -> R.drawable.sky_dusk
        else -> when (scene(code)) {
            Scene.CLEAR -> R.drawable.sky_clear; Scene.PARTLY -> R.drawable.sky_partly
            Scene.CLOUDY, Scene.FOG -> R.drawable.sky_cloudy; Scene.SNOW -> R.drawable.sky_snow
            Scene.STORM -> R.drawable.sky_storm; else -> R.drawable.sky_rain
        }
    }

    // ------------------------------------------------------------ kolorowe ikony (rysowane, ostre w każdym rozmiarze)
    private val cache = LruCache<String, Bitmap>(80)

    fun icon(code: Int, day: Boolean, px: Int, onLight: Boolean = false): Bitmap {
        val key = "$code-$day-$px-$onLight"
        cache.get(key)?.let { return it }
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        lightBg = onLight
        drawIcon(Canvas(b), scene(code), day, px.toFloat(), code >= 96)
        lightBg = false
        cache.put(key, b)
        return b
    }

    private val P = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lightBg = false

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        P.reset(); P.isAntiAlias = true
        P.shader = RadialGradient(cx, cy, r * 1.9f, intArrayOf(0x66FFD54A, 0x00FFD54A), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.9f, P)
        P.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.2f, intArrayOf(0xFFFFE680.toInt(), 0xFFFFB020.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, P)
        P.shader = null
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        P.reset(); P.isAntiAlias = true
        P.shader = RadialGradient(cx, cy, r * 1.8f, intArrayOf(0x40E8EEFF, 0x00E8EEFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.8f, P)
        P.shader = null; P.color = 0xFFEFF2FF.toInt()
        val path = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            val cut = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW) }
            op(cut, Path.Op.DIFFERENCE)
        }
        c.drawPath(path, P)
    }

    private fun cloud(c: Canvas, x: Float, y: Float, w: Float, top: Int, bottom: Int) {
        P.reset(); P.isAntiAlias = true
        P.shader = LinearGradient(0f, y - w * 0.45f, 0f, y + w * 0.18f, top, bottom, Shader.TileMode.CLAMP)
        val path = Path()
        path.addCircle(x - w * 0.22f, y, w * 0.2f, Path.Direction.CW)
        path.addCircle(x + w * 0.02f, y - w * 0.14f, w * 0.27f, Path.Direction.CW)
        path.addCircle(x + w * 0.27f, y - w * 0.02f, w * 0.19f, Path.Direction.CW)
        path.addRoundRect(RectF(x - w * 0.42f, y - w * 0.02f, x + w * 0.46f, y + w * 0.18f), w * 0.1f, w * 0.1f, Path.Direction.CW)
        c.drawPath(path, P)
        P.shader = null
    }

    private fun drawIcon(c: Canvas, s: Scene, day: Boolean, z: Float, hail: Boolean) {
        // na jasnym tle chmury muszą być szare, inaczej znikają
        val white = if (lightBg) 0xFFC9D2DC.toInt() else 0xFFFFFFFF.toInt()
        val light = if (lightBg) 0xFFA7B3C1.toInt() else 0xFFE3E8EF.toInt()
        val grey = if (lightBg) 0xFF7E8B9A.toInt() else 0xFFB8C2CE.toInt()
        val storm1 = 0xFF9AA3B2.toInt(); val storm2 = 0xFF5D6676.toInt()
        when (s) {
            Scene.CLEAR -> if (day) sun(c, z / 2, z / 2, z * 0.26f) else moon(c, z / 2, z / 2, z * 0.27f)
            Scene.PARTLY -> {
                if (day) sun(c, z * 0.36f, z * 0.36f, z * 0.2f) else moon(c, z * 0.36f, z * 0.34f, z * 0.19f)
                cloud(c, z * 0.56f, z * 0.66f, z * 0.62f, white, light)
            }
            Scene.CLOUDY -> { cloud(c, z * 0.38f, z * 0.48f, z * 0.5f, light, grey); cloud(c, z * 0.56f, z * 0.64f, z * 0.66f, white, light) }
            Scene.FOG -> {
                cloud(c, z * 0.5f, z * 0.46f, z * 0.66f, light, grey)
                P.reset(); P.isAntiAlias = true; P.color = 0xCCE3E8EF.toInt(); P.strokeWidth = z * 0.05f; P.strokeCap = Paint.Cap.ROUND
                c.drawLine(z * 0.18f, z * 0.72f, z * 0.82f, z * 0.72f, P); c.drawLine(z * 0.26f, z * 0.84f, z * 0.74f, z * 0.84f, P)
            }
            Scene.DRIZZLE, Scene.RAIN -> {
                cloud(c, z * 0.52f, z * 0.48f, z * 0.68f, light, grey)
                P.reset(); P.isAntiAlias = true; P.color = 0xFF4FA8FF.toInt(); P.strokeWidth = z * 0.045f; P.strokeCap = Paint.Cap.ROUND
                val n = if (s == Scene.RAIN) 3 else 2
                for (i in 0 until n) { val x = z * (0.32f + i * 0.18f + if (n == 2) 0.09f else 0f); c.drawLine(x, z * 0.7f, x - z * 0.05f, z * (if (s == Scene.RAIN) 0.86f else 0.79f), P) }
            }
            Scene.SNOW -> {
                cloud(c, z * 0.52f, z * 0.48f, z * 0.68f, white, light)
                P.reset(); P.isAntiAlias = true; P.color = 0xFFDDEEFF.toInt()
                for (i in 0 until 3) c.drawCircle(z * (0.32f + i * 0.18f), z * (0.76f + (i % 2) * 0.07f), z * 0.045f, P)
            }
            Scene.STORM -> {
                cloud(c, z * 0.52f, z * 0.46f, z * 0.7f, storm1, storm2)
                P.reset(); P.isAntiAlias = true; P.color = 0xFFFFD23F.toInt()
                val bolt = Path().apply { moveTo(z * 0.54f, z * 0.58f); lineTo(z * 0.42f, z * 0.77f); lineTo(z * 0.51f, z * 0.77f); lineTo(z * 0.46f, z * 0.94f); lineTo(z * 0.62f, z * 0.7f); lineTo(z * 0.53f, z * 0.7f); close() }
                c.drawPath(bolt, P)
                if (hail) { P.color = white; c.drawCircle(z * 0.3f, z * 0.82f, z * 0.035f, P); c.drawCircle(z * 0.72f, z * 0.84f, z * 0.035f, P) }
            }
        }
    }
}

/** Animowane niebo: gradient, słońce lub księżyc z gwiazdami, płynące chmury, deszcz, śnieg, błyskawice, mgła. */
class SkyView(c: Context) : View(c) {
    var scene = Scene.CLEAR
        set(v) { field = v; seed() }
    var phase = 2
        set(v) { field = v; seed() }
    /** Wschód słońca dla ekranu budzika: 0 → 1 rozjaśnia niebo i podnosi słońce. */
    var sunrise = -1f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t0 = System.currentTimeMillis()
    private class Drop(var x: Float, var y: Float, val v: Float, val len: Float, val a: Int)
    private class Cl(var x: Float, val y: Float, val w: Float, val v: Float, val alpha: Int)
    private val drops = ArrayList<Drop>()
    private val clouds = ArrayList<Cl>()
    private val stars = ArrayList<FloatArray>()
    private var flash = 0f
    private var nextFlash = 0L
    private var running = false

    private fun seed() {
        drops.clear(); clouds.clear(); stars.clear()
        val r = Random(42)
        val nDrops = when (scene) { Scene.RAIN -> 140; Scene.STORM -> 170; Scene.DRIZZLE -> 70; Scene.SNOW -> 90; else -> 0 }
        repeat(nDrops) {
            val snow = scene == Scene.SNOW
            drops.add(Drop(r.nextFloat(), r.nextFloat(), if (snow) 0.03f + r.nextFloat() * 0.05f else 0.9f + r.nextFloat() * 0.7f,
                if (snow) 2f + r.nextFloat() * 3f else 0.025f + r.nextFloat() * 0.03f, 90 + r.nextInt(120)))
        }
        val nCl = when (scene) { Scene.CLEAR -> 0; Scene.PARTLY -> 3; Scene.FOG -> 4; else -> 6 }
        repeat(nCl) { clouds.add(Cl(r.nextFloat() * 1.4f - 0.2f, 0.08f + r.nextFloat() * 0.42f, 0.35f + r.nextFloat() * 0.35f, 0.006f + r.nextFloat() * 0.012f, 110 + r.nextInt(110))) }
        if (phase == 0) repeat(70) { stars.add(floatArrayOf(r.nextFloat(), r.nextFloat() * 0.65f, 0.6f + r.nextFloat() * 1.6f, r.nextFloat() * 6.28f)) }
    }

    init { seed() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    private fun mix(a: Int, b: Int, t: Float) = Theme.blend(a, b, t.coerceIn(0f, 1f))

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val t = (System.currentTimeMillis() - t0) / 1000f
        // niebo
        var col = Art.sky(scene, phase)
        if (sunrise >= 0) {
            val night = Art.sky(Scene.CLEAR, 0); val dawn = Art.sky(Scene.CLEAR, 1); val day = Art.sky(Scene.CLEAR, 2)
            col = if (sunrise < 0.5f) intArrayOf(mix(night[0], dawn[0], sunrise * 2), mix(night[1], dawn[1], sunrise * 2))
            else intArrayOf(mix(dawn[0], day[0], (sunrise - 0.5f) * 2), mix(dawn[1], day[1], (sunrise - 0.5f) * 2))
        }
        p.shader = LinearGradient(0f, 0f, 0f, h, col[0], col[1], Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // gwiazdy
        if (phase == 0 && sunrise < 0) for (s in stars) {
            p.color = Color.argb((120 + 120 * sin(t * 1.3f + s[3])).toInt().coerceIn(30, 240), 255, 255, 255)
            cv.drawCircle(s[0] * w, s[1] * h, s[2], p)
        }
        // słońce / księżyc
        val bodyX = w * 0.74f
        if (sunrise >= 0) {
            val y = h * (0.95f - 0.6f * sunrise)
            sunBody(cv, w * 0.5f, y, minOf(w, h) * 0.13f, t)
        } else if (scene == Scene.CLEAR || scene == Scene.PARTLY) {
            if (phase == 0) moonBody(cv, bodyX, h * 0.26f, minOf(w, h) * 0.1f)
            else sunBody(cv, bodyX, h * (if (phase == 1) 0.62f else 0.26f), minOf(w, h) * 0.11f, t)
        }
        // chmury
        for (c in clouds) {
            c.x += c.v / 60f
            if (c.x > 1.3f) c.x = -0.5f
            val dark = scene == Scene.STORM || scene == Scene.RAIN
            val base = if (dark) 0xFF8D96A6.toInt() else if (phase == 0) 0xFF5A6478.toInt() else Color.WHITE
            p.color = Color.argb(c.alpha, Color.red(base), Color.green(base), Color.blue(base))
            p.maskFilter = BlurMaskFilter(c.w * w * 0.06f, BlurMaskFilter.Blur.NORMAL)
            val cx = c.x * w; val cy = c.y * h; val cw = c.w * w
            cv.drawCircle(cx, cy, cw * 0.22f, p); cv.drawCircle(cx + cw * 0.25f, cy - cw * 0.08f, cw * 0.28f, p)
            cv.drawCircle(cx + cw * 0.52f, cy, cw * 0.2f, p); cv.drawRect(cx, cy, cx + cw * 0.55f, cy + cw * 0.16f, p)
            p.maskFilter = null
        }
        // mgła
        if (scene == Scene.FOG) for (i in 0 until 4) {
            p.color = Color.argb(70, 235, 238, 242)
            val y = h * (0.45f + i * 0.13f) + sin(t * 0.4f + i) * h * 0.01f
            cv.drawRoundRect(-w * 0.1f + sin(t * 0.2f + i) * w * 0.05f, y, w * 1.1f, y + h * 0.06f, h * 0.03f, h * 0.03f, p)
        }
        // opady
        if (drops.isNotEmpty()) {
            val snow = scene == Scene.SNOW
            p.strokeCap = Paint.Cap.ROUND
            for (d in drops) {
                d.y += d.v / 60f * (if (snow) 1f else 1.2f)
                if (snow) d.x += sin(t * 1.5f + d.len) * 0.0007f
                if (d.y > 1.05f) { d.y = -0.05f; d.x = Random.nextFloat() }
                if (snow) { p.color = Color.argb(d.a + 30, 255, 255, 255); cv.drawCircle(d.x * w, d.y * h, d.len, p) }
                else {
                    p.color = Color.argb(d.a, 200, 220, 255); p.strokeWidth = 2f
                    cv.drawLine(d.x * w, d.y * h, d.x * w - d.len * h * 0.25f, d.y * h - d.len * h, p)
                }
            }
        }
        // błyskawice
        if (scene == Scene.STORM) {
            val now = System.currentTimeMillis()
            if (now > nextFlash) { flash = 1f; nextFlash = now + 2500 + Random.nextLong(5000) }
            if (flash > 0) { p.color = Color.argb((flash * 150).toInt(), 255, 255, 255); cv.drawRect(0f, 0f, w, h, p); flash -= 0.06f }
        }
        if (running) postInvalidateOnAnimation()
    }

    private fun sunBody(cv: Canvas, x: Float, y: Float, r: Float, t: Float) {
        val pulse = 1f + 0.05f * sin(t * 1.2f)
        p.shader = RadialGradient(x, y, r * 3.2f * pulse, intArrayOf(0x55FFE08A, 0x00FFE08A), null, Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r * 3.2f * pulse, p)
        p.shader = null
        p.color = 0x33FFF2C0
        p.strokeWidth = r * 0.12f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 12) {
            val a = (i * 30f + t * 6f) * (PI / 180).toFloat()
            cv.drawLine(x + cos(a) * r * 1.4f, y + sin(a) * r * 1.4f, x + cos(a) * r * 2.1f, y + sin(a) * r * 2.1f, p)
        }
        p.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.3f, intArrayOf(0xFFFFF1A6.toInt(), 0xFFFFB627.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r, p)
        p.shader = null
    }

    private fun moonBody(cv: Canvas, x: Float, y: Float, r: Float) {
        p.shader = RadialGradient(x, y, r * 3f, intArrayOf(0x33DCE4FF, 0x00DCE4FF), null, Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r * 3f, p); p.shader = null
        p.color = 0xFFF1F4FF.toInt(); cv.drawCircle(x, y, r, p)
        p.color = 0x22A0A8C0; cv.drawCircle(x - r * 0.3f, y - r * 0.2f, r * 0.18f, p); cv.drawCircle(x + r * 0.25f, y + r * 0.3f, r * 0.12f, p)
    }
}
