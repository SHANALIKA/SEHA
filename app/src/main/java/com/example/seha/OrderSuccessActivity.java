package com.example.seha;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Simple success screen after placing a medicine order.
 * No delivery / pharmacy tracking – order is considered complete.
 */
public class OrderSuccessActivity extends BaseDrawerActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_order_success);

        TextView txtMessage = findViewById(R.id.txtSuccessMessage);
        Button btnHome = findViewById(R.id.btnGoHome);
        Button btnMyOrders = findViewById(R.id.btnMyOrders);

        String orderId = getIntent().getStringExtra("orderId");
        if (orderId != null && !orderId.isEmpty()) {
            txtMessage.setText("Successfully Ordered!\n\nYour medicine order has been placed.\nOrder ID: " + orderId);
        } else {
            txtMessage.setText("Successfully Ordered!\n\nYour medicine order has been placed.");
        }

        if (btnHome != null) {
            btnHome.setOnClickListener(v -> {
                Intent i = new Intent(this, HomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            });
        }

        if (btnMyOrders != null) {
            btnMyOrders.setOnClickListener(v -> {
                Intent i = new Intent(this, MyOrdersActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                finish();
            });
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
}
