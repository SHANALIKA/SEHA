package com.example.seha;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Publishes the PATIENT's live GPS position to Firestore (liveLocations/{patientUid}) so that
 * a verified guardian (and the hospital during an SOS) can follow the patient on the map.
 *
 * Two independent reasons keep it running: the patient's "Share my live location" switch and an
 * active SOS. It stops only when both are off. Works while the app is open (no background service).
 */
public final class LiveShare {

    public static final String REASON_MANUAL = "manual";
    public static final String REASON_SOS = "sos";

    private static final String PREFS = "seha_live_share";
    private static final String KEY_ENABLED = "enabled";

    private static LiveShare instance;

    public static synchronized LiveShare get(Context c) {
        if (instance == null) instance = new LiveShare(c.getApplicationContext());
        return instance;
    }

    public static boolean hasPermission(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isEnabledPref(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabledPref(Context c, boolean on) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putBoolean(KEY_ENABLED, on);
        e.apply();
    }

    private final Context app;
    private final FusedLocationProviderClient fused;
    private final Set<String> reasons = new HashSet<>();
    private LocationCallback callback;
    private String uid;
    private String patientCode = "";
    private String patientName = "";
    private Location lastWritten;
    private long lastWriteAt = 0;

    private LiveShare(Context app) {
        this.app = app;
        this.fused = LocationServices.getFusedLocationProviderClient(app);
    }

    /** @return true if sharing is running (false = not signed in or no location permission) */
    public synchronized boolean start(String reason) {
        String me = Fs.me();
        if (me == null || !hasPermission(app)) return false;
        reasons.add(reason);
        if (callback != null && me.equals(uid)) return true;

        stopUpdates();                 // another account was using it before
        uid = me;
        patientCode = "";
        patientName = "";
        lastWritten = null;
        lastWriteAt = 0;
        loadIdentity(me);

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location l = result.getLastLocation();
                if (l != null) write(l);
            }
        };
        try {
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                    .setMinUpdateIntervalMillis(3000L)
                    .build();
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
            fused.getLastLocation().addOnSuccessListener(l -> {
                if (l != null) write(l);
            });
        } catch (SecurityException e) {
            callback = null;
            reasons.clear();
            return false;
        }
        return true;
    }

    public synchronized void stop(String reason) {
        reasons.remove(reason);
        if (!reasons.isEmpty() || callback == null) return;
        stopUpdates();
        if (uid != null) {
            Map<String, Object> m = new HashMap<>();
            m.put("sharing", false);
            m.put("sosActive", false);
            m.put("updatedAt", System.currentTimeMillis());
            FirebaseFirestore.getInstance().collection(Sos.LIVE).document(uid)
                    .set(m, SetOptions.merge());
        }
    }

    public synchronized boolean isRunning() {
        return callback != null;
    }

    private void stopUpdates() {
        if (callback != null) {
            fused.removeLocationUpdates(callback);
            callback = null;
        }
    }

    private void loadIdentity(String me) {
        FirebaseFirestore.getInstance().collection(Fs.PATIENTS).document(me).get()
                .addOnSuccessListener(doc -> {
                    String n = Sos.nz(doc.getString("fullName"));
                    if (n.isEmpty()) n = Sos.nz(doc.getString("patientName"));
                    patientName = n;
                    String code = Sos.nz(doc.getString("patientCode"));
                    patientCode = code.isEmpty() ? Fs.codeForUid(me) : code;
                });
    }

    private synchronized void write(Location l) {
        if (uid == null || callback == null) return;
        long now = System.currentTimeMillis();
        // do not spam Firestore: skip if the patient did not move and a write was done recently
        if (lastWritten != null && now - lastWriteAt < 15_000 && l.distanceTo(lastWritten) < 5f) return;
        lastWritten = l;
        lastWriteAt = now;

        Map<String, Object> m = new HashMap<>();
        m.put("lat", l.getLatitude());
        m.put("lng", l.getLongitude());
        m.put("accuracy", (double) l.getAccuracy());
        m.put("updatedAt", now);
        m.put("sharing", true);
        m.put("sosActive", reasons.contains(REASON_SOS));
        m.put("patientUid", uid);
        if (!patientCode.isEmpty()) m.put("patientCode", patientCode);
        if (!patientName.isEmpty()) m.put("patientName", patientName);
        FirebaseFirestore.getInstance().collection(Sos.LIVE).document(uid)
                .set(m, SetOptions.merge());
    }
}
