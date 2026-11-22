package com.example.seha;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.firebase.auth.FirebaseAuth;
import com.google.android.material.navigation.NavigationView;

/**
 * Base activity with side drawer.
 * Uses OnBackPressedDispatcher (required for modern AndroidX / SDK 34+).
 * Do NOT override deprecated onBackPressed() — it causes runtime errors.
 */
public abstract class BaseDrawerActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    protected DrawerLayout drawerLayout;
    protected NavigationView navigationView;
    private boolean backCallbackRegistered = false;

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        super.setContentView(R.layout.activity_base_drawer);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);

        FrameLayout container = findViewById(R.id.content_container);
        getLayoutInflater().inflate(layoutResID, container, true);

        if (navigationView != null) {
            navigationView.setNavigationItemSelectedListener(this);
        }

        View btnMenu = container.findViewById(R.id.btnMenu);
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> openDrawer());
        }

        registerBackHandler();
    }

    /** Close drawer on back, otherwise finish activity — no deprecated onBackPressed. */
    private void registerBackHandler() {
        if (backCallbackRegistered) return;
        backCallbackRegistered = true;

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    closeDrawer();
                } else {
                    // Disable this callback and let system handle back (finish)
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    protected void openDrawer() {
        if (drawerLayout != null) {
            drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    protected void closeDrawer() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_home) {
            goTo(HomeActivity.class);
        } else if (id == R.id.nav_medicine) {
            goTo(MyMedicineActivity.class);
        } else if (id == R.id.nav_orders) {
            goTo(MyOrdersActivity.class);
        } else if (id == R.id.nav_records) {
            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                Intent ri = new Intent(this, PatientRecordsActivity.class);
                ri.putExtra(PatientRecordsActivity.EXTRA_PATIENT_UID,
                        FirebaseAuth.getInstance().getCurrentUser().getUid());
                ri.putExtra(PatientRecordsActivity.EXTRA_READ_ONLY, true);
                startActivity(ri);
            }
        } else if (id == R.id.nav_profile) {
            goTo(Profile.class);
        } else if (id == R.id.nav_edit_profile) {
            goTo(EditProfileActivity.class);
        } else if (id == R.id.nav_face_scan) {
            goTo(FaceScanActivity.class);
        } else if (id == R.id.nav_ambulance) {
            goTo(PatientAmbulanceActivity.class);
        } else if (id == R.id.nav_logout) {
            FirebaseAuth.getInstance().signOut();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        }

        closeDrawer();
        return true;
    }

    private void goTo(Class<?> cls) {
        if (this.getClass().equals(cls)) return;
        startActivity(new Intent(this, cls));
    }
}
