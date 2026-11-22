package com.example.seha;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * SIGN UP - choose who you are, then only the needed fields are shown:
 *   Patient   : name, e-mail, phone, relative's phone, password
 *   Nurse     : NIC number, password
 *   Ambulance : vehicle number, password
 *   Hospital  : hospital (combo box), password
 */
public class SignupActivity extends AppCompatActivity {

    FirebaseAuth mAuth;
    FirebaseFirestore db;
    EditText fullname, email, phonenumber, relativePhone, vehicleNumber, nicNumber,
            password, confirmpassword;
    Spinner spinnerHospital;
    RadioGroup rgRole;
    LinearLayout layoutRelative, layoutVehicle, layoutName, layoutPhone, layoutEmail,
            layoutNic, layoutHospital;
    Button signupBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_signup);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        fullname = findViewById(R.id.editTextText2);
        email = findViewById(R.id.editTextTextEmailAddress);
        phonenumber = findViewById(R.id.editTextPhone);
        relativePhone = findViewById(R.id.editTextRelativePhone);
        vehicleNumber = findViewById(R.id.editTextVehicleNumber);
        nicNumber = findViewById(R.id.editTextNic);
        spinnerHospital = findViewById(R.id.spinnerHospital);
        password = findViewById(R.id.editTextTextPassword);
        confirmpassword = findViewById(R.id.editTextTextPassword2);
        rgRole = findViewById(R.id.rgRole);
        layoutRelative = findViewById(R.id.layoutRelative);
        layoutVehicle = findViewById(R.id.layoutVehicle);
        layoutName = findViewById(R.id.layoutName);
        layoutPhone = findViewById(R.id.layoutPhone);
        layoutEmail = findViewById(R.id.layoutEmail);
        layoutNic = findViewById(R.id.layoutNic);
        layoutHospital = findViewById(R.id.layoutHospital);
        signupBtn = findViewById(R.id.button2);

        ArrayAdapter<String> hospAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, HospitalCatalog.labels(this));
        hospAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerHospital.setAdapter(hospAdapter);

        rgRole.setOnCheckedChangeListener((group, checkedId) -> applyRoleFields(checkedId));
        applyRoleFields(rgRole.getCheckedRadioButtonId());

        signupBtn.setOnClickListener(v -> signUp());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private String selectedRole() {
        int checked = rgRole.getCheckedRadioButtonId();
        if (checked == R.id.rbAmbulance) return RoleRouter.ROLE_AMBULANCE;
        if (checked == R.id.rbHospital) return RoleRouter.ROLE_HOSPITAL;
        if (checked == R.id.rbNurse) return RoleRouter.ROLE_NURSE;
        return RoleRouter.ROLE_PATIENT;
    }

    private void signUp() {
        final String role = selectedRole();
        final boolean isPatient = RoleRouter.ROLE_PATIENT.equals(role);
        final boolean isNurse = RoleRouter.ROLE_NURSE.equals(role);
        final boolean isAmbulance = RoleRouter.ROLE_AMBULANCE.equals(role);
        final boolean isHospital = RoleRouter.ROLE_HOSPITAL.equals(role);

        final String userName = fullname.getText().toString().trim();
        final String userEmail = email.getText().toString().trim();
        final String userPhone = phonenumber.getText().toString().trim();
        final String relPhone = relativePhone.getText().toString().replaceAll("\\s+", "");
        final String vehNum = vehicleNumber.getText().toString().trim().toUpperCase();
        final String nic = AuthIds.nic(nicNumber.getText().toString());
        final String userPassword = password.getText().toString().trim();
        final String confirmPassword = confirmpassword.getText().toString().trim();

        String hospitalName = "";
        if (isHospital || isNurse || isAmbulance) {
            if (spinnerHospital.getSelectedItemPosition() <= 0) {
                Toast.makeText(this, isAmbulance ? "Select the hospital this ambulance belongs to"
                        : "Select your hospital", Toast.LENGTH_SHORT).show();
                return;
            }
            hospitalName = String.valueOf(spinnerHospital.getSelectedItem());
        }
        final String hospName = hospitalName;

        // ---- identifier checks per entity
        if (isPatient) {
            if (userEmail.isEmpty()) {
                email.setError("Enter Email");
                email.requestFocus();
                return;
            }
            if (relPhone.isEmpty()) {
                relativePhone.setError("Enter relative's phone number");
                relativePhone.requestFocus();
                return;
            }
            if (relPhone.replaceAll("[^0-9]", "").length() < 9) {
                relativePhone.setError("Enter a valid phone number");
                relativePhone.requestFocus();
                return;
            }
        } else if (isNurse) {
            if (!AuthIds.validNic(nic)) {
                nicNumber.setError("Enter a valid NIC (12 digits, or 9 digits + V)");
                nicNumber.requestFocus();
                return;
            }
            if (userPhone.replaceAll("[^0-9]", "").length() < 9) {
                phonenumber.setError("Enter your phone number (ambulance SMS alerts come here)");
                phonenumber.requestFocus();
                return;
            }
        } else if (isAmbulance) {
            if (Fs.vehicleId(vehNum).length() < 4) {
                vehicleNumber.setError("Enter vehicle number");
                vehicleNumber.requestFocus();
                return;
            }
        }

        // ---- password
        if (userPassword.length() < 6) {
            password.setError("Password must be at least 6 characters");
            password.requestFocus();
            return;
        }
        if (!userPassword.equals(confirmPassword)) {
            confirmpassword.setError("Passwords do not match");
            confirmpassword.requestFocus();
            return;
        }

        final String identifier = isPatient ? userEmail
                : isNurse ? nic
                : isAmbulance ? vehNum
                : hospName;
        final String authEmail = AuthIds.emailFor(role, identifier);

        signupBtn.setEnabled(false);

        mAuth.createUserWithEmailAndPassword(authEmail, userPassword)
                .addOnCompleteListener(task -> {
                    if (!(task.isSuccessful() && mAuth.getCurrentUser() != null)) {
                        signupBtn.setEnabled(true);
                        Exception ex = task.getException();
                        String err;
                        if (ex instanceof FirebaseAuthUserCollisionException) {
                            err = isNurse ? "An account with this NIC already exists. Please login."
                                    : isAmbulance ? "This vehicle number is already registered. Please login."
                                    : isHospital ? "This hospital already has an account. Please login."
                                    : "This email is already registered. Please login.";
                        } else {
                            err = ex != null ? ex.getMessage() : "Unknown error";
                        }
                        Toast.makeText(SignupActivity.this, err, Toast.LENGTH_LONG).show();
                        return;
                    }

                    String uid = mAuth.getCurrentUser().getUid();
                    Map<String, Object> data = new HashMap<>();
                    data.put("role", role);
                    data.put("email", authEmail);

                    if (isPatient) {
                        data.put("fullName", userName);
                        data.put("phone", userPhone);
                        data.put("relativePhone", relPhone);
                    } else if (isNurse) {
                        data.put("nic", nic);
                        data.put("phone", userPhone);
                        data.put("hospitalName", hospName);
                        data.put("fullName", "Nurse " + nic);
                    } else if (isAmbulance) {
                        data.put("vehicleNumber", vehNum);
                        data.put("fullName", vehNum);
                        data.put("hospitalName", hospName);
                    } else {
                        data.put("fullName", hospName);
                        data.put("hospitalName", hospName);
                        Map<String, Object> hos = new HashMap<>();
                        hos.put("name", hospName);
                        hos.put("uid", uid);
                        // real position on the Sri Lanka map (no GPS needed)
                        HospitalCatalog.Entry pos = HospitalCatalog.find(SignupActivity.this, hospName);
                        if (pos != null) {
                            hos.put("lat", pos.lat);
                            hos.put("lng", pos.lng);
                            hos.put("locSource", pos.exact ? "catalog" : "hint");
                        }
                        db.collection(Fs.HOSPITALS).document(uid).set(hos, SetOptions.merge());
                        if (pos != null && !pos.exact) {
                            // only a town position is known -> find the real hospital in OpenStreetMap
                            HospitalFinder.resolve(pos, best -> {
                                if (best == null) return;
                                Map<String, Object> fix = new HashMap<>();
                                fix.put("lat", best.lat);
                                fix.put("lng", best.lng);
                                fix.put("locSource", "osm");
                                fix.put("osmName", best.name);
                                db.collection(Fs.HOSPITALS).document(uid).set(fix, SetOptions.merge());
                            });
                        }
                    }

                    final Runnable persist = () -> db.collection(Fs.PATIENTS).document(uid)
                            .set(data, SetOptions.merge())
                            .addOnCompleteListener(saveTask -> {
                                if (!saveTask.isSuccessful()) {
                                    String err = saveTask.getException() != null
                                            ? saveTask.getException().getMessage() : "";
                                    Toast.makeText(SignupActivity.this,
                                            "Could not save details: " + err,
                                            Toast.LENGTH_LONG).show();
                                }

                                RoleRouter.cacheRole(SignupActivity.this, role);
                                // Patient ID (6 digits) is created right at sign up
                                if (isPatient) Fs.ensurePatientCode(uid, c -> { });
                                Toast.makeText(SignupActivity.this, "Signup Successful",
                                        Toast.LENGTH_SHORT).show();

                                if (isAmbulance) {
                                    RoleRouter.openMap(SignupActivity.this, RoleRouter.ROLE_AMBULANCE);
                                } else if (isHospital) {
                                    RoleRouter.openMap(SignupActivity.this, RoleRouter.ROLE_HOSPITAL);
                                } else if (isNurse) {
                                    RoleRouter.openMap(SignupActivity.this, RoleRouter.ROLE_NURSE);
                                } else {
                                    startActivity(new Intent(SignupActivity.this, EditProfileActivity.class));
                                    finish();
                                }
                            });

                    if (isAmbulance) {
                        // the ambulance belongs to ONE (registered) hospital
                        db.collection(Fs.HOSPITALS).whereEqualTo("name", hospName).get()
                                .addOnSuccessListener(qs -> {
                                    if (qs.isEmpty()) {
                                        abortNurseSignup("This hospital is not registered in SEHA yet. "
                                                + "The hospital must create its account first.");
                                        return;
                                    }
                                    String hid = qs.getDocuments().get(0).getId();
                                    data.put("hospitalId", hid);
                                    Map<String, Object> amb = new HashMap<>();
                                    amb.put("vehicleNumber", vehNum);
                                    amb.put("uid", uid);
                                    amb.put("online", false);
                                    amb.put("available", true);
                                    amb.put("hospitalId", hid);
                                    amb.put("hospitalName", hospName);
                                    db.collection(Fs.AMBULANCES).document(Fs.vehicleId(vehNum))
                                            .set(amb, SetOptions.merge());
                                    persist.run();
                                })
                                .addOnFailureListener(e -> abortNurseSignup("Could not check hospital: " + e.getMessage()));
                        return;
                    }

                    if (!isNurse) {
                        persist.run();
                        return;
                    }

                    // Nurse: link to the (registered) hospital, one nurse per hospital
                    db.collection(Fs.HOSPITALS).whereEqualTo("name", hospName).get()
                            .addOnSuccessListener(qs -> {
                                if (qs.isEmpty()) {
                                    abortNurseSignup("This hospital is not registered in SEHA yet. "
                                            + "The hospital must create its account first.");
                                    return;
                                }
                                com.google.firebase.firestore.DocumentSnapshot h = qs.getDocuments().get(0);
                                String existing = h.getString("nurseUid");
                                if (existing != null && !existing.isEmpty()) {
                                    abortNurseSignup("This hospital already has a nurse registered "
                                            + "(one nurse per hospital).");
                                    return;
                                }
                                data.put("hospitalId", h.getId());
                                Map<String, Object> hn = new HashMap<>();
                                hn.put("nurseUid", uid);
                                hn.put("nurseNic", nic);
                                hn.put("nursePhone", userPhone);
                                h.getReference().set(hn, SetOptions.merge());
                                persist.run();
                            })
                            .addOnFailureListener(e -> abortNurseSignup("Could not check hospital: " + e.getMessage()));
                });
    }

    /** Hospital missing / already has a nurse -> remove the just-created login again. */
    private void abortNurseSignup(String message) {
        if (mAuth.getCurrentUser() != null) {
            mAuth.getCurrentUser().delete();
        }
        mAuth.signOut();
        signupBtn.setEnabled(true);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void applyRoleFields(int checkedId) {
        boolean patient = checkedId == R.id.rbPatient;
        boolean nurse = checkedId == R.id.rbNurse;
        boolean ambulance = checkedId == R.id.rbAmbulance;
        boolean hospital = checkedId == R.id.rbHospital;

        layoutName.setVisibility(patient ? View.VISIBLE : View.GONE);
        layoutEmail.setVisibility(patient ? View.VISIBLE : View.GONE);
        layoutPhone.setVisibility(patient || nurse ? View.VISIBLE : View.GONE);
        layoutRelative.setVisibility(patient ? View.VISIBLE : View.GONE);
        layoutNic.setVisibility(nurse ? View.VISIBLE : View.GONE);
        layoutVehicle.setVisibility(ambulance ? View.VISIBLE : View.GONE);
        layoutHospital.setVisibility(hospital || nurse || ambulance ? View.VISIBLE : View.GONE);
    }
}
