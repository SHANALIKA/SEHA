package com.example.seha;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AmbulanceTrackActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_CODE = 3001;
    private static final String ROLE_PATIENT = "patient";
    private static final String ROLE_NURSE = "nurse";
    private static final String ROLE_AMBULANCE = "ambulance";

    MapView map;
    Marker patientMarker, nurseMarker, ambulanceMarker;
    Polyline routeLine;

    FusedLocationProviderClient fusedLocationClient;
    LocationCallback locationCallback;

    FirebaseFirestore db;
    ListenerRegistration trackListener;

    String trackId = "ambulance_track_001";

    TextView txtStatus, txtPatient, txtEta;
    TextView txtLegendPatient, txtLegendNurse, txtLegendAmbulance;
    Button btnAmbulanceShare;
    ImageView btnBack;

    String role = null;
    boolean cameraFitted = false;
    boolean fromLogin = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setUserAgentValue("SEHA-Medical-App/1.0 (com.example.seha; Android student project)");

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_ambulance_track);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            View statusCard = findViewById(R.id.statusCard);
            View btnBackView = findViewById(R.id.btnBack);
            View legend = findViewById(R.id.legendCard);
            float dens = getResources().getDisplayMetrics().density;
            if (statusCard != null) {
                android.view.ViewGroup.MarginLayoutParams lp =
                        (android.view.ViewGroup.MarginLayoutParams) statusCard.getLayoutParams();
                lp.bottomMargin = bars.bottom + (int) (12 * dens);
                lp.leftMargin = bars.left + (int) (12 * dens);
                lp.rightMargin = bars.right + (int) (12 * dens);
                statusCard.setLayoutParams(lp);
            }
            if (btnBackView != null) {
                android.view.ViewGroup.MarginLayoutParams lp =
                        (android.view.ViewGroup.MarginLayoutParams) btnBackView.getLayoutParams();
                lp.topMargin = bars.top + (int) (12 * dens);
                lp.leftMargin = bars.left + (int) (12 * dens);
                btnBackView.setLayoutParams(lp);
            }
            if (legend != null) {
                android.view.ViewGroup.MarginLayoutParams lp =
                        (android.view.ViewGroup.MarginLayoutParams) legend.getLayoutParams();
                lp.topMargin = bars.top + (int) (12 * dens);
                lp.rightMargin = bars.right + (int) (12 * dens);
                legend.setLayoutParams(lp);
            }
            return insets;
        });

        map = findViewById(R.id.map);
        txtStatus = findViewById(R.id.txtStatus);
        txtPatient = findViewById(R.id.txtPatient);
        txtEta = findViewById(R.id.txtEta);
        txtLegendPatient = findViewById(R.id.txtLegendPatient);
        txtLegendNurse = findViewById(R.id.txtLegendNurse);
        txtLegendAmbulance = findViewById(R.id.txtLegendAmbulance);
        btnAmbulanceShare = findViewById(R.id.btnAmbulanceShare);
        btnBack = findViewById(R.id.btnBack);

        fromLogin = getIntent().getBooleanExtra("fromLogin", false);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        if (btnAmbulanceShare != null) btnAmbulanceShare.setVisibility(View.GONE);

        db = FirebaseFirestore.getInstance();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        if (getIntent().hasExtra("trackId")) {
            trackId = getIntent().getStringExtra("trackId");
        }
        if (getIntent().hasExtra("patientName")) {
            String name = getIntent().getStringExtra("patientName");
            if (txtPatient != null) txtPatient.setText("Patient: " + name);
        }

        setupMap();

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> handleBack());
        }

        String roleExtra = getIntent().getStringExtra("role");
        if (ROLE_PATIENT.equals(roleExtra) || ROLE_NURSE.equals(roleExtra)
                || ROLE_AMBULANCE.equals(roleExtra)) {
            role = roleExtra;
            applyRole();
        } else {
            chooseRole();
        }
    }

    private void handleBack() {
        if (fromLogin) {
            new AlertDialog.Builder(this)
                    .setTitle("Logout?")
                    .setMessage("Do you want to log out?")
                    .setPositiveButton("Logout", (d, w) -> {
                        FirebaseAuth.getInstance().signOut();
                        Intent i = new Intent(this, LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            finish();
        }
    }

    private void chooseRole() {
        String[] options = {
                "🧑  Patient  -  Track Ambulance",
                "👩‍⚕️  Nurse  -  Track Ambulance",
                "🚑  Ambulance"
        };

        new AlertDialog.Builder(this)
                .setTitle("Select your interface")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) role = ROLE_PATIENT;
                    else if (which == 1) role = ROLE_NURSE;
                    else role = ROLE_AMBULANCE;
                    applyRole();
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void applyRole() {
        if (txtStatus != null) {
            if (ROLE_PATIENT.equals(role)) txtStatus.setText("PATIENT - TRACKING AMBULANCE");
            else if (ROLE_NURSE.equals(role)) txtStatus.setText("NURSE - TRACKING AMBULANCE");
            else txtStatus.setText("AMBULANCE - SHARING LIVE LOCATION");
        }
        if (txtEta != null) {
            txtEta.setText(ROLE_AMBULANCE.equals(role)
                    ? "📍 Getting your location..."
                    : "📍 Waiting for ambulance...");
        }
        resolveTrackIdThenStart();
    }

    /** Patient → track_{uid}. Ambulance → latest active patient track. */
    private void resolveTrackIdThenStart() {
        if (getIntent().hasExtra("trackId")) {
            trackId = getIntent().getStringExtra("trackId");
            createTrackDocumentIfNeeded();
            listenUpdates();
            checkLocationPermission();
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (ROLE_PATIENT.equals(role) && user != null) {
            trackId = "track_" + user.getUid();
            Map<String, Object> meta = new HashMap<>();
            meta.put("patientUid", user.getUid());
            meta.put("roleOwner", "patient");
            meta.put("updatedAt", System.currentTimeMillis());
            db.collection("AmbulanceTracks").document(trackId).set(meta, SetOptions.merge());
            createTrackDocumentIfNeeded();
            listenUpdates();
            checkLocationPermission();
            return;
        }

        if (txtEta != null) txtEta.setText("📍 Looking for patient...");

        db.collection("AmbulanceTracks")
                .get()
                .addOnSuccessListener(snap -> {
                    String bestId = null;
                    long bestTime = -1;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Double plat = d.getDouble("patientLat");
                        Double plng = d.getDouble("patientLng");
                        boolean hasPatient = plat != null && plng != null
                                && (plat != 0.0 || plng != 0.0);
                        if (!hasPatient) continue;
                        Long t = d.getLong("updatedAt");
                        long time = t != null ? t : 0L;
                        if (time >= bestTime) {
                            bestTime = time;
                            bestId = d.getId();
                        }
                    }
                    if (bestId != null) {
                        trackId = bestId;
                    } else if (user != null) {
                        trackId = "track_waiting_" + user.getUid();
                    } else {
                        trackId = "ambulance_track_001";
                    }
                    if (txtEta != null) {
                        txtEta.setText("📍 Track: " + trackId);
                    }
                    createTrackDocumentIfNeeded();
                    listenUpdates();
                    checkLocationPermission();
                })
                .addOnFailureListener(e -> {
                    trackId = "ambulance_track_001";
                    createTrackDocumentIfNeeded();
                    listenUpdates();
                    checkLocationPermission();
                });
    }

    private void setupMap() {
        map.setTileSource(MapTiles.STREET);
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(true);
        map.setTilesScaledToDpi(true);
        map.setHorizontalMapRepetitionEnabled(false);
        map.setVerticalMapRepetitionEnabled(false);
        map.setMinZoomLevel(5.0);
        map.setMaxZoomLevel(20.0);
        map.setClickable(true);
        map.setFocusable(true);

        IMapController controller = map.getController();
        controller.setZoom(15.0);
        controller.setCenter(new GeoPoint(6.9271, 79.8612));
    }

    private void createTrackDocumentIfNeeded() {
        DocumentReference ref = db.collection("AmbulanceTracks").document(trackId);
        ref.get().addOnSuccessListener(doc -> {
            if (doc == null || !doc.exists()) {
                Map<String, Object> data = new HashMap<>();
                data.put("status", "en_route");
                data.put("updatedAt", System.currentTimeMillis());
                ref.set(data, SetOptions.merge());
            }
        });
    }

    private void checkLocationPermission() {
        if (hasLocationPermission()) {
            startLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_CODE);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationUpdates() {
        if (role == null || !hasLocationPermission()) return;

        stopLocationUpdates();

        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                if (result.getLastLocation() == null) return;

                double lat = result.getLastLocation().getLatitude();
                double lng = result.getLastLocation().getLongitude();

                saveLocation(role, lat, lng);
                updateMarker(role, new GeoPoint(lat, lng));
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Toast.makeText(this, "Location permission missing", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    private Marker getMarker(String who) {
        if (ROLE_PATIENT.equals(who)) return patientMarker;
        if (ROLE_NURSE.equals(who)) return nurseMarker;
        return ambulanceMarker;
    }

    private void setMarker(String who, Marker m) {
        if (ROLE_PATIENT.equals(who)) patientMarker = m;
        else if (ROLE_NURSE.equals(who)) nurseMarker = m;
        else ambulanceMarker = m;
    }

    private String titleFor(String who) {
        boolean me = who.equals(role);
        if (ROLE_PATIENT.equals(who)) return me ? "You (Patient)" : "Patient";
        if (ROLE_NURSE.equals(who)) return me ? "You (Nurse)" : "Nurse";
        return me ? "You (Ambulance)" : "Ambulance";
    }

    private void updateMarker(String who, GeoPoint point) {
        Marker m = getMarker(who);
        if (m == null) {
            m = new Marker(map);
            m.setTitle(titleFor(who));
            m.setSnippet(titleFor(who));
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            m.setIcon(markerIcon(who));
            m.setPanToView(false);
            map.getOverlays().add(m);
            setMarker(who, m);
        }
        m.setPosition(point);
        m.setTitle(titleFor(who));
        m.setSnippet(titleFor(who));
        try {
            m.showInfoWindow();
        } catch (Exception ignored) { }
        map.invalidate();
        drawRoute();
        fitBothOnce();
        updateInfo();
    }

    private Drawable markerIcon(String who) {
        int resId;
        if (ROLE_PATIENT.equals(who)) {
            resId = R.drawable.marker_patient;
        } else if (ROLE_NURSE.equals(who)) {
            resId = R.drawable.marker_nurse;
        } else {
            int imgId = getResources().getIdentifier("ambulance", "drawable", getPackageName());
            resId = (imgId != 0) ? imgId : R.drawable.marker_ambulance;
        }

        Drawable d = ContextCompat.getDrawable(this, resId);
        if (d == null) return null;
        int size = (int) (56 * getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        d.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        d.draw(canvas);
        return new BitmapDrawable(getResources(), bitmap);
    }

    private Marker destinationMarker() {
        if (ROLE_PATIENT.equals(role)) return patientMarker;
        if (ROLE_NURSE.equals(role)) return nurseMarker;
        return patientMarker != null ? patientMarker : nurseMarker;
    }

    private void drawRoute() {
        Marker dest = destinationMarker();
        if (ambulanceMarker == null || dest == null) return;

        if (routeLine != null) {
            map.getOverlays().remove(routeLine);
        }

        routeLine = new Polyline();
        List<GeoPoint> points = new ArrayList<>();
        points.add(ambulanceMarker.getPosition());
        points.add(dest.getPosition());
        routeLine.setPoints(points);
        routeLine.setWidth(8f);
        map.getOverlays().add(routeLine);
        map.invalidate();
    }

    private void fitBothOnce() {
        Marker dest = destinationMarker();
        if (cameraFitted || ambulanceMarker == null || dest == null) return;
        cameraFitted = true;

        GeoPoint a = ambulanceMarker.getPosition();
        GeoPoint b = dest.getPosition();

        double north = Math.max(a.getLatitude(), b.getLatitude());
        double south = Math.min(a.getLatitude(), b.getLatitude());
        double east = Math.max(a.getLongitude(), b.getLongitude());
        double west = Math.min(a.getLongitude(), b.getLongitude());

        if (Math.abs(north - south) < 0.0005 && Math.abs(east - west) < 0.0005) {
            map.getController().setCenter(a);
            return;
        }

        BoundingBox box = new BoundingBox(north, east, south, west);
        map.post(() -> map.zoomToBoundingBox(box, true, 120));
    }

    private void updateInfo() {
        if (txtLegendPatient != null) {
            txtLegendPatient.setText(patientMarker != null
                    ? "👤 Patient: Online"
                    : "👤 Patient: —");
        }
        if (txtLegendNurse != null) {
            txtLegendNurse.setText(nurseMarker != null
                    ? "🩺 Nurse: Online"
                    : "🩺 Nurse: —");
        }
        if (txtLegendAmbulance != null) {
            txtLegendAmbulance.setText(ambulanceMarker != null
                    ? "🚑 Ambulance: Online"
                    : "🚑 Ambulance: —");
        }

        if (txtEta == null) return;

        Marker dest = destinationMarker();
        if (ambulanceMarker == null || dest == null) {
            txtEta.setText("📍 Waiting for both locations...");
            return;
        }

        float[] res = new float[1];
        GeoPoint a = ambulanceMarker.getPosition();
        GeoPoint b = dest.getPosition();
        Location.distanceBetween(a.getLatitude(), a.getLongitude(),
                b.getLatitude(), b.getLongitude(), res);

        double km = res[0] / 1000.0;
        String dist = km < 1 ? ((int) res[0]) + " m" : String.format("%.1f km", km);

        if (ROLE_AMBULANCE.equals(role)) {
            txtEta.setText("🚑 Distance to patient: " + dist
                    + "\n(Blue=Patient, Purple=Nurse, Red=Ambulance)");
        } else {
            txtEta.setText("🚑 Ambulance is " + dist + " away"
                    + "\n(Blue=Patient, Purple=Nurse, Red=Ambulance)");
        }
    }

    private void saveLocation(String who, double lat, double lng) {
        Map<String, Object> data = new HashMap<>();
        data.put(who + "Lat", lat);
        data.put(who + "Lng", lng);
        data.put("status", "en_route");
        data.put("updatedAt", System.currentTimeMillis());
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) {
            data.put(who + "Uid", u.getUid());
            if (ROLE_PATIENT.equals(who)) {
                data.put("patientUid", u.getUid());
            }
        }
        db.collection("AmbulanceTracks").document(trackId).set(data, SetOptions.merge());
    }

    private void showFromDoc(DocumentSnapshot snapshot, String who) {
        Double lat = snapshot.getDouble(who + "Lat");
        Double lng = snapshot.getDouble(who + "Lng");
        if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
            updateMarker(who, new GeoPoint(lat, lng));
        }
    }

    private void listenUpdates() {
        if (trackListener != null) {
            trackListener.remove();
            trackListener = null;
        }
        DocumentReference ref = db.collection("AmbulanceTracks").document(trackId);

        trackListener = ref.addSnapshotListener((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists() || role == null) return;

            if (ROLE_AMBULANCE.equals(role)) {
                showFromDoc(snapshot, ROLE_PATIENT);
                showFromDoc(snapshot, ROLE_NURSE);
            } else {
                showFromDoc(snapshot, ROLE_AMBULANCE);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_CODE) {
            boolean granted = false;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) granted = true;
            }
            if (granted) {
                startLocationUpdates();
            } else {
                Toast.makeText(this, "Location permission needed", Toast.LENGTH_LONG).show();
            }
        }
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
        stopLocationUpdates();
        if (trackListener != null) trackListener.remove();
    }
}