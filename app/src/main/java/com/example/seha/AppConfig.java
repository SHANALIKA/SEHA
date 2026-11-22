package com.example.seha;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/**
 * One place for the face-recognition server address.
 * For the demo the laptop IP changes with every Wi-Fi/hotspot, so it can be changed
 * inside the app (long-press the status text on the Face Scan screen) without rebuilding.
 */
public final class AppConfig {

    /** Fallback address if nothing was saved in the app. */
    public static final String DEFAULT_FACE_BASE = "http://192.168.81.92:5000";

    private static final String PREFS = "seha_prefs";
    private static final String KEY_FACE_BASE = "face_base";

    private AppConfig() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** e.g. http://192.168.1.10:5000 (no trailing slash) */
    public static String faceBase(Context c) {
        String s = prefs(c).getString(KEY_FACE_BASE, DEFAULT_FACE_BASE);
        if (s == null || s.trim().isEmpty()) s = DEFAULT_FACE_BASE;
        s = s.trim();
        boolean again = true;
        while (again) {
            again = false;
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            for (String suffix : new String[]{"/recognize", "/register"}) {
                if (s.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
                    s = s.substring(0, s.length() - suffix.length());
                    again = true;
                }
            }
        }
        return s;
    }

    /** Human readable explanation when the server answered with a web page / non-JSON. */
    public static String describeBadReply(int code, String body, String url) {
        boolean html = body != null && (body.trim().toLowerCase(java.util.Locale.ROOT).startsWith("<!doctype")
                || body.trim().toLowerCase(java.util.Locale.ROOT).startsWith("<html"));
        String why;
        if (code == 404) why = "Wrong address (page not found). Use only the base link, without /recognize.";
        else if (code == 502 || code == 503 || code == 504 || code == 530 || code == 1033)
            why = "The tunnel is up but the face server (python app.py) is not running, or the link is old.";
        else if (html) why = "A web page came back instead of the face server answer. The tunnel link is probably old/expired.";
        else why = "The server sent an unexpected reply.";
        return "Server problem (HTTP " + code + "):\n" + why
                + "\nChecklist: 1) python app.py running  2) cloudflared running  3) paste the NEW link"
                + " (long-press the status text)\nURL: " + url;
    }

    public static String recognizeUrl(Context c) { return faceBase(c) + "/recognize"; }

    public static String registerUrl(Context c) { return faceBase(c) + "/register"; }

    public static void promptFaceServer(Activity a) {
        final EditText et = new EditText(a);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(faceBase(a));
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(a)
                .setTitle("Face server address")
                .setMessage("Example: http://192.168.1.10:5000\n(Phone and laptop must be on the same Wi-Fi / hotspot)")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String v = et.getText().toString().trim();
                    if (!v.startsWith("http://") && !v.startsWith("https://")) {
                        v = "http://" + v;
                    }
                    prefs(a).edit().putString(KEY_FACE_BASE, v).apply();
                    Toast.makeText(a, "Saved: " + faceBase(a), Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Reset", (d, w) -> {
                    prefs(a).edit().remove(KEY_FACE_BASE).apply();
                    Toast.makeText(a, "Reset to default", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
