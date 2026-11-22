package com.example.seha;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

public class Profile extends BaseDrawerActivity {

    private boolean isOwn = false;
    private java.io.File cameraFile;

    private final androidx.activity.result.ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                try {
                    saveProfilePhoto(ImageUtil.decodeUri(this, uri, 400));
                } catch (Exception e) {
                    Toast.makeText(this, "Could not read image: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    private final androidx.activity.result.ActivityResultLauncher<android.net.Uri> cameraLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.TakePicture(), ok -> {
                if (!ok || cameraFile == null) return;
                try {
                    saveProfilePhoto(ImageUtil.decodeFile(cameraFile, 400));
                } catch (Exception e) {
                    Toast.makeText(this, "Could not read photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    private final androidx.activity.result.ActivityResultLauncher<String> cameraPerm =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchCamera();
                else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            });

    TextView txtPatientName, txtAge, txtBloodGroup, txtAllergies, txtChronic;
    Button btnHome, btnEdit;
    ImageView btnBack, imgProfile;

    FirebaseFirestore db;
    FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);

        txtPatientName = findViewById(R.id.txtPatientName);
        txtAge = findViewById(R.id.txtAge);
        txtBloodGroup = findViewById(R.id.txtBloodGroup);
        txtAllergies = findViewById(R.id.txtAllergies);
        txtChronic = findViewById(R.id.txtChronic);

        btnHome = findViewById(R.id.btnHome);
        btnEdit = findViewById(R.id.btnEdit);
        btnBack = findViewById(R.id.btnBack);
        imgProfile = findViewById(R.id.imageView2);

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        String person = getIntent().getStringExtra("person");

        if (imgProfile != null) imgProfile.setOnClickListener(v -> choosePhotoSource());
        android.view.View hint = findViewById(R.id.txtChangePhoto);

        if (person != null && !person.trim().isEmpty()) {
            if (hint != null) hint.setVisibility(android.view.View.GONE);
            loadByName(person.trim());
        } else {
            loadByUid();
        }

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        if (btnHome != null) {
            btnHome.setOnClickListener(v -> {
                Intent intent = new Intent(Profile.this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            });
        }

        if (btnEdit != null) {
            btnEdit.setOnClickListener(v -> {
                Intent intent = new Intent(Profile.this, EditProfileActivity.class);
                startActivity(intent);
            });
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void loadByName(String name) {
        db.collection("Patients")
                .whereEqualTo("patientName", name)
                .get()
                .addOnSuccessListener(query -> {
                    if (!query.isEmpty()) {
                        setData(query.getDocuments().get(0));
                    } else {
                        Toast.makeText(this, "Patient Not Found", Toast.LENGTH_LONG).show();
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void loadByUid() {
        if (auth.getCurrentUser() == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        isOwn = true;
        String uid = auth.getCurrentUser().getUid();
        db.collection("Patients").document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        setData(doc);
                    } else {
                        Toast.makeText(this, "No profile found", Toast.LENGTH_LONG).show();
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void setData(DocumentSnapshot doc) {
        txtPatientName.setText(safe(doc.getString("patientName")));
        txtAge.setText(safe(doc.getString("age")));
        txtBloodGroup.setText(safe(doc.getString("bloodGroup")));
        txtAllergies.setText(safe(doc.getString("allergies")));
        txtChronic.setText(safe(doc.getString("chronicConditions")));

        showPhoto(doc);
    }

    /** Photo saved in Firestore (works on every phone / network). Old accounts: photo from the face server. */
    private void showPhoto(DocumentSnapshot doc) {
        if (imgProfile == null) return;
        String b64 = doc.getString("profilePhotoB64");
        if (b64 != null && !b64.isEmpty()) {
            try {
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                Glide.with(this).load(bytes).placeholder(R.drawable.person).circleCrop().into(imgProfile);
                return;
            } catch (Exception ignored) { }
        }
        String personName = doc.getString("patientName");
        if (personName != null && !personName.isEmpty()) {
            String photoUrl = AppConfig.faceBase(this) + "/photo/" + personName.replace(" ", "%20");
            Glide.with(this).load(photoUrl).placeholder(R.drawable.person)
                    .error(R.drawable.person).circleCrop().into(imgProfile);
        }
    }

    private void choosePhotoSource() {
        if (!isOwn) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Profile photo")
                .setItems(new String[]{"📷 Take a photo", "🖼 Choose from gallery"}, (d, which) -> {
                    if (which == 0) {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                                android.Manifest.permission.CAMERA)
                                == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera();
                        else cameraPerm.launch(android.Manifest.permission.CAMERA);
                    } else {
                        galleryLauncher.launch("image/*");
                    }
                })
                .show();
    }

    private void launchCamera() {
        try {
            cameraFile = java.io.File.createTempFile("profile_", ".jpg", getCacheDir());
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", cameraFile);
            cameraLauncher.launch(uri);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open camera", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveProfilePhoto(android.graphics.Bitmap bmp) {
        if (auth.getCurrentUser() == null) return;
        String b64 = ImageUtil.toBase64(bmp, 80);
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("profilePhotoB64", b64);
        db.collection("Patients").document(auth.getCurrentUser().getUid())
                .set(m, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(v -> {
                    Toast.makeText(this, "Profile photo updated", Toast.LENGTH_SHORT).show();
                    if (imgProfile != null) {
                        Glide.with(this).load(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                                .circleCrop().into(imgProfile);
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Could not save photo: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private String safe(String value) {
        return (value == null || value.trim().isEmpty()) ? "—" : value;
    }
}