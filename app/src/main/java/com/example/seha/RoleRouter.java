package com.example.seha;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

/** Decides which screen to open after login / app start, based on the user's role. */
public class RoleRouter {

    public static final String ROLE_NURSE = "nurse";
    public static final String ROLE_PATIENT = "patient";
    public static final String ROLE_AMBULANCE = "ambulance";
    public static final String ROLE_HOSPITAL = "hospital";
    private static final String PREFS = "seha_prefs";

    public static void cacheRole(Context ctx, String role) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putString("role", role).apply();
    }

    public static String cachedRole(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("role", null);
    }

    /** Opens the right screen and finishes the calling activity. */
    public static void route(Activity from) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            open(from, LoginActivity.class);
            return;
        }

        FirebaseFirestore.getInstance().collection("Patients")
                .document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    String role = doc.exists() ? doc.getString("role") : null;
                    if (role == null) role = ROLE_PATIENT;
                    cacheRole(from, role);

                    if (ROLE_AMBULANCE.equals(role)) {
                        openMap(from, ROLE_AMBULANCE);
                    } else if (ROLE_HOSPITAL.equals(role)) {
                        openMap(from, ROLE_HOSPITAL);
                    } else if (ROLE_NURSE.equals(role)) {
                        openMap(from, ROLE_NURSE);
                    } else {
                        String name = doc.exists() ? doc.getString("patientName") : null;
                        boolean profileDone = name != null && !name.trim().isEmpty();
                        open(from, profileDone ? HomeActivity.class : EditProfileActivity.class);
                    }
                })
                .addOnFailureListener(e -> {
                    String cached = cachedRole(from);
                    if (ROLE_AMBULANCE.equals(cached)) openMap(from, ROLE_AMBULANCE);
                    else if (ROLE_HOSPITAL.equals(cached)) openMap(from, ROLE_HOSPITAL);
                    else if (ROLE_NURSE.equals(cached)) openMap(from, ROLE_NURSE);
                    else open(from, HomeActivity.class);
                });
    }

    /** Open ambulance tracking map for nurse or ambulance (no role dialog). */
    public static void openMap(Activity from, String mapRole) {
        Class<?> target;
        if (ROLE_AMBULANCE.equals(mapRole)) target = AmbulanceDashboardActivity.class;
        else if (ROLE_HOSPITAL.equals(mapRole)) target = HospitalDashboardActivity.class;
        else if (ROLE_NURSE.equals(mapRole)) target = NurseDashboardActivity.class;
        else target = AmbulanceTrackActivity.class;
        Intent i = new Intent(from, target);
        i.putExtra("role", mapRole);
        i.putExtra("fromLogin", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        from.startActivity(i);
        from.finish();
    }

    /** @deprecated use openMap(from, ROLE_NURSE) */
    public static void openNurse(Activity from) {
        openMap(from, ROLE_NURSE);
    }

    private static void open(Activity from, Class<?> target) {
        Intent i = new Intent(from, target);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        from.startActivity(i);
        from.finish();
    }
}
