package com.example.seha;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * PATIENT: shows the Patient ID and the big "Request nearest ambulance" button.
 * After the request is created the live Trip screen opens.
 */
public class PatientAmbulanceActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private FusedLocationProviderClient fused;
    private String uid;
    private DocumentSnapshot patientDoc;
    private String code = null;

    private TextView txtCode, txtResume, txtHint;
    private View layoutResume;
    private Button btnRequest, btnResume, btnGuardian;
    private String activeRequestId = null;

    private final ActivityResultLauncher<String[]> locPerm =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (hasLocation()) {
                    requestAmbulance();
                } else {
                    Toast.makeText(this, "Location permission is needed to find the nearest ambulance",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_patient_ambulance);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(s.left + dp(20), s.top + dp(12), s.right + dp(20), s.bottom + dp(12));
            return insets;
        });

        db = FirebaseFirestore.getInstance();
        fused = LocationServices.getFusedLocationProviderClient(this);

        txtCode = findViewById(R.id.txtCode);
        txtResume = findViewById(R.id.txtResume);
        txtHint = findViewById(R.id.txtHint);
        layoutResume = findViewById(R.id.layoutResume);
        btnRequest = findViewById(R.id.btnRequest);
        btnResume = findViewById(R.id.btnResume);
        btnGuardian = findViewById(R.id.btnGuardian);
        ((ImageView) findViewById(R.id.btnBack)).setOnClickListener(v -> finish());

        btnRequest.setOnClickListener(v -> confirmRequest());
        btnResume.setOnClickListener(v -> {
            if (activeRequestId != null) openTrip(activeRequestId);
        });
        btnGuardian.setOnClickListener(v ->
                startActivity(new Intent(this, GuardianTrackActivity.class)));

        btnRequest.setEnabled(false);
        Fs.ensureSignedIn(() -> {
            uid = Fs.me();
            if (uid == null) {
                txtHint.setText("Please log in again.");
                return;
            }
            loadPatient();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (uid != null) checkActiveRequest();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void loadPatient() {
        db.collection(Fs.PATIENTS).document(uid).get()
                .addOnSuccessListener(doc -> {
                    patientDoc = doc;
                    btnRequest.setEnabled(true);
                })
                .addOnFailureListener(e -> {
                    txtHint.setText("Could not load your profile: " + e.getMessage());
                    btnRequest.setEnabled(true);
                });

        Fs.ensurePatientCode(uid, c -> {
            code = c;
            txtCode.setText(c != null ? c : "—");
        });
        checkActiveRequest();
    }

    /** If this patient already has a running trip, offer to open it instead of making a 2nd one. */
    private void checkActiveRequest() {
        db.collection(Fs.REQUESTS).whereEqualTo("requesterUid", uid).get()
                .addOnSuccessListener(qs -> {
                    DocumentSnapshot best = null;
                    long bestT = -1;
                    for (DocumentSnapshot d : qs.getDocuments()) {
                        String st = d.getString("status");
                        if (st == null || Fs.isTerminal(st) || Fs.S_NO_AMBULANCE.equals(st)) continue;
                        Long t = d.getLong("createdAt");
                        long tt = t != null ? t : 0;
                        if (tt > bestT) {
                            bestT = tt;
                            best = d;
                        }
                    }
                    if (best != null) {
                        activeRequestId = best.getId();
                        layoutResume.setVisibility(View.VISIBLE);
                        txtResume.setText("You have an active ambulance request (" + best.getString("status") + ").");
                        btnRequest.setVisibility(View.GONE);
                        txtHint.setText("");
                    } else {
                        activeRequestId = null;
                        layoutResume.setVisibility(View.GONE);
                        btnRequest.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void confirmRequest() {
        new AlertDialog.Builder(this)
                .setTitle("Request ambulance?")
                .setMessage("The nearest available ambulance will be sent to your current location.")
                .setPositiveButton("Yes, send", (d, w) -> requestAmbulance())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean hasLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAmbulance() {
        if (!hasLocation()) {
            locPerm.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        btnRequest.setEnabled(false);
        txtHint.setText("Getting your location...");

        try {
            CancellationTokenSource cts = new CancellationTokenSource();
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            createAndOpen(loc);
                        } else {
                            fallbackLastLocation();
                        }
                    })
                    .addOnFailureListener(e -> fallbackLastLocation());
        } catch (SecurityException e) {
            btnRequest.setEnabled(true);
            txtHint.setText("Location permission missing");
        }
    }

    private void fallbackLastLocation() {
        try {
            fused.getLastLocation().addOnSuccessListener(loc -> {
                if (loc != null) {
                    createAndOpen(loc);
                } else {
                    btnRequest.setEnabled(true);
                    txtHint.setText("Could not get your location. Turn on GPS and try again.");
                }
            }).addOnFailureListener(e -> {
                btnRequest.setEnabled(true);
                txtHint.setText("Could not get your location. Turn on GPS and try again.");
            });
        } catch (SecurityException e) {
            btnRequest.setEnabled(true);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void createAndOpen(Location loc) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "patient_request");
        m.put("requesterUid", uid);
        m.put("requesterRole", "patient");
        m.put("patientUid", uid);
        m.put("pickupLat", loc.getLatitude());
        m.put("pickupLng", loc.getLongitude());
        if (code != null) m.put("patientCode", code);

        if (patientDoc != null && patientDoc.exists()) {
            String name = nz(patientDoc.getString("patientName"));
            if (name.isEmpty()) name = nz(patientDoc.getString("fullName"));
            m.put("patientName", name);
            m.put("patientPhone", nz(patientDoc.getString("phone")));
            m.put("guardianPhone", nz(patientDoc.getString("relativePhone")));
            m.put("condition", nz(patientDoc.getString("chronicConditions")));
            m.put("bloodGroup", nz(patientDoc.getString("bloodGroup")));
            m.put("allergies", nz(patientDoc.getString("allergies")));
        }
        String id = DispatchHelper.createRequest(db, m);
        txtHint.setText("");
        btnRequest.setEnabled(true);
        openTrip(id);
    }

    private void openTrip(String id) {
        Intent i = new Intent(this, TripMapActivity.class);
        i.putExtra(TripMapActivity.EXTRA_REQUEST_ID, id);
        i.putExtra(TripMapActivity.EXTRA_ROLE, TripMapActivity.ROLE_PATIENT);
        startActivity(i);
    }
}
