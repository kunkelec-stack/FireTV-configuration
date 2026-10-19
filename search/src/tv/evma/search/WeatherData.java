package tv.evma.search;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Locale;

final class WeatherData {
    final String temperature, condition, range;
    final long observedAt;
    private WeatherData(String temperature, String condition, String range, long observedAt) {
        this.temperature = temperature; this.condition = condition;
        this.range = range; this.observedAt = observedAt;
    }
    static WeatherData parse(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject current = root.getJSONObject("current");
            if (!"°F".equals(root.getJSONObject("current_units").getString("temperature_2m"))) throw new IOException("Unexpected weather units");
            double temperature = current.getDouble("temperature_2m");
            long seconds = current.getLong("time");
            if (!Double.isFinite(temperature) || seconds <= 0) throw new IOException("Incomplete weather data");
            int code = current.getInt("weather_code");
            boolean day = current.optInt("is_day", 1) == 1;
            String range = "";
            JSONObject daily = root.optJSONObject("daily");
            if (daily != null) {
                JSONArray high = daily.optJSONArray("temperature_2m_max");
                JSONArray low = daily.optJSONArray("temperature_2m_min");
                if (high != null && low != null && Double.isFinite(high.optDouble(0)) && Double.isFinite(low.optDouble(0))) {
                    range = String.format(Locale.US, "H %.0f° · L %.0f°", high.getDouble(0), low.getDouble(0));
                }
            }
            return new WeatherData(symbol(code, day) + " " + Math.round(temperature) + "°F", condition(code), range, seconds * 1000);
        } catch (JSONException e) { throw new IOException("Incomplete weather response", e); }
    }
    static String condition(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: case 63: case 65: return "Rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Rain showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Thunderstorms with hail";
            default: return "Conditions unavailable";
        }
    }
    private static String symbol(int code, boolean day) {
        if (code == 0 || code == 1) return day ? "☀" : "☾";
        if (code >= 95) return "ϟ";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return "☂";
        return "☁";
    }
}
