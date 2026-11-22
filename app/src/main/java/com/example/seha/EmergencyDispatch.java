package com.example.seha;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Map;

/**
 * "Send the nearest ambulance" for any person (registered patient or unknown):
 * gets the phone's location, creates the request (the nearest ambulance is notified
 * inside the app with a sound) and opens the live trip screen.
 * The caller must already have location permission (see {@link #hasLocationPermission}).
 */
public final class EmergencyDispatch {

    private EmergencyDispatch() { }

    public static boolean hasLocationPermission(Activity a) {
        return ContextCompat.checkSelfPermission(a, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(a, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public interface Done { void run(); }

    /**
     * @param fields   patient info (patientName, condition, patientUid, ...) - location is added here
     * @param role     TripMapActivity viewer role (patient / hospital)
     * @param finished called when the request was created or something failed (re-enable buttons)
     */
    public static void run(Activity a, Map<String, Object> fields, String role, Done finished) {
        if (!hasLocationPermission(a)) {
            Toast.makeText(a, "Location permission is needed", Toast.LENGTH_LONG).show();
            if (finished != null) finished.run();
            return;
        }
        Toast.makeText(a, "Getting your location...", Toast.LENGTH_SHORT).show();
        Fs.ensureSignedIn(() -> {
            FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(a);
            try {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,
                                new CancellationTokenSource().getToken())
                        .addOnSuccessListener(loc -> {
                            if (loc != null) {
                                create(a, fields, role, loc.getLatitude(), loc.getLongitude(), finished);
                            } else {
                                try {
                                    fused.getLastLocation().addOnSuccessListener(l2 -> {
                                        if (l2 != null) {
                                            create(a, fields, role, l2.getLatitude(), l2.getLongitude(), finished);
                                        } else {
                                            fail(a, "Could not get location. Turn on GPS.", finished);
                                        }
                                    });
                                } catch (SecurityException e) {
                                    fail(a, "Location permission missing", finished);
                                }
                            }
                        })
                        .addOnFailureListener(e -> fail(a, "Location error: " + e.getMessage(), finished));
            } catch (SecurityException e) {
                fail(a, "Location permission missing", finished);
            }
        });
    }

    private static void fail(Activity a, String msg, Done finished) {
        Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
        if (finished != null) finished.run();
    }

    private static void create(Activity a, Map<String, Object> fields, String role,
                               double lat, double lng, Done finished) {
        fields.put("requesterUid", Fs.me() != null ? Fs.me() : "");
        fields.put("pickupLat", lat);
        fields.put("pickupLng", lng);
        String id = DispatchHelper.createRequest(FirebaseFirestore.getInstance(), fields);
        if (finished != null) finished.run();
        Intent i = new Intent(a, TripMapActivity.class);
        i.putExtra(TripMapActivity.EXTRA_REQUEST_ID, id);
        i.putExtra(TripMapActivity.EXTRA_ROLE, role);
        a.startActivity(i);
    }
}
