package com.example.seha;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HOSPITAL: update which ambulances are available. The vehicle number is the ambulance ID.
 * Ambulances that are available AND whose driver is online show up as dispatchable.
 */
public class HospitalAmbulancesActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private String uid;
    private EditText etVehicle;
    private TextView txtEmpty;
    private final List<DocumentSnapshot> items = new ArrayList<>();
    private AmbAdapter adapter;
    private ListenerRegistration listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_hospital_ambulances);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            float d = getResources().getDisplayMetrics().density;
            v.setPadding(s.left + (int) (20 * d), s.top + (int) (12 * d),
                    s.right + (int) (20 * d), s.bottom + (int) (12 * d));
            return insets;
        });

        db = FirebaseFirestore.getInstance();
        uid = Fs.me();
        if (uid == null) {
            finish();
            return;
        }

        etVehicle = findViewById(R.id.etVehicle);
        txtEmpty = findViewById(R.id.txtEmpty);
        Button btnAdd = findViewById(R.id.btnAdd);
        ListView lv = findViewById(R.id.lvAmb);
        ((ImageView) findViewById(R.id.btnBack)).setOnClickListener(v -> finish());

        adapter = new AmbAdapter(this);
        lv.setAdapter(adapter);

        // ambulances register themselves under their hospital - nothing to add by hand
        ((View) etVehicle.getParent()).setVisibility(View.GONE);

        listener = db.collection(Fs.AMBULANCES).whereEqualTo("hospitalId", uid)
                .addSnapshotListener((qs, e) -> {
                    if (qs == null) return;
                    items.clear();
                    items.addAll(qs.getDocuments());
                    Collections.sort(items, (a, b) -> a.getId().compareTo(b.getId()));
                    txtEmpty.setText("No ambulance is registered under this hospital yet.");
                    txtEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    adapter.notifyDataSetChanged();
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (listener != null) listener.remove();
    }

    private void addVehicle() {
        String raw = etVehicle.getText().toString().trim().toUpperCase();
        String id = Fs.vehicleId(raw);
        if (id.isEmpty()) {
            etVehicle.setError("Enter the vehicle number");
            return;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("vehicleNumber", raw);
        m.put("hospitalId", uid);
        m.put("available", true);
        db.collection(Fs.AMBULANCES).document(id).set(m, SetOptions.merge())
                .addOnSuccessListener(v -> {
                    etVehicle.setText("");
                    Toast.makeText(this, raw + " added", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void confirmRemove(DocumentSnapshot d) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + d.getId() + "?")
                .setMessage("It will no longer be listed under this hospital.")
                .setPositiveButton("Remove", (dd, w) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("hospitalId", FieldValue.delete());
                    db.collection(Fs.AMBULANCES).document(d.getId()).set(m, SetOptions.merge());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setAvailable(String id, boolean available) {
        Map<String, Object> m = new HashMap<>();
        m.put("available", available);
        db.collection(Fs.AMBULANCES).document(id).set(m, SetOptions.merge());
    }

    // ------------------------------------------------------------------------------------

    private class AmbAdapter extends BaseAdapter {
        private final Context ctx;

        AmbAdapter(Context c) {
            ctx = c;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        private int dp(int v) {
            return (int) (v * ctx.getResources().getDisplayMetrics().density);
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            DocumentSnapshot d = items.get(pos);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));

            LinearLayout left = new LinearLayout(ctx);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String vn = d.getString("vehicleNumber");
            TextView title = new TextView(ctx);
            title.setText("🚑 " + (vn != null && !vn.isEmpty() ? vn : d.getId()));
            title.setTextSize(16);
            title.setTextColor(Color.parseColor("#212121"));
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            left.addView(title);

            Long upd = d.getLong("updatedAt");
            boolean fresh = upd != null && System.currentTimeMillis() - upd < Fs.STALE_MS;
            boolean online = Boolean.TRUE.equals(d.getBoolean("online")) && fresh;
            boolean busy = Boolean.TRUE.equals(d.getBoolean("busy"));
            Boolean av = d.getBoolean("available");
            boolean available = av == null || av;

            TextView st = new TextView(ctx);
            String text;
            int color;
            if (busy) {
                text = "🟠 On a trip";
                color = Color.parseColor("#E65100");
            } else if (online && available) {
                text = "🟢 Online — ready to dispatch";
                color = Color.parseColor("#2E7D32");
            } else if (online) {
                text = "🟡 Online — paused (not available)";
                color = Color.parseColor("#F9A825");
            } else {
                text = "🔴 Offline (driver not logged in / online)";
                color = Color.parseColor("#C62828");
            }
            st.setText(text);
            st.setTextSize(12);
            st.setTextColor(color);
            left.addView(st);

            SwitchCompat sw = new SwitchCompat(ctx);
            sw.setText("Available ");
            sw.setChecked(available);
            final String id = d.getId();
            sw.setOnCheckedChangeListener((b, checked) -> setAvailable(id, checked));
            row.addView(sw);
            return row;
        }
    }
}
