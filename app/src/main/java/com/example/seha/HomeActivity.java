package com.example.seha;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

public class HomeActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    DrawerLayout drawerLayout;
    NavigationView navigationView;
    ImageView btnMenu;

    TextView tvUserName;
    CardView cardPharmacy, cardInform, cardEditProfile, cardAmbulance, cardSos;
    SwitchCompat switchShare;
    boolean suppressShare = false;

    // location permission for "Share my live location"
    private final ActivityResultLauncher<String[]> sharePerm =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (LiveShare.hasPermission(this)) {
                    enableShare();
                } else {
                    setShareSwitch(false);
                    Toast.makeText(this, "Location permission is needed to share your location",
                            Toast.LENGTH_LONG).show();
                }
            });
    ImageButton btnHome, btnOrders, btnAccount;
    ImageView btnBack;

    FirebaseAuth auth;
    FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        btnMenu = findViewById(R.id.btnMenu);

        tvUserName = findViewById(R.id.tvUserName);
        cardPharmacy = findViewById(R.id.cardPharmacy);
        cardInform = findViewById(R.id.cardInform);
        cardEditProfile = findViewById(R.id.cardEditProfile);
        cardAmbulance = findViewById(R.id.cardAmbulance);

        btnHome = findViewById(R.id.imageButton);
        btnOrders = findViewById(R.id.imageButton2);
        btnAccount = findViewById(R.id.imageButton3);
        btnBack = findViewById(R.id.imageView3);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        // ☰ → sidebar open
        if (btnMenu != null && drawerLayout != null) {
            btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        }

        if (navigationView != null) {
            navigationView.setNavigationItemSelectedListener(this);
        }

        loadUserName();

        // Cards
        if (cardPharmacy != null) {
            cardPharmacy.setOnClickListener(v ->
                    startActivity(new Intent(HomeActivity.this, MyMedicineActivity.class)));
        }
        if (cardInform != null) {
            cardInform.setOnClickListener(v ->
                    startActivity(new Intent(HomeActivity.this, FaceScanActivity.class)));
        }
        if (cardEditProfile != null) {
            cardEditProfile.setOnClickListener(v ->
                    startActivity(new Intent(HomeActivity.this, EditProfileActivity.class)));
        }
        android.view.View cardRecords = findViewById(R.id.cardRecords);
        if (cardRecords != null) cardRecords.setOnClickListener(v -> openMyRecords());
        if (cardAmbulance != null) {
            cardAmbulance.setOnClickListener(v ->
                    startActivity(new Intent(HomeActivity.this, PatientAmbulanceActivity.class)));
        }

        // SOS + live location sharing
        cardSos = findViewById(R.id.cardSos);
        if (cardSos != null) {
            cardSos.setOnClickListener(v -> startActivity(new Intent(HomeActivity.this, SosActivity.class)));
        }
        switchShare = findViewById(R.id.switchShareLoc);
        if (switchShare != null) {
            setShareSwitch(LiveShare.isEnabledPref(this) && LiveShare.hasPermission(this));
            switchShare.setOnCheckedChangeListener((btn, on) -> {
                if (suppressShare) return;
                if (on) enableShare();
                else disableShare();
            });
            if (switchShare.isChecked()) LiveShare.get(this).start(LiveShare.REASON_MANUAL);
        }

        // Bottom nav
        if (btnHome != null) {
            btnHome.setOnClickListener(v -> {
                // already on Home
            });
        }
        if (btnOrders != null) {
            btnOrders.setOnClickListener(v -> {
                Intent intent = new Intent(HomeActivity.this, MyOrdersActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            });
        }
        if (btnAccount != null) {
            btnAccount.setOnClickListener(v ->
                    startActivity(new Intent(HomeActivity.this, Profile.class)));
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }
    }

    private void setShareSwitch(boolean on) {
        if (switchShare == null) return;
        suppressShare = true;
        switchShare.setChecked(on);
        suppressShare = false;
    }

    private void enableShare() {
        if (!LiveShare.hasPermission(this)) {
            sharePerm.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        if (LiveShare.get(this).start(LiveShare.REASON_MANUAL)) {
            LiveShare.setEnabledPref(this, true);
            setShareSwitch(true);
            Toast.makeText(this, "Your guardian can now see your live location", Toast.LENGTH_SHORT).show();
        } else {
            setShareSwitch(false);
            Toast.makeText(this, "Could not start location sharing. Please log in again.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void disableShare() {
        LiveShare.setEnabledPref(this, false);
        LiveShare.get(this).stop(LiveShare.REASON_MANUAL);
        Toast.makeText(this, "Location sharing stopped", Toast.LENGTH_SHORT).show();
    }

    private void openMyRecords() {
        if (auth.getCurrentUser() == null) return;
        Intent i = new Intent(this, PatientRecordsActivity.class);
        i.putExtra(PatientRecordsActivity.EXTRA_PATIENT_UID, auth.getCurrentUser().getUid());
        i.putExtra(PatientRecordsActivity.EXTRA_READ_ONLY, true);
        startActivity(i);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_home) {
            // already home
        } else if (id == R.id.nav_medicine) {
            startActivity(new Intent(this, MyMedicineActivity.class));
        } else if (id == R.id.nav_orders) {
            startActivity(new Intent(this, MyOrdersActivity.class));
        } else if (id == R.id.nav_records) {
            openMyRecords();
        } else if (id == R.id.nav_profile) {
            startActivity(new Intent(this, Profile.class));
        } else if (id == R.id.nav_edit_profile) {
            startActivity(new Intent(this, EditProfileActivity.class));
        } else if (id == R.id.nav_face_scan) {
            startActivity(new Intent(this, FaceScanActivity.class));
        } else if (id == R.id.nav_sos) {
            startActivity(new Intent(this, SosActivity.class));
        } else if (id == R.id.nav_ambulance) {
            startActivity(new Intent(this, PatientAmbulanceActivity.class));
        } else if (id == R.id.nav_logout) {
            LiveShare.get(this).stop(LiveShare.REASON_MANUAL);
            LiveShare.get(this).stop(LiveShare.REASON_SOS);
            auth.signOut();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        }

        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        return true;
    }

    private void loadUserName() {
        if (auth.getCurrentUser() == null) return;

        String uid = auth.getCurrentUser().getUid();
        Fs.ensurePatientCode(uid, c -> { });   // old accounts get their Patient ID too
        db.collection("Patients").document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists() && tvUserName != null) {
                        String name = doc.getString("patientName");
                        if (name != null && !name.isEmpty()) {
                            tvUserName.setText(name);
                        }
                    }
                });
    }

    private void setupBackPress() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }
}
