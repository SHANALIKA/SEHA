package com.example.seha;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;

import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Constants + small helpers shared by the SOS / live-location features. */
public final class Sos {

    /** SOS alerts sent by patients (one document per alert). */
    public static final String ALERTS = "sos_alerts";
    /** Live GPS position of a patient: document id = patient uid. */
    public static final String LIVE = "liveLocations";

    // alert life-cycle: new -> acknowledged (hospital saw it) -> resolved   |   cancelled (patient is safe)
    public static final String S_NEW = "new";
    public static final String S_ACK = "acknowledged";
    public static final String S_RESOLVED = "resolved";
    public static final String S_CANCELLED = "cancelled";

    /** An alert older than this is not "active" any more (so an old forgotten alert never blocks a new SOS). */
    public static final long ACTIVE_WINDOW_MS = 3L * 60 * 60 * 1000;
    /** The hospital pop-up is only shown for fresh alerts. */
    public static final long POPUP_WINDOW_MS = 30L * 60 * 1000;

    /** Sri Lanka national ambulance service (Suwa Seriya). */
    public static final String AMBULANCE_NUMBER = "1990";

    private Sos() { }

    public static String nz(String s) {
        return s == null ? "" : s;
    }

    /** lower-case, trimmed, single spaces: "  Kamal   PERERA " -> "kamal perera" */
    public static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    public static boolean isActive(DocumentSnapshot d) {
        if (d == null) return false;
        String st = nz(d.getString("status"));
        if (!S_NEW.equals(st) && !S_ACK.equals(st)) return false;
        Long t = d.getLong("createdAt");
        return t != null && System.currentTimeMillis() - t < ACTIVE_WINDOW_MS;
    }

    public static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 10) return "just now";
        if (s < 60) return s + " s ago";
        long m = s / 60;
        if (m < 60) return m + " min ago";
        return (m / 60) + " h " + (m % 60) + " min ago";
    }

    public static String mapsLink(double lat, double lng) {
        return String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lng);
    }

    /**
     * Sends an SMS silently (no screen) when the app has the SMS permission and a SIM.
     * @return true if at least one SMS was handed to the phone
     */
    public static boolean sendSmsQuiet(Context c, List<String> phones, String body) {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) return false;
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null || tm.getSimState() != TelephonyManager.SIM_STATE_READY) return false;
        int ok = 0;
        try {
            SmsManager sms = Build.VERSION.SDK_INT >= 31
                    ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
            Set<String> done = new HashSet<>();
            for (String p : phones) {
                String t = p == null ? "" : p.replaceAll("\\s+", "");
                if (t.isEmpty() || !done.add(t)) continue;
                try {
                    sms.sendMultipartTextMessage(t, null, sms.divideMessage(body), null, null);
                    ok++;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return ok > 0;
    }
}
