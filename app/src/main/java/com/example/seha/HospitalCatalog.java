package com.example.seha;

import android.content.Context;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Government hospitals of the combo box (res/values/hospitals.xml).
 * Entries marked "e" have an EXACT verified position. The others only have an approximate town position:
 * their real position is looked up from OpenStreetMap (see {@link HospitalFinder#resolve}).
 */
public final class HospitalCatalog {

    public static class Entry {
        public String name;
        public double lat;
        public double lng;
        /** true = verified exact position, false = only a hint (town centre) */
        public boolean exact;
    }

    private HospitalCatalog() { }

    public static List<Entry> all(Context c) {
        List<Entry> list = new ArrayList<>();
        String[] items = c.getResources().getStringArray(R.array.hospital_catalog);
        for (int i = 1; i < items.length; i++) {          // item 0 = "Select hospital"
            String[] p = items[i].split("\\|");
            if (p.length < 3) continue;
            try {
                Entry e = new Entry();
                e.name = p[0].trim();
                e.lat = Double.parseDouble(p[1].trim());
                e.lng = Double.parseDouble(p[2].trim());
                e.exact = p.length >= 4 && p[3].trim().equalsIgnoreCase("e");
                list.add(e);
            } catch (NumberFormatException ignored) { }
        }
        return list;
    }

    /** "Select hospital" followed by the hospital names (A-Z) - what the spinner shows. */
    public static String[] labels(Context c) {
        List<Entry> all = all(c);
        List<String> names = new ArrayList<>();
        for (Entry e : all) names.add(e.name);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        String[] out = new String[names.size() + 1];
        out[0] = c.getResources().getStringArray(R.array.hospital_catalog)[0];
        for (int i = 0; i < names.size(); i++) out[i + 1] = names.get(i);
        return out;
    }

    /**
     * Position of a hospital document.
     *  - exact catalog entry  -> the verified position always wins (wrong old values are ignored)
     *  - other hospitals      -> the position saved in Firestore (resolved from OpenStreetMap / chosen
     *                            by the hospital), else the approximate town position
     */
    public static double[] position(Context c, DocumentSnapshot d) {
        Entry e = find(c, d.getString("name"));
        if (e != null && e.exact) return new double[]{e.lat, e.lng};
        Double la = d.getDouble("lat");
        Double lo = d.getDouble("lng");
        if (la != null && lo != null && (la != 0.0 || lo != 0.0)) return new double[]{la, lo};
        if (e != null) return new double[]{e.lat, e.lng};
        return null;
    }

    public static Entry find(Context c, String name) {
        if (name == null) return null;
        for (Entry e : all(c)) if (e.name.equalsIgnoreCase(name.trim())) return e;
        return null;
    }
}
