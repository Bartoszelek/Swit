package pl.swit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** Szczegółowa prognoza do ekranu „Zaawansowana pogoda”. */
class Adv(
    val place: Place, val at: Long, val model: String,
    // godzinowe (16 dni)
    val ht: LongArray, val temp: DoubleArray, val feels: DoubleArray, val dew: DoubleArray, val rh: DoubleArray,
    val rain: DoubleArray, val prob: DoubleArray, val snow: DoubleArray, val code: IntArray, val day: BooleanArray,
    val cloud: DoubleArray, val cLow: DoubleArray, val cMid: DoubleArray, val cHigh: DoubleArray,
    val press: DoubleArray, val vis: DoubleArray, val wind: DoubleArray, val gust: DoubleArray, val dir: DoubleArray,
    val uv: DoubleArray, val cape: DoubleArray, val freeze: DoubleArray,
    // dzienne (16 dni)
    val dt: LongArray, val dCode: IntArray, val dMax: DoubleArray, val dMin: DoubleArray, val dFMax: DoubleArray, val dFMin: DoubleArray,
    val dSum: DoubleArray, val dHours: DoubleArray, val dProb: DoubleArray, val dSnow: DoubleArray, val dWind: DoubleArray, val dGust: DoubleArray,
    val dDir: DoubleArray, val dUv: DoubleArray, val dRise: LongArray, val dSet: LongArray, val dSun: DoubleArray,
    // trend do ~46 dni: średnia z członków ensemble i rozrzut (najniższy/najwyższy)
    val xt: LongArray, val xMax: DoubleArray, val xMin: DoubleArray, val xMaxLo: DoubleArray, val xMaxHi: DoubleArray, val xSum: DoubleArray, val xSource: String
) {
    /** Temperatura nocy: średnia z godzin 22–6. */
    fun night(i: Int): Double {
        val start = dt[i] + 22 * 3600_000L; val end = start + 8 * 3600_000L
        val v = ht.indices.filter { ht[it] in start until end }.map { temp[it] }.filter { !it.isNaN() }
        return if (v.isEmpty()) Double.NaN else v.average()
    }
}

object AdvWeather {
    val MODELS = linkedMapOf("best_match" to "Najlepszy dla miejsca", "ecmwf_ifs025" to "ECMWF (Europa)", "icon_seamless" to "ICON (Niemcy)",
        "gfs_seamless" to "GFS (USA)", "meteofrance_seamless" to "Météo-France", "ukmo_seamless" to "UK Met Office")

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun t(s: String): Long = try { ISO.parse(s)!!.time } catch (e: Exception) { try { DAY.parse(s)!!.time } catch (x: Exception) { 0L } }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 40_000; c.setRequestProperty("User-Agent", "Swit")
        if (c.responseCode != 200) throw IllegalStateException("kod ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun file(c: Context, p: Place, m: String) = File(c.cacheDir, "adv_${Weather.key(p).replace(',', '_')}_$m.json")

    /** Pobiera (lub bierze z pamięci, jeśli świeże) pełne dane. */
    fun load(c: Context, p: Place, model: String, force: Boolean = false): Adv {
        val f = file(c, p, model)
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 30 * 60_000L) try { return parse(p, model, JSONObject(f.readText())) } catch (e: Exception) {}
        val ll = "latitude=${p.lat}&longitude=${p.lon}&timezone=auto"
        val m = if (model == "best_match") "" else "&models=$model"
        val main = JSONObject(get("https://api.open-meteo.com/v1/forecast?$ll&forecast_days=16&wind_speed_unit=kmh$m" +
            "&hourly=temperature_2m,apparent_temperature,dew_point_2m,relative_humidity_2m,precipitation,precipitation_probability,snowfall,weather_code,is_day," +
            "cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,pressure_msl,visibility,wind_speed_10m,wind_gusts_10m,wind_direction_10m,uv_index,cape,freezing_level_height" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_sum,precipitation_hours," +
            "precipitation_probability_max,snowfall_sum,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,uv_index_max,sunrise,sunset,sunshine_duration"))
        // trend do 46 dni – ECMWF EC46 (seasonal API), awaryjnie ensemble GFS (35 dni)
        var ext = JSONObject(); var src = ""
        val daily = "daily=temperature_2m_max,temperature_2m_min,precipitation_sum"
        for ((url, name) in listOf(
            "https://seasonal-api.open-meteo.com/v1/seasonal?$ll&$daily&forecast_days=46&models=ecmwf_ec46" to "ECMWF EC46",
            "https://seasonal-api.open-meteo.com/v1/seasonal?$ll&$daily&forecast_days=46" to "ECMWF (sezonowa)",
            "https://ensemble-api.open-meteo.com/v1/ensemble?$ll&$daily&forecast_days=35&models=gfs_seamless" to "GFS ensemble")) {
            try { val o = JSONObject(get(url)); if (o.optJSONObject("daily") != null) { ext = o; src = name; break } } catch (e: Exception) {}
        }
        val all = JSONObject().put("main", main).put("ext", ext).put("src", src).put("at", System.currentTimeMillis())
        f.writeText(all.toString())
        return parse(p, model, all)
    }

    private fun arr(o: JSONObject?, k: String, n: Int): DoubleArray { val a = o?.optJSONArray(k); return DoubleArray(n) { i -> a?.optDouble(i, Double.NaN) ?: Double.NaN } }
    private fun ints(o: JSONObject?, k: String, n: Int): IntArray { val a = o?.optJSONArray(k); return IntArray(n) { i -> a?.optInt(i, 3) ?: 3 } }

    /** Średnia i rozrzut z członków ensemble (klucze „x”, „x_member01”, …). */
    private fun members(o: JSONObject, base: String, n: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val keys = o.keys().asSequence().filter { it == base || it.startsWith(base + "_member") }.toList()
        val mean = DoubleArray(n); val lo = DoubleArray(n) { Double.NaN }; val hi = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            val v = keys.mapNotNull { k -> o.optJSONArray(k)?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() } }
            mean[i] = if (v.isEmpty()) Double.NaN else v.average()
            if (v.isNotEmpty()) { val s = v.sorted(); lo[i] = s[(s.size * 0.1).toInt().coerceAtMost(s.size - 1)]; hi[i] = s[(s.size * 0.9).toInt().coerceAtMost(s.size - 1)] }
        }
        return Triple(mean, lo, hi)
    }

    private fun parse(p: Place, model: String, all: JSONObject): Adv {
        val m = all.getJSONObject("main")
        val h = m.getJSONObject("hourly"); val hn = h.getJSONArray("time").length()
        val d = m.getJSONObject("daily"); val dn = d.getJSONArray("time").length()
        val ht = LongArray(hn) { t(h.getJSONArray("time").getString(it)) }
        val dt = LongArray(dn) { t(d.getJSONArray("time").getString(it)) }
        val e = all.optJSONObject("ext")?.optJSONObject("daily")
        val en = e?.optJSONArray("time")?.length() ?: 0
        val et = LongArray(en) { t(e!!.getJSONArray("time").getString(it)) }
        val (xMax, xMaxLo, xMaxHi) = if (e != null) members(e, "temperature_2m_max", en) else Triple(DoubleArray(0), DoubleArray(0), DoubleArray(0))
        val xMin = if (e != null) members(e, "temperature_2m_min", en).first else DoubleArray(0)
        val xSum = if (e != null) members(e, "precipitation_sum", en).first else DoubleArray(0)
        return Adv(p, all.optLong("at"), model,
            ht, arr(h, "temperature_2m", hn), arr(h, "apparent_temperature", hn), arr(h, "dew_point_2m", hn), arr(h, "relative_humidity_2m", hn),
            arr(h, "precipitation", hn), arr(h, "precipitation_probability", hn), arr(h, "snowfall", hn), ints(h, "weather_code", hn),
            BooleanArray(hn) { h.optJSONArray("is_day")?.optInt(it, 1) == 1 },
            arr(h, "cloud_cover", hn), arr(h, "cloud_cover_low", hn), arr(h, "cloud_cover_mid", hn), arr(h, "cloud_cover_high", hn),
            arr(h, "pressure_msl", hn), arr(h, "visibility", hn), arr(h, "wind_speed_10m", hn), arr(h, "wind_gusts_10m", hn), arr(h, "wind_direction_10m", hn),
            arr(h, "uv_index", hn), arr(h, "cape", hn), arr(h, "freezing_level_height", hn),
            dt, ints(d, "weather_code", dn), arr(d, "temperature_2m_max", dn), arr(d, "temperature_2m_min", dn), arr(d, "apparent_temperature_max", dn),
            arr(d, "apparent_temperature_min", dn), arr(d, "precipitation_sum", dn), arr(d, "precipitation_hours", dn), arr(d, "precipitation_probability_max", dn),
            arr(d, "snowfall_sum", dn), arr(d, "wind_speed_10m_max", dn), arr(d, "wind_gusts_10m_max", dn), arr(d, "wind_direction_10m_dominant", dn),
            arr(d, "uv_index_max", dn), LongArray(dn) { t(d.optJSONArray("sunrise")?.optString(it) ?: "") }, LongArray(dn) { t(d.optJSONArray("sunset")?.optString(it) ?: "") },
            arr(d, "sunshine_duration", dn),
            et, xMax, xMin, xMaxLo, xMaxHi, xSum, all.optString("src"))
    }
}
