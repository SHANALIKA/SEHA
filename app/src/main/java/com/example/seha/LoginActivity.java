package com.example.seha;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

public class LoginActivity extends BaseDrawerActivity {

    FirebaseAuth mAuth;
    FirebaseFirestore db;
    EditText email, password;
    Button loginBtn, signBtn, emergencyBtn;
    android.widget.RadioGroup rgRole;
    android.widget.Spinner spinnerHospital;
    android.widget.TextView txtIdLabel;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        email = findViewById(R.id.e1);
        password = findViewById(R.id.p1);
        loginBtn = findViewById(R.id.button);
        signBtn = findViewById(R.id.button4);
        emergencyBtn = findViewById(R.id.button6);   // Emergency button

        // ===================== SIGN UP =====================
        signBtn.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, SignupActivity.class);
            startActivity(intent);
        });

        // ===================== LOGIN (choose entity first) =====================
        rgRole = findViewById(R.id.rgRole);
        spinnerHospital = findViewById(R.id.spinnerHospital);
        txtIdLabel = findViewById(R.id.txtIdLabel);

        android.widget.ArrayAdapter<String> hospAdapter =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, HospitalCatalog.labels(this));
        hospAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerHospital.setAdapter(hospAdapter);

        rgRole.setOnCheckedChangeListener((g, id) -> applyRole());
        applyRole();

        loginBtn.setOnClickListener(v -> login());

        // ===================== EMERGENCY FACE SCAN =====================
        emergencyBtn.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, FaceScanActivity.class);
            startActivity(intent);
        });

        // ===================== GUARDIAN TRACKING =====================
        findViewById(R.id.btnGuardian).setOnClickListener(v ->
                startActivity(new Intent(LoginActivity.this, GuardianTrackActivity.class)));

        // Edge-to-edge padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private String selectedRole() {
        int id = rgRole.getCheckedRadioButtonId();
        if (id == R.id.rbNurse) return RoleRouter.ROLE_NURSE;
        if (id == R.id.rbAmbulance) return RoleRouter.ROLE_AMBULANCE;
        if (id == R.id.rbHospital) return RoleRouter.ROLE_HOSPITAL;
        return RoleRouter.ROLE_PATIENT;
    }

    /** Label / keyboard of the first field changes with the selected entity. */
    private void applyRole() {
        String role = selectedRole();
        email.setText("");
        email.setError(null);
        boolean hospital = RoleRouter.ROLE_HOSPITAL.equals(role);
        email.setVisibility(hospital ? android.view.View.GONE : android.view.View.VISIBLE);
        spinnerHospital.setVisibility(hospital ? android.view.View.VISIBLE : android.view.View.GONE);

        if (RoleRouter.ROLE_NURSE.equals(role)) {
            txtIdLabel.setText("NIC Number");
            email.setHint("Enter your NIC number");
            email.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        } else if (RoleRouter.ROLE_AMBULANCE.equals(role)) {
            txtIdLabel.setText("Vehicle Number");
            email.setHint("e.g. WP CAB-1234");
            email.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        } else if (hospital) {
            txtIdLabel.setText("Hospital");
        } else {
            txtIdLabel.setText("Email");
            email.setHint("Enter your email");
            email.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        }
    }

    private void login() {
        final String role = selectedRole();
        String id = email.getText().toString().trim();
        String userPassword = password.getText().toString().trim();

        if (RoleRouter.ROLE_HOSPITAL.equals(role)) {
            if (spinnerHospital.getSelectedItemPosition() <= 0) {
                Toast.makeText(this, "Select your hospital", Toast.LENGTH_SHORT).show();
                return;
            }
            id = String.valueOf(spinnerHospital.getSelectedItem());
        } else if (id.isEmpty()) {
            email.setError("Required");
            email.requestFocus();
            return;
        } else if (RoleRouter.ROLE_NURSE.equals(role) && !AuthIds.validNic(id)) {
            email.setError("Enter a valid NIC number");
            email.requestFocus();
            return;
        }

        if (userPassword.isEmpty()) {
            password.setError("Enter Password");
            password.requestFocus();
            return;
        }

        loginBtn.setEnabled(false);
        mAuth.signInWithEmailAndPassword(AuthIds.emailFor(role, id), userPassword)
                .addOnCompleteListener(task -> {
                    if (!(task.isSuccessful() && mAuth.getCurrentUser() != null)) {
                        loginBtn.setEnabled(true);
                        Exception ex = task.getException();
                        String msg = "Login failed";
                        if (ex instanceof com.google.firebase.auth.FirebaseAuthInvalidUserException
                                || ex instanceof com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
                            msg = "Wrong " + (RoleRouter.ROLE_PATIENT.equals(role) ? "email"
                                    : "ID") + " or password (or no " + role + " account yet)";
                        } else if (ex != null && ex.getMessage() != null) {
                            msg = ex.getMessage();
                        }
                        Toast.makeText(LoginActivity.this, msg, Toast.LENGTH_LONG).show();
                        return;
                    }

                    // make sure this account really is the selected entity
                    String uid = mAuth.getCurrentUser().getUid();
                    db.collection(Fs.PATIENTS).document(uid).get()
                            .addOnSuccessListener(doc -> {
                                String docRole = doc.exists() ? doc.getString("role") : null;
                                if (docRole == null) docRole = RoleRouter.ROLE_PATIENT;
                                if (!docRole.equals(role)) {
                                    mAuth.signOut();
                                    loginBtn.setEnabled(true);
                                    Toast.makeText(LoginActivity.this,
                                            "This account is a " + docRole + " account. Select \""
                                                    + docRole + "\" above.", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                RoleRouter.cacheRole(LoginActivity.this, role);
                                Toast.makeText(LoginActivity.this, "Login Successful",
                                        Toast.LENGTH_SHORT).show();
                                openNextScreen();
                            })
                            .addOnFailureListener(e -> {
                                // offline? trust the selection
                                Toast.makeText(LoginActivity.this, "Login Successful",
                                        Toast.LENGTH_SHORT).show();
                                openNextScreen();
                            });
                });
    }

    /** After login: nurse/ambulance -> map; patient -> profile or home. */
    private void openNextScreen() {
        RoleRouter.route(this);
    }
}