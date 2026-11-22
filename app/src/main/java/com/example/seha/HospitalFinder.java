package com.example.seha;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.firestore.DocumentSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Finds hospitals ANYWHERE in Sri Lanka, nearest first.
 *
 *  1. Real hospitals from OpenStreetMap (Overpass API, free, no key). The search circle grows
 *     15 km -> 40 km -> 100 km until hospitals are found, so there is always an answer.
 *  2. Hospitals registered in SEHA (they have a nurse who gets the SMS) are merged in and marked.
 *  3. No internet -> the SEHA hospitals + the built-in list are shown instead.
 *
 * Map data (c) OpenStreetMap contributors (ODbL).
 */
public final class HospitalFinder {

    public static class Hospital {
        public String id = "";          // SEHA hospital uid, or "osm_<id>"
        public String name = "";
        public double lat, lng;
        public double distance;         // metres, straight line from the search point
        public String nameEn = "";      // English name from OpenStreetMap (name:en), if any
        public String phone = "";       // hospital phone (call button)
        public String nursePhone = "";  // only SEHA hospitals with a registered nurse
        public boolean seha = false;    // registered in SEHA
    }

    public interface Callback {
        /** @param online true = OpenStreetMap list, false = offline fallback */
        void onResult(List<Hospital> list, boolean online);
    }

    private static final String[] ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"};
    private static final int[] RADII_M = {15_000, 40_000, 100_000};
    private static final int MAX_RESULTS = 15;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private HospitalFinder() { }

    /** @param sehaDocs hospital documents registered in SEHA (may be empty) */
    public static void find(Context c, double lat, double lng,
                            List<DocumentSnapshot> sehaDocs, Callback cb) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            List<Hospital> seha = fromSeha(app, lat, lng, sehaDocs);

            List<Hospital> osm = null;
            for (int radius : RADII_M) {
                List<Hospital> r = queryOsm(lat, lng, radius);
                if (r == null) break;                 // no internet / server down
                osm = r;
                if (!r.isEmpty()) break;              // found something -> stop growing the circle
            }
            final boolean online = osm != null;

            List<Hospital> all = new ArrayList<>(seha);
            if (osm != null) {
                for (Hospital o : osm) {
                    boolean dup = false;
                    for (Hospital s : seha) {
                        if (Geo.meters(o.lat, o.lng, s.lat, s.lng) < 400) { dup = true; break; }
                    }
                    if (!dup) all.add(o);
                }
            } else {
                // offline: add the built-in list of real hospitals
                for (HospitalCatalog.Entry e : HospitalCatalog.all(app)) {
                    boolean dup = false;
                    for (Hospital s : seha) if (s.name.equalsIgnoreCase(e.name)) dup = true;
                    if (dup) continue;
                    Hospital h = new Hospital();
                    h.id = "cat_" + e.name.hashCode();
                    h.name = e.name;
                    h.lat = e.lat;
                    h.lng = e.lng;
                    h.distance = Geo.meters(lat, lng, e.lat, e.lng);
                    all.add(h);
                }
            }

            Collections.sort(all, (a, b) -> Double.compare(a.distance, b.distance));
            final List<Hospital> out = all.size() > MAX_RESULTS
                    ? new ArrayList<>(all.subList(0, MAX_RESULTS)) : all;
            MAIN.post(() -> cb.onResult(out, online));
        }).start();
    }

    // ---------------------------------------------------------------------------------
    // find the REAL position of a catalog hospital (only an approximate town position is known)
    // ---------------------------------------------------------------------------------

    public interface ResolveCallback {
        /** @param best the matching OpenStreetMap hospital, or null if nothing matched / no internet */
        void onResolved(Hospital best);
    }

    private static final java.util.Set<String> GENERIC = new java.util.HashSet<>(java.util.Arrays.asList(
            "hospital", "teaching", "general", "district", "base", "national", "sri", "lanka", "the", "of",
            "and", "provincial", "government", "colombo", "hosp", "gh", "dgh", "th", "bh"));

    private static java.util.Set<String> tokens(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 3 && !GENERIC.contains(t)) out.add(t);
        }
        return out;
    }

    /** Looks around the approximate position (15 km) and returns the hospital whose name fits best. */
    public static void resolve(HospitalCatalog.Entry entry, ResolveCallback cb) {
        new Thread(() -> {
            List<Hospital> near = queryOsm(entry.lat, entry.lng, 15_000);
            Hospital best = null;
            if (near != null) {
                java.util.Set<String> want = tokens(entry.name);
                int bestScore = 0;
                for (Hospital h : near) {
                    java.util.Set<String> have = tokens(h.name);
                    have.addAll(tokens(h.nameEn));
                    int score = 0;
                    for (String t : want) if (have.contains(t)) score++;
                    if (score > bestScore || (score == bestScore && score > 0 && best != null
                            && h.distance < best.distance)) {
                        bestScore = score;
                        best = h;
                    }
                }
                if (best == null) {
                    // no name matched: nearest government looking hospital
                    for (Hospital h : near) {
                        String n = (h.name + " " + h.nameEn).toLowerCase(Locale.ROOT);
                        boolean gov = n.contains("teaching") || n.contains("general") || n.contains("base")
                                || n.contains("district") || n.contains("national") || n.contains("provincial");
                        if (gov && (best == null || h.distance < best.distance)) best = h;
                    }
                }
            }
            final Hospital result = best;
            MAIN.post(() -> cb.onResolved(result));
        }).start();
    }

    private static List<Hospital> fromSeha(Context c, double lat, double lng, List<DocumentSnapshot> docs) {
        List<Hospital> list = new ArrayList<>();
        if (docs == null) return list;
        for (DocumentSnapshot d : docs) {
            double[] pos = HospitalCatalog.position(c, d);
            if (pos == null) continue;
            Hospital h = new Hospital();
            h.id = d.getId();
            h.name = d.getString("name") != null ? d.getString("name") : "Hospital";
            h.lat = pos[0];
            h.lng = pos[1];
            h.phone = d.getString("phone") != null ? d.getString("phone") : "";
            h.nursePhone = d.getString("nursePhone") != null ? d.getString("nursePhone") : "";
            h.seha = true;
            h.distance = Geo.meters(lat, lng, h.lat, h.lng);
            list.add(h);
        }
        return list;
    }

    /** @return null when the internet / server failed, an (possibly empty) list otherwise */
    private static List<Hospital> queryOsm(double lat, double lng, int radius) {
        String q = String.format(Locale.US,
                "[out:json][timeout:25];(node[\"amenity\"=\"hospital\"](around:%d,%f,%f);"
                        + "way[\"amenity\"=\"hospital\"](around:%d,%f,%f);"
                        + "relation[\"amenity\"=\"hospital\"](around:%d,%f,%f););out center tags;",
                radius, lat, lng, radius, lat, lng, radius, lat, lng);

        for (String url : ENDPOINTS) {
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .post(new FormBody.Builder().add("data", q).build())
                        .header("User-Agent", "SEHA-Medical-App/1.0 (student final year project)")
                        .build();
                try (Response resp = CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) continue;
                    JSONObject root = new JSONObject(resp.body().string());
                    return parse(root.optJSONArray("elements"), lat, lng);
                }
            } catch (Exception ignored) {
                // try the next server
            }
        }
        return null;
    }

    private static List<Hospital> parse(JSONArray els, double lat, double lng) {
        List<Hospital> list = new ArrayList<>();
        if (els == null) return list;
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.optJSONObject(i);
            if (e == null) continue;
            JSONObject tags = e.optJSONObject("tags");
            if (tags == null) continue;

            String name = tags.optString("name", "");
            if (name.isEmpty()) name = tags.optString("name:en", "");
            if (name.isEmpty()) continue;                         // skip unnamed buildings
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("veterinar")) continue;

            double hLat, hLng;
            if (e.has("lat") && e.has("lon")) {
                hLat = e.optDouble("lat");
                hLng = e.optDouble("lon");
            } else if (e.optJSONObject("center") != null) {
                hLat = e.optJSONObject("center").optDouble("lat");
                hLng = e.optJSONObject("center").optDouble("lon");
            } else {
                continue;
            }

            Hospital h = new Hospital();
            h.id = "osm_" + e.optString("type", "x") + e.optLong("id", i);
            h.name = name;
            h.nameEn = tags.optString("name:en", "");
            h.lat = hLat;
            h.lng = hLng;
            h.phone = tags.optString("phone", tags.optString("contact:phone", ""));
            h.distance = Geo.meters(lat, lng, hLat, hLng);
            list.add(h);
        }
        return list;
    }
}
