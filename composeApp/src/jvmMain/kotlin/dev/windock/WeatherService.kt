package dev.windock

import dev.windock.mvi.WeatherStatus
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale

/**
 * Current weather from Open-Meteo (free, no API key), either for the device's own position or for a city
 * the user typed. Positions are named with OpenStreetMap's Nominatim; both lookups are cached, so the
 * network is only hit for the forecast itself every refresh.
 */
internal class WeatherService {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val cityCoordinates = HashMap<String, Pair<Double, Double>>()
    private val placeNames = HashMap<String, String>()

    /** Weather for a typed city name; null when the city can't be found or the service is unreachable. */
    fun forCity(city: String): WeatherStatus? {
        val (latitude, longitude) = cityCoordinates[city.lowercase()]
            ?: geocode(city)?.also { cityCoordinates[city.lowercase()] = it } ?: return null
        return forecast(latitude, longitude, city, fromDevice = false)
    }

    /** Weather where the device is, named after its municipality. */
    fun forPosition(latitude: Double, longitude: Double): WeatherStatus? {
        val name = municipality(latitude, longitude) ?: return null
        return forecast(latitude, longitude, name, fromDevice = true)
    }

    private fun forecast(latitude: Double, longitude: Double, place: String, fromDevice: Boolean): WeatherStatus? {
        val body = get("https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,weather_code,is_day&timezone=auto") ?: return null
        // Read from the "current" object only; "current_units" repeats the same keys with string values.
        val current = body.substringAfter("\"current\":{", "").substringBefore('}')
        val temperature = number(current, "temperature_2m") ?: return null
        return WeatherStatus(
            city = place,
            temperatureC = Math.round(temperature).toInt(),
            code = number(current, "weather_code")?.toInt() ?: 0,
            isDay = number(current, "is_day")?.toInt() != 0,
            updatedAtMs = System.currentTimeMillis(),
            fromDevice = fromDevice,
        )
    }

    private fun geocode(city: String): Pair<Double, Double>? {
        val name = URLEncoder.encode(city, Charsets.UTF_8)
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=1&language=pt&format=json") ?: return null
        val first = body.substringAfter("\"results\":[", "").ifEmpty { return null }
        return (number(first, "latitude") ?: return null) to (number(first, "longitude") ?: return null)
    }

    /**
     * The municipality containing the point. Rounded to ~1 km for the cache, which also keeps the
     * request coarse; Nominatim's usage policy asks for an identifying User-Agent and few requests.
     */
    private fun municipality(latitude: Double, longitude: Double): String? {
        val key = String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude)
        placeNames[key]?.let { return it }
        val body = get(
            String.format(Locale.ROOT, "https://nominatim.openstreetmap.org/reverse?lat=%.2f&lon=%.2f&format=jsonv2&zoom=10&accept-language=pt-BR", latitude, longitude),
        ) ?: return null
        val address = body.substringAfter("\"address\":{", "").substringBefore('}')
        // Brazilian municipalities come back as "city", "town" or "municipality" depending on their size.
        val name = listOf("city", "town", "municipality", "village").firstNotNullOfOrNull { text(address, it) } ?: return null
        placeNames[key] = name
        return name
    }

    private fun get(url: String): String? = runCatching {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "WinDock/0.1 (desktop dock; weather tile)").build()
        http.send(request, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()
    }.getOrNull()

    private fun number(json: String, key: String): Double? =
        Regex("\"$key\":(-?[0-9]+(?:\\.[0-9]+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun text(json: String, key: String): String? =
        Regex("\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }

    private fun unescape(value: String): String =
        Regex("\\\\u([0-9a-fA-F]{4})|\\\\(.)").replace(value) { m ->
            m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt(16)?.toChar()?.toString() ?: m.groupValues[2]
        }
}
