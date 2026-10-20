package tv.evma.search;

import java.io.IOException;

public final class WeatherDataTest {
    private WeatherDataTest() {}
    public static void main(String[] args) throws Exception {
        String sample = "{\"current_units\":{\"temperature_2m\":\"°F\"},"
                + "\"current\":{\"temperature_2m\":50.2,\"weather_code\":0,\"is_day\":1,\"time\":1730000000},"
                + "\"daily\":{\"temperature_2m_max\":[62.3],\"temperature_2m_min\":[42.1]}}";
        WeatherData data = WeatherData.parse(sample);
        check(data.temperature.equals("☀ 50°F"), "Fahrenheit and rounding");
        check(data.range.equals("H 62° · L 42°"), "Daily range");
        check(data.observedAt == 1730000000000L, "Observation seconds converted to milliseconds");
        check(WeatherData.parse(sample.replace("\"is_day\":1", "\"is_day\":0")).temperature.startsWith("☾"), "Night symbol");
        check(WeatherData.condition(65).equals("Rain"), "WMO rain condition");
        check(WeatherData.condition(86).equals("Snow showers"), "WMO snow condition");
        check(WeatherData.condition(1234).equals("Conditions unavailable"), "Unknown codes do not invent conditions");
        expectFailure(sample.replace("°F", "°C"));
        expectFailure(sample.replace("50.2", "null"));
        expectFailure("{}");
        System.out.println("Weather parsing tests passed (units, timestamps, day/night, codes and incomplete data).");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void expectFailure(String json) {
        try { WeatherData.parse(json); } catch (IOException expected) { return; }
        throw new AssertionError("Invalid weather data must not be displayed as current conditions");
    }
}
