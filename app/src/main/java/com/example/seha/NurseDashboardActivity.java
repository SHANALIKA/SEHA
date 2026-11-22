package com.example.seha;

import android.content.Intent;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NURSE HOME
 *  - scan an unconscious patient's face (details + medical records + send nearest ambulance)
 *  - track an ambulance by typing its vehicle number (the number comes in the SMS)
 *  - live list of ambulances that are bringing patients to this nurse's hospital
 */
public class NurseDashboardActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private String uid;
    private String hospitalId = null;
    private String hospitalName = "";

    private TextView txtHospital, txtInfo, txtEmpty;
    private EditText etVehicle;
    private ListView lv;
    private final List<DocumentSnapshot> incoming = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListenerRegistration listener;
    private ListenerRegistration sosWatch;
    private final Set<String> alerted = new HashSet<>();
    private boolean firstSnapshot = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_nurse_dashboard);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            float d = getResources().getDisplayMetrics().density;
            v.setPadding(s.left + (int) (20 * d), s.top + (int) (16 * d),
                    s.right + (int) (20 * d), s.bottom + (int) (16 * d));
            return insets;
        });

        db = FirebaseFirestore.getInstance();
        txtHospital = findViewById(R.id.txtNurseHospital);
        txtInfo = findViewById(R.id.txtNurseInfo);
        txtEmpty = findViewById(R.id.txtEmpty);
        etVehicle = findViewById(R.id.etVehicle);
        lv = findViewById(R.id.lvIncoming);
        Button btnScan = findViewById(R.id.btnScan);
        Button btnTrack = findViewById(R.id.btnTrack);
        Button btnLogout = findViewById(R.id.btnLogout);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> openTrip(incoming.get(pos).getId()));

        btnScan.setOnClickListener(v -> startActivity(new Intent(this, FaceScanActivity.class)));
        btnTrack.setOnClickListener(v -> trackByVehicle());
        findViewById(R.id.btnFindPatient).setOnClickListener(v -> findPatient());
        btnLogout.setOnClickListener(v -> logout());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                logout();
            }
        });

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }
        uid = u.getUid();

        db.collection(Fs.PATIENTS).document(uid).get().addOnSuccessListener(doc -> {
            hospitalId = doc.getString("hospitalId");
            hospitalName = doc.getString("hospitalName") != null ? doc.getString("hospitalName") : "";
            txtHospital.setText("👩‍⚕️ Nurse" + (hospitalName.isEmpty() ? "" : " — " + hospitalName));
            txtInfo.setText("NIC " + (doc.getString("nic") != null ? doc.getString("nic") : "")
                    + (doc.getString("phone") != null ? "   📞 " + doc.getString("phone") : ""));
            if (hospitalId != null) {
                listen();
                // SOS of a patient whose nearest hospital is this one: pop-up + alarm
                sosWatch = SosAlertWatcher.start(this, hospitalId, PatientLiveMapActivity.ROLE_NURSE);
            }
            else txtEmpty.setText("This nurse account is not linked to a hospital.");
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (listener != null) listener.remove();
        if (sosWatch != null) sosWatch.remove();
    }

    private void logout() {
        new AlertDialog.Builder(this)
                .setTitle("Logout?")
                .setPositiveButton("Logout", (d, w) -> {
                    FirebaseAuth.getInstance().signOut();
                    Intent i = new Intent(this, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void listen() {
        listener = db.collection(Fs.REQUESTS).whereEqualTo("hospitalId", hospitalId)
                .addSnapshotListener((qs, e) -> {
                    if (qs == null) return;
                    incoming.clear();
                    for (DocumentSnapshot d : qs.getDocuments()) {
                        if (!Fs.isTerminal(d.getString("status"))) incoming.add(d);
                    }
                    Collections.sort(incoming, (a, b) -> Long.compare(
                            b.getLong("createdAt") != null ? b.getLong("createdAt") : 0,
                            a.getLong("createdAt") != null ? a.getLong("createdAt") : 0));

                    rows.clear();
                    for (DocumentSnapshot d : incoming) {
                        String n = nz(d.getString("patientName"));
                        String c = nz(d.getString("condition"));
                        rows.add("🚑 " + nz(d.getString("ambulanceNumber")) + "  →  "
                                + (n.isEmpty() ? "Unknown patient" : n)
                                + (c.isEmpty() ? "" : "  ⚠ " + c)
                                + "\n" + (Fs.S_PICKED_UP.equals(d.getString("status"))
                                ? "Patient on board — tap to track" : "Heading to patient"));

                        // new ambulance coming -> sound + toast
                        if (!firstSnapshot && alerted.add(d.getId())) {
                            Toast.makeText(this, "🚑 Ambulance " + nz(d.getString("ambulanceNumber"))
                                    + " is bringing a patient", Toast.LENGTH_LONG).show();
                            try {
                                RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(
                                        RingtoneManager.TYPE_NOTIFICATION)).play();
                            } catch (Exception ignored) { }
                        } else {
                            alerted.add(d.getId());
                        }
                    }
                    firstSnapshot = false;
                    adapter.notifyDataSetChanged();
                    txtEmpty.setVisibility(incoming.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
                });
    }

    private void openTrip(String requestId) {
        Intent i = new Intent(this, TripMapActivity.class);
        i.putExtra(TripMapActivity.EXTRA_REQUEST_ID, requestId);
        i.putExtra(TripMapActivity.EXTRA_ROLE, TripMapActivity.ROLE_NURSE);
        startActivity(i);
    }

    /** Patient ID (6 digits) or patient name -> medical records screen (view + add X-ray / notes). */
    private void findPatient() {
        EditText et = findViewById(R.id.etPatient);
        String q = et.getText().toString().trim();
        if (q.isEmpty()) {
            et.setError("Enter Patient ID or name");
            return;
        }
        com.google.firebase.firestore.Query query = q.matches("\\d{4,8}")
                ? db.collection(Fs.PATIENTS).whereEqualTo("patientCode", q)
                : db.collection(Fs.PATIENTS).whereEqualTo("patientName", q);
        final boolean numeric = q.matches("\\d{4,8}");
        query.get().addOnSuccessListener(qs -> {
            final List<DocumentSnapshot> found = new ArrayList<>();
            for (DocumentSnapshot d : qs.getDocuments()) {
                String role = d.getString("role");
                if (role == null || "patient".equals(role)) found.add(d);
            }
            if (found.isEmpty() && numeric) {
                // The Patient ID in the emergency SMS is calculated from the patient's uid
                // (Fs.codeForUid). If the patient never opened the app afterwards, the code was never
                // saved in the profile, so the plain "patientCode" search finds nothing.
                // Fall back to matching the calculated code.
                db.collection(Fs.PATIENTS).get().addOnSuccessListener(all -> {
                    List<DocumentSnapshot> byCalc = new ArrayList<>();
                    for (DocumentSnapshot d : all.getDocuments()) {
                        String role = d.getString("role");
                        if ((role == null || "patient".equals(role))
                                && q.equals(Fs.codeForUid(d.getId()))) byCalc.add(d);
                    }
                    showFound(byCalc);
                }).addOnFailureListener(e ->
                        Toast.makeText(this, "Search failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            showFound(found);
        }).addOnFailureListener(e ->
                Toast.makeText(this, "Search failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void showFound(final List<DocumentSnapshot> found) {
        if (found.isEmpty()) {
            Toast.makeText(this, "No patient found. Check the Patient ID / exact name.",
                    Toast.LENGTH_LONG).show();
        } else if (found.size() == 1) {
            openRecords(found.get(0).getId());
        } else {
            String[] labels = new String[found.size()];
            for (int i = 0; i < found.size(); i++) {
                DocumentSnapshot d = found.get(i);
                labels[i] = nz(d.getString("patientName")) + "  (ID " + nz(d.getString("patientCode"))
                        + ", age " + nz(d.getString("age")) + ")";
            }
            new AlertDialog.Builder(this).setTitle("Select patient")
                    .setItems(labels, (dd, which) -> openRecords(found.get(which).getId()))
                    .show();
        }
    }

    private void openRecords(String patientUid) {
        Intent i = new Intent(this, PatientRecordsActivity.class);
        i.putExtra(PatientRecordsActivity.EXTRA_PATIENT_UID, patientUid);
        startActivity(i);
    }

    /** Type the vehicle number from the SMS -> the ambulance appears on the map. */
    private void trackByVehicle() {
        String raw = etVehicle.getText().toString().trim();
        String vid = Fs.vehicleId(raw);
        if (vid.isEmpty()) {
            etVehicle.setError("Enter the ambulance vehicle number");
            return;
        }
        db.collection(Fs.AMBULANCES).document(vid).get().addOnSuccessListener(a -> {
            if (!a.exists()) {
                Toast.makeText(this, "No ambulance registered with this vehicle number",
                        Toast.LENGTH_LONG).show();
                return;
            }
            String cur = a.getString("currentRequestId");
            if (cur == null || cur.isEmpty()) {
                Toast.makeText(this, "Ambulance " + raw.toUpperCase() + " is not on a trip right now",
                        Toast.LENGTH_LONG).show();
                return;
            }
            openTrip(cur);
        }).addOnFailureListener(e ->
                Toast.makeText(this, "Could not find ambulance: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }
}
