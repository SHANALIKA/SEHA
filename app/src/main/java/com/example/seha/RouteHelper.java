package com.example.seha;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Road route (distance, ETA and the line to draw) using the free OSRM service.
 * If the internet/route service fails, it falls back to a straight line with an
 * estimated distance/time, so the screen NEVER breaks during a demo.
 */
public final class RouteHelper {

    public static class Result {
        public List<GeoPoint> points = new ArrayList<>();
        public double meters;
        public double seconds;
        /** true = straight-line estimate (route service unavailable) */
        public boolean approximate;
    }

    public interface Callback { void onResult(Result r); }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RouteHelper() { }

    /** Async. The callback always runs on the main thread, and always gets a result. */
    public static void route(GeoPoint from, GeoPoint to, Callback cb) {
        new Thread(() -> {
            Result res = fetch(from, to);
            if (res == null) res = straight(from, to);
            final Result out = res;
            MAIN.post(() -> cb.onResult(out));
        }).start();
    }

    private static Result fetch(GeoPoint from, GeoPoint to) {
        try {
            String url = String.format(Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f"
                            + "?overview=full&geometries=geojson",
                    from.getLongitude(), from.getLatitude(),
                    to.getLongitude(), to.getLatitude());
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "SEHA-Medical-App/1.0 (student final year project)")
                    .build();
            try (Response resp = CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JSONObject root = new JSONObject(resp.body().string());
                if (!"Ok".equals(root.optString("code"))) return null;
                JSONObject route = root.getJSONArray("routes").getJSONObject(0);

                Result r = new Result();
                r.meters = route.getDouble("distance");
                r.seconds = route.getDouble("duration");
                JSONArray coords = route.getJSONObject("geometry").getJSONArray("coordinates");
                for (int i = 0; i < coords.length(); i++) {
                    JSONArray c = coords.getJSONArray(i);
                    r.points.add(new GeoPoint(c.getDouble(1), c.getDouble(0)));
                }
                if (r.points.size() < 2) return null;
                return r;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Result straight(GeoPoint from, GeoPoint to) {
        Result r = new Result();
        double direct = Geo.meters(from.getLatitude(), from.getLongitude(),
                to.getLatitude(), to.getLongitude());
        r.meters = direct * 1.3;                 // roads are longer than a straight line
        r.seconds = r.meters / (40000.0 / 3600); // assume 40 km/h
        r.points.add(from);
        r.points.add(to);
        r.approximate = true;
        return r;
    }
}
