package pl.swit

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Ustawienia jednego widżetu z podglądem na żywo. */
class WidgetSetup : Activity() {
    private var wid = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var d: Design
    private lateinit var col: LinearLayout
    private lateinit var preview: FrameLayout
    private var wDp = 300
    private var hDp = 200
    private var sel = ""
    private lateinit var selBar: LinearLayout
    private lateinit var guide: View

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f, top: Int = 0) = LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }
    private fun tv(t: String, s: Float = 15f, bold: Boolean = false, color: Int = Theme.fg) = TextView(this).apply {
        text = t; textSize = s; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun round(c: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat(); if (stroke > 0) setStroke(dp(stroke), Theme.line) }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun header(t: String) = col.addView(tv(t.uppercase(), 13f, true, Theme.dim).apply { letterSpacing = 0.08f }, lp(top = 22))

    private fun chips(opts: List<Pair<String, String>>, cur: () -> String, set: (String) -> Unit) {
        val r = hbox()
        fun draw() {
            r.removeAllViews()
            for ((k, n) in opts) {
                val on = cur() == k
                r.addView(tv(n, 13f, on, if (on) Theme.onAccent else Theme.fg).apply {
                    maxLines = 1; setPadding(dp(13), dp(8), dp(13), dp(8))
                    background = if (on) Theme.gradient(dp(16).toFloat()) else round(Theme.surface, 16, 1)
                    setOnClickListener { set(k); draw(); refresh() }
                }, lp(-2, -2).apply { rightMargin = dp(6) })
            }
        }
        draw()
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(top = 6))
    }

    private fun slider(label: String, min: Int, max: Int, cur: Int, unit: String, set: (Int) -> Unit) {
        val t = tv("$label: $cur$unit", 14f)
        col.addView(t, lp(top = 10))
        col.addView(SeekBar(this).apply {
            this.max = max - min; progress = cur - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { set(p + min); t.text = "$label: ${p + min}$unit"; if (u) refresh() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, lp(top = 2))
    }

    private fun sw(label: String, on: Boolean, set: (Boolean) -> Unit) {
        val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
        r.addView(tv(label, 15f), lp(0, -2, 1f))
        r.addView(Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, b -> set(b); refresh() } })
        col.addView(r)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        wid = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (wid == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        Theme.apply(this, Prefs.get(this).getString("theme", "ocean") ?: "ocean", Prefs.get(this).getString("mode", "system") ?: "system")
        window.statusBarColor = Theme.bg; window.navigationBarColor = Theme.bg
        d = WD.get(this, wid)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg) }
        // podgląd na górze, zawsze widoczny
        val pv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(10)) }
        pv.addView(tv("Wygląd widżetu", 24f, true))
        preview = FrameLayout(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF4B6E5A.toInt(), 0xFF27445E.toInt())).apply { cornerRadius = dp(20).toFloat() }; setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val opts = AppWidgetManager.getInstance(this).getAppWidgetOptions(wid)
        wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 300
        hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 200
        val maxH = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * 0.42f).toInt()
        pv.addView(preview, lp(-1, dp(minOf(hDp, maxH) + 28), top = 10))
        selBar = hbox()
        pv.addView(selBar, lp(top = 6))
        pv.addView(tv("Podgląd w rozmiarze Twojego widżetu. Rozmiar samego widżetu zmieniasz na ekranie: przytrzymaj go i przeciągnij uchwyty.", 11f, color = Theme.dim), lp(top = 6))
        root.addView(pv)
        val sv = ScrollView(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(30)) }
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = hbox().apply { setPadding(dp(16), dp(10), dp(16), dp(14)); setBackgroundColor(Theme.surface) }
        bar.addView(tv("Anuluj", 16f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = round(Theme.bg, 16, 1); setOnClickListener { finish() } }, lp(0, -2, 1f).apply { rightMargin = dp(8) })
        bar.addView(tv("Zapisz", 16f, true, Theme.onAccent).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = Theme.gradient(dp(16).toFloat()); setOnClickListener { save() } }, lp(0, -2, 1f))
        root.addView(bar)
        setContentView(root)
        build()
        preview.post { refresh() }
    }

    private fun refresh() {
        preview.removeAllViews()
        selBar.removeAllViews()
        try {
            if (d.free && android.os.Build.VERSION.SDK_INT >= 31) { freeEditor(); return }
            val v = WD.render(this, d, wDp, hDp).apply(this, preview)
            preview.addView(v, FrameLayout.LayoutParams(-1, -1))
        } catch (e: Exception) {
            preview.addView(tv("Podgląd niedostępny: ${e.message}", 12f, color = Color.WHITE))
        }
    }

    /** Układ swobodny: przeciągaj elementy palcem, rozsuwaj dwoma palcami, linie pomocnicze przyciągają do środka. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun freeEditor() {
        val den = resources.displayMetrics.density
        val availW = preview.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(60))
        val availH = preview.height.takeIf { it > 0 } ?: dp(200)
        val wPx = wDp * den; val hPx = hDp * den
        val sc = minOf(1f, (availW - dp(28)) / wPx, (availH - dp(28)) / hPx)
        val stage = FrameLayout(this)
        val v = WD.render(this, d, wDp, hDp).apply(this, stage)
        stage.addView(v, FrameLayout.LayoutParams(wPx.toInt(), hPx.toInt()))
        guide = View(this).apply { setBackgroundColor(0xCCFF375F.toInt()); visibility = View.GONE }
        stage.addView(guide, FrameLayout.LayoutParams(dp(2), hPx.toInt()).apply { leftMargin = (wPx / 2).toInt() })
        stage.pivotX = 0f; stage.pivotY = 0f; stage.scaleX = sc; stage.scaleY = sc
        preview.addView(stage, FrameLayout.LayoutParams(wPx.toInt(), hPx.toInt()))
        val free = v.findViewById<FrameLayout>(R.id.w_free) ?: return
        free.isMotionEventSplittingEnabled = false
        val keys = d.shown
        for (i in 0 until free.childCount) {
            val child = free.getChildAt(i); val key = keys.getOrNull(i) ?: continue
            if (key == sel) child.background = GradientDrawable().apply { setStroke(dp(2), Theme.accent); cornerRadius = dp(6).toFloat() }
            var lastX = 0f; var lastY = 0f; var scale = 1f
            val sg = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(g: android.view.ScaleGestureDetector): Boolean {
                    scale = (scale * g.scaleFactor).coerceIn(0.3f, 4f); child.pivotX = 0f; child.pivotY = 0f; child.scaleX = scale; child.scaleY = scale; return true
                }
            })
            child.setOnTouchListener { view, e ->
                sg.onTouchEvent(e)
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY; scale = 1f; if (sel != key) { sel = key; showSel() } }
                    android.view.MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !sg.isInProgress) {
                        view.translationX += (e.rawX - lastX) / sc; view.translationY += (e.rawY - lastY) / sc
                        lastX = e.rawX; lastY = e.rawY
                        // przyciąganie do środka w poziomie
                        val lpm = view.layoutParams as android.view.ViewGroup.MarginLayoutParams
                        val cx = lpm.leftMargin + view.translationX + view.width * view.scaleX / 2
                        val snap = Math.abs(cx - wPx / 2) < dp(8)
                        if (snap) view.translationX = wPx / 2 - view.width * view.scaleX / 2 - lpm.leftMargin
                        guide.visibility = if (snap) View.VISIBLE else View.GONE
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        guide.visibility = View.GONE
                        val lpm = view.layoutParams as android.view.ViewGroup.MarginLayoutParams
                        val old = d.pos[key] ?: floatArrayOf(0.05f, 0.05f, 1f)
                        val nx = ((lpm.leftMargin + view.translationX) / wPx).coerceIn(-0.2f, 0.98f)
                        val ny = ((lpm.topMargin + view.translationY) / hPx).coerceIn(-0.2f, 0.98f)
                        d.pos[key] = floatArrayOf(nx, ny, (old.getOrElse(2) { 1f } * scale).coerceIn(0.3f, 4f))
                        refresh()
                    }
                }
                true
            }
        }
        showSel()
    }

    /** Pasek pod podglądem: zaznaczony element, mniejszy / większy, wyśrodkuj. */
    private fun showSel() {
        selBar.removeAllViews()
        if (!d.free) return
        if (sel.isEmpty()) { selBar.addView(tv("Stuknij element na podglądzie, przeciągnij go palcem, rozsuń dwoma palcami.", 12f, color = Theme.dim)); return }
        selBar.addView(tv(WD.ELEMENTS[sel] ?: sel, 13f, true), lp(0, -2, 1f))
        fun b(t: String, f: () -> Unit) = tv(t, 15f, true).apply { setPadding(dp(12), dp(6), dp(12), dp(6)); background = round(Theme.surface, 12, 1); setOnClickListener { f(); refresh() } }
        val p = d.pos.getOrPut(sel) { floatArrayOf(0.05f, 0.05f, 1f) }
        selBar.addView(b("−") { p[2] = (p[2] / 1.1f).coerceAtLeast(0.3f) }, lp(-2, -2).apply { rightMargin = dp(6) })
        selBar.addView(b("+") { p[2] = (p[2] * 1.1f).coerceAtMost(4f) }, lp(-2, -2).apply { rightMargin = dp(6) })
        selBar.addView(b("⇔ Środek") {
            val free = preview.findViewById<FrameLayout>(R.id.w_free)
            val idx = d.shown.indexOf(sel); val ch = free?.getChildAt(idx)
            if (ch != null) p[0] = ((wDp * resources.displayMetrics.density - ch.width * ch.scaleX) / 2f / (wDp * resources.displayMetrics.density)).coerceAtLeast(0f)
        })
    }

    private fun build() {
        col.removeAllViews()
        header("Gotowe układy")
        chips(WD.PRESET_NAMES.map { it.key to it.value }, { "" }) { k ->
            d = WD.PRESETS[k]!!.copy().also { n -> for (e in WD.ELEMENTS.keys) if (e !in n.items) { n.items.add(e); n.off.add(e) } }
            build()
        }

        header("Układ")
        chips(listOf("list" to "Jeden pod drugim", "free" to "Swobodny – przeciągaj palcem"), { if (d.free) "free" else "list" }) {
            d.free = it == "free"
            if (d.free) for ((i, k) in d.shown.withIndex()) d.pos.getOrPut(k) { floatArrayOf(0.05f, (0.06f + 0.16f * i).coerceAtMost(0.9f), 1f) }
            build()
        }
        if (d.free && android.os.Build.VERSION.SDK_INT < 31) col.addView(tv("Układ swobodny wymaga Androida 12 lub nowszego.", 12f, color = Theme.dim))

        header("Co widać i w jakiej kolejności")
        col.addView(tv("Włącz elementy przełącznikiem, strzałkami ustaw kolejność od góry.", 12f, color = Theme.dim), lp(top = 2))
        for ((i, key) in d.items.withIndex()) {
            val r = hbox().apply { setPadding(0, dp(6), 0, dp(6)) }
            r.addView(Switch(this).apply { isChecked = key !in d.off; setOnCheckedChangeListener { _, b -> if (b) d.off.remove(key) else d.off.add(key); build(); refresh() } })
            r.addView(tv(WD.ELEMENTS[key] ?: key, 14f, key !in d.off, if (key in d.off) Theme.dim else Theme.fg), lp(0, -2, 1f).apply { leftMargin = dp(8) })
            r.addView(tv("▲", 18f, true, if (i > 0) Theme.fg else Theme.line).apply { setPadding(dp(12), dp(4), dp(8), dp(4)); setOnClickListener {
                if (i > 0) { d.items.add(i - 1, d.items.removeAt(i)); build(); refresh() } } })
            r.addView(tv("▼", 18f, true, if (i < d.items.size - 1) Theme.fg else Theme.line).apply { setPadding(dp(8), dp(4), dp(4), dp(4)); setOnClickListener {
                if (i < d.items.size - 1) { d.items.add(i + 1, d.items.removeAt(i)); build(); refresh() } } })
            col.addView(r)
        }
        if ("hours" !in d.off) slider("Godzin w prognozie", 2, 12, d.hours, "") { d.hours = it }
        if ("days" !in d.off) slider("Dni w prognozie", 2, 10, d.days, "") { d.days = it }
        if ("events" !in d.off) slider("Wpisów z kalendarza", 1, 8, d.events, "") { d.events = it }

        header("Tło")
        chips(listOf("sky" to "Niebo wg pogody", "theme" to "Kolor motywu", "dark" to "Ciemne", "light" to "Jasne", "color" to "Własny kolor", "none" to "Brak (przezroczyste)"), { d.bg }) {
            d.bg = it; if (it == "none") d.alpha = 0 else if (d.alpha == 0) d.alpha = 85; build()
        }
        if (d.bg == "color") {
            val pal = listOf(0xFF111827, 0xFF1E293B, 0xFF0F3D3E, 0xFF14532D, 0xFF3F2A56, 0xFF7C2D12, 0xFF7F1D1D, 0xFF1E3A8A, 0xFF0369A1, 0xFFF5F5F4, 0xFFFDE68A, 0xFFE5E7EB).map { it.toInt() }
            val r = hbox()
            for (cc in pal) r.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cc); setStroke(dp(if (d.color == cc) 3 else 1), if (d.color == cc) Theme.accent else Theme.line) }
                setOnClickListener { d.color = cc; build(); refresh() }
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(8) })
            col.addView(HorizontalScrollView(this).apply { addView(r) }, lp(top = 8))
        }
        if (d.bg != "none") slider("Krycie tła (0 = przezroczyste)", 0, 100, d.alpha, "%") { d.alpha = it }
        col.addView(tv("Zaokrąglenie rogów", 14f), lp(top = 10))
        chips(listOf("0" to "Proste", "12" to "Małe", "24" to "Średnie", "36" to "Duże"), { d.radius.toString() }) { d.radius = it.toInt() }

        header("Tekst")
        chips(listOf("auto" to "Kolor automatycznie", "white" to "Biały", "black" to "Czarny"), { d.text }) { d.text = it }
        slider("Wielkość tekstu", 70, 160, d.scale, "%") { d.scale = it }
        sw("Cień pod tekstem (czytelność na zdjęciu)", d.shadow) { d.shadow = it }

        header("Zegar i ikona")
        slider("Wielkość zegara", 20, 130, d.clock, " sp") { d.clock = it }
        sw("Pokazuj sekundy", d.seconds) { d.seconds = it }
        slider("Wielkość ikony pogody", 20, 110, d.icon, " dp") { d.icon = it }

        header("Położenie")
        col.addView(tv("W poziomie", 14f), lp(top = 4))
        chips(listOf("start" to "Do lewej", "center" to "Środek", "end" to "Do prawej"), { d.align }) { d.align = it }
        col.addView(tv("W pionie", 14f), lp(top = 8))
        chips(listOf("top" to "Góra", "center" to "Środek", "bottom" to "Dół"), { d.valign }) { d.valign = it }
        slider("Odstęp od krawędzi", 0, 40, d.pad, " dp") { d.pad = it }
    }

    private fun save() {
        WD.save(this, wid, d)
        try { AppWidgetManager.getInstance(this).updateAppWidget(wid, WD.render(this, d, wDp, hDp)) } catch (e: Exception) {}
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
        finish()
    }
}
