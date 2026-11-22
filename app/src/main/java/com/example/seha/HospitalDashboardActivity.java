package com.example.seha;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HOSPITAL ACCOUNT
 *  - hospital location (used as a destination for ambulances) and its nurse
 *  - available ambulances with details (online / ready / on a trip, driver phone)
 *  - active trips heading to this hospital (tap = live tracking)
 *  - trip history
 * (Emergency face-scan is done by the nurse, not here.)
 */
public class HospitalDashboardActivity extends AppCompatActivity {

    private static final int TAB_AMB = 0, TAB_ACTIVE = 1, TAB_HISTORY = 2, TAB_SOS = 3;

    private FirebaseFirestore db;
    private FusedLocationProviderClient fused;
    private String uid;
    private String hospitalName = "Hospital";

    private TextView txtHospital, txtLoc, txtSummary, txtNurse, txtEmpty;
    private Button btnSetLoc, btnTabAmb, btnTabActive, btnTabHistory, btnTabSos;
    private ListView lv;

    private int tab = TAB_AMB;
    private final List<DocumentSnapshot> ambDocs = new ArrayList<>();
    private final List<DocumentSnapshot> activeDocs = new ArrayList<>();
    private final List<DocumentSnapshot> historyDocs = new ArrayList<>();
    private final List<DocumentSnapshot> sosDocs = new ArrayList<>();

    private final List<String> rowText = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private ListenerRegistration hospListener, ambListener, tripListener, sosListener, sosWatch;

    private final ActivityResultLauncher<String[]> locPerm =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (hasLoc()) saveCurrentLocation();
                else Toast.makeText(this, "Location permission needed", Toast.LENGTH_LONG).show();
            });

    private boolean hasLoc() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_hospital_dashboard);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            float d = getResources().getDisplayMetrics().density;
            v.setPadding(s.left + (int) (20 * d), s.top + (int) (16 * d),
                    s.right + (int) (20 * d), s.bottom + (int) (16 * d));
            return insets;
        });

        db = FirebaseFirestore.getInstance();
        fused = LocationServices.getFusedLocationProviderClient(this);

        txtHospital = findViewById(R.id.txtHospital);
        txtLoc = findViewById(R.id.txtLoc);
        txtNurse = findViewById(R.id.txtNurse);
        txtSummary = findViewById(R.id.txtSummary);
        txtEmpty = findViewById(R.id.txtEmptyList);
        btnSetLoc = findViewById(R.id.btnSetLoc);
        btnTabAmb = findViewById(R.id.btnTabAmb);
        btnTabActive = findViewById(R.id.btnTabActive);
        btnTabHistory = findViewById(R.id.btnTabHistory);
        btnTabSos = findViewById(R.id.btnTabSos);
        lv = findViewById(R.id.lvDispatch);
        Button btnFleet = findViewById(R.id.btnFleet);
        Button btnLogout = findViewById(R.id.btnLogout);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rowText);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> onRowClick(pos));

        btnTabAmb.setOnClickListener(v -> selectTab(TAB_AMB));
        btnTabActive.setOnClickListener(v -> selectTab(TAB_ACTIVE));
        btnTabHistory.setOnClickListener(v -> selectTab(TAB_HISTORY));
        btnTabSos.setOnClickListener(v -> selectTab(TAB_SOS));
        btnFleet.setOnClickListener(v -> startActivity(new Intent(this, HospitalAmbulancesActivity.class)));
        btnSetLoc.setText("📍 Wrong location? Choose my hospital from the map list");
        btnSetLoc.setVisibility(android.view.View.GONE);   // shown only for hospitals without a verified position
        btnSetLoc.setOnClickListener(v -> pickLocation());
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
        listen();
        selectTab(TAB_AMB);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hospListener != null) hospListener.remove();
        if (ambListener != null) ambListener.remove();
        if (tripListener != null) tripListener.remove();
        if (sosListener != null) sosListener.remove();
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

    // ------------------------------------------------------------------------------------
    // data
    // ------------------------------------------------------------------------------------

    private void listen() {
        // hospital document: name, location, nurse
        hospListener = db.collection(Fs.HOSPITALS).document(uid).addSnapshotListener((h, e) -> {
            if (h == null) return;
            if (!h.exists()) {
                db.collection(Fs.PATIENTS).document(uid).get().addOnSuccessListener(p -> {
                    String n = nz(p.getString("fullName"));
                    if (!n.isEmpty()) hospitalName = n;
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", hospitalName);
                    m.put("uid", uid);
                    db.collection(Fs.HOSPITALS).document(uid).set(m, SetOptions.merge());
                });
                return;
            }
            String n = nz(h.getString("name"));
            if (!n.isEmpty()) hospitalName = n;
            txtHospital.setText("🏥 " + hospitalName);

            HospitalCatalog.Entry real = HospitalCatalog.find(this, hospitalName);
            curLat = h.getDouble("lat");
            curLng = h.getDouble("lng");
            String src = nz(h.getString("locSource"));
            String osmName = nz(h.getString("osmName"));

            if (real != null && real.exact) {
                // verified hospital position: always wins (replaces any wrong value saved earlier)
                if (curLat == null || curLng == null || Geo.meters(curLat, curLng, real.lat, real.lng) > 20) {
                    Map<String, Object> fix = new HashMap<>();
                    fix.put("lat", real.lat);
                    fix.put("lng", real.lng);
                    fix.put("locSource", "catalog");
                    db.collection(Fs.HOSPITALS).document(uid).set(fix, SetOptions.merge());
                }
                txtLoc.setText(String.format(Locale.US, "📍 Map location: %.5f, %.5f (verified hospital position)",
                        real.lat, real.lng));
                btnSetLoc.setVisibility(android.view.View.GONE);
            } else {
                if (real != null && !src.equals("osm") && !src.equals("picked") && !askedLocation) {
                    askedLocation = true;
                    HospitalFinder.resolve(real, best -> {
                        Map<String, Object> fix = new HashMap<>();
                        if (best != null) {
                            fix.put("lat", best.lat);
                            fix.put("lng", best.lng);
                            fix.put("locSource", "osm");
                            fix.put("osmName", best.name);
                        } else if (curLat == null || curLng == null) {
                            fix.put("lat", real.lat);
                            fix.put("lng", real.lng);
                            fix.put("locSource", "hint");
                        }
                        if (!fix.isEmpty()) db.collection(Fs.HOSPITALS).document(uid).set(fix, SetOptions.merge());
                    });
                }
                if (Fs.hasLatLng(h, "lat", "lng")) {
                    txtLoc.setText(String.format(Locale.US, "📍 Map location: %.5f, %.5f", curLat, curLng)
                            + (osmName.isEmpty() ? "" : "\n(found in OpenStreetMap as: " + osmName + ")")
                            + (src.equals("picked") ? "\n(chosen by the hospital)" : "")
                            + (src.equals("hint") ? "\n⚠ approximate — please choose your hospital below" : ""));
                } else {
                    txtLoc.setText("📍 Finding the real position of this hospital...");
                }
                btnSetLoc.setVisibility(android.view.View.VISIBLE);
            }

            String nic = nz(h.getString("nurseNic"));
            String ph = nz(h.getString("nursePhone"));
            if (nic.isEmpty()) {
                txtNurse.setText("👩‍⚕️ Nurse: none registered yet (the nurse signs up with this hospital)");
            } else {
                txtNurse.setText("👩‍⚕️ Nurse: " + nic + "   📞 " + ph);
            }
            if (nz(h.getString("phone")).isEmpty() && !askedPhone) {
                askedPhone = true;
                askPhone();
            }
        });

        // all ambulances
        ambListener = db.collection(Fs.AMBULANCES).whereEqualTo("hospitalId", uid).addSnapshotListener((qs, e) -> {
            if (qs == null) return;
            ambDocs.clear();
            ambDocs.addAll(qs.getDocuments());
            final long now = System.currentTimeMillis();
            Collections.sort(ambDocs, (a, b) -> Integer.compare(rank(b, now), rank(a, now)));

            int online = 0, ready = 0, trip = 0;
            for (DocumentSnapshot a : ambDocs) {
                boolean on = isOnline(a, now);
                if (on) online++;
                if (Boolean.TRUE.equals(a.getBoolean("busy"))) trip++;
                Boolean av = a.getBoolean("available");
                if (on && (av == null || av) && !Boolean.TRUE.equals(a.getBoolean("busy"))) ready++;
            }
            txtSummary.setText("My ambulances: " + ambDocs.size() + "  •  online " + online
                    + "  •  ready " + ready + "  •  on trip " + trip);
            if (tab == TAB_AMB) refreshList();
        });

        // trips heading to this hospital
        tripListener = db.collection(Fs.REQUESTS).whereEqualTo("hospitalId", uid)
                .addSnapshotListener((qs, e) -> {
                    if (qs == null) return;
                    activeDocs.clear();
                    historyDocs.clear();
                    for (DocumentSnapshot d : qs.getDocuments()) {
                        if (Fs.isTerminal(d.getString("status"))) historyDocs.add(d);
                        else activeDocs.add(d);
                    }
                    Collections.sort(activeDocs, (a, b) -> Long.compare(ts(b, "createdAt"), ts(a, "createdAt")));
                    Collections.sort(historyDocs, (a, b) -> Long.compare(ts(b, "createdAt"), ts(a, "createdAt")));
                    btnTabActive.setText("Active trips (" + activeDocs.size() + ")");
                    btnTabHistory.setText("History (" + historyDocs.size() + ")");
                    if (tab != TAB_AMB) refreshList();
                });

        // SOS alerts of patients for whom this is the nearest hospital: pop-up + alarm, and a list
        sosWatch = SosAlertWatcher.start(this, uid, PatientLiveMapActivity.ROLE_HOSPITAL);
        sosListener = db.collection(Sos.ALERTS).whereEqualTo("hospitalId", uid)
                .addSnapshotListener((qs, e) -> {
                    if (qs == null) return;
                    sosDocs.clear();
                    sosDocs.addAll(qs.getDocuments());
                    Collections.sort(sosDocs, (a, b) -> Long.compare(ts(b, "createdAt"), ts(a, "createdAt")));
                    int active = 0;
                    for (DocumentSnapshot d : sosDocs) if (Sos.isActive(d)) active++;
                    btnTabSos.setText("🆘 SOS (" + active + ")");
                    if (tab == TAB_SOS) refreshList();
                });
    }

    private boolean askedLocation = false;
    private Double curLat = null, curLng = null;

    /** The hospital chooses itself from the real hospitals around it (OpenStreetMap). */
    private void pickLocation() {
        HospitalCatalog.Entry real = HospitalCatalog.find(this, hospitalName);
        double lat = curLat != null ? curLat : (real != null ? real.lat : 7.8);
        double lng = curLng != null ? curLng : (real != null ? real.lng : 80.7);
        btnSetLoc.setEnabled(false);
        HospitalFinder.find(this, lat, lng, new ArrayList<>(), (list, online) -> {
            btnSetLoc.setEnabled(true);
            if (list.isEmpty()) {
                Toast.makeText(this, online ? "No hospital found nearby" : "No internet", Toast.LENGTH_LONG).show();
                return;
            }
            String[] labels = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                labels[i] = list.get(i).name + "\n" + Geo.dist(list.get(i).distance) + " from the saved position";
            }
            new AlertDialog.Builder(this)
                    .setTitle("Which one is YOUR hospital?")
                    .setItems(labels, (d, which) -> {
                        HospitalFinder.Hospital h = list.get(which);
                        Map<String, Object> fix = new HashMap<>();
                        fix.put("lat", h.lat);
                        fix.put("lng", h.lng);
                        fix.put("locSource", "picked");
                        fix.put("osmName", h.name);
                        db.collection(Fs.HOSPITALS).document(uid).set(fix, SetOptions.merge());
                        Toast.makeText(this, "Location saved: " + h.name, Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }
    private boolean askedPhone = false;

    private static long ts(DocumentSnapshot d, String f) {
        Long v = d.getLong(f);
        return v != null ? v : 0;
    }

    private boolean isOnline(DocumentSnapshot a, long now) {
        Long upd = a.getLong("updatedAt");
        return Boolean.TRUE.equals(a.getBoolean("online")) && upd != null && now - upd < Fs.STALE_MS;
    }

    private int rank(DocumentSnapshot a, long now) {
        if (Boolean.TRUE.equals(a.getBoolean("busy"))) return 2;
        if (isOnline(a, now)) return 3;
        return 1;
    }

    // ------------------------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------------------------

    private void selectTab(int t) {
        tab = t;
        btnTabAmb.setBackgroundResource(t == TAB_AMB ? R.drawable.loginbutton : R.drawable.signupbutton);
        btnTabAmb.setTextColor(t == TAB_AMB ? 0xFFFFFFFF : 0xFF1976D2);
        btnTabActive.setBackgroundResource(t == TAB_ACTIVE ? R.drawable.loginbutton : R.drawable.signupbutton);
        btnTabActive.setTextColor(t == TAB_ACTIVE ? 0xFFFFFFFF : 0xFF1976D2);
        btnTabHistory.setBackgroundResource(t == TAB_HISTORY ? R.drawable.loginbutton : R.drawable.signupbutton);
        btnTabHistory.setTextColor(t == TAB_HISTORY ? 0xFFFFFFFF : 0xFF1976D2);
        btnTabSos.setBackgroundResource(t == TAB_SOS ? R.drawable.loginbutton : R.drawable.signupbutton);
        btnTabSos.setTextColor(t == TAB_SOS ? 0xFFFFFFFF : 0xFFD32F2F);
        refreshList();
    }

    private void refreshList() {
        rowText.clear();
        SimpleDateFormat f = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());
        long now = System.currentTimeMillis();

        if (tab == TAB_AMB) {
            for (DocumentSnapshot a : ambDocs) {
                String vn = nz(a.getString("vehicleNumber"));
                if (vn.isEmpty()) vn = a.getId();
                boolean busy = Boolean.TRUE.equals(a.getBoolean("busy"));
                boolean on = isOnline(a, now);
                Boolean av = a.getBoolean("available");
                boolean avail = av == null || av;
                String st = busy ? "🟠 On a trip"
                        : on && avail ? "🟢 Online — available"
                        : on ? "🟡 Online — paused" : "🔴 Offline";
                boolean mine = uid.equals(a.getString("hospitalId"));
                String phone = nz(a.getString("phone"));
                rowText.add("🚑 " + vn + "\n" + st
                        + (phone.isEmpty() ? "" : "   📞 " + phone));
            }
            txtEmpty.setText(ambDocs.isEmpty()
                    ? "No ambulance is registered under this hospital yet.\n(Ambulances choose their hospital when they sign up.)"
                    : "Ambulances of this hospital only. Tap one to call the driver.");
        } else if (tab == TAB_ACTIVE) {
            for (DocumentSnapshot d : activeDocs) {
                rowText.add("🏥 " + patientLabel(d) + "\n" + statusLabel(nz(d.getString("status")))
                        + "  •  🚑 " + nz(d.getString("ambulanceNumber")));
            }
            txtEmpty.setText(activeDocs.isEmpty() ? "No ambulance is heading to this hospital right now."
                    : "Tap a trip to track the ambulance live.");
        } else if (tab == TAB_SOS) {
            for (DocumentSnapshot d : sosDocs) {
                String st = nz(d.getString("status"));
                String label = Sos.S_NEW.equals(st) ? "🔴 NEW"
                        : Sos.S_ACK.equals(st) ? "🟠 Responding"
                        : Sos.S_RESOLVED.equals(st) ? "✅ Resolved" : "⛔ Cancelled";
                rowText.add("🆘 " + patientLabel(d) + "\n" + label
                        + "  •  ID " + nz(d.getString("patientCode"))
                        + "  •  " + Sos.ago(now - ts(d, "createdAt")));
            }
            txtEmpty.setText(sosDocs.isEmpty() ? "No SOS alerts for this hospital yet."
                    : "Tap an SOS alert to see the patient live on the map.");
        } else {
            for (DocumentSnapshot d : historyDocs) {
                long start = ts(d, "createdAt");
                long end = ts(d, "finishedAt");
                String dur = end > start ? "  •  " + Math.max(1, (end - start) / 60000) + " min" : "";
                rowText.add((Fs.S_COMPLETED.equals(d.getString("status")) ? "✅ " : "⛔ ")
                        + (start > 0 ? f.format(new Date(start)) : "") + "  " + patientLabel(d)
                        + "\n🚑 " + nz(d.getString("ambulanceNumber")) + dur);
            }
            txtEmpty.setText(historyDocs.isEmpty() ? "No trips yet." : "Tap a trip for details.");
        }
        adapter.notifyDataSetChanged();
    }

    private String patientLabel(DocumentSnapshot d) {
        String n = nz(d.getString("patientName"));
        String c = nz(d.getString("condition"));
        return (n.isEmpty() ? "Unknown patient" : n) + (c.isEmpty() ? "" : "  ⚠ " + c);
    }

    private String statusLabel(String st) {
        switch (st) {
            case Fs.S_ACCEPTED: return "🚑 Ambulance on the way to patient";
            case Fs.S_PICKED_UP: return "🏥 Patient on board — coming to this hospital";
            case Fs.S_COMPLETED: return "✅ Completed";
            case Fs.S_CANCELLED: return "⛔ Cancelled";
            default: return st;
        }
    }

    private void onRowClick(int pos) {
        if (tab == TAB_AMB) {
            if (pos >= ambDocs.size()) return;
            String phone = nz(ambDocs.get(pos).getString("phone"));
            if (phone.isEmpty()) Toast.makeText(this, "No driver phone saved", Toast.LENGTH_SHORT).show();
            else Fs.dial(this, phone);
        } else if (tab == TAB_ACTIVE) {
            if (pos >= activeDocs.size()) return;
            Intent i = new Intent(this, TripMapActivity.class);
            i.putExtra(TripMapActivity.EXTRA_REQUEST_ID, activeDocs.get(pos).getId());
            i.putExtra(TripMapActivity.EXTRA_ROLE, TripMapActivity.ROLE_NURSE);
            startActivity(i);
        } else if (tab == TAB_SOS) {
            if (pos >= sosDocs.size()) return;
            DocumentSnapshot d = sosDocs.get(pos);
            String pu = nz(d.getString("patientUid"));
            if (pu.isEmpty()) {
                Toast.makeText(this, "No patient linked to this alert", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, PatientLiveMapActivity.class);
            i.putExtra(PatientLiveMapActivity.EXTRA_PATIENT_UID, pu);
            i.putExtra(PatientLiveMapActivity.EXTRA_ROLE, PatientLiveMapActivity.ROLE_HOSPITAL);
            i.putExtra(PatientLiveMapActivity.EXTRA_SOS_ID, d.getId());
            i.putExtra(PatientLiveMapActivity.EXTRA_PATIENT_NAME, nz(d.getString("patientName")));
            startActivity(i);
        } else {
            if (pos >= historyDocs.size()) return;
            DocumentSnapshot d = historyDocs.get(pos);
            long start = ts(d, "createdAt");
            long end = ts(d, "finishedAt");
            SimpleDateFormat f = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault());
            String msg = "Patient: " + nz(d.getString("patientName"))
                    + "\nCondition: " + nz(d.getString("condition"))
                    + "\nAmbulance: " + nz(d.getString("ambulanceNumber"))
                    + "\nRequested: " + (start > 0 ? f.format(new Date(start)) : "-")
                    + "\nFinished: " + (end > 0 ? f.format(new Date(end)) : "-")
                    + "\nStatus: " + nz(d.getString("status"));
            new AlertDialog.Builder(this).setTitle("Trip details").setMessage(msg)
                    .setPositiveButton("OK", null).show();
        }
    }

    // ------------------------------------------------------------------------------------
    // location + phone
    // ------------------------------------------------------------------------------------

    private void askPhone() {
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        et.setHint("Hospital phone number");
        new AlertDialog.Builder(this)
                .setTitle("Hospital phone number")
                .setMessage("Ambulance drivers will use this number to call the hospital.")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) return;
                    Map<String, Object> m = new HashMap<>();
                    m.put("phone", p);
                    db.collection(Fs.HOSPITALS).document(uid).set(m, SetOptions.merge());
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private void saveCurrentLocation() {
        if (!hasLoc()) {
            locPerm.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        btnSetLoc.setEnabled(false);
        txtLoc.setText("Getting GPS location...");
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,
                            new CancellationTokenSource().getToken())
                    .addOnSuccessListener(loc -> {
                        btnSetLoc.setEnabled(true);
                        if (loc == null) {
                            txtLoc.setText("⚠ Could not get GPS. Turn on location and try again.");
                            return;
                        }
                        Map<String, Object> m = new HashMap<>();
                        m.put("name", hospitalName);
                        m.put("uid", uid);
                        m.put("lat", loc.getLatitude());
                        m.put("lng", loc.getLongitude());
                        m.put("locationUpdatedAt", System.currentTimeMillis());
                        db.collection(Fs.HOSPITALS).document(uid).set(m, SetOptions.merge());
                    })
                    .addOnFailureListener(e -> {
                        btnSetLoc.setEnabled(true);
                        txtLoc.setText("⚠ Could not get GPS: " + e.getMessage());
                    });
        } catch (SecurityException e) {
            btnSetLoc.setEnabled(true);
        }
    }
}
