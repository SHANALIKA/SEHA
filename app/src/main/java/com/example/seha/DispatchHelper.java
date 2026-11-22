package com.example.seha;

import android.os.Handler;
import android.os.Looper;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Find the nearest ambulance" logic.
 *
 * 1. The requester (patient / hospital) creates a request document  -> createRequest().
 * 2. While the requester's Trip screen is open, a {@link Driver} offers the request to the
 *    NEAREST online + available + not busy ambulance (distance from pickup point).
 * 3. That ambulance sees Accept / Decline. If it declines or does not answer in
 *    OFFER_TIMEOUT_MS, the Driver offers it to the next nearest ambulance, and so on.
 * 4. If nobody is left -> status "no_ambulance" (Retry button on screen).
 *
 * Everything is stored in the request document, so the logic is restartable.
 */
public final class DispatchHelper {

    public static final long OFFER_TIMEOUT_MS = 30_000;

    private DispatchHelper() { }

    /** Creates the request and returns its id immediately (the write continues in background). */
    public static String createRequest(FirebaseFirestore db, Map<String, Object> data) {
        DocumentReference ref = db.collection(Fs.REQUESTS).document();
        data.put("status", Fs.S_SEARCHING);
        data.put("createdAt", System.currentTimeMillis());
        data.put("tried", new ArrayList<String>());
        ref.set(data);
        return ref.getId();
    }

    public static void retry(FirebaseFirestore db, String requestId) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", Fs.S_SEARCHING);
        m.put("tried", new ArrayList<String>());
        m.put("targetAmbulanceId", FieldValue.delete());
        db.collection(Fs.REQUESTS).document(requestId).update(m);
    }

    private static class Cand {
        String id;
        double dist;
    }

    /** Lives inside the requester's Trip screen and keeps the search moving. */
    public static class Driver {
        private final FirebaseFirestore db;
        private final String requestId;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean offering = false;
        private String timerKey = null;
        private Runnable timeout = null;

        public Driver(FirebaseFirestore db, String requestId) {
            this.db = db;
            this.requestId = requestId;
        }

        public void onRequest(DocumentSnapshot d) {
            String st = d.getString("status");
            if (Fs.S_SEARCHING.equals(st) || Fs.S_DECLINED.equals(st)) {
                cancelTimeout();
                offerNext(d);
            } else if (Fs.S_REQUESTED.equals(st)) {
                offering = false;
                armTimeout(d);
            } else {
                offering = false;
                cancelTimeout();
            }
        }

        public void stop() {
            cancelTimeout();
        }

        @SuppressWarnings("unchecked")
        private void offerNext(DocumentSnapshot d) {
            if (offering) return;
            Double plat = d.getDouble("pickupLat");
            Double plng = d.getDouble("pickupLng");
            if (plat == null || plng == null) return;
            offering = true;

            List<String> tried = new ArrayList<>();
            Object t = d.get("tried");
            if (t instanceof List) tried.addAll((List<String>) t);

            DocumentReference ref = db.collection(Fs.REQUESTS).document(requestId);

            db.collection(Fs.AMBULANCES).whereEqualTo("online", true).get()
                    .addOnSuccessListener(qs -> {
                        long now = System.currentTimeMillis();
                        List<Cand> list = new ArrayList<>();
                        for (DocumentSnapshot a : qs.getDocuments()) {
                            if (tried.contains(a.getId())) continue;
                            Boolean available = a.getBoolean("available");
                            if (available != null && !available) continue;
                            Boolean busy = a.getBoolean("busy");
                            if (busy != null && busy) continue;
                            Long upd = a.getLong("updatedAt");
                            if (upd == null || now - upd > Fs.STALE_MS) continue;
                            Double la = a.getDouble("lat");
                            Double lo = a.getDouble("lng");
                            if (la == null || lo == null) continue;
                            Cand c = new Cand();
                            c.id = a.getId();
                            c.dist = Geo.meters(plat, plng, la, lo);
                            list.add(c);
                        }
                        if (list.isEmpty()) {
                            ref.update("status", Fs.S_NO_AMBULANCE,
                                    "targetAmbulanceId", FieldValue.delete())
                                    .addOnFailureListener(e -> offering = false);
                            return;
                        }
                        Collections.sort(list, (x, y) -> Double.compare(x.dist, y.dist));
                        Cand best = list.get(0);

                        Map<String, Object> upd = new HashMap<>();
                        upd.put("status", Fs.S_REQUESTED);
                        upd.put("targetAmbulanceId", best.id);
                        upd.put("targetDistance", best.dist);
                        upd.put("offeredAt", System.currentTimeMillis());
                        upd.put("tried", FieldValue.arrayUnion(best.id));
                        ref.update(upd).addOnFailureListener(e -> offering = false);
                    })
                    .addOnFailureListener(e -> offering = false);
        }

        private void armTimeout(DocumentSnapshot d) {
            Long offeredAt = d.getLong("offeredAt");
            String target = d.getString("targetAmbulanceId");
            if (offeredAt == null || target == null) return;
            String key = target + ":" + offeredAt;
            if (key.equals(timerKey)) return;
            cancelTimeout();
            timerKey = key;

            long delay = Math.max(1000, offeredAt + OFFER_TIMEOUT_MS - System.currentTimeMillis());
            DocumentReference ref = db.collection(Fs.REQUESTS).document(requestId);
            timeout = () -> db.runTransaction(tr -> {
                DocumentSnapshot s = tr.get(ref);
                if (Fs.S_REQUESTED.equals(s.getString("status"))
                        && target.equals(s.getString("targetAmbulanceId"))) {
                    tr.update(ref, "status", Fs.S_DECLINED);
                }
                return null;
            });
            handler.postDelayed(timeout, delay);
        }

        private void cancelTimeout() {
            if (timeout != null) handler.removeCallbacks(timeout);
            timeout = null;
            timerKey = null;
        }
    }
}
