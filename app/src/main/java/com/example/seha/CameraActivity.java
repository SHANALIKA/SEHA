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
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

public class CameraActivity extends AppCompatActivity {


    private static final String SERVER_IP = "192.168.81.92";

    TextView txtPatientName, txtAge, txtBloodGroup, txtAllergies, txtChronic;
    Button btnHome, btnEdit;
    ImageView btnBack, imgProfile;

    FirebaseFirestore db;

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

        String person = getIntent().getStringExtra("person");

        if (person == null || person.trim().isEmpty()) {
            Toast.makeText(this, "No Person Found", Toast.LENGTH_SHORT).show();
        } else {
            loadPatient(person.trim());
        }

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        if (btnHome != null) {
            btnHome.setOnClickListener(v -> {
                Intent intent = new Intent(CameraActivity.this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            });
        }

        if (btnEdit != null) {
            btnEdit.setOnClickListener(v ->
                    Toast.makeText(this, "Edit not available in emergency mode", Toast.LENGTH_SHORT).show());
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void loadPatient(String name) {
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

    private void setData(DocumentSnapshot doc) {
        txtPatientName.setText(safe(doc.getString("patientName")));
        txtAge.setText(safe(doc.getString("age")));
        txtBloodGroup.setText(safe(doc.getString("bloodGroup")));
        txtAllergies.setText(safe(doc.getString("allergies")));
        txtChronic.setText(safe(doc.getString("chronicConditions")));

        // Profile photo – DeepFace server එකෙන්
        String personName = doc.getString("patientName");
        if (personName != null && !personName.isEmpty() && imgProfile != null) {
            String photoUrl = "http://" + SERVER_IP + ":5000/photo/" + personName.replace(" ", "%20");
            Glide.with(this)
                    .load(photoUrl)
                    .placeholder(R.drawable.person)
                    .error(R.drawable.person)
                    .circleCrop()
                    .into(imgProfile);
        }
    }

    private String safe(String value) {
        return (value == null || value.trim().isEmpty()) ? "—" : value;
    }
}