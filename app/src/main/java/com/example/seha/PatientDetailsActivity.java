package com.example.seha;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.widget.Button;
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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class PatientDetailsActivity extends AppCompatActivity {

    private static final String AMBULANCE_NUMBER = "+94718930356";
    private static final String NURSE_NUMBER = "+94779349746";
    // Change this to your demo hospital name
    private static final String HOSPITAL_NAME = "National Hospital of Sri Lanka";
    private static final String ACTION_SMS_SENT = "com.example.seha.SMS_SENT";

    TextView txtPatientName, txtAge, txtBloodGroup, txtAllergies, txtChronic;
    Button btnInform, btnCall, btnDispatch;
    TextView txtConditionBanner;
    private DocumentSnapshot patientDoc = null;
    private final androidx.activity.result.ActivityResultLauncher<String[]> dispatchPerm =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> dispatchAmbulance());

    FirebaseFirestore db;
    FirebaseAuth auth;

    // Relative's number, loaded from Firestore (Patients/{uid}.relativePhone)
    private String relativePhone = null;

    // number -> how many SMS parts are still waiting for a result
    private final Map<String, Integer> pendingParts = new HashMap<>();
    private int requestCode = 0;

    // Gets the REAL result from the phone/carrier for every SMS part
    private final BroadcastReceiver smsSentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String to = intent.getStringExtra("to");
            if (to == null || !pendingParts.containsKey(to)) return;

            int code = getResultCode();
            if (code == Activity.RESULT_OK) {
                int left = pendingParts.get(to) - 1;
                if (left <= 0) {
                    pendingParts.remove(to);
                    Toast.makeText(PatientDetailsActivity.this,
                            "SMS sent to " + to, Toast.LENGTH_SHORT).show();
                } else {
                    pendingParts.put(to, left);
                }
            } else {
                pendingParts.remove(to); // report failure only once per number
                Toast.makeText(PatientDetailsActivity.this,
                        "SMS FAILED to " + to + ": " + describeError(code),
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean smsOk = ContextCompat.checkSelfPermission(this,
                        Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
                if (smsOk) {
                    sendEmergency();
                } else {
                    Toast.makeText(this, "SMS permission is required to inform", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_patient_details);

        txtPatientName = findViewById(R.id.txtPatientName);
        txtAge = findViewById(R.id.txtAge);
        txtBloodGroup = findViewById(R.id.txtBloodGroup);
        txtAllergies = findViewById(R.id.txtAllergies);
        txtChronic = findViewById(R.id.txtChronic);
        btnInform = findViewById(R.id.button3);
        btnCall = findViewById(R.id.button5);
        btnDispatch = findViewById(R.id.btnDispatch);
        txtConditionBanner = findViewById(R.id.txtConditionBanner);

        // Hospital / nurse staff can send the nearest ambulance straight from the scan result
        String myRole = RoleRouter.cachedRole(this);
        boolean staff = RoleRouter.ROLE_HOSPITAL.equals(myRole) || RoleRouter.ROLE_NURSE.equals(myRole);
        if (staff && com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {

            Button btnRecords = findViewById(R.id.btnRecords);
            btnRecords.setVisibility(android.view.View.VISIBLE);
            btnRecords.setOnClickListener(v -> {
                if (patientDoc == null) {
                    Toast.makeText(this, "Patient details not loaded yet", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent ri = new Intent(this, PatientRecordsActivity.class);
                ri.putExtra(PatientRecordsActivity.EXTRA_PATIENT_UID, patientDoc.getId());
                startActivity(ri);
            });
        }

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        ContextCompat.registerReceiver(this, smsSentReceiver,
                new IntentFilter(ACTION_SMS_SENT), ContextCompat.RECEIVER_NOT_EXPORTED);

        loadPatient();

        btnInform.setText("🚑 Send nearest ambulance");
        btnInform.setOnClickListener(v -> dispatchAmbulance());

        btnCall.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:1990"))));

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(smsSentReceiver); } catch (Exception ignored) { }
    }

    private void loadPatient() {
        // the scanner may be a stranger who is not logged in -> anonymous sign-in first
        Fs.ensureSignedIn(this::loadPatientNow);
    }

    private void loadPatientNow() {
        String person = getIntent().getStringExtra("person");
        String personUid = getIntent().getStringExtra("uid");

        if (personUid != null && !personUid.isEmpty()) {
            db.collection("Patients").document(personUid).get()
                    .addOnSuccessListener(doc -> {
                        if (doc.exists()) showPatient(doc);
                        else Toast.makeText(this, "Patient Not Found", Toast.LENGTH_LONG).show();
                    })
                    .addOnFailureListener(e ->
                            Toast.makeText(this, Fs.explain(e), Toast.LENGTH_LONG).show());

        } else if (person != null && !person.isEmpty()) {
            db.collection("Patients")
                    .whereEqualTo("patientName", person)
                    .get()
                    .addOnSuccessListener(qs -> {
                        if (!qs.isEmpty()) {
                            showPatient(qs.getDocuments().get(0));
                        } else {
                            Toast.makeText(this, "Patient Not Found", Toast.LENGTH_LONG).show();
                        }
                    })
                    .addOnFailureListener(e ->
                            Toast.makeText(this, Fs.explain(e), Toast.LENGTH_LONG).show());

        } else if (auth.getCurrentUser() != null) {
            db.collection("Patients")
                    .document(auth.getCurrentUser().getUid())
                    .get()
                    .addOnSuccessListener(doc -> {
                        if (doc.exists()) showPatient(doc);
                    });
        }
    }

    private void showPatient(DocumentSnapshot d) {
        txtPatientName.setText(d.getString("patientName"));
        txtAge.setText(d.getString("age"));
        txtBloodGroup.setText(d.getString("bloodGroup"));
        txtAllergies.setText(d.getString("allergies"));
        txtChronic.setText(d.getString("chronicConditions"));
        patientDoc = d;

        // Big red medical alert (e.g. "DIABETIC") so rescuers see it immediately
        String chronic = d.getString("chronicConditions");
        if (chronic != null && !chronic.trim().isEmpty()
                && !chronic.trim().equalsIgnoreCase("none") && !chronic.trim().equals("-")) {
            txtConditionBanner.setText("⚠ MEDICAL ALERT\n" + chronic.trim().toUpperCase());
            txtConditionBanner.setVisibility(android.view.View.VISIBLE);
        } else {
            txtConditionBanner.setVisibility(android.view.View.GONE);
        }

        // Relative's number saved at sign up
        relativePhone = d.getString("relativePhone");
    }

    @SuppressLint("MissingPermission")
    private void sendEmergency() {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null || tm.getSimState() != TelephonyManager.SIM_STATE_READY) {
            Toast.makeText(this, "No active SIM card found. SMS cannot be sent.", Toast.LENGTH_LONG).show();
            return;
        }

        boolean hasLocation = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!hasLocation) {
            sendSms(null, null);
            return;
        }

        FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(this);
        fused.getLastLocation()
                .addOnSuccessListener(loc -> {
                    if (loc != null) sendSms(loc.getLatitude(), loc.getLongitude());
                    else sendSms(null, null);
                })
                .addOnFailureListener(e -> sendSms(null, null));
    }

    private SmsManager getSmsManagerForDefaultSim() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return getSystemService(SmsManager.class).createForSubscriptionId(subId);
            } else {
                //noinspection deprecation
                return SmsManager.getSmsManagerForSubscriptionId(subId);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return getSystemService(SmsManager.class);
        }
        //noinspection deprecation
        return SmsManager.getDefault();
    }

    private void sendSms(Double lat, Double lng) {
        String name = txtPatientName.getText().toString();
        if (name == null || name.trim().isEmpty() || "—".equals(name)) {
            name = "Unknown patient";
        }
        String age = txtAge.getText().toString();
        String blood = txtBloodGroup.getText().toString();
        String allergies = txtAllergies.getText().toString();
        String chronic = txtChronic.getText().toString();

        // Ambulance & nurse: medical details
        String pid = patientDoc != null && patientDoc.getString("patientCode") != null
                ? patientDoc.getString("patientCode") : "";
        String idPart = pid.isEmpty() ? "" : "Patient ID: " + pid + "\n";

        String details = "EMERGENCY! " + idPart + "Patient: " + name
                + ", Age: " + age
                + ", Blood: " + blood
                + ", Allergies: " + allergies
                + ", Conditions: " + chronic;

        // Relative: patient name + emergency + taken to hospital by ambulance
        String relativeMsg = "EMERGENCY ALERT\n"
                + idPart
                + "Patient: " + name + "\n"
                + "They are being taken by ambulance to "
                + HOSPITAL_NAME + ".\n"
                + (pid.isEmpty() ? "" : "Track the ambulance live: SEHA app > Track a patient > enter Patient ID "
                + pid + ".\n")
                + "Please contact the hospital or stay reachable.";

        String locationMsg = (lat != null && lng != null)
                ? "Patient location: https://maps.google.com/?q=" + lat + "," + lng
                : null;

        String rel = relativePhone == null ? "" : relativePhone.replaceAll("\\s+", "");
        boolean sendToRelative = !rel.isEmpty()
                && !rel.equals(AMBULANCE_NUMBER)
                && !rel.equals(NURSE_NUMBER);

        try {
            SmsManager sms = getSmsManagerForDefaultSim();

            sendOne(sms, AMBULANCE_NUMBER, details);
            sendOne(sms, NURSE_NUMBER, details);

            if (sendToRelative) {
                sendOne(sms, rel, relativeMsg);
            } else {
                Toast.makeText(this,
                        "No relative number saved. Add it in Sign Up / Edit Profile.",
                        Toast.LENGTH_LONG).show();
            }

            if (locationMsg != null) {
                sendOne(sms, AMBULANCE_NUMBER, locationMsg);
                sendOne(sms, NURSE_NUMBER, locationMsg);
                if (sendToRelative) {
                    sendOne(sms, rel, locationMsg);
                }
            }
            Toast.makeText(this, "Sending emergency alert...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "SMS failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        saveEmergencyRecord(name, lat, lng);
    }

    private void sendOne(SmsManager sms, String number, String text) {
        ArrayList<String> parts = sms.divideMessage(text);
        ArrayList<PendingIntent> sentIntents = new ArrayList<>();

        for (int i = 0; i < parts.size(); i++) {
            Intent i2 = new Intent(ACTION_SMS_SENT);
            i2.setPackage(getPackageName());
            i2.putExtra("to", number);
            sentIntents.add(PendingIntent.getBroadcast(this, requestCode++, i2,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }

        Integer old = pendingParts.get(number);
        pendingParts.put(number, (old == null ? 0 : old) + parts.size());

        sms.sendMultipartTextMessage(number, null, parts, sentIntents, null);
    }

    private String describeError(int code) {
        switch (code) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                return "Generic failure (carrier/SIM rejected it, no credit, or OEM blocked it)";
            case SmsManager.RESULT_ERROR_NO_SERVICE:
                return "No network signal / no service";
            case SmsManager.RESULT_ERROR_RADIO_OFF:
                return "Airplane mode / radio is off";
            case SmsManager.RESULT_ERROR_NULL_PDU:
                return "Invalid message or number";
            default:
                return "Error code " + code;
        }
    }

    // ------------------------------------------------------------------ dispatch ambulance
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Nearest ambulance gets the request (with a sound). The SMS to nurse + guardian is sent by the ambulance. */
    private void dispatchAmbulance() {
        if (patientDoc == null) {
            Toast.makeText(this, "Patient details not loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!EmergencyDispatch.hasLocationPermission(this)) {
            dispatchPerm.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        String myRole = RoleRouter.cachedRole(this);
        boolean staff = (RoleRouter.ROLE_HOSPITAL.equals(myRole) || RoleRouter.ROLE_NURSE.equals(myRole))
                && auth.getCurrentUser() != null && !auth.getCurrentUser().isAnonymous();

        Map<String, Object> m = new HashMap<>();
        m.put("type", "scan_request");
        m.put("requesterRole", staff ? myRole : "public");
        m.put("patientUid", patientDoc.getId());
        m.put("patientName", nz(patientDoc.getString("patientName")));
        m.put("patientPhone", nz(patientDoc.getString("phone")));
        m.put("guardianPhone", nz(patientDoc.getString("relativePhone")));
        String storedCode = nz(patientDoc.getString("patientCode"));
        m.put("patientCode", storedCode.isEmpty() ? Fs.codeForUid(patientDoc.getId()) : storedCode);
        m.put("condition", nz(patientDoc.getString("chronicConditions")));
        m.put("bloodGroup", nz(patientDoc.getString("bloodGroup")));
        m.put("allergies", nz(patientDoc.getString("allergies")));

        btnInform.setEnabled(false);
        EmergencyDispatch.run(this, m,
                staff ? TripMapActivity.ROLE_HOSPITAL : TripMapActivity.ROLE_PATIENT,
                () -> btnInform.setEnabled(true));
    }

    private void saveEmergencyRecord(String name, Double lat, Double lng) {
        Map<String, Object> record = new HashMap<>();
        record.put("patientName", name);
        record.put("lat", lat);
        record.put("lng", lng);
        record.put("status", "sent");
        record.put("time", FieldValue.serverTimestamp());
        if (relativePhone != null) {
            record.put("relativePhone", relativePhone);
        }
        if (auth.getCurrentUser() != null) {
            record.put("uid", auth.getCurrentUser().getUid());
        }
        db.collection("Emergencies").add(record);
    }
}