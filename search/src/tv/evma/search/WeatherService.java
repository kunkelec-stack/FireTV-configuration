package tv.evma.search;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Bound only while home is visible. Accepts requests from the same-signed launcher. */
public final class WeatherService extends Service {
    private static final long REFRESH = 20 * 60 * 1000L, RETRY = 5 * 60 * 1000L;
    // Fairfield city center, Ohio; no device location or location permission.
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=39.34589&longitude=-84.5605"
            + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
            + "&temperature_unit=fahrenheit&timezone=America%2FNew_York&timeformat=unixtime&forecast_days=1";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what != 1 || message.replyTo == null || !trusted(message.sendingUid)) return true;
        Messenger reply = message.replyTo;
        io.execute(() -> respond(reply));
        return true;
    }));
    private boolean trusted(int uid) {
        String[] packages = getPackageManager().getPackagesForUid(uid);
        return packages != null && Arrays.asList(packages).contains("tv.dumbtv.home")
                && getPackageManager().checkSignatures("tv.dumbtv.home", getPackageName()) == PackageManager.SIGNATURE_MATCH;
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    private void respond(Messenger reply) {
        SharedPreferences prefs = getSharedPreferences("weather", MODE_PRIVATE);
        long now = System.currentTimeMillis(), age = now - prefs.getLong("fetchedAt", 0);
        String saved = prefs.getString("json", "");
        boolean cached = false;
        WeatherData data = null;
        try {
            if (!saved.isEmpty() && age >= 0 && age < REFRESH) {
                data = WeatherData.parse(saved);
            } else if (prefs.getLong("retryAfter", 0) - now > 0 && prefs.getLong("retryAfter", 0) - now <= RETRY) {
                cached = true;
                if (!saved.isEmpty()) data = WeatherData.parse(saved);
            } else {
                String fresh = fetch();
                data = WeatherData.parse(fresh);
                prefs.edit().putString("json", fresh).putLong("fetchedAt", now).remove("retryAfter").apply();
            }
        } catch (IOException e) {
            prefs.edit().putLong("retryAfter", now + RETRY).apply();
            cached = true;
            try { if (!saved.isEmpty()) data = WeatherData.parse(saved); }
            catch (IOException ignored) { data = null; }
        }
        Bundle payload = new Bundle();
        payload.putBoolean("ok", data != null);
        if (data != null) {
            payload.putString("temperature", data.temperature);
            payload.putString("condition", data.condition);
            payload.putString("range", data.range);
            payload.putLong("observedAt", data.observedAt);
            payload.putBoolean("cached", cached);
        }
        Message response = Message.obtain(null, 1); response.setData(payload);
        try { reply.send(response); } catch (RemoteException ignored) { /* Home left the foreground. */ }
    }
    private String fetch() throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(WEATHER_URL).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Weather request failed");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (out.size() + count > 65536) throw new IOException("Weather response too large");
                    out.write(buffer, 0, count);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
    @Override public void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
