package com.example.seha;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
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

/**
 * LIVE TRIP SCREEN for everybody except the driver.
 *
 *  viewerRole = "patient"  : the patient who requested the ambulance
 *  viewerRole = "guardian" : guardian / relative tracking by Patient ID (read only)
 *  viewerRole = "hospital" : hospital / nurse who dispatched an ambulance after a face scan
 *
 * Shows the ambulance moving on the map, the road route, distance + ETA,
 * a call button, and (for the hospital) "slide to complete" when the ambulance arrives.
 */
public class TripMapActivity extends AppCompatActivity {

    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_ROLE = "viewerRole";
    public static final String ROLE_PATIENT = "patient";
    public static final String ROLE_GUARDIAN = "guardian";
    public static final String ROLE_HOSPITAL = "hospital";   // staff who dispatched (face scan)
    public static final String ROLE_NURSE = "nurse";         // nurse / hospital tracking an incoming ambulance

    private FirebaseFirestore db;
    private String requestId;
    private String viewerRole;
    private String myUid;

    private MapView map;
    private TextView txtStatus, txtInfo, txtDetail;
    private LinearLayout layoutActions;
    private View layoutSlide;
    private Button btnCall, btnPrimary, btnCancel, btnRecords;
    private Drawable icStart, icEnd;
    private Polyline tripLine;
    private String tripKey = null;
    private double tripMeters = 0;
    private TextView txtSlide;
    private SeekBar seekSlide;

    private ListenerRegistration reqListener, ambListener;
    private String listeningAmbulance = null;
    private DispatchHelper.Driver dispatcher = null;
    private DocumentSnapshot request;

    private Marker ambMarker, pickupMarker, hospitalMarker;
    private Polyline routeLine;
    private Drawable icAmb, icPatient, icHospital;

    private GeoPoint ambPos;
    private Long ambUpdatedAt = null;

    private int routeSeq = 0;
    private long lastRouteAt = 0;
    private GeoPoint lastRouteFrom = null;
    private String lastRouteKey = null;
    private RouteHelper.Result lastRoute = null;
    private String lastLeg = null;
    private boolean finishedDialogShown = false;

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

        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        viewerRole = getIntent().getStringExtra(EXTRA_ROLE);
        if (viewerRole == null) viewerRole = ROLE_PATIENT;
        if (requestId == null) {
            Toast.makeText(this, "No trip to show", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        db = FirebaseFirestore.getInstance();
        myUid = Fs.me();

        map = findViewById(R.id.map);
        txtStatus = findViewById(R.id.txtStatus);
        txtInfo = findViewById(R.id.txtInfo);
        txtDetail = findViewById(R.id.txtDetail);
        layoutActions = findViewById(R.id.layoutActions);
        layoutSlide = findViewById(R.id.layoutSlide);
        btnCall = findViewById(R.id.btnCall);
        btnPrimary = findViewById(R.id.btnPrimary);
        btnCancel = findViewById(R.id.btnCancel);
        btnRecords = findViewById(R.id.btnNotify);
        txtSlide = findViewById(R.id.txtSlide);
        seekSlide = findViewById(R.id.seekSlide);
        ImageView btnBack = findViewById(R.id.btnBack);

        MapUi.setup(map);
        icAmb = MapUi.icon(this, R.drawable.marker_ambulance, 56);
        icPatient = MapUi.icon(this, R.drawable.marker_patient, 52);
        icHospital = MapUi.icon(this, R.drawable.hospital, 46);
        icStart = MapUi.icon(this, R.drawable.marker_start, 40);
        icEnd = MapUi.icon(this, R.drawable.marker_end, 52);

        btnBack.setOnClickListener(v -> handleBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        btnCall.setText("📞 Call ambulance");
        btnCall.setOnClickListener(v -> {
            String phone = request != null ? request.getString("ambulancePhone") : null;
            Fs.dial(this, phone);
        });
        btnCancel.setOnClickListener(v -> confirmCancel(false));
        btnRecords.setText("📋 Patient records");
        btnRecords.setOnClickListener(v -> {
            String pu = request != null ? request.getString("patientUid") : null;
            if (pu == null || pu.isEmpty()) {
                Toast.makeText(this, "No patient profile linked to this trip", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.Intent i = new android.content.Intent(this, PatientRecordsActivity.class);
            i.putExtra(PatientRecordsActivity.EXTRA_PATIENT_UID, pu);
            startActivity(i);
        });
        btnPrimary.setOnClickListener(v -> {
            // only used for "Try again" when no ambulance was found
            DispatchHelper.retry(db, requestId);
        });

        seekSlide.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (s.getProgress() >= 90) {
                    s.setEnabled(false);
                    String amb = request != null ? request.getString("ambulanceId") : null;
                    Fs.finishRequest(requestId, amb, Fs.S_COMPLETED);
                } else {
                    s.setProgress(0);
                }
            }
        });

        txtStatus.setText("Loading trip...");

        Fs.ensureSignedIn(() -> {
            myUid = Fs.me();
            startListening();
        });
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
        if (dispatcher != null) dispatcher.stop();
        if (reqListener != null) reqListener.remove();
        if (ambListener != null) ambListener.remove();
    }

    // ------------------------------------------------------------------------------------

    private boolean isRequester() {
        return request != null && myUid != null && myUid.equals(request.getString("requesterUid"));
    }

    private boolean isSearching(String st) {
        return Fs.S_SEARCHING.equals(st) || Fs.S_REQUESTED.equals(st) || Fs.S_DECLINED.equals(st);
    }

    private void handleBack() {
        String st = request != null ? request.getString("status") : null;
        if (isRequester() && isSearching(st)) {
            confirmCancel(true);
        } else {
            finish();
        }
    }

    private void confirmCancel(boolean thenFinish) {
        new AlertDialog.Builder(this)
                .setTitle("Cancel ambulance request?")
                .setPositiveButton("Yes, cancel", (d, w) -> {
                    String amb = request != null ? request.getString("ambulanceId") : null;
                    Fs.finishRequest(requestId, amb, Fs.S_CANCELLED);
                    if (thenFinish) finish();
                })
                .setNegativeButton("No", null)
                .show();
    }

    // ------------------------------------------------------------------------------------
    // listeners
    // ------------------------------------------------------------------------------------

    private void startListening() {
        reqListener = db.collection(Fs.REQUESTS).document(requestId).addSnapshotListener((s, e) -> {
            if (e != null) {
                txtStatus.setText("Connection problem — retrying...");
                return;
            }
            if (s == null || !s.exists()) return;
            request = s;
            onRequest(s);
        });
    }

    private void listenAmbulance(String ambulanceId) {
        if (ambulanceId == null || ambulanceId.equals(listeningAmbulance)) return;
        listeningAmbulance = ambulanceId;
        if (ambListener != null) ambListener.remove();
        ambListener = db.collection(Fs.AMBULANCES).document(ambulanceId).addSnapshotListener((s, e) -> {
            if (s == null || !s.exists()) return;
            if (Fs.hasLatLng(s, "lat", "lng")) {
                ambPos = new GeoPoint(s.getDouble("lat"), s.getDouble("lng"));
                ambUpdatedAt = s.getLong("updatedAt");
                ambMarker = MapUi.marker(map, ambMarker, ambPos,
                        "Ambulance " + listeningAmbulance, icAmb);
                if (request != null) render(request);
            }
        });
    }

    private void onRequest(DocumentSnapshot d) {
        String st = d.getString("status");

        // The screen of whoever created the request keeps the "find nearest ambulance" loop going.
        if (!ROLE_GUARDIAN.equals(viewerRole) && isRequester()) {
            if (dispatcher == null) dispatcher = new DispatchHelper.Driver(db, requestId);
            dispatcher.onRequest(d);
        }

        String amb = d.getString("ambulanceId");
        if (amb != null && (Fs.S_ACCEPTED.equals(st) || Fs.S_PICKED_UP.equals(st))) {
            listenAmbulance(amb);
        }
        render(d);
    }

    // ------------------------------------------------------------------------------------
    // drawing
    // ------------------------------------------------------------------------------------

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void render(DocumentSnapshot d) {
        String st = nz(d.getString("status"));
        boolean requester = isRequester() && !ROLE_GUARDIAN.equals(viewerRole);
        boolean hospitalViewer = ROLE_HOSPITAL.equals(viewerRole);

        String patientName = nz(d.getString("patientName"));
        String ambNo = nz(d.getString("ambulanceNumber"));
        String cond = nz(d.getString("condition"));

        // static markers
        GeoPoint pickup = null;
        if (Fs.hasLatLng(d, "pickupLat", "pickupLng")) {
            pickup = new GeoPoint(d.getDouble("pickupLat"), d.getDouble("pickupLng"));
            boolean hospLeg = Fs.S_PICKED_UP.equals(st);
            pickupMarker = MapUi.marker(map, pickupMarker, pickup,
                    hospLeg ? "START: patient pickup point"
                            : (patientName.isEmpty() ? "Patient" : "Patient: " + patientName),
                    hospLeg ? icStart : icPatient);
        }
        GeoPoint hospital = null;
        if (Fs.hasLatLng(d, "hospitalLat", "hospitalLng")) {
            hospital = new GeoPoint(d.getDouble("hospitalLat"), d.getDouble("hospitalLng"));
            hospitalMarker = MapUi.marker(map, hospitalMarker, hospital,
                    "END: " + nz(d.getString("hospitalName")), icEnd);
        }

        // defaults
        layoutActions.setVisibility(View.GONE);
        btnPrimary.setVisibility(View.GONE);
        btnCancel.setVisibility(View.GONE);
        layoutSlide.setVisibility(View.GONE);

        StringBuilder detail = new StringBuilder();
        if (!patientName.isEmpty()) detail.append("Patient: ").append(patientName);
        if (!cond.isEmpty()) detail.append("   ⚠ ").append(cond);
        if (!ambNo.isEmpty()) detail.append("\nAmbulance: ").append(ambNo);
        String code = nz(d.getString("patientCode"));
        if (!code.isEmpty()) detail.append("   •   Patient ID: ").append(code);
        txtDetail.setText(detail.toString().trim());

        if (isSearching(st)) {
            txtStatus.setText("🔎 Finding the nearest ambulance...");
            if (Fs.S_REQUESTED.equals(st)) {
                Double td = d.getDouble("targetDistance");
                txtInfo.setText("Request sent to " + nz(d.getString("targetAmbulanceId"))
                        + (td != null ? " (" + Geo.dist(td) + " away)" : "")
                        + ". Waiting for the driver to accept...");
            } else {
                txtInfo.setText("Checking which ambulances are online...");
            }
            if (requester) btnCancel.setVisibility(View.VISIBLE);
            MapUi.fit(map, pickup);   // keep patient in view
            return;
        }

        if (Fs.S_NO_AMBULANCE.equals(st)) {
            txtStatus.setText("❌ No ambulance available right now");
            txtInfo.setText("All ambulances are offline, busy or did not respond.");
            if (requester) {
                btnPrimary.setText("🔄 Try again");
                btnPrimary.setVisibility(View.VISIBLE);
                btnCancel.setVisibility(View.VISIBLE);
            }
            return;
        }

        if (Fs.S_CANCELLED.equals(st)) {
            txtStatus.setText("⛔ Request cancelled");
            txtInfo.setText("");
            clearRoute();
            return;
        }

        if (Fs.S_COMPLETED.equals(st)) {
            txtStatus.setText("✅ Ambulance arrived — trip completed");
            txtInfo.setText("");
            clearRoute();
            if (!finishedDialogShown) {
                finishedDialogShown = true;
                new AlertDialog.Builder(this)
                        .setTitle("Completed ✅")
                        .setMessage("The ambulance has arrived. Trip completed.")
                        .setCancelable(false)
                        .setPositiveButton("OK", (dd, w) -> finish())
                        .show();
            }
            return;
        }

        // accepted / picked_up : ambulance is moving
        boolean toHospital = Fs.S_PICKED_UP.equals(st);
        GeoPoint target = toHospital ? hospital : pickup;
        String leg = toHospital ? "hospital" : "pickup";

        layoutActions.setVisibility(View.VISIBLE);
        if (requester) btnCancel.setVisibility(View.VISIBLE);
        if (toHospital) btnCancel.setVisibility(View.GONE);     // patient already on board
        // only the ambulance driver can complete a trip (no slide-to-complete for others)
        boolean nurseViewer = ROLE_NURSE.equals(viewerRole);
        boolean staff = hospitalViewer || nurseViewer;
        btnRecords.setVisibility(staff && !nz(d.getString("patientUid")).isEmpty()
                ? View.VISIBLE : View.GONE);

        if (toHospital) {
            String hn = nz(d.getString("hospitalName"));
            txtStatus.setText("🏥 Patient picked up — heading to " + (hn.isEmpty() ? "the hospital" : hn));
        } else {
            txtStatus.setText("🚑 Ambulance " + ambNo + " is on the way");
        }

        if (ambPos == null) {
            txtInfo.setText("Waiting for the ambulance location...");
            return;
        }
        if (target == null) {
            txtInfo.setText("Ambulance is on its way");
            return;
        }

        if (!leg.equals(lastLeg)) {
            lastLeg = leg;
            lastRoute = null;
            lastRouteKey = null;
            clearRoute();
            MapUi.fit(map, ambPos, target);
        }
        if (toHospital && pickup != null && hospital != null) drawPlannedTrip(pickup, hospital);
        showInfo(target, toHospital);
        maybeRoute(target, leg, toHospital ? Color.parseColor("#2E7D32") : Color.parseColor("#D32F2F"),
                toHospital);
    }

    /** Whole trip START (patient) -> END (hospital), drawn once like a Google Maps preview. */
    private void drawPlannedTrip(GeoPoint start, GeoPoint end) {
        final String key = start.getLatitude() + "," + start.getLongitude() + ">"
                + end.getLatitude() + "," + end.getLongitude();
        if (key.equals(tripKey)) return;
        tripKey = key;
        RouteHelper.route(start, end, r -> {
            if (!key.equals(tripKey)) return;
            tripMeters = r.meters;
            tripLine = MapUi.line(map, tripLine, r.points, Color.parseColor("#90A4AE"), 22f, true);
            if (request != null) render(request);
        });
    }

    private void showInfo(GeoPoint target, boolean toHospital) {
        String what = toHospital ? "Hospital" : "Ambulance";
        String tail = "";
        if (ambUpdatedAt != null) {
            long age = (System.currentTimeMillis() - ambUpdatedAt) / 1000;
            if (age > 45) tail = "\n⚠ Ambulance location updated " + age + " s ago";
        }
        if (lastRoute != null) {
            String when = toHospital ? "arrives at hospital in " : "arrives in ";
            txtInfo.setText("📍 " + what + " is " + Geo.dist(lastRoute.meters) + " away  •  "
                    + when + Geo.eta(lastRoute.seconds)
                    + (lastRoute.approximate ? " (estimate)" : "")
                    + (toHospital && tripMeters > 0 ? "\nTrip total: " + Geo.dist(tripMeters) : "")
                    + tail);
        } else {
            double m = Geo.meters(ambPos.getLatitude(), ambPos.getLongitude(),
                    target.getLatitude(), target.getLongitude());
            txtInfo.setText("📍 " + Geo.dist(m) + " away (straight line)" + tail);
        }
    }

    private void clearRoute() {
        routeSeq++;
        MapUi.removeLine(map, tripLine);
        tripLine = null;
        tripKey = null;
        MapUi.removeLine(map, routeLine);
        routeLine = null;
    }

    private void maybeRoute(GeoPoint target, String leg, int color, boolean toHospital) {
        if (ambPos == null) return;
        String key = leg + ":" + target.getLatitude() + "," + target.getLongitude();
        long now = System.currentTimeMillis();
        if (key.equals(lastRouteKey) && lastRouteFrom != null && now - lastRouteAt < 10_000
                && Geo.meters(lastRouteFrom.getLatitude(), lastRouteFrom.getLongitude(),
                ambPos.getLatitude(), ambPos.getLongitude()) < 50) {
            return;
        }
        lastRouteKey = key;
        lastRouteAt = now;
        lastRouteFrom = ambPos;
        final int seq = ++routeSeq;
        RouteHelper.route(ambPos, target, r -> {
            if (seq != routeSeq) return;
            lastRoute = r;
            routeLine = MapUi.line(map, routeLine, r.points, color);
            showInfo(target, toHospital);
        });
    }
}
