package com.example.seha;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PATIENT MEDICAL RECORDS (nurse: view + add, patient: view only)
 *
 * Six categories, each saved and shown separately:
 *   X-ray | Scan | Blood report | Medicine | History | Others
 * Tap a category tile -> only that category is shown, newest first, grouped by date.
 * Every record shows which nurse (NIC) added it and cannot be edited afterwards.
 * Images are compressed and stored inside Firestore (no paid Storage plan needed).
 */
public class PatientRecordsActivity extends AppCompatActivity {

    public static final String EXTRA_PATIENT_UID = "patientUid";
    public static final String EXTRA_READ_ONLY = "readOnly";   // patient viewing their own records

    private static final String[] KEYS = {"xray", "scan", "blood", "medicine", "other"};
    private static final String[] NAMES = {"X-ray", "Scan", "Blood report", "Medicine", "Others"};
    private static final String[] ICONS = {"🦴", "🧲", "🩸", "💊", "📁"};
    private static final String[] HINTS = {
            "e.g. Chest X-ray, Left arm X-ray",
            "e.g. CT brain, MRI knee, Ultrasound abdomen",
            "e.g. Full blood count, Fasting blood sugar",
            "Medicine name & dose, e.g. Metformin 500mg twice a day",
            "e.g. Discharge letter, Insurance paper, Referral letter"};

    private FirebaseFirestore db;
    private String patientUid;
    private String myUid;
    private boolean readOnly = false;

    // my (nurse) identity, stamped on every record I add
    private String myHospital = "", myNic = "", myRole = "", myHospitalId = "";

    private LinearLayout root, headerBox, contentBox;
    private TextView txtCategoryTitle;
    private Button btnAdd;
    private final LinearLayout[] tiles = new LinearLayout[5];
    private final TextView[] tileName = new TextView[5];
    private final TextView[] tileCount = new TextView[5];

    private int selected = 0;
    private final List<DocumentSnapshot> allRecords = new ArrayList<>();
    private final List<String> medicineItems = new ArrayList<>();      // patient's current medicine list
    private final List<DocumentSnapshot> orders = new ArrayList<>();   // pharmacy orders
    private ListenerRegistration recordsListener;

    // image chosen inside the "add record" dialog
    private String pendingImageB64 = null;
    private ImageView pendingPreview = null;
    private TextView pendingLabel = null;
    private File cameraFile;

    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) setPendingImage(uri);
            });

    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
                if (ok && cameraFile != null && cameraFile.exists()) {
                    setPendingImage(Uri.fromFile(cameraFile));
                }
            });

    private final ActivityResultLauncher<String> cameraPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchCamera();
                else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            });

    // =====================================================================================
    // screen
    // =====================================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        patientUid = getIntent().getStringExtra(EXTRA_PATIENT_UID);
        if (patientUid == null || patientUid.isEmpty()) {
            Toast.makeText(this, "No patient selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        db = FirebaseFirestore.getInstance();
        myUid = Fs.me();
        readOnly = getIntent().getBooleanExtra(EXTRA_READ_ONLY, false);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F7FA"));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return insets;
        });

        TextView back = text("←  Back", 15, "#1976D2", true);
        back.setPadding(0, 0, 0, dp(8));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        root.addView(text(readOnly ? "📋 My medical records" : "📋 Medical records", 22, "#0D47A1", true));

        headerBox = card();
        root.addView(headerBox);

        // ---- add button (nurse only)
        btnAdd = new Button(this);
        btnAdd.setText("➕  Add new record");
        btnAdd.setAllCaps(false);
        btnAdd.setTextColor(Color.WHITE);
        btnAdd.setTextSize(15);
        btnAdd.setBackgroundResource(R.drawable.loginbutton);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        ap.topMargin = dp(12);
        root.addView(btnAdd, ap);
        btnAdd.setOnClickListener(v -> showAddDialog());
        if (readOnly) btnAdd.setVisibility(View.GONE);

        // ---- category tiles (2 rows x 3)
        root.addView(sectionTitle("Select a category"));
        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                int i = row * 3 + col;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(84), 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                if (i < NAMES.length) r.addView(makeTile(i), lp);
                else r.addView(new View(this), lp);        // empty slot
            }
            root.addView(r);
        }

        txtCategoryTitle = sectionTitle("");
        root.addView(txtCategoryTitle);
        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(contentBox);

        refreshAll();

        Fs.ensureSignedIn(() -> {
            myUid = Fs.me();
            loadMyIdentity();
            loadPatient();
            listenRecords();
            loadOrders();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recordsListener != null) recordsListener.remove();
    }

    // =====================================================================================
    // small UI helpers
    // =====================================================================================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 16, "#212121", true);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(Color.WHITE);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private View makeTile(int i) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        TextView ic = text(ICONS[i], 24, "#000000", false);
        ic.setGravity(Gravity.CENTER);
        TextView nm = text(NAMES[i], 12, "#0D47A1", true);
        nm.setGravity(Gravity.CENTER);
        TextView ct = text("0", 11, "#78909C", false);
        ct.setGravity(Gravity.CENTER);
        t.addView(ic);
        t.addView(nm);
        t.addView(ct);
        tiles[i] = t;
        tileName[i] = nm;
        tileCount[i] = ct;
        final int idx = i;
        t.setOnClickListener(v -> {
            selected = idx;
            refreshAll();
        });
        return t;
    }

    private void styleTile(int i, boolean on) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(14));
        gd.setColor(on ? Color.parseColor("#1565C0") : Color.WHITE);
        gd.setStroke(dp(1), on ? Color.parseColor("#1565C0") : Color.parseColor("#CFD8DC"));
        tiles[i].setBackground(gd);
        tileName[i].setTextColor(on ? Color.WHITE : Color.parseColor("#0D47A1"));
        tileCount[i].setTextColor(on ? Color.parseColor("#BBDEFB") : Color.parseColor("#78909C"));
    }

    // =====================================================================================
    // data
    // =====================================================================================

    private void loadMyIdentity() {
        if (myUid == null) return;
        db.collection(Fs.PATIENTS).document(myUid).get().addOnSuccessListener(d -> {
            myHospital = nz(d.getString("hospitalName"));
            myNic = nz(d.getString("nic"));
            myRole = nz(d.getString("role"));
            myHospitalId = nz(d.getString("hospitalId"));
            boolean staff = RoleRouter.ROLE_NURSE.equals(myRole) || RoleRouter.ROLE_HOSPITAL.equals(myRole);
            if (!staff && btnAdd != null) btnAdd.setVisibility(View.GONE);   // only nurses can add
        });
    }

    private void loadPatient() {
        db.collection(Fs.PATIENTS).document(patientUid).get().addOnSuccessListener(d -> {
            headerBox.removeAllViews();
            medicineItems.clear();
            if (!d.exists()) {
                headerBox.addView(text("Patient not found", 15, "#D32F2F", true));
                return;
            }
            String name = nz(d.getString("patientName"));
            if (name.isEmpty()) name = nz(d.getString("fullName"));
            headerBox.addView(text(name.isEmpty() ? "Unknown patient" : name, 20, "#212121", true));

            String code = nz(d.getString("patientCode"));
            headerBox.addView(text("Age: " + nz(d.getString("age")) + "     Blood group: "
                    + nz(d.getString("bloodGroup")) + (code.isEmpty() ? "" : "     ID: " + code),
                    14, "#455A64", false));
            headerBox.addView(text("Allergies: " + nz(d.getString("allergies")), 14, "#455A64", false));

            String chronic = nz(d.getString("chronicConditions")).trim();
            if (!chronic.isEmpty() && !chronic.equalsIgnoreCase("none") && !chronic.equals("-")) {
                TextView banner = text("⚠ MEDICAL ALERT\n" + chronic.toUpperCase(), 16, "#B71C1C", true);
                banner.setBackgroundColor(Color.parseColor("#FFEBEE"));
                banner.setGravity(Gravity.CENTER);
                banner.setPadding(dp(10), dp(10), dp(10), dp(10));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bp.topMargin = dp(10);
                headerBox.addView(banner, bp);
            }

            Object raw = d.get("medicineList");
            if (raw instanceof List) {
                for (Object o : (List<?>) raw) {
                    if (o instanceof Map) {
                        Map<?, ?> m = (Map<?, ?>) o;
                        Object q = m.get("qty");
                        int qty = q instanceof Number ? ((Number) q).intValue() : 1;
                        medicineItems.add(m.get("name") + "   × " + qty
                                + (m.get("unit") != null ? "   (" + m.get("unit") + ")" : ""));
                    }
                }
            }
            refreshAll();
        }).addOnFailureListener(e -> {
            headerBox.removeAllViews();
            headerBox.addView(text("Could not load patient: " + Fs.explain(e), 14, "#D32F2F", false));
        });
    }

    private void listenRecords() {
        recordsListener = db.collection(Fs.PATIENTS).document(patientUid)
                .collection(Fs.RECORDS).addSnapshotListener((qs, e) -> {
                    if (qs == null) return;
                    allRecords.clear();
                    allRecords.addAll(qs.getDocuments());
                    sortNewestFirst(allRecords);
                    refreshAll();
                });
    }

    private void loadOrders() {
        db.collection("Orders").whereEqualTo("userId", patientUid).get()
                .addOnSuccessListener(qs -> {
                    orders.clear();
                    orders.addAll(qs.getDocuments());
                    sortNewestFirst(orders);
                    refreshAll();
                });
    }

    private static void sortNewestFirst(List<DocumentSnapshot> list) {
        Collections.sort(list, (a, b) -> {
            Long x = a.getLong("createdAt");
            Long y = b.getLong("createdAt");
            return Long.compare(y != null ? y : 0, x != null ? x : 0);
        });
    }

    /** Old records (without a category field) are sorted by their type text. */
    private int categoryOf(DocumentSnapshot d) {
        String c = d.getString("category");
        if (c != null) {
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(c)) return i;
        }
        String t = nz(d.getString("type")).toLowerCase(Locale.ROOT);
        if (t.contains("x-ray") || t.contains("xray")) return 0;
        if (t.contains("scan")) return 1;
        if (t.contains("blood")) return 2;
        if (t.contains("prescription") || t.contains("medicine")) return 3;
        return 4;   // history / illness / anything else -> Others
    }

    // =====================================================================================
    // rendering
    // =====================================================================================

    private void refreshAll() {
        int[] counts = new int[NAMES.length];
        for (DocumentSnapshot d : allRecords) counts[categoryOf(d)]++;
        counts[3] += medicineItems.size() + orders.size();

        for (int i = 0; i < NAMES.length; i++) {
            styleTile(i, i == selected);
            tileCount[i].setText(counts[i] + (counts[i] == 1 ? " item" : " items"));
        }
        txtCategoryTitle.setText(ICONS[selected] + "  " + NAMES[selected]);

        contentBox.removeAllViews();

        // records of the selected category, grouped by date (newest first)
        List<DocumentSnapshot> list = new ArrayList<>();
        for (DocumentSnapshot d : allRecords) if (categoryOf(d) == selected) list.add(d);
        addGroupedByDate(list, false);

        if (selected == 3) {
            // medicine category also shows the current medicine list and pharmacy orders
            contentBox.addView(subTitle("💊 Current medicine list"));
            LinearLayout mc = card();
            if (medicineItems.isEmpty()) {
                mc.addView(text("No medicines in the patient's list.", 13, "#78909C", false));
            } else {
                for (String m : medicineItems) mc.addView(text("• " + m, 14, "#37474F", false));
            }
            contentBox.addView(mc);

            contentBox.addView(subTitle("🧾 Pharmacy orders"));
            if (orders.isEmpty()) {
                LinearLayout oc = card();
                oc.addView(text("No pharmacy orders.", 13, "#78909C", false));
                contentBox.addView(oc);
            } else {
                addGroupedByDate(orders, true);
            }
        } else if (list.isEmpty()) {
            LinearLayout c = card();
            c.addView(text("No " + NAMES[selected] + " records yet."
                    + (readOnly ? "" : "\nTap  ➕ Add new record  to add one."), 13, "#78909C", false));
            contentBox.addView(c);
        }
    }

    private TextView subTitle(String s) {
        TextView t = text(s, 14, "#455A64", true);
        t.setPadding(0, dp(16), 0, dp(2));
        return t;
    }

    /** Date header ("Today", "12 Nov 2026") followed by the records of that day. */
    private void addGroupedByDate(List<DocumentSnapshot> list, boolean asOrders) {
        SimpleDateFormat dayKey = new SimpleDateFormat("yyyyMMdd", Locale.US);
        SimpleDateFormat dayLabel = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        long now = System.currentTimeMillis();
        String today = dayKey.format(new Date(now));
        String yesterday = dayKey.format(new Date(now - 86_400_000L));

        String lastKey = "";
        for (DocumentSnapshot d : list) {
            Long t = d.getLong("createdAt");
            long ts = t != null ? t : 0;
            String key = ts > 0 ? dayKey.format(new Date(ts)) : "none";
            if (!key.equals(lastKey)) {
                lastKey = key;
                String label = key.equals("none") ? "Undated"
                        : key.equals(today) ? "Today  •  " + dayLabel.format(new Date(ts))
                        : key.equals(yesterday) ? "Yesterday  •  " + dayLabel.format(new Date(ts))
                        : dayLabel.format(new Date(ts));
                TextView h = text("📅  " + label, 13, "#1565C0", true);
                h.setPadding(0, dp(14), 0, dp(0));
                contentBox.addView(h);
            }
            String time = ts > 0 ? timeFmt.format(new Date(ts)) : "";
            contentBox.addView(asOrders ? orderView(d, time) : recordView(d, time));
        }
    }

    private View recordView(DocumentSnapshot d, String time) {
        LinearLayout c = card();
        String title = nz(d.getString("title"));
        c.addView(text(title.isEmpty() ? NAMES[categoryOf(d)] : title, 16, "#0D47A1", true));

        String hosp = nz(d.getString("hospitalName"));
        c.addView(text("🕒 " + time + (hosp.isEmpty() ? "" : "   •   🏥 " + hosp), 12, "#78909C", false));

        String by = nz(d.getString("nurseNic"));
        TextView added = text("✍ Added by nurse ID " + (by.isEmpty() ? "(unknown)" : by)
                + "   🔒 cannot be edited", 12, "#2E7D32", true);
        added.setPadding(0, dp(2), 0, 0);
        c.addView(added);

        String notes = nz(d.getString("notes"));
        if (!notes.isEmpty()) {
            TextView n = text(notes, 14, "#37474F", false);
            n.setPadding(0, dp(6), 0, 0);
            c.addView(n);
        }

        String b64 = d.getString("imageB64");
        if (b64 != null && !b64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                final Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setBackgroundColor(Color.BLACK);
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(200));
                    ip.topMargin = dp(8);
                    c.addView(iv, ip);
                    c.addView(text("Tap the image to open full screen", 11, "#90A4AE", false));
                    iv.setOnClickListener(v -> showFullImage(bmp));
                }
            } catch (Exception ignored) { }
        }
        return c;
    }

    private View orderView(DocumentSnapshot d, String time) {
        LinearLayout c = card();
        c.addView(text("🧾 Order  •  " + time + "  •  " + nz(d.getString("status")), 14, "#0D47A1", true));
        Object raw = d.get("medicines");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    Object q = m.get("qty");
                    int qty = q instanceof Number ? ((Number) q).intValue() : 1;
                    c.addView(text("• " + m.get("name") + "  × " + qty, 14, "#37474F", false));
                }
            }
        }
        return c;
    }

    /** Full-screen image with pinch-zoom, drag and double-tap. */
    private void showFullImage(Bitmap bmp) {
        final android.app.Dialog dlg = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        ZoomImageView iv = new ZoomImageView(this);
        iv.setImageBitmap(bmp);
        frame.addView(iv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView close = text("✕", 26, "#FFFFFF", true);
        close.setPadding(dp(18), dp(12), dp(18), dp(12));
        close.setBackgroundColor(Color.parseColor("#66000000"));
        android.widget.FrameLayout.LayoutParams cp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        cp.setMargins(0, dp(36), dp(12), 0);
        frame.addView(close, cp);
        close.setOnClickListener(v -> dlg.dismiss());

        TextView hint = text("Pinch to zoom  •  double-tap to zoom in / reset", 12, "#B0BEC5", false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(24));
        frame.addView(hint, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        dlg.setContentView(frame);
        dlg.show();
    }

    // =====================================================================================
    // add record
    // =====================================================================================

    private void showAddDialog() {
        pendingImageB64 = null;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), 0);

        form.addView(text("Category (it will be saved here)", 12, "#607D8B", true));
        final Spinner category = new Spinner(this);
        String[] labels = new String[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) labels[i] = ICONS[i] + "  " + NAMES[i];
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        category.setSelection(selected);
        form.addView(category);

        final EditText title = new EditText(this);
        title.setHint(HINTS[selected]);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(title);

        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                title.setHint(HINTS[pos]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) { }
        });

        final EditText notes = new EditText(this);
        notes.setHint("Notes / result / treatment (optional)");
        notes.setMinLines(3);
        notes.setGravity(Gravity.TOP);
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(notes);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        Button cam = new Button(this);
        cam.setText("📷 Camera");
        cam.setAllCaps(false);
        Button gal = new Button(this);
        gal.setText("🖼 Gallery");
        gal.setAllCaps(false);
        row.addView(cam, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(gal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        form.addView(row, rp);

        pendingLabel = text("No image attached (optional)", 12, "#78909C", false);
        form.addView(pendingLabel);
        pendingPreview = new ImageView(this);
        pendingPreview.setAdjustViewBounds(true);
        pendingPreview.setVisibility(View.GONE);
        form.addView(pendingPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));

        cam.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) launchCamera();
            else cameraPerm.launch(Manifest.permission.CAMERA);
        });
        gal.setOnClickListener(v -> galleryLauncher.launch("image/*"));

        ScrollView sv = new ScrollView(this);
        sv.addView(form);

        new AlertDialog.Builder(this)
                .setTitle("New record")
                .setView(sv)
                .setPositiveButton("Save", (d, w) -> {
                    String ti = title.getText().toString().trim();
                    String no = notes.getText().toString().trim();
                    if (ti.isEmpty() && no.isEmpty() && pendingImageB64 == null) {
                        Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveRecord(category.getSelectedItemPosition(), ti, no);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveRecord(int cat, String title, String notes) {
        com.google.firebase.auth.FirebaseUser u =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (u == null || u.isAnonymous() || myNic.isEmpty()
                || !(RoleRouter.ROLE_NURSE.equals(myRole) || RoleRouter.ROLE_HOSPITAL.equals(myRole))) {
            Toast.makeText(this, "Only a logged-in nurse can add records", Toast.LENGTH_LONG).show();
            return;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("category", KEYS[cat]);
        m.put("type", NAMES[cat]);
        m.put("title", title);
        m.put("notes", notes);
        m.put("hospitalName", myHospital);
        m.put("hospitalId", myHospitalId);
        m.put("nurseUid", u.getUid());
        m.put("nurseNic", myNic);
        m.put("createdAt", System.currentTimeMillis());
        if (pendingImageB64 != null) m.put("imageB64", pendingImageB64);

        db.collection(Fs.PATIENTS).document(patientUid).collection(Fs.RECORDS).add(m)
                .addOnSuccessListener(r -> {
                    Toast.makeText(this, "Saved in " + NAMES[cat], Toast.LENGTH_SHORT).show();
                    selected = cat;          // jump to the category the record was saved in
                    refreshAll();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Could not save: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
        pendingImageB64 = null;
    }

    private void launchCamera() {
        try {
            cameraFile = File.createTempFile("rec_", ".jpg", getCacheDir());
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraFile);
            cameraLauncher.launch(uri);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open camera", Toast.LENGTH_SHORT).show();
        }
    }

    /** Shrinks the photo (max 1000 px, JPEG) so it fits inside a Firestore document. */
    private void setPendingImage(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) throw new Exception("decode failed");

            int max = 1000;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (Math.max(w, h) > max) {
                float sc = max / (float) Math.max(w, h);
                bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * sc), Math.round(h * sc), true);
            }

            int quality = 75;
            byte[] bytes;
            do {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
                bytes = out.toByteArray();
                quality -= 15;
            } while (bytes.length > 600_000 && quality > 20);

            pendingImageB64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            if (pendingPreview != null) {
                pendingPreview.setImageBitmap(bmp);
                pendingPreview.setVisibility(View.VISIBLE);
            }
            if (pendingLabel != null) pendingLabel.setText("Image attached (" + bytes.length / 1024 + " KB)");
        } catch (Exception e) {
            Toast.makeText(this, "Could not read image: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
