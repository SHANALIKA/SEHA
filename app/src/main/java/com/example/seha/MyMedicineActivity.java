package com.example.seha;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interface 6 – My Medicine list.
 *  - "Update List"    -> opens the search screen, user picks medicines, list comes back here
 *  - "Order Medicine" -> goes to nearby pharmacies (Interface 7) with the list
 * The list is stored in Firestore: Patients/{uid}.medicineList
 */
public class MyMedicineActivity extends BaseDrawerActivity {

    private static final int[] COLORS = {
            0xFF4CAF50, 0xFFFF9800, 0xFF7B1FA2, 0xFF1976D2, 0xFFE91E63
    };

    Button btnUpdateList, btnOrderMedicine;
    ImageButton btnHome, btnOrders, btnAccount;
    LinearLayout listContainer;
    TextView txtEmpty, txtTotal;

    private ArrayList<MedicineItem> items = new ArrayList<>();
    private boolean loaded = false;   // don't save before the first load finishes

    private FirebaseFirestore db;
    private FirebaseAuth auth;

    @SuppressWarnings({"unchecked", "deprecation"})
    private final ActivityResultLauncher<Intent> pickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Serializable s = result.getData()
                            .getSerializableExtra(MedicineSearchActivity.EXTRA_SELECTED);
                    if (s instanceof ArrayList) {
                        items = (ArrayList<MedicineItem>) s;
                        render();
                        saveList();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_my_medicine);

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        btnUpdateList = findViewById(R.id.button9);
        btnOrderMedicine = findViewById(R.id.button10);
        btnHome = findViewById(R.id.imageButton);
        btnOrders = findViewById(R.id.imageButton2);
        btnAccount = findViewById(R.id.imageButton3);
        listContainer = findViewById(R.id.listContainer);
        txtEmpty = findViewById(R.id.txtEmpty);
        txtTotal = findViewById(R.id.txtTotal);

        btnUpdateList.setOnClickListener(v -> {
            Intent i = new Intent(this, MedicineSearchActivity.class);
            i.putExtra(MedicineSearchActivity.EXTRA_CURRENT, new ArrayList<>(items));
            pickerLauncher.launch(i);
        });

        btnOrderMedicine.setOnClickListener(v -> {
            if (items.isEmpty()) {
                Toast.makeText(this, "Add medicines first (Update List)", Toast.LENGTH_SHORT).show();
                return;
            }
            saveList();
            placeMedicineOrder();
        });

        // ========== BOTTOM NAV ==========
        btnHome.setOnClickListener(v -> {
            Intent intent = new Intent(this, HomeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });
        btnOrders.setOnClickListener(v -> {
            Intent intent = new Intent(this, MyOrdersActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        });
        btnAccount.setOnClickListener(v -> startActivity(new Intent(this, Profile.class)));

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        render();
        loadList();
    }

    // ---------------------------------------------------------------- Firestore

    private void loadList() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Please login to save your list", Toast.LENGTH_SHORT).show();
            return;
        }
        db.collection("Patients").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    ArrayList<MedicineItem> loadedItems = new ArrayList<>();
                    Object raw = doc.get("medicineList");
                    if (raw instanceof List) {
                        for (Object o : (List<?>) raw) {
                            if (o instanceof Map) {
                                loadedItems.add(MedicineItem.fromMap((Map<?, ?>) o));
                            }
                        }
                    }
                    // if the user already picked something while loading, keep that instead
                    if (items.isEmpty()) {
                        items = loadedItems;
                    }
                    loaded = true;
                    render();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Could not load list: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
    }

    private void saveList() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || !loaded && items.isEmpty()) return;

        List<Map<String, Object>> list = new ArrayList<>();
        for (MedicineItem m : items) list.add(m.toMap());

        Map<String, Object> data = new HashMap<>();
        data.put("medicineList", list);
        db.collection("Patients").document(user.getUid()).set(data, SetOptions.merge());
        loaded = true;
    }

    // ---------------------------------------------------------------- UI

    private void render() {
        listContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        double total = 0;
        for (int i = 0; i < items.size(); i++) {
            MedicineItem m = items.get(i);
            View row = inflater.inflate(R.layout.item_my_medicine, listContainer, false);

            row.findViewById(R.id.indicator).setBackgroundColor(COLORS[i % COLORS.length]);
            ((TextView) row.findViewById(R.id.txtName)).setText(m.name);
            ((TextView) row.findViewById(R.id.txtSub)).setText(
                    m.unit + " • " + MedicineItem.money(m.price));

            TextView txtQty = row.findViewById(R.id.txtQty);
            txtQty.setText(String.valueOf(m.qty));

            row.findViewById(R.id.btnPlus).setOnClickListener(v -> {
                if (m.qty < 99) m.qty++;
                txtQty.setText(String.valueOf(m.qty));
                updateTotal();
                saveList();
            });
            row.findViewById(R.id.btnMinus).setOnClickListener(v -> {
                if (m.qty > 1) m.qty--;
                txtQty.setText(String.valueOf(m.qty));
                updateTotal();
                saveList();
            });
            row.findViewById(R.id.btnRemove).setOnClickListener(v -> {
                items.remove(m);
                render();
                saveList();
            });

            listContainer.addView(row);
            total += m.lineTotal();
        }

        txtEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        txtTotal.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        txtTotal.setText("Estimated total: " + MedicineItem.money(total));
    }

    private void updateTotal() {
        double total = 0;
        for (MedicineItem m : items) total += m.lineTotal();
        txtTotal.setText("Estimated total: " + MedicineItem.money(total));
    }


    /** Place order without pharmacy/delivery flow. Goes straight to success screen. */
    private void placeMedicineOrder() {
        String orderId = "order_" + System.currentTimeMillis();

        List<Map<String, Object>> medList = new ArrayList<>();
        double total = 0;
        for (MedicineItem m : items) {
            medList.add(m.toMap());
            total += m.lineTotal();
        }

        Map<String, Object> order = new HashMap<>();
        order.put("status", "ordered");
        order.put("medicines", medList);
        order.put("total", total);
        order.put("createdAt", System.currentTimeMillis());
        if (auth.getCurrentUser() != null) {
            order.put("userId", auth.getCurrentUser().getUid());
        }

        db.collection("Orders").document(orderId).set(order)
                .addOnSuccessListener(aVoid -> {
                    Intent i = new Intent(this, OrderSuccessActivity.class);
                    i.putExtra("orderId", orderId);
                    startActivity(i);
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Order failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveList();   // persists quantity changes
    }
}