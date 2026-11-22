package com.example.seha;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PATIENT: SOS.
 *
 *  1. 5 second countdown (can be cancelled - protects against an accidental tap)
 *  2. GPS position of the phone
 *  3. nearest hospital registered in SEHA
 *  4. one alert document (sos_alerts) with the patient's condition, Patient ID and GPS position:
 *     the hospital + its nurse get a pop-up with an alarm sound
 *  5. SMS with Patient ID + map link to the guardian and the hospital's nurse
 *  6. live GPS sharing while the SOS is active, so the hospital and the guardian can follow the patient
 */
public class SosActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private FusedLocationProviderClient fused;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView txtTitle, txtStatus, txtDetail;
    private Button btnCancel, btnRetry, btnSms, btnCallHospital, btnAmbulance, btnCall1990;

    private String uid;
    private String sosId = null;
    private ListenerRegistration sosListener;

    private int countdown = 5;
    private boolean cancelled = false;
    private boolean flowStarted = false;
    private boolean activated = false;      // live sharing + SMS done for this alert
    private boolean freshAlert = false;     // true = created by this screen (send the SMS)

    private DocumentSnapshot patientDoc;
    private List<String> smsPhones = new ArrayList<>();
    private String smsBody = "";
    private String hospitalPhone = "";
    private Runnable tick;

    private final ActivityResultLauncher<String[]> perms =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (hasLocation()) {
                    beginCountdown();
                } else {
                    flowStarted = false;
                    setTitleAndStatus("🆘 SOS", "Location permission is needed to find the nearest hospital.");
                    btnRetry.setText("Allow location and send SOS");
                    btnRetry.setVisibility(View.VISIBLE);
                    btnCancel.setText("✖ Close");
                    btnCancel.setVisibility(View.VISIBLE);
                }
            });

    // ------------------------------------------------------------------------------------
    // screen
    // ------------------------------------------------------------------------------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button makeButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(textColor);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bgColor);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setVisibility(View.GONE);
        return b;
    }

    private void addBlock(LinearLayout root, View v, int height, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = dp(topMargin);
        root.addView(v, lp);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        db = FirebaseFirestore.getInstance();
        fused = LocationServices.getFusedLocationProviderClient(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#B71C1C"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        txtTitle = new TextView(this);
        txtTitle.setText("🆘 SOS");
        txtTitle.setTextSize(30);
        txtTitle.setTextColor(Color.WHITE);
        txtTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        txtTitle.setGravity(Gravity.CENTER);
        addBlock(root, txtTitle, LinearLayout.LayoutParams.WRAP_CONTENT, 24);

        txtStatus = new TextView(this);
        txtStatus.setTextSize(20);
        txtStatus.setTextColor(Color.WHITE);
        txtStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        txtStatus.setGravity(Gravity.CENTER);
        addBlock(root, txtStatus, LinearLayout.LayoutParams.WRAP_CONTENT, 24);

        txtDetail = new TextView(this);
        txtDetail.setTextSize(14);
        txtDetail.setTextColor(Color.parseColor("#FFCDD2"));
        txtDetail.setGravity(Gravity.CENTER);
        addBlock(root, txtDetail, LinearLayout.LayoutParams.WRAP_CONTENT, 16);

        btnCallHospital = makeButton("📞 Call the hospital", Color.WHITE, Color.parseColor("#B71C1C"));
        btnCall1990 = makeButton("📞 Call " + Sos.AMBULANCE_NUMBER + " (ambulance)", Color.WHITE,
                Color.parseColor("#B71C1C"));
        btnAmbulance = makeButton("🚑 Also request the nearest ambulance", Color.parseColor("#7F0000"),
                Color.WHITE);
        btnSms = makeButton("✉ Inform guardian / nurse by SMS", Color.parseColor("#7F0000"), Color.WHITE);
        btnRetry = makeButton("🔄 Try again", Color.WHITE, Color.parseColor("#B71C1C"));
        btnCancel = makeButton("✖ Cancel", Color.parseColor("#7F0000"), Color.WHITE);

        addBlock(root, btnCallHospital, dp(54), 20);
        addBlock(root, btnCall1990, dp(54), 10);
        addBlock(root, btnAmbulance, dp(54), 10);
        addBlock(root, btnSms, dp(54), 10);
        addBlock(root, btnRetry, dp(54), 10);
        addBlock(root, btnCancel, dp(60), 24);

        setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(s.left + dp(20), s.top + dp(12), s.right + dp(20), s.bottom + dp(20));
            return insets;
        });

        btnCall1990.setVisibility(View.VISIBLE);
        btnCall1990.setOnClickListener(v -> Fs.dial(this, Sos.AMBULANCE_NUMBER));
        btnCallHospital.setOnClickListener(v -> Fs.dial(this, hospitalPhone));
        btnAmbulance.setOnClickListener(v ->
                startActivity(new Intent(this, PatientAmbulanceActivity.class)));
        btnSms.setOnClickListener(v -> Fs.sendSmsMulti(this, smsPhones, smsBody));
        btnRetry.setOnClickListener(v -> {
            btnRetry.setVisibility(View.GONE);
            startFlow();
        });
        btnCancel.setOnClickListener(v -> onCancelPressed());

        setTitleAndStatus("🆘 SOS", "Checking...");
        Fs.ensureSignedIn(() -> {
            uid = Fs.me();
            if (uid == null) {
                setTitleAndStatus("🆘 SOS", "Please log in again.");
                return;
            }
            checkExistingAlert();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (sosListener != null) sosListener.remove();
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private void setTitleAndStatus(String title, String status) {
        txtTitle.setText(title);
        txtStatus.setText(status);
    }

    private boolean hasLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------------------------
    // start: is there already a running SOS?
    // ------------------------------------------------------------------------------------

    private void checkExistingAlert() {
        db.collection(Sos.ALERTS).whereEqualTo("patientUid", uid).get()
                .addOnCompleteListener(t -> {
                    if (!alive()) return;
                    DocumentSnapshot best = null;
                    long bestT = -1;
                    if (t.isSuccessful() && t.getResult() != null) {
                        QuerySnapshot qs = t.getResult();
                        for (DocumentSnapshot d : qs.getDocuments()) {
                            if (!Sos.isActive(d)) continue;
                            Long c = d.getLong("createdAt");
                            long cc = c != null ? c : 0;
                            if (cc > bestT) {
                                bestT = cc;
                                best = d;
                            }
                        }
                    }
                    if (best != null) {
                        freshAlert = false;
                        observe(best.getId());          // resume the running SOS
                    } else {
                        startFlow();
                    }
                });
    }

    private void startFlow() {
        if (flowStarted && !cancelled) return;
        flowStarted = true;
        cancelled = false;
        if (!hasLocation()) {
            perms.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.SEND_SMS});
            return;
        }
        beginCountdown();
    }

    // ------------------------------------------------------------------------------------
    // 1. countdown
    // ------------------------------------------------------------------------------------

    private void beginCountdown() {
        countdown = 5;
        btnRetry.setVisibility(View.GONE);
        btnCancel.setText("✖ CANCEL");
        btnCancel.setVisibility(View.VISIBLE);
        txtDetail.setText("Tap CANCEL if this was a mistake.\nThe nearest hospital will get your GPS location, "
                + "Patient ID and medical condition.");
        tick = new Runnable() {
            @Override
            public void run() {
                if (cancelled || !alive()) return;
                if (countdown <= 0) {
                    sendSos();
                    return;
                }
                setTitleAndStatus("🆘 SOS", "Sending SOS in " + countdown + " ...");
                countdown--;
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(tick);
    }

    private void onCancelPressed() {
        if (sosId == null) {
            // countdown / sending phase: nothing was sent yet
            cancelled = true;
            flowStarted = false;
            handler.removeCallbacksAndMessages(null);
            finish();
            return;
        }
        // an alert exists -> mark it cancelled ("I'm safe")
        Map<String, Object> m = new HashMap<>();
        m.put("status", Sos.S_CANCELLED);
        m.put("updatedAt", System.currentTimeMillis());
        db.collection(Sos.ALERTS).document(sosId).update(m);
        LiveShare.get(this).stop(LiveShare.REASON_SOS);
        Toast.makeText(this, "SOS cancelled", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ------------------------------------------------------------------------------------
    // 2. GPS
    // ------------------------------------------------------------------------------------

    private void sendSos() {
        btnCancel.setVisibility(View.GONE);
        setTitleAndStatus("🆘 SOS", "📡 Getting your GPS location...");
        txtDetail.setText("");

        final CancellationTokenSource cts = new CancellationTokenSource();
        final boolean[] done = {false};
        final Runnable timeout = cts::cancel;
        handler.postDelayed(timeout, 15_000);
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnCompleteListener(t -> {
                        handler.removeCallbacks(timeout);
                        if (done[0] || !alive()) return;
                        done[0] = true;
                        Location loc = t.isSuccessful() ? t.getResult() : null;
                        if (loc != null) onLocation(loc);
                        else lastKnownLocation();
                    });
        } catch (SecurityException e) {
            fail("Location permission is missing.");
        }
    }

    private void lastKnownLocation() {
        try {
            fused.getLastLocation().addOnCompleteListener(t -> {
                if (!alive()) return;
                Location l = t.isSuccessful() ? t.getResult() : null;
                if (l != null) onLocation(l);
                else fail("Could not get your location. Turn on GPS and try again.");
            });
        } catch (SecurityException e) {
            fail("Location permission is missing.");
        }
    }

    // ------------------------------------------------------------------------------------
    // 3. patient details + nearest hospital
    // ------------------------------------------------------------------------------------

    private void onLocation(Location loc) {
        setTitleAndStatus("🆘 SOS", "👤 Loading your details...");
        db.collection(Fs.PATIENTS).document(uid).get().addOnCompleteListener(t -> {
            if (!alive()) return;
            patientDoc = t.isSuccessful() ? t.getResult() : null;
            findHospital(loc);
        });
    }

    private void findHospital(Location loc) {
        setTitleAndStatus("🆘 SOS", "🏥 Finding the nearest hospital...");
        db.collection(Fs.HOSPITALS).get().addOnCompleteListener(t -> {
            if (!alive()) return;
            if (!t.isSuccessful()) {
                fail(Fs.explain(t.getException()));
                return;
            }
            DocumentSnapshot best = null;
            double bestDist = Double.MAX_VALUE;
            for (DocumentSnapshot d : t.getResult().getDocuments()) {
                double[] p = HospitalCatalog.position(this, d);
                if (p == null) continue;
                double dist = Geo.meters(loc.getLatitude(), loc.getLongitude(), p[0], p[1]);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = d;
                }
            }
            createAlert(loc, best, bestDist);
        });
    }

    // ------------------------------------------------------------------------------------
    // 4. the alert
    // ------------------------------------------------------------------------------------

    private String pd(String key) {
        return patientDoc != null && patientDoc.exists() ? Sos.nz(patientDoc.getString(key)) : "";
    }

    private void createAlert(Location loc, DocumentSnapshot hospital, double hospitalDist) {
        String name = pd("fullName");
        if (name.isEmpty()) name = pd("patientName");
        String code = pd("patientCode");
        if (code.isEmpty()) code = Fs.codeForUid(uid);
        String condition = pd("chronicConditions");

        Map<String, Object> m = new HashMap<>();
        long now = System.currentTimeMillis();
        m.put("type", "sos");
        m.put("status", Sos.S_NEW);
        m.put("createdAt", now);
        m.put("updatedAt", now);
        m.put("patientUid", uid);
        m.put("patientCode", code);
        m.put("patientName", name);
        m.put("condition", condition);
        m.put("bloodGroup", pd("bloodGroup"));
        m.put("allergies", pd("allergies"));
        m.put("patientPhone", pd("phone"));
        m.put("guardianPhone", pd("relativePhone"));
        m.put("lat", loc.getLatitude());
        m.put("lng", loc.getLongitude());
        m.put("hospitalId", "");

        String nursePhone = "";
        if (hospital != null) {
            double[] hp = HospitalCatalog.position(this, hospital);
            m.put("hospitalId", hospital.getId());
            m.put("hospitalName", Sos.nz(hospital.getString("name")));
            m.put("hospitalPhone", Sos.nz(hospital.getString("phone")));
            m.put("hospitalDistance", hospitalDist);
            if (hp != null) {
                m.put("hospitalLat", hp[0]);
                m.put("hospitalLng", hp[1]);
            }
            nursePhone = Sos.nz(hospital.getString("nursePhone"));
            hospitalPhone = Sos.nz(hospital.getString("phone"));
            if (hospitalPhone.isEmpty()) hospitalPhone = nursePhone;
        }

        // SMS text + numbers (guardian and the hospital's nurse)
        smsPhones = new ArrayList<>();
        if (!pd("relativePhone").isEmpty()) smsPhones.add(pd("relativePhone"));
        if (!nursePhone.isEmpty()) smsPhones.add(nursePhone);
        smsBody = "SEHA SOS: " + (name.isEmpty() ? "A patient" : name) + " needs emergency help.\n"
                + "Patient ID: " + code + "\n"
                + (condition.isEmpty() ? "" : "Condition: " + condition + "\n")
                + "Location: " + Sos.mapsLink(loc.getLatitude(), loc.getLongitude())
                + (hospital != null ? "\nNearest hospital: " + Sos.nz(hospital.getString("name")) : "")
                + "\nTrack in the SEHA app: Guardian > Patient ID + username.";

        DocumentReference ref = db.collection(Sos.ALERTS).document();
        freshAlert = true;
        observe(ref.getId());                 // the listener also fires for the local (pending) write
        ref.set(m).addOnFailureListener(e -> {
            if (!alive()) return;
            sosId = null;
            if (sosListener != null) {
                sosListener.remove();
                sosListener = null;
            }
            fail(Fs.explain(e));
        });
    }

    // ------------------------------------------------------------------------------------
    // 5. show the running SOS
    // ------------------------------------------------------------------------------------

    private void observe(String id) {
        sosId = id;
        if (sosListener != null) sosListener.remove();
        btnCancel.setText("✅ I'm safe — cancel SOS");
        btnCancel.setVisibility(View.VISIBLE);
        btnAmbulance.setVisibility(View.VISIBLE);
        sosListener = db.collection(Sos.ALERTS).document(id).addSnapshotListener(MetadataChanges.INCLUDE, (s, e) -> {
            if (!alive() || s == null || !s.exists()) return;
            render(s);
        });
    }

    private void render(DocumentSnapshot s) {
        String st = Sos.nz(s.getString("status"));
        boolean pending = s.getMetadata().hasPendingWrites();
        String hospital = Sos.nz(s.getString("hospitalName"));
        Double dist = s.getDouble("hospitalDistance");
        String code = Sos.nz(s.getString("patientCode"));
        String cond = Sos.nz(s.getString("condition"));

        StringBuilder detail = new StringBuilder();
        detail.append("Patient ID: ").append(code);
        if (!cond.isEmpty()) detail.append("\n⚠ ").append(cond);

        if (Sos.S_CANCELLED.equals(st) || Sos.S_RESOLVED.equals(st)) {
            LiveShare.get(this).stop(LiveShare.REASON_SOS);
            setTitleAndStatus(Sos.S_RESOLVED.equals(st) ? "✅ SOS resolved" : "SOS cancelled",
                    Sos.S_RESOLVED.equals(st) ? "The hospital marked this emergency as resolved." : "");
            txtDetail.setText("");
            btnCancel.setText("Close");
            btnAmbulance.setVisibility(View.GONE);
            btnSms.setVisibility(View.GONE);
            btnCallHospital.setVisibility(View.GONE);
            return;
        }

        if (pending) {
            setTitleAndStatus("🆘 SOS", "⏳ Sending your SOS...\n(waiting for internet)");
            detail.append("\n\nNo internet? Call ").append(Sos.AMBULANCE_NUMBER).append(" now.");
        } else if (hospital.isEmpty()) {
            setTitleAndStatus("🆘 SOS SENT",
                    "⚠ No SEHA hospital is registered near you.\nYour guardian is informed.");
            detail.append("\n\nPlease call ").append(Sos.AMBULANCE_NUMBER).append(" now.");
        } else if (Sos.S_ACK.equals(st)) {
            setTitleAndStatus("🆘 HELP IS COMING",
                    "🏥 " + hospital + " has seen your alert and is responding.");
        } else {
            setTitleAndStatus("🆘 SOS SENT",
                    "✅ Nearest hospital notified:\n🏥 " + hospital
                            + (dist != null ? "  (" + Geo.dist(dist) + " away)" : ""));
        }
        detail.append("\n\n📍 Your live location is being shared with the hospital and your guardian.")
                .append("\nKeep this screen open.");
        txtDetail.setText(detail.toString());

        if (!hospitalPhone.isEmpty() || !Sos.nz(s.getString("hospitalPhone")).isEmpty()) {
            if (hospitalPhone.isEmpty()) hospitalPhone = Sos.nz(s.getString("hospitalPhone"));
            btnCallHospital.setVisibility(View.VISIBLE);
        }

        if (!activated && !pending) {
            activated = true;
            // live GPS while the SOS runs
            LiveShare.get(this).start(LiveShare.REASON_SOS);
            if (freshAlert && !smsPhones.isEmpty()) {
                boolean sent = Sos.sendSmsQuiet(this, smsPhones, smsBody);
                if (sent) Toast.makeText(this, "SMS sent to guardian / nurse", Toast.LENGTH_LONG).show();
                btnSms.setText(sent ? "✉ Resend SMS to guardian / nurse" : "✉ Send SMS to guardian / nurse");
                btnSms.setVisibility(View.VISIBLE);
            }
        }
    }

    // ------------------------------------------------------------------------------------

    private void fail(String msg) {
        if (!alive()) return;
        flowStarted = false;
        setTitleAndStatus("🆘 SOS NOT SENT", msg);
        txtDetail.setText("Call " + Sos.AMBULANCE_NUMBER + " now if this is an emergency.");
        btnRetry.setText("🔄 Try again");
        btnRetry.setVisibility(View.VISIBLE);
        btnCancel.setText("✖ Close");
        btnCancel.setVisibility(View.VISIBLE);
    }
}
