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
import androidx.cardview.widget.CardView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * My Orders – shows order summary + list from Firestore.
 * Summary (count + total spent) updates after placing an order.
 */
public class MyOrdersActivity extends BaseDrawerActivity {

    Button btnUpdateList, btnOrderMedicine;
    ImageButton btnHome, btnOrders, btnAccount;
    LinearLayout ordersContainer;
    TextView txtEmptyOrders, txtSummaryCount, txtSummaryTotal;
    CardView cardSummary;

    private FirebaseFirestore db;
    private FirebaseAuth auth;

    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("dd MMM yyyy", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_my_orders);

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        btnUpdateList = findViewById(R.id.button9);
        btnOrderMedicine = findViewById(R.id.button10);
        btnHome = findViewById(R.id.imageButton);
        btnOrders = findViewById(R.id.imageButton2);
        btnAccount = findViewById(R.id.imageButton3);
        ordersContainer = findViewById(R.id.ordersContainer);
        txtEmptyOrders = findViewById(R.id.txtEmptyOrders);
        cardSummary = findViewById(R.id.cardSummary);
        txtSummaryCount = findViewById(R.id.txtSummaryCount);
        txtSummaryTotal = findViewById(R.id.txtSummaryTotal);

        if (btnUpdateList != null) {
            btnUpdateList.setOnClickListener(v ->
                    startActivity(new Intent(this, MyMedicineActivity.class)));
        }
        if (btnOrderMedicine != null) {
            btnOrderMedicine.setOnClickListener(v ->
                    startActivity(new Intent(this, MyMedicineActivity.class)));
        }
        if (btnHome != null) {
            btnHome.setOnClickListener(v ->
                    startActivity(new Intent(this, HomeActivity.class)));
        }
        if (btnAccount != null) {
            btnAccount.setOnClickListener(v ->
                    startActivity(new Intent(this, Profile.class)));
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadOrders();
    }

    private void loadOrders() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            showEmpty("Please login to see your orders");
            return;
        }

        db.collection("Orders")
                .whereEqualTo("userId", user.getUid())
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(snap -> {
                    List<DocumentSnapshot> docs = new ArrayList<>();
                    for (QueryDocumentSnapshot d : snap) docs.add(d);
                    renderOrders(docs);
                })
                .addOnFailureListener(e -> {
                    // No composite index – load all, filter + sort client-side
                    db.collection("Orders")
                            .get()
                            .addOnSuccessListener(all -> {
                                String uid = user.getUid();
                                List<DocumentSnapshot> docs = new ArrayList<>();
                                for (DocumentSnapshot d : all.getDocuments()) {
                                    if (uid.equals(d.getString("userId"))) {
                                        docs.add(d);
                                    }
                                }
                                docs.sort((a, b) -> {
                                    long ca = a.getLong("createdAt") != null ? a.getLong("createdAt") : 0L;
                                    long cb = b.getLong("createdAt") != null ? b.getLong("createdAt") : 0L;
                                    return Long.compare(cb, ca);
                                });
                                renderOrders(docs);
                            })
                            .addOnFailureListener(e2 ->
                                    Toast.makeText(this, "Could not load orders: " + e2.getMessage(),
                                            Toast.LENGTH_LONG).show());
                });
    }

    private void renderOrders(List<DocumentSnapshot> docs) {
        ordersContainer.removeAllViews();

        if (docs == null || docs.isEmpty()) {
            showEmpty("No orders yet.\nOrder medicine from My Medicine.");
            return;
        }

        txtEmptyOrders.setVisibility(View.GONE);
        cardSummary.setVisibility(View.VISIBLE);

        double grandTotal = 0;
        LayoutInflater inflater = LayoutInflater.from(this);
        int index = 1;
        for (DocumentSnapshot doc : docs) {
            grandTotal += addOrderCard(inflater, doc, index++);
        }

        txtSummaryCount.setText(String.valueOf(docs.size()));
        txtSummaryTotal.setText(MedicineItem.money(grandTotal));
    }

    private void showEmpty(String msg) {
        ordersContainer.removeAllViews();
        txtEmptyOrders.setText(msg);
        txtEmptyOrders.setVisibility(View.VISIBLE);
        if (cardSummary != null) cardSummary.setVisibility(View.GONE);
    }

    /** @return line total for this order (used for grand total) */
    @SuppressWarnings("unchecked")
    private double addOrderCard(LayoutInflater inflater, DocumentSnapshot doc, int index) {
        View row = inflater.inflate(R.layout.item_order, ordersContainer, false);

        TextView txtOrderId = row.findViewById(R.id.txtOrderId);
        TextView txtStatus = row.findViewById(R.id.txtStatus);
        TextView txtDate = row.findViewById(R.id.txtDate);
        TextView txtMedicines = row.findViewById(R.id.txtMedicines);
        TextView txtTotal = row.findViewById(R.id.txtTotal);

        txtOrderId.setText("Order #" + String.format(Locale.US, "%02d", index));

        String status = doc.getString("status");
        if (status == null || status.isEmpty()) status = "ordered";
        status = status.substring(0, 1).toUpperCase(Locale.US) + status.substring(1);
        txtStatus.setText(status);

        Long createdAt = doc.getLong("createdAt");
        if (createdAt != null && createdAt > 0) {
            txtDate.setText(dateFmt.format(new Date(createdAt)));
        } else {
            txtDate.setText("—");
        }

        StringBuilder meds = new StringBuilder();
        Object raw = doc.get("medicines");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    String name = String.valueOf(m.get("name"));
                    Object q = m.get("qty");
                    int qty = q instanceof Number ? ((Number) q).intValue() : 1;
                    if (meds.length() > 0) meds.append("\n");
                    meds.append("• ").append(name).append("  ×  ").append(qty);
                }
            }
        }
        txtMedicines.setText(meds.length() > 0 ? meds.toString() : "—");

        Object totalObj = doc.get("total");
        double total = totalObj instanceof Number ? ((Number) totalObj).doubleValue() : 0;
        txtTotal.setText("Total • " + MedicineItem.money(total));

        ordersContainer.addView(row);
        return total;
    }
}
