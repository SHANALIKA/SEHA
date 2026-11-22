package com.example.seha;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.HashMap;
import java.util.Map;

/**
 * LIVE PATIENT MAP - shows where the PATIENT is right now (GPS from the patient's phone).
 *
 *  ROLE_GUARDIAN : a guardian who passed the Patient ID + username check (read only)
 *  ROLE_HOSPITAL / ROLE_NURSE : the hospital that received the patient's SOS:
 *        road route + distance + ETA from the hospital to the patient, call the patient,
 *        patient records, "Acknowledge" / "Mark resolved".
 */
public class PatientLiveMapActivity extends AppCompatActivity {

    public static final String EXTRA_PATIENT_UID = "patientUid";
    public static final String EXTRA_ROLE = "role";
    public static final String EXTRA_SOS_ID = "sosId";
    public static final String EXTRA_PATIENT_NAME = "patientName";
    public static final String EXTRA_PATIENT_CODE = "patientCode";

    public static final String ROLE_GUARDIAN = "guardian";
    public static final String ROLE_HOSPITAL = "hospital";
    public static final String ROLE_NURSE = "nurse";

    private FirebaseFirestore db;
    private String patientUid, role, sosId, patientName, patientCode;
    private boolean staff;

    private MapView map;
    private TextView txtStatus, txtInfo, txtDetail;
    private LinearLayout layoutActions;
    private Button btnCall, btnSecond, btnPrimary;

    private Marker patientMarker, hospitalMarker;
    private Polyline routeLine;
    private Drawable icPatient, icHospital;

    private GeoPoint livePos, alertPos, hospitalPos;
    private Long liveUpdatedAt = null;
    private boolean sharing = true;
    private boolean fitted = false;

    private DocumentSnapshot alert;          // the SOS alert (staff: given, guardian: newest active one)
    private String activeRequestId = null;   // guardian: running ambulance trip of this patient

    private ListenerRegistration liveL, alertL, alertsQueryL, reqL;

    private RouteHelper.Result lastRoute = null;
    private GeoPoint lastRouteFrom = null;
    private long lastRouteAt = 0;
    private int routeSeq = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapUi.init(getApplicationContext());
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_ops_map);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return insets;
        });

        patientUid = getIntent().getStringExtra(EXTRA_PATIENT_UID);
        role = getIntent().getStringExtra(EXTRA_ROLE);
        sosId = getIntent().getStringExtra(EXTRA_SOS_ID);
        patientName = Sos.nz(getIntent().getStringExtra(EXTRA_PATIENT_NAME));
        patientCode = Sos.nz(getIntent().getStringExtra(EXTRA_PATIENT_CODE));
        if (role == null) role = ROLE_GUARDIAN;
        staff = ROLE_HOSPITAL.equals(role) || ROLE_NURSE.equals(role);
        if (patientUid == null || patientUid.isEmpty()) {
            Toast.makeText(this, "No patient to show", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        db = FirebaseFirestore.getInstance();

        map = findViewById(R.id.map);
        txtStatus = findViewById(R.id.txtStatus);
        txtInfo = findViewById(R.id.txtInfo);
        txtDetail = findViewById(R.id.txtDetail);
        layoutActions = findViewById(R.id.layoutActions);
        btnCall = findViewById(R.id.btnCall);
        btnSecond = findViewById(R.id.btnNotify);
        btnPrimary = findViewById(R.id.btnPrimary);
        ((ImageView) findViewById(R.id.btnBack)).setOnClickListener(v -> finish());
        findViewById(R.id.btnCancel).setVisibility(View.GONE);
        findViewById(R.id.layoutSlide).setVisibility(View.GONE);
        findViewById(R.id.layoutRequest).setVisibility(View.GONE);

        MapUi.setup(map);
        icPatient = MapUi.icon(this, R.drawable.marker_patient, 52);
        icHospital = MapUi.icon(this, R.drawable.hospital, 46);

        btnCall.setText("📞 Call patient");
        btnCall.setOnClickListener(v -> Fs.dial(this, alert != null ? Sos.nz(alert.getString("patientPhone")) : ""));
        btnSecond.setOnClickListener(v -> onSecondButton());
        btnPrimary.setOnClickListener(v -> onPrimaryButton());

        txtStatus.setText("Loading patient location...");
        Fs.ensureSignedIn(this::startListening);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (map != null) map.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (liveL != null) liveL.remove();
        if (alertL != null) alertL.remove();
        if (alertsQueryL != null) alertsQueryL.remove();
        if (reqL != null) reqL.remove();
    }

    // ------------------------------------------------------------------------------------
    // data
    // ------------------------------------------------------------------------------------

    private void startListening() {
        if (isFinishing() || isDestroyed()) return;

        // the patient's live GPS position
        liveL = db.collection(Sos.LIVE).document(patientUid).addSnapshotListener((s, e) -> {
            if (s == null || !s.exists()) {
                render();
                return;
            }
            if (Fs.hasLatLng(s, "lat", "lng")) {
                livePos = new GeoPoint(s.getDouble("lat"), s.getDouble("lng"));
                liveUpdatedAt = s.getLong("updatedAt");
            }
            Boolean sh = s.getBoolean("sharing");
            sharing = sh == null || sh;
            String n = Sos.nz(s.getString("patientName"));
            if (patientName.isEmpty() && !n.isEmpty()) patientName = n;
            onPosition();
            render();
        });

        if (sosId != null && !sosId.isEmpty()) {
            // hospital / nurse: the alert they were notified about
            alertL = db.collection(Sos.ALERTS).document(sosId).addSnapshotListener((s, e) -> {
                if (s == null || !s.exists()) return;
                alert = s;
                applyAlert();
            });
        } else {
            // guardian: newest running SOS of this patient (if any)
            alertsQueryL = db.collection(Sos.ALERTS).whereEqualTo("patientUid", patientUid)
                    .addSnapshotListener((qs, e) -> {
                        if (qs == null) return;
                        DocumentSnapshot best = null;
                        long bestT = -1;
                        for (DocumentSnapshot d : qs.getDocuments()) {
                            if (!Sos.isActive(d)) continue;
                            Long c = d.getLong("createdAt");
                            long cc = c != null ? c : 0;
                            if (cc > bestT) {
                                bestT = cc;
                                best = d;
                            }
                        }
                        alert = best;
                        applyAlert();
                    });
        }

        if (!staff) {
            // guardian: is an ambulance trip running for this patient?
            reqL = db.collection(Fs.REQUESTS).whereEqualTo("patientUid", patientUid)
                    .addSnapshotListener((qs, e) -> {
                        if (qs == null) return;
                        String bestId = null;
                        long bestT = -1;
                        for (DocumentSnapshot d : qs.getDocuments()) {
                            String st = Sos.nz(d.getString("status"));
                            if (Fs.isTerminal(st) || Fs.S_NO_AMBULANCE.equals(st)) continue;
                            Long c = d.getLong("createdAt");
                            long cc = c != null ? c : 0;
                            if (cc > bestT) {
                                bestT = cc;
                                bestId = d.getId();
                            }
                        }
                        activeRequestId = bestId;
                        render();
                    });
        }
    }

    /** Alert changed: fallback position, hospital marker, names, buttons. */
    private void applyAlert() {
        if (alert != null) {
            if (Fs.hasLatLng(alert, "lat", "lng")) {
                alertPos = new GeoPoint(alert.getDouble("lat"), alert.getDouble("lng"));
            }
            if (Fs.hasLatLng(alert, "hospitalLat", "hospitalLng")) {
                hospitalPos = new GeoPoint(alert.getDouble("hospitalLat"), alert.getDouble("hospitalLng"));
                if (staff) {
                    hospitalMarker = MapUi.marker(map, hospitalMarker, hospitalPos,
                            Sos.nz(alert.getString("hospitalName")), icHospital);
                }
            }
            String n = Sos.nz(alert.getString("patientName"));
            if (!n.isEmpty()) patientName = n;
            String c = Sos.nz(alert.getString("patientCode"));
            if (!c.isEmpty()) patientCode = c;
        }
        onPosition();
        render();
    }

    private GeoPoint currentPos() {
        return livePos != null ? livePos : alertPos;
    }

    // ------------------------------------------------------------------------------------
    // map
    // ------------------------------------------------------------------------------------

    private void onPosition() {
        GeoPoint p = currentPos();
        if (p == null) return;
        patientMarker = MapUi.marker(map, patientMarker, p,
                patientName.isEmpty() ? "Patient" : "Patient: " + patientName, icPatient);
        if (!fitted) {
            fitted = true;
            MapUi.fit(map, p, staff ? hospitalPos : null);
        } else {
            keepInView(p);
        }
        if (staff) maybeRoute(p);
    }

    private void keepInView(GeoPoint p) {
        try {
            if (!map.getBoundingBox().contains(p.getLatitude(), p.getLongitude())) {
                map.getController().animateTo(p);
            }
        } catch (Exception ignored) { }
    }

    /** Road route hospital -> patient (distance + ETA), refreshed when the patient moved. */
    private void maybeRoute(GeoPoint patient) {
        if (hospitalPos == null) return;
        long now = System.currentTimeMillis();
        if (lastRouteFrom != null && now - lastRouteAt < 15_000
                && Geo.meters(lastRouteFrom.getLatitude(), lastRouteFrom.getLongitude(),
                patient.getLatitude(), patient.getLongitude()) < 50) {
            return;
        }
        lastRouteFrom = patient;
        lastRouteAt = now;
        final int seq = ++routeSeq;
        RouteHelper.route(hospitalPos, patient, r -> {
            if (seq != routeSeq || isFinishing() || isDestroyed()) return;
            lastRoute = r;
            routeLine = MapUi.line(map, routeLine, r.points, Color.parseColor("#D32F2F"));
            render();
        });
    }

    // ------------------------------------------------------------------------------------
    // texts + buttons
    // ------------------------------------------------------------------------------------

    private void render() {
        if (txtStatus == null) return;
        GeoPoint p = currentPos();
        String who = patientName.isEmpty() ? "Patient" : patientName;
        boolean sosActive = alert != null && Sos.isActive(alert);

        // ---- status + info
        if (p == null) {
            txtStatus.setText((sosActive ? "🆘 " : "") + "📍 Waiting for the patient's location...");
            txtInfo.setText(staff ? "The patient's phone has not sent a GPS position yet."
                    : "The patient is not sharing a location right now.\n"
                    + "Ask the patient to switch on \"Share my live location\" on the home screen.");
        } else {
            txtStatus.setText((sosActive ? "🆘 SOS — " : "📍 ") + who);
            StringBuilder info = new StringBuilder();
            long t = liveUpdatedAt != null ? liveUpdatedAt
                    : (alert != null && alert.getLong("createdAt") != null ? alert.getLong("createdAt") : 0);
            if (t > 0) {
                long age = System.currentTimeMillis() - t;
                info.append("Location updated ").append(Sos.ago(age));
                if (livePos == null) info.append(" (position when the SOS was sent)");
                if (!sharing) info.append("\n⏹ The patient stopped sharing the location.");
                else if (age > 120_000) info.append("\n⚠ Old position — the phone may be offline or the app closed.");
            }
            if (staff && lastRoute != null) {
                info.append("\n🚑 ").append(Geo.dist(lastRoute.meters)).append(" from the hospital  •  ")
                        .append(Geo.eta(lastRoute.seconds))
                        .append(lastRoute.approximate ? " (estimate)" : "");
            }
            txtInfo.setText(info.toString());
        }

        // ---- details
        StringBuilder d = new StringBuilder();
        if (!patientName.isEmpty()) d.append("Patient: ").append(patientName);
        if (!patientCode.isEmpty()) d.append(d.length() > 0 ? "   •   " : "").append("Patient ID: ").append(patientCode);
        if (staff && alert != null) {
            String cond = Sos.nz(alert.getString("condition"));
            String blood = Sos.nz(alert.getString("bloodGroup"));
            String allergy = Sos.nz(alert.getString("allergies"));
            if (!cond.isEmpty()) d.append("\n⚠ ").append(cond);
            if (!blood.isEmpty()) d.append("\nBlood group: ").append(blood);
            if (!allergy.isEmpty()) d.append("   Allergies: ").append(allergy);
        }
        if (!staff && sosActive) {
            String h = Sos.nz(alert.getString("hospitalName"));
            d.append(d.length() > 0 ? "\n" : "").append("🆘 SOS active")
                    .append(h.isEmpty() ? "" : " — " + h + " notified");
        }
        txtDetail.setText(d.toString());

        // ---- buttons
        if (staff) {
            boolean hasPhone = alert != null && !Sos.nz(alert.getString("patientPhone")).isEmpty();
            btnCall.setVisibility(hasPhone ? View.VISIBLE : View.GONE);
            btnSecond.setText("📋 Patient records");
            btnSecond.setVisibility(View.VISIBLE);
            layoutActions.setVisibility(View.VISIBLE);

            String st = alert != null ? Sos.nz(alert.getString("status")) : "";
            if (Sos.S_NEW.equals(st)) {
                btnPrimary.setText("✅ Acknowledge — we are responding");
                btnPrimary.setVisibility(View.VISIBLE);
            } else if (Sos.S_ACK.equals(st)) {
                btnPrimary.setText("🏁 Mark SOS as resolved");
                btnPrimary.setVisibility(View.VISIBLE);
            } else {
                btnPrimary.setVisibility(View.GONE);
            }
        } else {
            btnCall.setVisibility(View.GONE);
            btnPrimary.setVisibility(View.GONE);
            btnSecond.setText("🚑 Open ambulance trip");
            btnSecond.setVisibility(activeRequestId != null ? View.VISIBLE : View.GONE);
            layoutActions.setVisibility(activeRequestId != null ? View.VISIBLE : View.GONE);
        }
    }

    private void onSecondButton() {
        if (staff) {
            Intent i = new Intent(this, PatientRecordsActivity.class);
            i.putExtra(PatientRecordsActivity.EXTRA_PATIENT_UID, patientUid);
            startActivity(i);
        } else if (activeRequestId != null) {
            Intent i = new Intent(this, TripMapActivity.class);
            i.putExtra(TripMapActivity.EXTRA_REQUEST_ID, activeRequestId);
            i.putExtra(TripMapActivity.EXTRA_ROLE, TripMapActivity.ROLE_GUARDIAN);
            startActivity(i);
        }
    }

    private void onPrimaryButton() {
        if (alert == null) return;
        String st = Sos.nz(alert.getString("status"));
        Map<String, Object> m = new HashMap<>();
        long now = System.currentTimeMillis();
        m.put("updatedAt", now);
        if (Sos.S_NEW.equals(st)) {
            m.put("status", Sos.S_ACK);
            m.put("ackAt", now);
            m.put("ackBy", Sos.nz(Fs.me()));
        } else if (Sos.S_ACK.equals(st)) {
            m.put("status", Sos.S_RESOLVED);
            m.put("resolvedAt", now);
            m.put("resolvedBy", Sos.nz(Fs.me()));
        } else {
            return;
        }
        db.collection(Sos.ALERTS).document(alert.getId()).update(m)
                .addOnFailureListener(e -> Toast.makeText(this, Fs.explain(e), Toast.LENGTH_LONG).show());
    }
}
