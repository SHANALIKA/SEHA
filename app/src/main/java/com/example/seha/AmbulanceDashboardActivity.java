package com.example.seha;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AMBULANCE DRIVER SCREEN
 *  - Online / Offline switch (online = visible to patients & hospitals as nearest ambulance)
 *  - Incoming emergency request with Accept / Decline
 *  - Route + distance + ETA to the patient, then to the nearest hospital
 *  - Call patient / guardian / hospital, SMS the guardian so they can track live
 *  - "Picked up" and "Complete" buttons
 */
public class AmbulanceDashboardActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private String uid;
    private String vehicleId = "";
    private String vehicleNumber = "";
    private String myPhone = "";
    private String myName = "";

    private MapView map;
    private TextView txtStatus, txtInfo, txtDetail, txtRequest;
    private LinearLayout layoutRequest, layoutActions;
    private Button btnAccept, btnDecline, btnCall, btnNotify, btnPrimary, btnCancel;
    private SwitchCompat switchOnline;

    private FusedLocationProviderClient fused;
    private LocationCallback locationCallback;
    private GeoPoint myPos;

    private boolean online = false;
    private boolean ignoreSwitch = false;
    private boolean availableByHospital = true;
    private String hospitalOfAmbulance = "";

    private Marker meMarker, patientMarker, hospitalMarker;
    private android.graphics.drawable.Drawable icAmb, icPatient, icHospital, icStart, icEnd;
    private Marker pickupAsStart;
    private Polyline tripLine;
    private String tripKey = null;
    private double tripMeters = 0;
    private Polyline routeLine;

    private ListenerRegistration ambListener, incomingListener, jobListener;
    private DocumentSnapshot incoming;          // request currently offered to me
    private String lastAlertedRequest = null;

    private String jobId = null;                // request I am working on
    private DocumentSnapshot job;
    private String lastLeg = null;              // "pickup" / "hospital" (to refit camera)

    // route throttling
    private int routeSeq = 0;
    private long lastRouteAt = 0;
    private GeoPoint lastRouteFrom = null;
    private String lastRouteKey = null;
    private RouteHelper.Result lastRoute = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (online && vehicleId.length() > 0) {
                Map<String, Object> m = new HashMap<>();
                m.put("updatedAt", System.currentTimeMillis());
                db.collection(Fs.AMBULANCES).document(vehicleId).set(m, SetOptions.merge());
            }
            handler.postDelayed(this, 15_000);
        }
    };

    private Runnable pendingAfterSmsPermission = null;

    private final ActivityResultLauncher<String[]> locPerm =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (hasLocationPermission()) {
                    goOnline();
                } else {
                    setSwitch(false);
                    Toast.makeText(this, "Location permission is required to go online",
                            Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> smsPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Runnable r = pendingAfterSmsPermission;
                pendingAfterSmsPermission = null;
                if (r != null) r.run();   // Fs.sendSms falls back to the SMS app if denied
            });

    // =====================================================================================
    // lifecycle
    // =====================================================================================

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

        db = FirebaseFirestore.getInstance();
        fused = LocationServices.getFusedLocationProviderClient(this);

        map = findViewById(R.id.map);
        txtStatus = findViewById(R.id.txtStatus);
        txtInfo = findViewById(R.id.txtInfo);
        txtDetail = findViewById(R.id.txtDetail);
        txtRequest = findViewById(R.id.txtRequest);
        layoutRequest = findViewById(R.id.layoutRequest);
        layoutActions = findViewById(R.id.layoutActions);
        btnAccept = findViewById(R.id.btnAccept);
        btnDecline = findViewById(R.id.btnDecline);
        btnCall = findViewById(R.id.btnCall);
        btnNotify = findViewById(R.id.btnNotify);
        btnPrimary = findViewById(R.id.btnPrimary);
        btnCancel = findViewById(R.id.btnCancel);
        switchOnline = findViewById(R.id.switchOnline);
        ImageView btnBack = findViewById(R.id.btnBack);

        MapUi.setup(map);
        icAmb = MapUi.icon(this, R.drawable.marker_ambulance, 52);
        icPatient = MapUi.icon(this, R.drawable.marker_patient, 52);
        icHospital = MapUi.icon(this, R.drawable.hospital, 46);
        icStart = MapUi.icon(this, R.drawable.marker_start, 40);
        icEnd = MapUi.icon(this, R.drawable.marker_end, 52);
        switchOnline.setVisibility(View.VISIBLE);
        btnNotify.setVisibility(View.GONE);
        btnNotify.setText("✉ Resend SMS");

        btnBack.setOnClickListener(v -> confirmLogout());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmLogout();
            }
        });

        switchOnline.setOnCheckedChangeListener((btn, checked) -> {
            if (ignoreSwitch) return;
            if (checked) goOnline();
            else goOffline();
        });

        btnAccept.setOnClickListener(v -> acceptIncoming());
        btnDecline.setOnClickListener(v -> declineIncoming());
        btnCall.setOnClickListener(v -> callMenu());
        btnNotify.setOnClickListener(v -> resendTripSms());
        btnPrimary.setOnClickListener(v -> primaryAction());

        txtStatus.setText("Loading your ambulance...");
        loadProfile();
        handler.postDelayed(heartbeat, 15_000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (map != null) map.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(heartbeat);
        stopLocationUpdates();
        if (ambListener != null) ambListener.remove();
        if (incomingListener != null) incomingListener.remove();
        if (jobListener != null) jobListener.remove();
        // leaving the screen while idle = go offline (so nobody dispatches a dead phone)
        if (online && jobId == null && vehicleId.length() > 0) {
            Map<String, Object> m = new HashMap<>();
            m.put("online", false);
            db.collection(Fs.AMBULANCES).document(vehicleId).set(m, SetOptions.merge());
        }
    }

    private void confirmLogout() {
        if (jobId != null) {
            Toast.makeText(this, "Finish the current trip first", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Logout?")
                .setMessage("You will go offline and be logged out.")
                .setPositiveButton("Logout", (d, w) -> {
                    goOffline();
                    FirebaseAuth.getInstance().signOut();
                    Intent i = new Intent(this, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =====================================================================================
    // profile + vehicle
    // =====================================================================================

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void loadProfile() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }
        uid = u.getUid();
        db.collection(Fs.PATIENTS).document(uid).get()
                .addOnSuccessListener(doc -> {
                    vehicleNumber = nz(doc.getString("vehicleNumber"));
                    myPhone = nz(doc.getString("phone"));
                    myName = nz(doc.getString("fullName"));
                    if (vehicleNumber.trim().isEmpty()) askVehicle();
                    else afterVehicle();
                })
                .addOnFailureListener(e -> txtStatus.setText("Could not load profile: " + e.getMessage()));
    }

    private void askVehicle() {
        final EditText et = new EditText(this);
        et.setHint("e.g. WP CAB-1234");
        new AlertDialog.Builder(this)
                .setTitle("Vehicle number")
                .setMessage("Enter this ambulance's vehicle number (used as its ID).")
                .setView(et)
                .setCancelable(false)
                .setPositiveButton("Save", (d, w) -> {
                    String v = et.getText().toString().trim();
                    if (Fs.vehicleId(v).isEmpty()) {
                        askVehicle();
                        return;
                    }
                    vehicleNumber = v;
                    Map<String, Object> m = new HashMap<>();
                    m.put("vehicleNumber", v);
                    db.collection(Fs.PATIENTS).document(uid).set(m, SetOptions.merge());
                    afterVehicle();
                })
                .show();
    }

    /** Ambulances made before hospitals existed: choose the hospital this ambulance belongs to. */
    private void askHospital() {
        final List<HospitalCatalog.Entry> all = HospitalCatalog.all(this);
        String[] names = new String[all.size()];
        for (int i = 0; i < names.length; i++) names[i] = all.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Which hospital does this ambulance belong to?")
                .setItems(names, (d, which) -> {
                    final String hn = all.get(which).name;
                    db.collection(Fs.HOSPITALS).whereEqualTo("name", hn).get().addOnSuccessListener(qs -> {
                        if (qs.isEmpty()) {
                            Toast.makeText(this, "That hospital has no SEHA account yet", Toast.LENGTH_LONG).show();
                            askHospital();
                            return;
                        }
                        String hid = qs.getDocuments().get(0).getId();
                        Map<String, Object> m = new HashMap<>();
                        m.put("hospitalId", hid);
                        m.put("hospitalName", hn);
                        db.collection(Fs.AMBULANCES).document(vehicleId).set(m, SetOptions.merge());
                        db.collection(Fs.PATIENTS).document(uid).set(m, SetOptions.merge());
                        Toast.makeText(this, "Ambulance registered under " + hn, Toast.LENGTH_LONG).show();
                    });
                })
                .setCancelable(false)
                .show();
    }

    private void askPhone() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_PHONE);
        et.setHint("07XXXXXXXX");
        new AlertDialog.Builder(this)
                .setTitle("Your phone number")
                .setMessage("Patients, guardians and the hospital will use this number to call the ambulance.")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) return;
                    myPhone = p;
                    Map<String, Object> m = new HashMap<>();
                    m.put("phone", p);
                    db.collection(Fs.PATIENTS).document(uid).set(m, SetOptions.merge());
                    Map<String, Object> a = new HashMap<>();
                    a.put("phone", p);
                    db.collection(Fs.AMBULANCES).document(vehicleId).set(a, SetOptions.merge());
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private void afterVehicle() {
        vehicleId = Fs.vehicleId(vehicleNumber);
        DocumentReference ref = db.collection(Fs.AMBULANCES).document(vehicleId);
        ref.get().addOnSuccessListener(a -> {
            boolean busy = a.exists() && Boolean.TRUE.equals(a.getBoolean("busy"));
            Map<String, Object> m = new HashMap<>();
            m.put("vehicleNumber", vehicleNumber);
            m.put("uid", uid);
            m.put("phone", myPhone);
            m.put("driverName", myName);
            if (!a.exists()) m.put("available", true);
            if (!busy) m.put("online", false);
            ref.set(m, SetOptions.merge());

            startListening();
            if (a.getString("hospitalId") == null || a.getString("hospitalId").isEmpty()) askHospital();
            if (busy) {
                // app was closed during a trip -> resume it
                setSwitch(true);
                online = true;
                if (hasLocationPermission()) startLocationUpdates();
            }
            if (myPhone.isEmpty()) askPhone();
            renderIdle();
        }).addOnFailureListener(e -> {
            startListening();
            renderIdle();
        });
    }

    // =====================================================================================
    // online / offline + location
    // =====================================================================================

    private void setSwitch(boolean on) {
        ignoreSwitch = true;
        switchOnline.setChecked(on);
        ignoreSwitch = false;
        switchOnline.setText(on ? "Online  " : "Offline  ");
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void goOnline() {
        if (vehicleId.isEmpty()) {
            setSwitch(false);
            Toast.makeText(this, "Vehicle not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasLocationPermission()) {
            locPerm.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        online = true;
        setSwitch(true);
        startLocationUpdates();
        Map<String, Object> m = new HashMap<>();
        m.put("online", true);
        m.put("updatedAt", System.currentTimeMillis());
        db.collection(Fs.AMBULANCES).document(vehicleId).set(m, SetOptions.merge());
        renderIdle();
    }

    private void goOffline() {
        if (jobId != null) {
            setSwitch(true);
            Toast.makeText(this, "Finish the current trip before going offline",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        online = false;
        setSwitch(false);
        stopLocationUpdates();
        if (vehicleId.length() > 0) {
            Map<String, Object> m = new HashMap<>();
            m.put("online", false);
            db.collection(Fs.AMBULANCES).document(vehicleId).set(m, SetOptions.merge());
        }
        MapUi.removeMarker(map, meMarker);
        meMarker = null;
        incoming = null;
        renderIdle();
    }

    private void startLocationUpdates() {
        if (!hasLocationPermission()) return;
        stopLocationUpdates();

        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                if (result.getLastLocation() == null || !online) return;
                onMyLocation(result.getLastLocation().getLatitude(),
                        result.getLastLocation().getLongitude());
            }
        };
        try {
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
            fused.getLastLocation().addOnSuccessListener(l -> {
                if (l != null && online && myPos == null) {
                    onMyLocation(l.getLatitude(), l.getLongitude());
                }
            });
        } catch (SecurityException e) {
            Toast.makeText(this, "Location permission missing", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null) {
            fused.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    private void onMyLocation(double lat, double lng) {
        boolean first = myPos == null;
        myPos = new GeoPoint(lat, lng);

        Map<String, Object> m = new HashMap<>();
        m.put("lat", lat);
        m.put("lng", lng);
        m.put("online", true);
        m.put("updatedAt", System.currentTimeMillis());
        db.collection(Fs.AMBULANCES).document(vehicleId).set(m, SetOptions.merge());

        meMarker = MapUi.marker(map, meMarker, myPos, "You (" + vehicleNumber + ")",
                icAmb);

        if (first && jobId == null) {
            map.getController().setZoom(16.0);
            map.getController().setCenter(myPos);
        }
        if (incoming != null) showIncoming(incoming);   // refresh distance text
        if (job != null) renderJob(job, false);
    }

    // =====================================================================================
    // Firestore listeners
    // =====================================================================================

    private void startListening() {
        if (ambListener != null) ambListener.remove();
        if (incomingListener != null) incomingListener.remove();

        // my ambulance document -> tells me which job I am on, and if the hospital paused me
        ambListener = db.collection(Fs.AMBULANCES).document(vehicleId)
                .addSnapshotListener((s, e) -> {
                    if (s == null || !s.exists()) return;
                    Boolean av = s.getBoolean("available");
                    availableByHospital = av == null || av;
                    hospitalOfAmbulance = s.getString("hospitalName") != null ? s.getString("hospitalName") : "";
                    String cur = s.getString("currentRequestId");
                    if (cur != null && !cur.equals(jobId)) {
                        startJob(cur);
                    } else if (cur == null && jobId != null) {
                        endJob();
                    } else if (jobId == null) {
                        renderIdle();
                    }
                });

        // requests offered to me
        incomingListener = db.collection(Fs.REQUESTS)
                .whereEqualTo("targetAmbulanceId", vehicleId)
                .addSnapshotListener((qs, e) -> {
                    if (qs == null) return;
                    DocumentSnapshot found = null;
                    for (DocumentSnapshot d : qs.getDocuments()) {
                        if (Fs.S_REQUESTED.equals(d.getString("status"))) {
                            found = d;
                            break;
                        }
                    }
                    if (found != null && online && jobId == null) {
                        showIncoming(found);
                    } else {
                        incoming = null;
                        layoutRequest.setVisibility(View.GONE);
                    }
                });
    }

    // =====================================================================================
    // incoming request
    // =====================================================================================

    private void showIncoming(DocumentSnapshot d) {
        incoming = d;
        String name = nz(d.getString("patientName"));
        String cond = nz(d.getString("condition"));
        StringBuilder sb = new StringBuilder("🚨 EMERGENCY REQUEST\n");
        sb.append("Patient: ").append(name.isEmpty() ? "Unknown" : name);
        if (!cond.isEmpty()) sb.append("\n⚠ ").append(cond);
        Double la = d.getDouble("pickupLat");
        Double lo = d.getDouble("pickupLng");
        if (la != null && lo != null && myPos != null) {
            sb.append("\nPickup is ").append(Geo.dist(
                    Geo.meters(myPos.getLatitude(), myPos.getLongitude(), la, lo))).append(" away");
        }
        txtRequest.setText(sb.toString());
        layoutRequest.setVisibility(View.VISIBLE);
        layoutActions.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.GONE);
        txtStatus.setText("🟢 ONLINE — new request!");

        if (!d.getId().equals(lastAlertedRequest)) {
            lastAlertedRequest = d.getId();
            try {
                RingtoneManager.getRingtone(this,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play();
            } catch (Exception ignored) { }
        }
    }

    private void acceptIncoming() {
        if (incoming == null) return;
        final String reqId = incoming.getId();
        final DocumentReference reqRef = db.collection(Fs.REQUESTS).document(reqId);
        final DocumentReference ambRef = db.collection(Fs.AMBULANCES).document(vehicleId);
        btnAccept.setEnabled(false);

        db.runTransaction(tr -> {
            DocumentSnapshot s = tr.get(reqRef);
            if (!Fs.S_REQUESTED.equals(s.getString("status"))
                    || !vehicleId.equals(s.getString("targetAmbulanceId"))) {
                throw new FirebaseFirestoreException("Request is no longer available",
                        FirebaseFirestoreException.Code.ABORTED);
            }
            Map<String, Object> r = new HashMap<>();
            r.put("status", Fs.S_ACCEPTED);
            r.put("ambulanceId", vehicleId);
            r.put("ambulanceUid", uid);
            r.put("ambulanceNumber", vehicleNumber);
            r.put("ambulancePhone", myPhone);
            r.put("acceptedAt", System.currentTimeMillis());
            tr.set(reqRef, r, SetOptions.merge());

            Map<String, Object> a = new HashMap<>();
            a.put("busy", true);
            a.put("currentRequestId", reqId);
            tr.set(ambRef, a, SetOptions.merge());
            return null;
        }).addOnSuccessListener(v -> {
            btnAccept.setEnabled(true);
            incoming = null;
            layoutRequest.setVisibility(View.GONE);
            startJob(reqId);
        }).addOnFailureListener(e -> {
            btnAccept.setEnabled(true);
            Toast.makeText(this, "Could not accept: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void declineIncoming() {
        if (incoming == null) return;
        final String reqId = incoming.getId();
        incoming = null;
        layoutRequest.setVisibility(View.GONE);
        // status "declined" -> the requester's screen offers it to the next nearest ambulance
        DocumentReference ref = db.collection(Fs.REQUESTS).document(reqId);
        db.runTransaction(tr -> {
            DocumentSnapshot s = tr.get(ref);
            if (Fs.S_REQUESTED.equals(s.getString("status"))
                    && vehicleId.equals(s.getString("targetAmbulanceId"))) {
                tr.update(ref, "status", Fs.S_DECLINED);
            }
            return null;
        });
        renderIdle();
    }

    // =====================================================================================
    // current job
    // =====================================================================================

    private void startJob(String reqId) {
        jobId = reqId;
        job = null;
        lastLeg = null;
        lastRoute = null;
        lastRouteKey = null;
        if (jobListener != null) jobListener.remove();
        jobListener = db.collection(Fs.REQUESTS).document(reqId).addSnapshotListener((s, e) -> {
            if (s == null || !s.exists()) return;
            job = s;
            String st = s.getString("status");
            if (Fs.isTerminal(st)) {
                String msg = Fs.S_COMPLETED.equals(st) ? "Trip completed ✅" : "Trip was cancelled";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                // make sure I'm free even if the other side only changed the request
                Map<String, Object> a = new HashMap<>();
                a.put("busy", false);
                a.put("currentRequestId", FieldValue.delete());
                db.collection(Fs.AMBULANCES).document(vehicleId).set(a, SetOptions.merge());
                endJob();
                return;
            }
            renderJob(s, true);
        });
    }

    private void endJob() {
        if (jobListener != null) jobListener.remove();
        jobListener = null;
        jobId = null;
        job = null;
        lastRoute = null;
        lastRouteKey = null;
        routeSeq++;
        MapUi.removeLine(map, routeLine);
        routeLine = null;
        MapUi.removeLine(map, tripLine);
        tripLine = null;
        tripKey = null;
        tripMeters = 0;
        MapUi.removeMarker(map, patientMarker);
        patientMarker = null;
        MapUi.removeMarker(map, hospitalMarker);
        hospitalMarker = null;
        renderIdle();
        if (myPos != null) {
            map.getController().setZoom(16.0);
            map.getController().setCenter(myPos);
        }
    }

    private void renderIdle() {
        if (jobId != null) return;
        layoutActions.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.GONE);
        btnCancel.setVisibility(View.GONE);
        if (incoming == null) layoutRequest.setVisibility(View.GONE);
        if (incoming != null) return;

        if (!online) {
            txtStatus.setText("🔴 OFFLINE");
            txtInfo.setText("Turn the switch ON (top right) to receive emergency requests");
        } else if (!availableByHospital) {
            txtStatus.setText("🟠 ONLINE — paused by hospital");
            txtInfo.setText("Your hospital marked this ambulance as not available.");
        } else {
            txtStatus.setText("🟢 ONLINE — waiting for emergency requests");
            txtInfo.setText(myPos == null ? "Getting your GPS location..." : "Your location is being shared live.");
        }
        txtDetail.setText("Vehicle: " + vehicleNumber + (myPhone.isEmpty() ? "" : "   •   " + myPhone)
                + (hospitalOfAmbulance.isEmpty() ? "" : "\n🏥 " + hospitalOfAmbulance));
    }

    private void renderJob(DocumentSnapshot d, boolean newSnapshot) {
        String st = nz(d.getString("status"));
        boolean toHospital = Fs.S_PICKED_UP.equals(st);

        layoutRequest.setVisibility(View.GONE);
        layoutActions.setVisibility(View.VISIBLE);
        btnPrimary.setVisibility(View.VISIBLE);
        btnNotify.setVisibility(toHospital ? View.VISIBLE : View.GONE);

        String patientName = nz(d.getString("patientName"));
        if (patientName.isEmpty()) patientName = "Unknown patient";

        // markers
        GeoPoint pickup = null;
        if (Fs.hasLatLng(d, "pickupLat", "pickupLng")) {
            pickup = new GeoPoint(d.getDouble("pickupLat"), d.getDouble("pickupLng"));
            boolean legToHospital = Fs.S_PICKED_UP.equals(st);
            patientMarker = MapUi.marker(map, patientMarker, pickup,
                    legToHospital ? "START: patient pickup point" : "Patient: " + patientName,
                    legToHospital ? icStart : icPatient);
        }
        GeoPoint hospital = null;
        if (Fs.hasLatLng(d, "hospitalLat", "hospitalLng")) {
            hospital = new GeoPoint(d.getDouble("hospitalLat"), d.getDouble("hospitalLng"));
            hospitalMarker = MapUi.marker(map, hospitalMarker, hospital,
                    "END: " + nz(d.getString("hospitalName")), icEnd);
        }

        GeoPoint target = toHospital ? hospital : pickup;
        String leg = toHospital ? "hospital" : "pickup";

        if (toHospital) {
            String hn = nz(d.getString("hospitalName"));
            txtStatus.setText("🏥 Taking patient to " + (hn.isEmpty() ? "hospital" : hn));
            btnPrimary.setText("🏁 Arrived at hospital — Complete trip");
        } else {
            txtStatus.setText("🚑 Go to patient: " + patientName);
            btnPrimary.setText("✅ Patient picked up → go to nearest hospital");
        }

        StringBuilder sb = new StringBuilder();
        String cond = nz(d.getString("condition"));
        if (!cond.isEmpty()) sb.append("⚠ ").append(cond).append("\n");
        String blood = nz(d.getString("bloodGroup"));
        if (!blood.isEmpty()) sb.append("Blood: ").append(blood).append("   ");
        String allergy = nz(d.getString("allergies"));
        if (!allergy.isEmpty()) sb.append("Allergies: ").append(allergy);
        String code = nz(d.getString("patientCode"));
        if (!code.isEmpty()) sb.append("\nPatient ID: ").append(code);
        txtDetail.setText(sb.toString().trim());

        if (target == null) {
            txtInfo.setText(toHospital ? "No hospital registered yet — complete the trip when done"
                    : "Waiting for patient location...");
            return;
        }

        if (!leg.equals(lastLeg)) {
            lastLeg = leg;
            lastRoute = null;
            lastRouteKey = null;
            MapUi.removeLine(map, routeLine);
            routeLine = null;
            MapUi.fit(map, myPos, target);
        }
        if (toHospital && pickup != null && hospital != null) drawPlannedTrip(pickup, hospital);
        refreshInfo(target);
        maybeRoute(target, leg, toHospital ? Color.parseColor("#2E7D32") : Color.parseColor("#1565C0"));
    }

    /** Whole trip START (patient) -> END (hospital) drawn once, like a Google Maps preview. */
    private void drawPlannedTrip(GeoPoint start, GeoPoint end) {
        String key = start.getLatitude() + "," + start.getLongitude() + ">" + end.getLatitude() + "," + end.getLongitude();
        if (key.equals(tripKey)) return;
        tripKey = key;
        RouteHelper.route(start, end, r -> {
            if (!key.equals(tripKey)) return;
            tripMeters = r.meters;
            tripLine = MapUi.line(map, tripLine, r.points, Color.parseColor("#90A4AE"), 22f, true);
            if (lastRoute != null && job != null) refreshInfoFromJob();
        });
    }

    private void refreshInfoFromJob() {
        if (job == null) return;
        GeoPoint t = null;
        if (Fs.S_PICKED_UP.equals(job.getString("status")) && Fs.hasLatLng(job, "hospitalLat", "hospitalLng")) {
            t = new GeoPoint(job.getDouble("hospitalLat"), job.getDouble("hospitalLng"));
        } else if (Fs.hasLatLng(job, "pickupLat", "pickupLng")) {
            t = new GeoPoint(job.getDouble("pickupLat"), job.getDouble("pickupLng"));
        }
        if (t != null) refreshInfo(t);
    }

    private void refreshInfo(GeoPoint target) {
        if (lastRoute != null) {
            txtInfo.setText("📍 " + Geo.dist(lastRoute.meters) + " to go  •  ETA " + Geo.eta(lastRoute.seconds)
                    + (lastRoute.approximate ? "  (estimate)" : "")
                    + (tripMeters > 0 && Fs.S_PICKED_UP.equals(job != null ? job.getString("status") : "")
                    ? "\nTrip total: " + Geo.dist(tripMeters) : ""));
        } else if (myPos != null) {
            txtInfo.setText("📍 " + Geo.dist(Geo.meters(myPos.getLatitude(), myPos.getLongitude(),
                    target.getLatitude(), target.getLongitude())) + " (straight line)");
        } else {
            txtInfo.setText("Getting your GPS location...");
        }
    }

    private void maybeRoute(GeoPoint target, String leg, int color) {
        if (myPos == null) return;
        String key = leg + ":" + target.getLatitude() + "," + target.getLongitude();
        long now = System.currentTimeMillis();
        boolean same = key.equals(lastRouteKey);
        if (same && lastRouteFrom != null && now - lastRouteAt < 10_000
                && Geo.meters(lastRouteFrom.getLatitude(), lastRouteFrom.getLongitude(),
                myPos.getLatitude(), myPos.getLongitude()) < 50) {
            return;
        }
        lastRouteKey = key;
        lastRouteAt = now;
        lastRouteFrom = myPos;
        final int seq = ++routeSeq;
        RouteHelper.route(myPos, target, r -> {
            if (seq != routeSeq || jobId == null) return;
            lastRoute = r;
            routeLine = MapUi.line(map, routeLine, r.points, color);
            refreshInfo(target);
        });
    }

    // =====================================================================================
    // buttons
    // =====================================================================================

    private void primaryAction() {
        if (job == null || jobId == null) return;
        String st = nz(job.getString("status"));

        if (Fs.S_ACCEPTED.equals(st)) {
            chooseHospital();

        } else if (Fs.S_PICKED_UP.equals(st)) {
            new AlertDialog.Builder(this)
                    .setTitle("Complete trip?")
                    .setMessage("Confirm the patient has been handed over at the hospital.")
                    .setPositiveButton("Complete", (d, w) ->
                            Fs.finishRequest(jobId, vehicleId, Fs.S_COMPLETED))
                    .setNegativeButton("Not yet", null)
                    .show();
        }
    }

    // =====================================================================================
    // choose destination hospital (nearest first) + SMS the hospital's nurse
    // =====================================================================================

    private void chooseHospital() {
        if (job == null) return;
        final double lat = Fs.hasLatLng(job, "pickupLat", "pickupLng") ? job.getDouble("pickupLat")
                : (myPos != null ? myPos.getLatitude() : 0);
        final double lng = Fs.hasLatLng(job, "pickupLat", "pickupLng") ? job.getDouble("pickupLng")
                : (myPos != null ? myPos.getLongitude() : 0);

        btnPrimary.setEnabled(false);
        Toast.makeText(this, "Searching hospitals near the patient...", Toast.LENGTH_SHORT).show();

        // 1) hospitals registered in SEHA (they have a nurse) ...
        db.collection(Fs.HOSPITALS).get().addOnSuccessListener(qs -> {
            // RESET: a wrong position saved earlier is replaced by the real one
            for (DocumentSnapshot h : qs.getDocuments()) {
                double[] pos = HospitalCatalog.position(this, h);
                HospitalCatalog.Entry en = HospitalCatalog.find(this, h.getString("name"));
                if (pos != null && en != null && en.exact) {
                    Double sl = h.getDouble("lat");
                    Double sg = h.getDouble("lng");
                    if (sl == null || sg == null || Geo.meters(sl, sg, pos[0], pos[1]) > 20) {
                        Map<String, Object> fix = new HashMap<>();
                        fix.put("lat", pos[0]);
                        fix.put("lng", pos[1]);
                        db.collection(Fs.HOSPITALS).document(h.getId()).set(fix, SetOptions.merge());
                    }
                }
            }
            searchHospitals(lat, lng, qs.getDocuments());
        }).addOnFailureListener(e -> searchHospitals(lat, lng, new ArrayList<>()));
    }

    // 2) ... plus every real hospital around the patient (OpenStreetMap), nearest first
    private void searchHospitals(double lat, double lng, List<DocumentSnapshot> sehaDocs) {
        HospitalFinder.find(this, lat, lng, sehaDocs, (list, online) -> {
            btnPrimary.setEnabled(true);
            if (jobId == null) return;
            if (list.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("No hospital found")
                        .setMessage("Could not find any hospital. Continue without a destination hospital?")
                        .setPositiveButton("Continue", (d, w) -> confirmPickup(null))
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            if (!online) {
                Toast.makeText(this, "No internet: showing SEHA hospitals only", Toast.LENGTH_LONG).show();
            }
            showHospitalPicker(list);
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.GradientDrawable roundBg(int fill, int stroke, int strokeDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private TextView label(String text, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    /** One card per hospital: name, distance, SEHA tag and a SELECT button. Nearest first. */
    private void showHospitalPicker(final List<HospitalFinder.Hospital> list) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F7FA"));
        root.setPadding(dp(14), dp(16), dp(14), dp(12));

        root.addView(label("Select destination hospital", 18, "#0D47A1", true));
        TextView sub = label("Nearest first  •  distance from the patient", 12, "#607D8B", false);
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < list.size(); i++) {
            final HospitalFinder.Hospital h = list.get(i);
            boolean nearest = i == 0;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackground(nearest ? roundBg(Color.parseColor("#F1F8E9"), Color.parseColor("#2E7D32"), 2)
                    : roundBg(Color.WHITE, Color.parseColor("#CFD8DC"), 1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = dp(10);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            if (nearest) left.addView(label("⭐ NEAREST", 11, "#2E7D32", true));
            left.addView(label(h.name, 15, "#0D47A1", true));
            left.addView(label("📍 " + Geo.dist(h.distance) + " away", 13, "#455A64", false));
            if (h.seha) {
                left.addView(label(h.nursePhone.isEmpty() ? "SEHA hospital • no nurse yet"
                        : "✅ SEHA hospital • nurse will be alerted", 12,
                        h.nursePhone.isEmpty() ? "#E65100" : "#2E7D32", true));
            } else {
                left.addView(label("Guardian SMS only", 12, "#78909C", false));
            }
            card.addView(left, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button select = new Button(this);
            select.setText("Select");
            select.setAllCaps(false);
            select.setTextColor(Color.WHITE);
            select.setTextSize(14);
            select.setBackgroundResource(R.drawable.loginbutton);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(92), dp(44));
            sp.leftMargin = dp(8);
            card.addView(select, sp);

            View.OnClickListener pick = v -> {
                dlg.dismiss();
                confirmPickup(h);
            };
            select.setOnClickListener(pick);
            card.setOnClickListener(pick);
            box.addView(card, cp);
        }

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setTextColor(Color.parseColor("#D32F2F"));
        cancel.setBackgroundResource(R.drawable.signupbutton);
        cancel.setOnClickListener(v -> dlg.dismiss());
        LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        xp.topMargin = dp(6);
        root.addView(cancel, xp);

        dlg.setContentView(root);
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.82));
            dlg.getWindow().setBackgroundDrawable(roundBg(Color.parseColor("#F5F7FA"), Color.TRANSPARENT, 0));
        }
    }

    private void confirmPickup(HospitalFinder.Hospital hospital) {
        if (jobId == null || job == null) return;

        Map<String, Object> m = new HashMap<>();
        m.put("status", Fs.S_PICKED_UP);
        m.put("pickedUpAt", System.currentTimeMillis());
        if (Fs.hasLatLng(job, "pickupLat", "pickupLng")) {
            m.put("startLat", job.getDouble("pickupLat"));
            m.put("startLng", job.getDouble("pickupLng"));
        }
        String nursePhone = "";
        String hospName = "";
        double approxMeters = 0;
        if (hospital != null) {
            hospName = hospital.name;
            nursePhone = hospital.nursePhone;
            m.put("hospitalId", hospital.id);
            m.put("hospitalName", hospital.name);
            m.put("hospitalPhone", hospital.phone);
            m.put("nursePhone", nursePhone);
            m.put("hospitalLat", hospital.lat);
            m.put("hospitalLng", hospital.lng);
            m.put("hospitalSeha", hospital.seha);
            if (myPos != null) {
                approxMeters = Geo.meters(myPos.getLatitude(), myPos.getLongitude(),
                        hospital.lat, hospital.lng) * 1.3;
            }
        }
        db.collection(Fs.REQUESTS).document(jobId).set(m, SetOptions.merge());

        if (hospital == null) return;

        // automatic emergency SMS (nurse + guardian) - sent ONLY by the ambulance
        sendTripSms(hospName, nursePhone, approxMeters);
    }

    private void callMenu() {
        if (job == null) return;
        final List<String> labels = new ArrayList<>();
        final List<String> numbers = new ArrayList<>();
        addNumber(labels, numbers, "Patient", job.getString("patientPhone"));
        addNumber(labels, numbers, "Guardian / relative", job.getString("guardianPhone"));
        if (Fs.S_PICKED_UP.equals(job.getString("status"))) {
            addNumber(labels, numbers, "Hospital", job.getString("hospitalPhone"));
        }
        if (numbers.isEmpty()) {
            Toast.makeText(this, "No phone numbers saved for this patient", Toast.LENGTH_LONG).show();
            return;
        }
        if (numbers.size() == 1) {
            Fs.dial(this, numbers.get(0));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Call")
                .setItems(labels.toArray(new String[0]), (d, which) -> Fs.dial(this, numbers.get(which)))
                .show();
    }

    private static void addNumber(List<String> labels, List<String> numbers, String label, String n) {
        if (n != null && !n.trim().isEmpty()) {
            labels.add(label + "  (" + n.trim() + ")");
            numbers.add(n.trim());
        }
    }

    // =====================================================================================
    // automatic emergency SMS (nurse + guardian)
    // =====================================================================================

    /** Same message again (e.g. the first one failed). */
    private void resendTripSms() {
        if (job == null) return;
        String hospName = nz(job.getString("hospitalName"));
        String nursePhone = nz(job.getString("nursePhone"));
        double meters = 0;
        if (myPos != null && Fs.hasLatLng(job, "hospitalLat", "hospitalLng")) {
            meters = Geo.meters(myPos.getLatitude(), myPos.getLongitude(),
                    job.getDouble("hospitalLat"), job.getDouble("hospitalLng")) * 1.3;
        }
        sendTripSms(hospName, nursePhone, meters);
    }

    /**
     * When the trip to the hospital starts: ONE message with the Patient ID, destination hospital,
     * emergency notice and the ambulance phone number goes to the hospital's nurse AND the guardian.
     */
    private void sendTripSms(final String hospName, final String nursePhone, final double approxMeters) {
        if (job == null) return;
        final String patientUid = nz(job.getString("patientUid"));
        final String code0 = nz(job.getString("patientCode"));
        final String guardian0 = nz(job.getString("guardianPhone"));
        final String name = nz(job.getString("patientName"));

        // a registered patient ALWAYS has a Patient ID (stable code made from the uid)
        final String fallbackCode = code0.isEmpty() && !patientUid.isEmpty()
                ? Fs.codeForUid(patientUid) : code0;

        if (!patientUid.isEmpty() && (code0.isEmpty() || guardian0.isEmpty())) {
            db.collection(Fs.PATIENTS).document(patientUid).get()
                    .addOnSuccessListener(p -> {
                        String stored = nz(p.getString("patientCode"));
                        String c = !code0.isEmpty() ? code0 : (!stored.isEmpty() ? stored : fallbackCode);
                        String g = guardian0.isEmpty() ? nz(p.getString("relativePhone")) : guardian0;
                        doSendTripSms(c, name, g, hospName, nursePhone, approxMeters);
                    })
                    .addOnFailureListener(e ->
                            doSendTripSms(fallbackCode, name, guardian0, hospName, nursePhone, approxMeters));
        } else {
            doSendTripSms(fallbackCode, name, guardian0, hospName, nursePhone, approxMeters);
        }
    }

    private void doSendTripSms(String code, String name, String guardianPhone,
                               String hospName, String nursePhone, double approxMeters) {
        if (jobId != null && (!code.isEmpty() || !guardianPhone.isEmpty())) {
            Map<String, Object> m = new HashMap<>();
            if (!code.isEmpty()) m.put("patientCode", code);
            if (!guardianPhone.isEmpty()) m.put("guardianPhone", guardianPhone);
            m.put("tripSmsSentAt", System.currentTimeMillis());
            db.collection(Fs.REQUESTS).document(jobId).set(m, SetOptions.merge());
        }

        final List<String> phones = new ArrayList<>();
        if (!nursePhone.isEmpty()) phones.add(nursePhone);
        if (!guardianPhone.isEmpty()) phones.add(guardianPhone);
        if (phones.isEmpty()) {
            Toast.makeText(this, "No nurse or guardian phone number found — no SMS sent",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String eta = approxMeters > 0 ? "\nETA about " + Geo.eta(approxMeters / (40000.0 / 3600)) : "";
        final String body = "SEHA EMERGENCY ALERT"
                + "\nPatient ID: " + (code.isEmpty() ? "none (unidentified person)" : code)
                + (name.isEmpty() ? "" : " (" + name + ")")
                + "\nAmbulance " + vehicleNumber + (myPhone.isEmpty() ? "" : ", call " + myPhone)
                + "\nGoing to: " + (hospName.isEmpty() ? "hospital" : hospName)
                + eta
                + (code.isEmpty() ? "" : "\nGuardian: SEHA app > Track patient > ID " + code)
                + (nursePhone.isEmpty() ? "" : "\nNurse: SEHA app > Track ambulance > " + vehicleNumber);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAfterSmsPermission = () -> Fs.sendSmsMulti(this, phones, body);
            smsPerm.launch(Manifest.permission.SEND_SMS);
        } else {
            Fs.sendSmsMulti(this, phones, body);
        }
    }
}
