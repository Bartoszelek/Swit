package pl.swit

import android.content.Context
import android.location.Location
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Miejsce prognozy. gps = „Moja lokalizacja” (współrzędne odświeżane z telefonu). */
class Place(val name: String, val lat: Double, val lon: Double, val gps: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("n", name).put("la", lat).put("lo", lon).put("g", gps)
    companion object {
        fun of(o: JSONObject) = Place(o.optString("n"), o.optDouble("la"), o.optDouble("lo"), o.optBoolean("g"))
    }
}

/** Jedna godzina / dzień prognozy. */
class Hour(val t: Long, val temp: Double, val code: Int, val pop: Int, val rain: Double, val wind: Double, val day: Boolean)
class Day(val t: Long, val code: Int, val max: Double, val min: Double, val rain: Double, val pop: Int, val gusts: Double, val uv: Double, val sunrise: Long, val sunset: Long)

class Forecast(
    val place: Place, val at: Long,
    val temp: Double, val feels: Double, val code: Int, val day: Boolean, val humidity: Int, val pressure: Double,
    val wind: Double, val gusts: Double, val windDir: Int, val clouds: Int, val rainNow: Double,
    val hours: List<Hour>, val days: List<Day>, val q15: List<Pair<Long, Double>>,
    val aqi: Int, val pm25: Double, val pm10: Double, val pollen: Map<String, Double>
)

object Weather {
    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ------------------------------------------------------------ miejsca
    fun places(c: Context): List<Place> {
        val a = try { JSONArray(Prefs.get(c).getString("places", "[]")) } catch (e: Exception) { JSONArray() }
        val l = (0 until a.length()).map { Place.of(a.getJSONObject(it)) }
        return if (l.isEmpty()) listOf(Place("Moja lokalizacja", 51.25, 22.57, true)) else l
    }

    fun savePlaces(c: Context, l: List<Place>) = Prefs.get(c).edit().putString("places", JSONArray(l.map { it.json() }).toString()).apply()

    fun current(c: Context): Place { val l = places(c); return l[Prefs.get(c).getInt("place", 0).coerceIn(0, l.size - 1)] }

    fun select(c: Context, i: Int) = Prefs.get(c).edit().putInt("place", i).apply()

    /** Wyszukiwanie miejscowości (po polsku). */
    fun search(q: String): List<Place> {
        val js = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&language=pl&name=" + URLEncoder.encode(q, "UTF-8")))
        val a = js.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val extra = listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() && it != "null" }.joinToString(", ")
            Place(o.optString("name") + if (extra.isNotEmpty()) " ($extra)" else "", o.optDouble("latitude"), o.optDouble("longitude"))
        }
    }

    @Suppress("MissingPermission")
    private fun gps(c: Context): Location? {
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        return try {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    // ------------------------------------------------------------ pobieranie
    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000; con.readTimeout = 25_000
        con.setRequestProperty("User-Agent", "Swit")
        if (con.responseCode != 200) throw IllegalStateException("Serwer pogody: kod ${con.responseCode}")
        return con.inputStream.bufferedReader().use { it.readText() }
    }

    private fun cacheFile(c: Context) = File(c.filesDir, "forecast.json")

    /** Pobiera prognozę dla bieżącego miejsca i zapisuje w pamięci. */
    fun refresh(c: Context): Forecast {
        var p = current(c)
        if (p.gps) gps(c)?.let { loc ->
            var name = p.name
            try {
                val g = android.location.Geocoder(c, Locale("pl"))
                @Suppress("DEPRECATION")
                g.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { a -> name = a.locality ?: a.subAdminArea ?: a.adminArea ?: name }
            } catch (e: Exception) {
            }
            p = Place(if (name == "Moja lokalizacja") name else name, loc.latitude, loc.longitude, true)
        }
        val ll = "latitude=${p.lat}&longitude=${p.lon}"
        val f = JSONObject(get("https://api.open-meteo.com/v1/forecast?$ll&timezone=auto&forecast_days=16&wind_speed_unit=kmh" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,is_day" +
            "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_gusts_10m_max,uv_index_max,sunrise,sunset" +
            "&minutely_15=precipitation&forecast_minutely_15=12"))
        val aq = try {
            JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?$ll&timezone=auto" +
                "&current=european_aqi,pm2_5,pm10,alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,ragweed_pollen"))
        } catch (e: Exception) { JSONObject() }
        val o = JSONObject().put("place", p.json()).put("at", System.currentTimeMillis()).put("f", f).put("aq", aq)
        cacheFile(c).writeText(o.toString())
        return parse(o)
    }

    /** Ostatnia zapisana prognoza (bez internetu). */
    fun cached(c: Context): Forecast? = try { parse(JSONObject(cacheFile(c).readText())) } catch (e: Exception) { null }

    fun fresh(c: Context, maxAgeMin: Int = 45): Forecast? {
        val f = cached(c)
        val p = current(c)
        val same = f != null && (if (p.gps) f.place.gps else Math.abs(f.place.lat - p.lat) < 0.01 && Math.abs(f.place.lon - p.lon) < 0.01)
        if (f != null && same && System.currentTimeMillis() - f.at < maxAgeMin * 60_000L) return f
        return try { refresh(c) } catch (e: Exception) { f }
    }

    private fun t(s: String): Long = try { ISO.parse(s)!!.time } catch (e: Exception) { try { DAY.parse(s)!!.time } catch (x: Exception) { 0L } }

    private fun parse(o: JSONObject): Forecast {
        val f = o.getJSONObject("f")
        val cur = f.getJSONObject("current")
        val h = f.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val now = System.currentTimeMillis() - 3600_000L
        val hours = (0 until ht.length()).map { i ->
            Hour(t(ht.getString(i)), h.getJSONArray("temperature_2m").optDouble(i), h.getJSONArray("weather_code").optInt(i),
                h.getJSONArray("precipitation_probability").optInt(i), h.getJSONArray("precipitation").optDouble(i, 0.0),
                h.getJSONArray("wind_speed_10m").optDouble(i), h.getJSONArray("is_day").optInt(i) == 1)
        }.filter { it.t >= now }
        val d = f.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = (0 until dt.length()).map { i ->
            Day(t(dt.getString(i)), d.getJSONArray("weather_code").optInt(i), d.getJSONArray("temperature_2m_max").optDouble(i), d.getJSONArray("temperature_2m_min").optDouble(i),
                d.getJSONArray("precipitation_sum").optDouble(i, 0.0), d.getJSONArray("precipitation_probability_max").optInt(i), d.getJSONArray("wind_gusts_10m_max").optDouble(i),
                d.getJSONArray("uv_index_max").optDouble(i, 0.0), t(d.getJSONArray("sunrise").optString(i)), t(d.getJSONArray("sunset").optString(i)))
        }
        val q15 = f.optJSONObject("minutely_15")?.let { m ->
            val tt = m.getJSONArray("time"); val pr = m.getJSONArray("precipitation")
            (0 until tt.length()).map { Pair(t(tt.getString(it)), pr.optDouble(it, 0.0)) }
        } ?: emptyList()
        val aq = o.optJSONObject("aq")?.optJSONObject("current") ?: JSONObject()
        val pollen = linkedMapOf("Olcha" to aq.optDouble("alder_pollen", -1.0), "Brzoza" to aq.optDouble("birch_pollen", -1.0), "Trawy" to aq.optDouble("grass_pollen", -1.0),
            "Bylica" to aq.optDouble("mugwort_pollen", -1.0), "Ambrozja" to aq.optDouble("ragweed_pollen", -1.0)).filter { it.value >= 0 }
        return Forecast(Place.of(o.getJSONObject("place")), o.optLong("at"),
            cur.optDouble("temperature_2m"), cur.optDouble("apparent_temperature"), cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1,
            cur.optInt("relative_humidity_2m"), cur.optDouble("pressure_msl"), cur.optDouble("wind_speed_10m"), cur.optDouble("wind_gusts_10m"),
            cur.optInt("wind_direction_10m"), cur.optInt("cloud_cover"), cur.optDouble("precipitation", 0.0),
            hours, days, q15, aq.optInt("european_aqi", -1), aq.optDouble("pm2_5", -1.0), aq.optDouble("pm10", -1.0), pollen)
    }

    // ------------------------------------------------------------ opisy i ikony (kody WMO)
    fun text(code: Int): String = when (code) {
        0 -> "Bezchmurnie"; 1 -> "Przeważnie słonecznie"; 2 -> "Częściowe zachmurzenie"; 3 -> "Pochmurno"
        45, 48 -> "Mgła"; 51, 53, 55 -> "Mżawka"; 56, 57 -> "Marznąca mżawka"
        61 -> "Słaby deszcz"; 63 -> "Deszcz"; 65 -> "Ulewa"; 66, 67 -> "Marznący deszcz"
        71 -> "Słaby śnieg"; 73 -> "Śnieg"; 75 -> "Intensywny śnieg"; 77 -> "Krupa śnieżna"
        80 -> "Przelotny deszcz"; 81 -> "Przelotne opady"; 82 -> "Gwałtowna ulewa"; 85, 86 -> "Przelotny śnieg"
        95 -> "Burza"; 96, 99 -> "Burza z gradem"; else -> "—"
    }

    fun icon(code: Int, day: Boolean = true): Int = when (code) {
        0, 1 -> if (day) R.drawable.w_sun else R.drawable.w_moon
        2 -> if (day) R.drawable.w_partly else R.drawable.w_partly_night
        3 -> R.drawable.w_cloud
        45, 48 -> R.drawable.w_fog
        51, 53, 55, 56, 57 -> R.drawable.w_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.w_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.w_snow
        95, 96, 99 -> R.drawable.w_storm
        else -> R.drawable.w_cloud
    }

    fun deg(v: Double) = "${v.roundToInt()}°"

    fun windDir(d: Int) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((d + 22) % 360) / 45]

    fun aqiText(a: Int) = when { a < 0 -> "brak danych"; a <= 20 -> "bardzo dobra"; a <= 40 -> "dobra"; a <= 60 -> "umiarkowana"; a <= 80 -> "słaba"; a <= 100 -> "zła"; else -> "bardzo zła" }

    fun pollenText(v: Double) = when { v < 1 -> "brak"; v < 20 -> "niskie"; v < 100 -> "średnie"; v < 500 -> "wysokie"; else -> "bardzo wysokie" }

    /** Kiedy zacznie lub przestanie padać (najbliższe 3 godziny, co 15 min). */
    fun nowcast(f: Forecast): String {
        val now = System.currentTimeMillis()
        val q = f.q15.filter { it.first >= now - 15 * 60_000L }
        if (q.isEmpty()) return ""
        val wet = q.first().second >= 0.1
        val change = q.indexOfFirst { (it.second >= 0.1) != wet }
        return when {
            !wet && change < 0 -> "Bez opadów przez najbliższe 3 godziny"
            !wet -> "Za ok. ${((q[change].first - now) / 60_000).coerceAtLeast(5)} min zacznie padać"
            change < 0 -> "Pada i będzie padać przez najbliższe 3 godziny"
            else -> "Przestanie padać za ok. ${((q[change].first - now) / 60_000).coerceAtLeast(5)} min"
        }
    }

    /** Ostrzeżenia wyliczone z prognozy na najbliższe 24 h. */
    fun warnings(f: Forecast): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val lim = System.currentTimeMillis() + 24 * 3600_000L
        val hrs = f.hours.filter { it.t <= lim }
        val df = SimpleDateFormat("HH:mm", Locale("pl"))
        hrs.firstOrNull { it.code >= 95 }?.let { out += "storm" to "Burza ok. ${df.format(Date(it.t))}" + if (it.code >= 96) " – możliwy grad" else "" }
        val d0 = f.days.firstOrNull()
        val d1 = f.days.getOrNull(1)
        val gust = listOfNotNull(d0?.gusts, f.gusts).maxOrNull() ?: 0.0
        if (gust >= 70) out += "wind" to "Silny wiatr – porywy do ${gust.roundToInt()} km/h"
        val rain24 = hrs.sumOf { it.rain }
        if (rain24 >= 20) out += "rain" to "Intensywne opady – ok. ${rain24.roundToInt()} mm w ciągu doby"
        if (hrs.any { it.code in listOf(56, 57, 66, 67) }) out += "ice" to "Marznące opady – gołoledź"
        val minT = hrs.minOfOrNull { it.temp } ?: 99.0
        if (minT <= -15) out += "frost" to "Silny mróz – do ${minT.roundToInt()}°"
        else if (minT <= 0 && Calendar.getInstance().get(Calendar.MONTH) in 3..5) out += "frost" to "Przymrozek – do ${minT.roundToInt()}°"
        val maxT = listOfNotNull(d0?.max, d1?.max).maxOrNull() ?: 0.0
        if (maxT >= 30) out += "heat" to "Upał – do ${maxT.roundToInt()}°"
        if ((d0?.uv ?: 0.0) >= 7) out += "uv" to "Bardzo wysoki indeks UV (${d0!!.uv.roundToInt()})"
        if (f.aqi > 80) out += "air" to "Zła jakość powietrza (indeks ${f.aqi})"
        return out
    }

    /** Krótkie podsumowanie dnia (powiadomienie poranne, budzik). */
    fun summary(f: Forecast): String {
        val d = f.days.firstOrNull() ?: return "${deg(f.temp)}, ${text(f.code).lowercase()}"
        val rain = if (d.pop >= 40) ", opady ${d.pop}%" else ""
        return "Teraz ${deg(f.temp)}, ${text(f.code).lowercase()}. Dziś od ${deg(d.min)} do ${deg(d.max)}$rain."
    }

    /** Tekst do odczytania na głos przy budziku. */
    fun speech(f: Forecast): String {
        val d = f.days.firstOrNull()
        val sb = StringBuilder("Na zewnątrz ${f.temp.roundToInt()} stopni, ${text(f.code).lowercase()}. ")
        if (d != null) sb.append("Dziś najwyżej ${d.max.roundToInt()} stopni. ")
        if (d != null && d.pop >= 50) sb.append("Weź parasol, szansa opadów ${d.pop} procent. ")
        warnings(f).firstOrNull()?.let { sb.append("Uwaga: ${it.second}.") }
        return sb.toString()
    }
}
