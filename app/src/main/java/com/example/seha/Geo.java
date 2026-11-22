package com.example.seha;

import android.location.Location;

import java.util.Locale;

/** Small distance / time formatting helpers. */
public final class Geo {

    private Geo() { }

    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        float[] r = new float[1];
        Location.distanceBetween(lat1, lng1, lat2, lng2, r);
        return r[0];
    }

    public static String dist(double meters) {
        if (meters < 1000) return ((int) Math.round(meters)) + " m";
        return String.format(Locale.US, "%.1f km", meters / 1000.0);
    }

    public static String eta(double seconds) {
        int min = (int) Math.ceil(seconds / 60.0);
        if (min < 1) return "< 1 min";
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
