package com.example.seha;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DELAY = 2500; // 2.5 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // Already logged in -> straight to Home, otherwise Login page
            boolean loggedIn = FirebaseAuth.getInstance().getCurrentUser() != null;

            Intent intent = new Intent(SplashActivity.this,
                    loggedIn ? HomeActivity.class : LoginActivity.class);
            startActivity(intent);
            finish(); // remove Splash from back stack
        }, SPLASH_DELAY);
    }
}