package com.example.seha;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class PlaceOrderActivity extends BaseDrawerActivity {

    Button btnPlaceOrder;
    ImageButton btnHome, btnOrders, btnAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_place_order);

        btnPlaceOrder = findViewById(R.id.button10);
        btnHome = findViewById(R.id.imageButton);
        btnOrders = findViewById(R.id.imageButton2);
        btnAccount = findViewById(R.id.imageButton3);

        if (btnPlaceOrder != null) {
            btnPlaceOrder.setOnClickListener(v -> placeOrder());
        }

        if (btnHome != null) {
            btnHome.setOnClickListener(v -> {
                Intent intent = new Intent(PlaceOrderActivity.this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            });
        }

        if (btnOrders != null) {
            btnOrders.setOnClickListener(v -> {
                Intent intent = new Intent(PlaceOrderActivity.this, MyOrdersActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            });
        }

        if (btnAccount != null) {
            btnAccount.setOnClickListener(v ->
                    startActivity(new Intent(PlaceOrderActivity.this, Profile.class)));
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void placeOrder() {
        String orderId = "order_" + System.currentTimeMillis();

        Map<String, Object> order = new HashMap<>();
        order.put("status", "ordered");
        order.put("createdAt", System.currentTimeMillis());
        // Delivery removed – order only

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            order.put("userId", FirebaseAuth.getInstance().getCurrentUser().getUid());
        }

        FirebaseFirestore.getInstance()
                .collection("Orders")
                .document(orderId)
                .set(order)
                .addOnSuccessListener(aVoid -> {
                    Intent intent = new Intent(PlaceOrderActivity.this, OrderSuccessActivity.class);
                    intent.putExtra("orderId", orderId);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }
}