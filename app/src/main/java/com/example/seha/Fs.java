package com.example.seha;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Collection names, request statuses and small shared helpers. */
public final class Fs {

    public static final String PATIENTS = "Patients";
    public static final String AMBULANCES = "ambulances";   // doc id = vehicle number (normalised)
    public static final String HOSPITALS = "hospitals";     // doc id = hospital user uid
    public static final String REQUESTS = "requests";
    public static final String RECORDS = "records";         // Patients/{id}/records (medical records)

    // Request life-cycle:
    // searching -> requested (offered to one ambulance) -> accepted -> picked_up -> completed
    //                         \-> declined (ambulance said no / timeout) -> next ambulance
    public static final String S_SEARCHING = "searching";
    public static final String S_REQUESTED = "requested";
    public static final String S_DECLINED = "declined";
    public static final String S_ACCEPTED = "accepted";
    public static final String S_PICKED_UP = "picked_up";
    public static final String S_COMPLETED = "completed";
    public static final String S_CANCELLED = "cancelled";
    public static final String S_NO_AMBULANCE = "no_ambulance";

    /** An ambulance that hasn't sent a location for this long is treated as offline. */
    public static final long STALE_MS = 120_000;

    private Fs() { }

    public static boolean isTerminal(String s) {
        return S_COMPLETED.equals(s) || S_CANCELLED.equals(s);
    }

    /** "WP CAB-1234" -> "WPCAB1234" (used as ambulance document id) */
    public static String vehicleId(String v) {
        if (v == null) return "";
        return v.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public static String me() {
        return FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
    }

    public interface Done { void run(); }

    /** Make sure there is a signed-in user (anonymous if nobody logged in). Never blocks forever. */
    public static void ensureSignedIn(Done next) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            next.run();
            return;
        }
        auth.signInAnonymously().addOnCompleteListener(t -> {
            if (!t.isSuccessful()) {
                lastAuthError = t.getException() != null ? t.getException().getMessage() : "sign-in failed";
            }
            next.run();
        });
    }

    /** Why the anonymous sign-in failed (null = fine). */
    public static String lastAuthError = null;

    /** Readable explanation for the usual Firestore "permission denied" problem. */
    public static String explain(Exception e) {
        String m = e != null && e.getMessage() != null ? e.getMessage() : "";
        if (m.contains("PERMISSION_DENIED") || m.toLowerCase().contains("insufficient permissions")) {
            if (me() == null) {
                return "Permission denied: not signed in.\nFirebase Console > Authentication > "
                        + "Sign-in method > enable ANONYMOUS."
                        + (lastAuthError != null ? "\n(" + lastAuthError + ")" : "");
            }
            return "Permission denied by Firestore rules.\nFirebase Console > Firestore Database > "
                    + "Rules > paste the latest firestore.rules and press Publish.";
        }
        return m;
    }

    public static void dial(Context c, String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            Toast.makeText(c, "Phone number not available", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.trim()));
        c.startActivity(i);
    }

    // ---------------------------------------------------------------- patient id

    public interface CodeCb { void onCode(String code); }

    /** Short 6-digit Patient ID that can be told over the phone / entered by a guardian. */
    public static void ensurePatientCode(String uid, CodeCb cb) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection(PATIENTS).document(uid).get().addOnSuccessListener(doc -> {
            String existing = doc.getString("patientCode");
            if (existing != null && !existing.isEmpty()) {
                cb.onCode(existing);
            } else {
                generateUnique(db, uid, 0, cb);
            }
        }).addOnFailureListener(e -> cb.onCode(null));
    }

    /**
     * Stable 6-digit Patient ID made from the patient's uid. Anyone (the ambulance, a nurse) can compute
     * it without writing to the patient's profile, so the SMS always has the same Patient ID that the
     * patient sees after opening the app.
     */
    public static String codeForUid(String uid) {
        if (uid == null || uid.isEmpty()) return "";
        long h = uid.hashCode();
        return String.valueOf(100000 + Math.abs(h) % 900000);
    }

    private static void generateUnique(FirebaseFirestore db, String uid, int attempt, CodeCb cb) {
        // first try: the stable code; only if another patient already owns it, a random one
        String code = attempt == 0 ? codeForUid(uid)
                : String.valueOf(100000 + new Random().nextInt(900000));
        db.collection(PATIENTS).whereEqualTo("patientCode", code).get()
                .addOnSuccessListener((QuerySnapshot qs) -> {
                    if (!qs.isEmpty() && attempt < 6) {
                        generateUnique(db, uid, attempt + 1, cb);
                        return;
                    }
                    Map<String, Object> m = new HashMap<>();
                    m.put("patientCode", code);
                    db.collection(PATIENTS).document(uid).set(m, SetOptions.merge())
                            .addOnSuccessListener(v -> cb.onCode(code))
                            .addOnFailureListener(e -> cb.onCode(null));
                })
                .addOnFailureListener(e -> cb.onCode(null));
    }

    // ---------------------------------------------------------------- finishing a request

    /** Mark the request finished and free the ambulance (one atomic batch). */
    public static void finishRequest(String requestId, String ambulanceId, String status) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch b = db.batch();
        Map<String, Object> r = new HashMap<>();
        r.put("status", status);
        r.put("finishedAt", System.currentTimeMillis());
        b.set(db.collection(REQUESTS).document(requestId), r, SetOptions.merge());
        if (ambulanceId != null && !ambulanceId.isEmpty()) {
            Map<String, Object> a = new HashMap<>();
            a.put("busy", false);
            a.put("currentRequestId", FieldValue.delete());
            b.set(db.collection(AMBULANCES).document(ambulanceId), a, SetOptions.merge());
        }
        b.commit();
    }

    // ---------------------------------------------------------------- SMS

    /**
     * Sends an SMS directly if the app has permission and a SIM; otherwise opens the
     * messaging app with the text already written (so it always works in a demo).
     */
    public static void sendSms(Context c, String phone, String body) {
        if (phone == null || phone.trim().isEmpty()) {
            Toast.makeText(c, "No phone number for this contact", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean permitted = ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        boolean simReady = tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_READY;

        if (permitted && simReady) {
            try {
                SmsManager sms = Build.VERSION.SDK_INT >= 31
                        ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
                ArrayList<String> parts = sms.divideMessage(body);
                sms.sendMultipartTextMessage(phone.trim(), null, parts, null, null);
                Toast.makeText(c, "SMS sent to " + phone, Toast.LENGTH_SHORT).show();
                return;
            } catch (Exception ignored) {
                // fall through to the messaging app
            }
        }
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone.trim()));
            i.putExtra("sms_body", body);
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "Could not open messaging app", Toast.LENGTH_SHORT).show();
        }
    }

    /** Same SMS to several numbers (nurse + guardian). Falls back to the SMS app with all numbers filled. */
    public static void sendSmsMulti(Context c, java.util.List<String> phones, String body) {
        java.util.List<String> clean = new ArrayList<>();
        for (String p : phones) {
            if (p == null) continue;
            String t = p.replaceAll("\\s+", "");
            if (!t.isEmpty() && !clean.contains(t)) clean.add(t);
        }
        if (clean.isEmpty()) {
            Toast.makeText(c, "No phone numbers to send the SMS to", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean permitted = ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        boolean simReady = tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_READY;

        if (permitted && simReady) {
            int ok = 0;
            try {
                SmsManager sms = Build.VERSION.SDK_INT >= 31
                        ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
                for (String p : clean) {
                    try {
                        sms.sendMultipartTextMessage(p, null, sms.divideMessage(body), null, null);
                        ok++;
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            if (ok > 0) {
                Toast.makeText(c, "Emergency SMS sent to " + ok + " number(s)", Toast.LENGTH_LONG).show();
                return;
            }
        }
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + android.text.TextUtils.join(";", clean)));
            i.putExtra("sms_body", body);
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "Could not open messaging app", Toast.LENGTH_SHORT).show();
        }
    }

    /** helper for reading a Firestore document's coordinates */
    public static boolean hasLatLng(DocumentSnapshot d, String latKey, String lngKey) {
        Double a = d.getDouble(latKey);
        Double b = d.getDouble(lngKey);
        return a != null && b != null && (a != 0.0 || b != 0.0);
    }
}
