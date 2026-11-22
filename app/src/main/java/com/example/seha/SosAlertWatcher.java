package com.example.seha;

import android.app.Activity;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashSet;
import java.util.Set;

/**
 * Used by the hospital and nurse dashboards: when a patient sends an SOS and THIS hospital is the
 * nearest one, a pop-up with an alarm sound appears. "Track patient" opens the live map.
 */
public final class SosAlertWatcher {

    private static final Set<String> SHOWN = new HashSet<>();
    private static Ringtone ringtone;

    private SosAlertWatcher() { }

    /** @param role PatientLiveMapActivity.ROLE_HOSPITAL or ROLE_NURSE. Remove the returned listener in onDestroy. */
    public static ListenerRegistration start(Activity a, String hospitalId, String role) {
        return FirebaseFirestore.getInstance().collection(Sos.ALERTS)
                .whereEqualTo("hospitalId", hospitalId)
                .addSnapshotListener((qs, e) -> {
                    if (qs == null) return;
                    long now = System.currentTimeMillis();
                    for (DocumentSnapshot d : qs.getDocuments()) {
                        if (!Sos.S_NEW.equals(d.getString("status"))) continue;
                        Long t = d.getLong("createdAt");
                        if (t == null || now - t > Sos.POPUP_WINDOW_MS) continue;
                        if (SHOWN.add(d.getId())) show(a, d, role);
                    }
                });
    }

    private static void show(Activity a, DocumentSnapshot d, String role) {
        if (a.isFinishing() || a.isDestroyed()) return;

        String name = Sos.nz(d.getString("patientName"));
        String cond = Sos.nz(d.getString("condition"));
        String blood = Sos.nz(d.getString("bloodGroup"));
        String allergy = Sos.nz(d.getString("allergies"));
        Double dist = d.getDouble("hospitalDistance");

        StringBuilder msg = new StringBuilder();
        msg.append("Patient: ").append(name.isEmpty() ? "Unknown" : name);
        msg.append("\nPatient ID: ").append(Sos.nz(d.getString("patientCode")));
        msg.append("\nCondition: ").append(cond.isEmpty() ? "not specified" : cond);
        if (!blood.isEmpty()) msg.append("\nBlood group: ").append(blood);
        if (!allergy.isEmpty()) msg.append("\nAllergies: ").append(allergy);
        if (dist != null) msg.append("\nDistance from this hospital: ").append(Geo.dist(dist));

        final String patientUid = Sos.nz(d.getString("patientUid"));
        final String sosId = d.getId();
        final String patientName = name;

        startSound(a);
        new AlertDialog.Builder(a)
                .setTitle("🆘 SOS EMERGENCY")
                .setMessage(msg.toString())
                .setCancelable(false)
                .setPositiveButton("📍 Track patient", (dlg, w) -> {
                    Intent i = new Intent(a, PatientLiveMapActivity.class);
                    i.putExtra(PatientLiveMapActivity.EXTRA_PATIENT_UID, patientUid);
                    i.putExtra(PatientLiveMapActivity.EXTRA_ROLE, role);
                    i.putExtra(PatientLiveMapActivity.EXTRA_SOS_ID, sosId);
                    i.putExtra(PatientLiveMapActivity.EXTRA_PATIENT_NAME, patientName);
                    a.startActivity(i);
                })
                .setNegativeButton("Later", null)
                .setOnDismissListener(dlg -> stopSound())
                .show();
    }

    private static void startSound(Activity a) {
        try {
            stopSound();
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ringtone = RingtoneManager.getRingtone(a, uri);
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) { }
    }

    private static void stopSound() {
        try {
            if (ringtone != null) ringtone.stop();
        } catch (Exception ignored) { }
        ringtone = null;
    }
}
