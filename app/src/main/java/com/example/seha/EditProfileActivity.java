package com.example.seha;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class EditProfileActivity extends BaseDrawerActivity {

    // ★★★ ඔයාගේ Laptop IP එක ★★★

    Spinner spBloodGroup;
    EditText etPatientName, etAge, etAllergies, etChronic, etRelativePhone;
    Button btnSave, btnScanFace;
    ImageView btnBack;

    ArrayAdapter<CharSequence> bloodAdapter;

    FirebaseFirestore db;
    FirebaseAuth auth;

    File photoFile;
    Uri photoUri;
    OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(240, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (success && photoFile != null && photoFile.exists()) {
                    uploadFaceToServer();
                } else {
                    Toast.makeText(this, "Photo capture cancelled", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    openCameraForRegister();
                } else {
                    Toast.makeText(this, "Camera permission needed", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_edit_profile);

        spBloodGroup = findViewById(R.id.spBloodGroup);
        etPatientName = findViewById(R.id.editTextText);
        etAge = findViewById(R.id.editTextNumber);
        etAllergies = findViewById(R.id.editTextTextMultiLine2);
        etChronic = findViewById(R.id.editTextTextMultiLine3);
        etRelativePhone = findViewById(R.id.editTextRelativePhone);
        btnSave = findViewById(R.id.button7);
        btnScanFace = findViewById(R.id.btnScanFace);
        btnBack = findViewById(R.id.imageView3);

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        bloodAdapter = ArrayAdapter.createFromResource(
                this, R.array.blood_groups, android.R.layout.simple_spinner_item);
        bloodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBloodGroup.setAdapter(bloodAdapter);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        // long-press = change the face server address (laptop IP / tunnel link)
        btnScanFace.setOnLongClickListener(v -> {
            AppConfig.promptFaceServer(this);
            return true;
        });
        btnScanFace.setOnClickListener(v -> {
            String name = etPatientName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "First enter Patient Name", Toast.LENGTH_SHORT).show();
                etPatientName.requestFocus();
                return;
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                openCameraForRegister();
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnSave.setOnClickListener(v -> savePatientDetails());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        loadExistingDetails();
    }

    // Pre-fill the form with what is already saved (including relative's number)
    private void loadExistingDetails() {
        if (auth.getCurrentUser() == null) return;

        db.collection("Patients").document(auth.getCurrentUser().getUid())
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc == null || !doc.exists()) return;

                    fillIfEmpty(etPatientName, doc.getString("patientName"));
                    fillIfEmpty(etAge, doc.getString("age"));
                    fillIfEmpty(etAllergies, doc.getString("allergies"));
                    fillIfEmpty(etChronic, doc.getString("chronicConditions"));
                    fillIfEmpty(etRelativePhone, doc.getString("relativePhone"));

                    String blood = doc.getString("bloodGroup");
                    if (blood != null && !blood.isEmpty() && bloodAdapter != null) {
                        int pos = bloodAdapter.getPosition(blood);
                        if (pos >= 0) spBloodGroup.setSelection(pos);
                    }
                });
    }

    private void fillIfEmpty(EditText et, String value) {
        if (value != null && !value.isEmpty() && et.getText().toString().trim().isEmpty()) {
            et.setText(value);
        }
    }

    private void openCameraForRegister() {
        try {
            photoFile = File.createTempFile("face_", ".jpg", getCacheDir());
            photoUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    photoFile
            );
            takePictureLauncher.launch(photoUri);
        } catch (IOException e) {
            Toast.makeText(this, "Cannot create file", Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadFaceToServer() {
        String personName = etPatientName.getText().toString().trim();
        Toast.makeText(this, "Uploading face...", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                // smaller upright copy = much faster upload through the tunnel
                File upload = ImageUtil.prepareForUpload(this, photoFile, 1000, 88);
                RequestBody fileBody = RequestBody.create(upload, MediaType.parse("image/jpeg"));

                MultipartBody body = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("image", upload.getName(), fileBody)
                        .addFormDataPart("person_name", personName)
                        .build();

                Request request = new Request.Builder()
                        .url(AppConfig.registerUrl(this))
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String json = response.body() != null ? response.body().string() : "";

                runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        Toast.makeText(this, "Face Registered on Server!", Toast.LENGTH_SHORT).show();
                        uploadToFirebaseStorage(personName);
                    } else {
                        Toast.makeText(this, AppConfig.describeBadReply(response.code(), json,
                                AppConfig.registerUrl(this)), Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Cannot reach face server (" + AppConfig.faceBase(this) + "). Is it running? Long-press the Scan Face button to change the address.\n" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // Photo eka DeepFace server eke save wenawa, Firebase Storage ona nae.
    // Firestore ekata nama withari save karanawa.
    private void uploadToFirebaseStorage(String personName) {
        if (auth.getCurrentUser() == null) {
            Toast.makeText(this, "Login first to save profile", Toast.LENGTH_SHORT).show();
            return;
        }

        String uid = auth.getCurrentUser().getUid();

        Map<String, Object> update = new HashMap<>();
        update.put("patientName", personName);
        // the registered face photo also becomes the profile picture (stored in Firestore)
        try {
            if (photoFile != null && photoFile.exists()) {
                update.put("profilePhotoB64",
                        ImageUtil.toBase64(ImageUtil.decodeFile(photoFile, 400), 80));
            }
        } catch (Exception ignored) { }

        db.collection("Patients").document(uid)
                .set(update, SetOptions.merge())
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, "Profile saved!", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Firestore error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void savePatientDetails() {
        String patientName = etPatientName.getText().toString().trim();
        String age = etAge.getText().toString().trim();
        String bloodGroup = spBloodGroup.getSelectedItem().toString();
        String allergies = etAllergies.getText().toString().trim();
        String chronic = etChronic.getText().toString().trim();
        String relativePhone = etRelativePhone.getText().toString().replaceAll("\\s+", "");

        if (patientName.isEmpty() || age.isEmpty() || allergies.isEmpty() || chronic.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bloodGroup.equals("Select Blood Group")) {
            Toast.makeText(this, "Please select a blood group", Toast.LENGTH_SHORT).show();
            return;
        }

        if (relativePhone.isEmpty()) {
            etRelativePhone.setError("Enter relative's phone number");
            etRelativePhone.requestFocus();
            return;
        }

        if (relativePhone.replaceAll("[^0-9]", "").length() < 9) {
            etRelativePhone.setError("Enter a valid phone number");
            etRelativePhone.requestFocus();
            return;
        }

        if (auth.getCurrentUser() == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        String uid = auth.getCurrentUser().getUid();

        Map<String, Object> patient = new HashMap<>();
        patient.put("patientName", patientName);
        patient.put("age", age);
        patient.put("bloodGroup", bloodGroup);
        patient.put("allergies", allergies);
        patient.put("chronicConditions", chronic);
        patient.put("relativePhone", relativePhone);

        db.collection("Patients")
                .document(uid)
                .set(patient, SetOptions.merge())
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, "Details Saved Successfully", Toast.LENGTH_SHORT).show();

                    Intent intent = new Intent(EditProfileActivity.this, Profile.class);
                    intent.putExtra("person", patientName);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}