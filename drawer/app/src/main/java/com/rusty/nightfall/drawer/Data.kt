package com.rusty.nightfall.drawer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.CalendarContract
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.wear.tiles.TileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

fun granted(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

fun battery(ctx: Context): Int = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

// ---------------------------------------------------------------- steps (Health Services, passive: no polling)
object Steps {
    suspend fun register(ctx: Context) {
        if (!granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) return
        val cfg = PassiveListenerConfig.builder().setDataTypes(setOf(DataType.STEPS_DAILY)).build()
        HealthServices.getClient(ctx).passiveMonitoringClient.setPassiveListenerServiceAsync(StepsService::class.java, cfg).await()
    }

    /** Today's steps, or null if none recorded yet today / no permission. */
    fun today(ctx: Context): Long? {
        val p = prefs(ctx)
        return if (p.getString("steps_day", "") == today()) p.getLong("steps", 0) else null
    }
}

class StepsService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val last = dataPoints.getData(DataType.STEPS_DAILY).lastOrNull() ?: return
        val p = prefs(this)
        p.edit().putLong("steps", last.value).putString("steps_day", today()).apply()
        val now = System.currentTimeMillis()
        if (now - p.getLong("steps_tile", 0) > 5 * 60_000) {   // the Day tile refreshes at most every 5 min for steps
            p.edit().putLong("steps_tile", now).apply()
            runCatching { TileService.getUpdater(this).requestUpdate(DayTileService::class.java) }
        }
    }
}

// ---------------------------------------------------------------- calendar
data class NextEvent(val start: Long, val title: String, val allDay: Boolean)

fun nextEvent(ctx: Context): NextEvent? {
    if (!granted(ctx, Manifest.permission.READ_CALENDAR)) return null
    val now = System.currentTimeMillis()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now); ContentUris.appendId(it, now + 36 * 3_600_000L)
    }.build()
    return runCatching {
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY), null, null,
            "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            var allDay: NextEvent? = null
            while (c.moveToNext()) {
                val ev = NextEvent(c.getLong(0), c.getString(2).orEmpty(), c.getInt(3) == 1)
                if (c.getLong(1) < now) continue
                if (!ev.allDay) return@use ev
                if (allDay == null) allDay = ev
            }
            allDay
        }
    }.getOrNull()
}

// ---------------------------------------------------------------- location (approximate, cached while Nightfall is open)
object Where {
    /** Called while a Nightfall screen is in the foreground, so no background-location permission is needed. */
    @SuppressLint("MissingPermission")
    suspend fun refresh(ctx: Context) {
        if (!granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val p = prefs(ctx)
        if (System.currentTimeMillis() - p.getLong("loc_time", 0) < 3 * 3_600_000L) return
        val lm = ctx.getSystemService(LocationManager::class.java)
        val loc = lm.getProviders(true).firstNotNullOfOrNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            ?: withTimeoutOrNull(8000) {
                suspendCancellableCoroutine { cont ->
                    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                        .firstOrNull { lm.isProviderEnabled(it) }
                    if (provider == null) cont.resume(null) else
                        lm.getCurrentLocation(provider, null, ctx.mainExecutor) { if (cont.isActive) cont.resume(it) }
                }
            }
            ?: return
        // Rounded to ~10 km: plenty for a forecast, and it's all that ever leaves the watch.
        p.edit().putFloat("lat", (loc.latitude * 10).roundToInt() / 10f).putFloat("lon", (loc.longitude * 10).roundToInt() / 10f)
            .putLong("loc_time", System.currentTimeMillis()).apply()
    }

    fun get(ctx: Context): Pair<Float, Float>? {
        val p = prefs(ctx)
        return if (p.contains("lat")) p.getFloat("lat", 0f) to p.getFloat("lon", 0f) else null
    }
}

// ---------------------------------------------------------------- weather (Open-Meteo, no account, cached 30 min)
data class Hour(val label: String, val temp: Int, val code: Int, val day: Boolean)
data class Weather(val temp: Int, val code: Int, val day: Boolean, val hi: Int, val lo: Int, val hours: List<Hour>, val time: Long)

object WeatherRepo {
    private val fahrenheit get() = Locale.getDefault().country in setOf("US", "LR", "MM")

    suspend fun get(ctx: Context): Weather? {
        val p = prefs(ctx)
        cached(ctx)?.let { if (System.currentTimeMillis() - it.time < 30 * 60_000L) return it }
        val (lat, lon) = Where.get(ctx) ?: return cached(ctx)
        val raw = withContext(Dispatchers.IO) {
            runCatching {
                val u = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto" +
                    (if (fahrenheit) "&temperature_unit=fahrenheit" else ""))
                (u.openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000; readTimeout = 8000
                    inputStream.bufferedReader().use { it.readText() }
                }
            }.getOrNull()
        } ?: return cached(ctx)
        p.edit().putString("weather", raw).putLong("weather_time", System.currentTimeMillis()).apply()
        return cached(ctx)
    }

    private fun cached(ctx: Context): Weather? {
        val p = prefs(ctx)
        val raw = p.getString("weather", null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val cur = j.getJSONObject("current")
            val hourly = j.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val nowKey = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).format(Date())
            var start = (0 until times.length()).firstOrNull { times.getString(it) >= nowKey } ?: 0
            start = (start + 1).coerceAtMost(times.length() - 1)
            val hours = (start until (start + 9).coerceAtMost(times.length()) step 2).map { i ->
                val h = times.getString(i).substring(11, 13).toInt()
                val label = if (h == 0) "12A" else if (h < 12) "${h}A" else if (h == 12) "12P" else "${h - 12}P"
                Hour(label, hourly.getJSONArray("temperature_2m").getDouble(i).roundToInt(),
                    hourly.getJSONArray("weather_code").getInt(i), hourly.getJSONArray("is_day").getInt(i) == 1)
            }.take(4)
            val daily = j.getJSONObject("daily")
            Weather(cur.getDouble("temperature_2m").roundToInt(), cur.getInt("weather_code"), cur.getInt("is_day") == 1,
                daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(), hours, p.getLong("weather_time", 0))
        }.getOrNull()
    }

    /** WMO weather code -> pixel icon + short name. */
    fun look(code: Int, day: Boolean): Pair<List<String>, String> = when (code) {
        0 -> (if (day) Icons.SUN else Icons.MOON) to "CLEAR"
        1, 2 -> Icons.PARTLY to "PARTLY CLOUDY"
        3 -> Icons.CLOUD to "CLOUDY"
        45, 48 -> Icons.FOG to "FOG"
        in 51..67, in 80..82 -> Icons.RAIN to if (code in 51..57) "DRIZZLE" else "RAIN"
        in 71..77, 85, 86 -> Icons.SNOW to "SNOW"
        in 95..99 -> Icons.STORM to "STORM"
        else -> Icons.CLOUD to "--"
    }
}

// ---------------------------------------------------------------- Flick presets (via Flick's signature-protected provider)
data class FlickPreset(val id: String, val name: String, val emoji: String, val active: Boolean)

object Flick {
    private val URI = Uri.parse("content://com.rusty.flick.presets/presets")
    const val PKG = "com.rusty.flick"

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.isSuccess

    fun presets(ctx: Context): List<FlickPreset>? = runCatching {
        ctx.contentResolver.query(URI, null, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(FlickPreset(c.getString(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("name")),
                    c.getString(c.getColumnIndexOrThrow("emoji")), c.getInt(c.getColumnIndexOrThrow("active")) == 1))
            }
        }
    }.getOrNull()

    /** Make [id] the beaming preset ("" = beaming off). */
    fun select(ctx: Context, id: String) {
        ctx.sendBroadcast(Intent("com.rusty.flick.SELECT_PRESET").setPackage(PKG).putExtra("id", id), "com.rusty.flick.permission.PRESETS")
    }
}

fun hourMinute(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    return "%d:%02d%s".format(h, c.get(Calendar.MINUTE), if (c.get(Calendar.AM_PM) == Calendar.AM) "A" else "P")
}
