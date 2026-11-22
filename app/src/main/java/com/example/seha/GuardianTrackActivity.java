package com.example.seha;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;

/**
 * GUARDIAN / RELATIVE: enter the Patient ID (from the SMS or from the patient), then confirm it with
 * the patient's USERNAME (security check) - only after that the ambulance or the patient's live GPS
 * location is shown. No account needed.
 *
 *   step 1  Patient ID  ->  [Track ambulance]  or  [Track patient (GPS)]
 *   step 2  "Security check": the patient's username must match the Patient ID
 *   step 3  live map
 */
public class GuardianTrackActivity extends AppCompatActivity {

    private static final int MODE_AMBULANCE = 1, MODE_PATIENT = 2;
    private static final int MAX_TRIES = 3;
    private static final long LOCK_MS = 60_000;

    // static: closing and re-opening the screen does not reset the wrong-attempt counter
    private static int wrongTries = 0;
    private static long lockedUntil = 0;

    private EditText etCode, etName;
    private Button btnAmb, btnPat, btnVerify;
    private TextView txtMsg;
    private LinearLayout cardVerify;
    private ScrollView scrollView;

    /** Patient profiles that own the checked Patient ID (normally exactly one). */
    private final List<DocumentSnapshot> candidates = new ArrayList<>();
    private String codeChecked = "";
    private int mode = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F7FA"));
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setClipToPadding(false);
        scrollView = scroll;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("👪 Track patient");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor("#0D47A1"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Enter the Patient ID you received by SMS to see the ambulance live on the map, "
                + "the route to the patient / hospital and the arrival time, or the patient's live GPS location.\n"
                + "For security you will also be asked for the patient's username.");
        sub.setTextColor(Color.parseColor("#546E7A"));
        sub.setTextSize(14);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(8);
        root.addView(sub, sp);

        etCode = new EditText(this);
        etCode.setHint("Patient ID (6 digits)");
        etCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCode.setTextSize(22);
        etCode.setGravity(Gravity.CENTER);
        etCode.setBackgroundResource(R.drawable.edittext);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        ep.topMargin = dp(24);
        root.addView(etCode, ep);

        btnAmb = new Button(this);
        btnAmb.setText("Track ambulance");
        btnAmb.setAllCaps(false);
        btnAmb.setTextColor(Color.WHITE);
        btnAmb.setTextSize(16);
        btnAmb.setBackgroundResource(R.drawable.loginbutton);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        bp.topMargin = dp(16);
        root.addView(btnAmb, bp);

        btnPat = new Button(this);
        btnPat.setText("📍 Track patient (GPS)");
        btnPat.setAllCaps(false);
        btnPat.setTextColor(Color.WHITE);
        btnPat.setTextSize(16);
        btnPat.setBackgroundResource(R.drawable.bluebutton);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        pp.topMargin = dp(12);
        root.addView(btnPat, pp);

        // ---- step 2: security check (hidden until the Patient ID was found)
        cardVerify = new LinearLayout(this);
        cardVerify.setOrientation(LinearLayout.VERTICAL);
        cardVerify.setBackgroundColor(Color.WHITE);
        cardVerify.setPadding(dp(16), dp(16), dp(16), dp(16));
        cardVerify.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(20);
        root.addView(cardVerify, cp);

        TextView vTitle = new TextView(this);
        vTitle.setText("🔒 Security check");
        vTitle.setTextSize(18);
        vTitle.setTextColor(Color.parseColor("#0D47A1"));
        vTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        cardVerify.addView(vTitle);

        TextView vSub = new TextView(this);
        vSub.setText("Patient ID found. To make sure this is the right patient, enter the patient's "
                + "username (the name the patient registered with).");
        vSub.setTextSize(13);
        vSub.setTextColor(Color.parseColor("#546E7A"));
        LinearLayout.LayoutParams vsp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vsp.topMargin = dp(6);
        cardVerify.addView(vSub, vsp);

        etName = new EditText(this);
        etName.setHint("Patient's username");
        etName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etName.setTextSize(18);
        etName.setSingleLine(true);
        etName.setGravity(Gravity.CENTER);
        etName.setBackgroundResource(R.drawable.edittext);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        np.topMargin = dp(12);
        cardVerify.addView(etName, np);

        btnVerify = new Button(this);
        btnVerify.setAllCaps(false);
        btnVerify.setTextColor(Color.WHITE);
        btnVerify.setTextSize(16);
        btnVerify.setBackgroundResource(R.drawable.loginbutton);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        vp.topMargin = dp(12);
        cardVerify.addView(btnVerify, vp);

        txtMsg = new TextView(this);
        txtMsg.setTextColor(Color.parseColor("#D32F2F"));
        txtMsg.setTextSize(13);
        txtMsg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(12);
        root.addView(txtMsg, mp);

        setContentView(scroll);
        final int pad = dp(24);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(s.left + pad, s.top + pad, s.right + pad, s.bottom + pad);
            return insets;
        });

        btnAmb.setOnClickListener(v -> checkId(MODE_AMBULANCE));
        btnPat.setOnClickListener(v -> checkId(MODE_PATIENT));
        btnVerify.setOnClickListener(v -> verify());

        // a different Patient ID -> the security check starts again
        etCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                if (!s.toString().trim().equals(codeChecked)) resetVerify();
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void showMsg(String text, boolean error) {
        txtMsg.setTextColor(Color.parseColor(error ? "#D32F2F" : "#546E7A"));
        txtMsg.setText(text);
    }

    private void setBusy(boolean busy) {
        btnAmb.setEnabled(!busy);
        btnPat.setEnabled(!busy);
    }

    private void resetVerify() {
        candidates.clear();
        codeChecked = "";
        cardVerify.setVisibility(android.view.View.GONE);
        etName.setText("");
        txtMsg.setText("");
    }

    private static boolean isPatientDoc(DocumentSnapshot d) {
        String r = d.getString("role");
        return r == null || r.isEmpty() || "patient".equalsIgnoreCase(r);
    }

    // ------------------------------------------------------------------------------------
    // step 1: does this Patient ID exist?
    // ------------------------------------------------------------------------------------

    private void checkId(int newMode) {
        final String code = etCode.getText().toString().trim();
        if (!code.matches("[0-9]{6}")) {
            showMsg("Enter the 6-digit Patient ID.", true);
            return;
        }
        mode = newMode;
        if (code.equals(codeChecked) && !candidates.isEmpty()) {
            showVerify(code);            // same ID, only the button (ambulance / patient) changed
            return;
        }
        setBusy(true);
        showMsg("Checking the Patient ID...", false);

        Fs.ensureSignedIn(() -> FirebaseFirestore.getInstance().collection(Fs.PATIENTS)
                .whereEqualTo("patientCode", code).get()
                .addOnSuccessListener(qs -> {
                    candidates.clear();
                    for (DocumentSnapshot d : qs.getDocuments()) {
                        if (isPatientDoc(d)) candidates.add(d);
                    }
                    if (!candidates.isEmpty()) {
                        setBusy(false);
                        showVerify(code);
                    } else {
                        scanAllPatients(code);
                    }
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    showMsg("Could not connect: " + Fs.explain(e), true);
                }));
    }

    /**
     * Old accounts may not have the code saved in the profile yet: the Patient ID is also computed from
     * the patient's uid (the same way the ambulance SMS does it), so look for that too.
     */
    private void scanAllPatients(String code) {
        FirebaseFirestore.getInstance().collection(Fs.PATIENTS).get()
                .addOnSuccessListener(all -> {
                    setBusy(false);
                    candidates.clear();
                    for (DocumentSnapshot d : all.getDocuments()) {
                        if (isPatientDoc(d) && code.equals(Fs.codeForUid(d.getId()))) candidates.add(d);
                    }
                    if (candidates.isEmpty()) {
                        showMsg("No patient found with this Patient ID.", true);
                    } else {
                        showVerify(code);
                    }
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    showMsg("Could not connect: " + Fs.explain(e), true);
                });
    }

    // ------------------------------------------------------------------------------------
    // step 2: security check with the patient's username
    // ------------------------------------------------------------------------------------

    private void showVerify(String code) {
        codeChecked = code;
        cardVerify.setVisibility(android.view.View.VISIBLE);
        btnVerify.setText(mode == MODE_PATIENT ? "Verify & show patient location"
                : "Verify & show ambulance");
        showMsg("", false);
        etName.requestFocus();
        // bring the security-check card into view
        scrollView.post(() -> scrollView.fullScroll(android.view.View.FOCUS_DOWN));
    }

    private void verify() {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            showMsg("Too many wrong attempts. Try again in " + ((lockedUntil - now + 999) / 1000) + " s.", true);
            return;
        }
        if (candidates.isEmpty() || codeChecked.isEmpty()) {
            showMsg("Enter the Patient ID first.", true);
            return;
        }
        String typed = Sos.norm(etName.getText().toString());
        if (typed.isEmpty()) {
            showMsg("Enter the patient's username.", true);
            return;
        }

        DocumentSnapshot match = null;
        for (DocumentSnapshot d : candidates) {
            if (typed.equals(Sos.norm(d.getString("fullName")))
                    || typed.equals(Sos.norm(d.getString("patientName")))) {
                match = d;
                break;
            }
        }

        if (match == null) {
            wrongTries++;
            if (wrongTries >= MAX_TRIES) {
                wrongTries = 0;
                lockedUntil = now + LOCK_MS;
                showMsg("Too many wrong attempts. Locked for " + (LOCK_MS / 1000) + " seconds.", true);
            } else {
                showMsg("The username does not match this Patient ID. Attempts left: "
                        + (MAX_TRIES - wrongTries), true);
            }
            return;
        }

        // verified
        wrongTries = 0;
        showMsg("", false);
        etName.setText("");
        if (mode == MODE_PATIENT) openPatientMap(match);
        else trackAmbulance(codeChecked);
    }

    // ------------------------------------------------------------------------------------
    // step 3a: the patient's live GPS location
    // ------------------------------------------------------------------------------------

    private void openPatientMap(DocumentSnapshot patient) {
        String name = Sos.nz(patient.getString("fullName"));
        if (name.isEmpty()) name = Sos.nz(patient.getString("patientName"));
        Intent i = new Intent(this, PatientLiveMapActivity.class);
        i.putExtra(PatientLiveMapActivity.EXTRA_PATIENT_UID, patient.getId());
        i.putExtra(PatientLiveMapActivity.EXTRA_ROLE, PatientLiveMapActivity.ROLE_GUARDIAN);
        i.putExtra(PatientLiveMapActivity.EXTRA_PATIENT_NAME, name);
        i.putExtra(PatientLiveMapActivity.EXTRA_PATIENT_CODE, codeChecked);
        startActivity(i);
    }

    // ------------------------------------------------------------------------------------
    // step 3b: the ambulance of this patient (unchanged logic, now only after the check)
    // ------------------------------------------------------------------------------------

    private void trackAmbulance(final String code) {
        setBusy(true);
        showMsg("Searching...", false);

        Fs.ensureSignedIn(() -> FirebaseFirestore.getInstance().collection(Fs.REQUESTS)
                .whereEqualTo("patientCode", code).get()
                .addOnSuccessListener(qs -> {
                    setBusy(false);
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
                    if (best == null) {
                        showMsg("No active ambulance trip found for this patient. "
                                + "You can still use \"Track patient (GPS)\".", true);
                        return;
                    }
                    showMsg("", false);
                    Intent i = new Intent(this, TripMapActivity.class);
                    i.putExtra(TripMapActivity.EXTRA_REQUEST_ID, best.getId());
                    i.putExtra(TripMapActivity.EXTRA_ROLE, TripMapActivity.ROLE_GUARDIAN);
                    startActivity(i);
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    showMsg("Could not connect: " + Fs.explain(e), true);
                }));
    }
}
