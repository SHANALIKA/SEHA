package com.example.seha;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Search + multi-select medicines.
 * Input : EXTRA_CURRENT  (ArrayList<MedicineItem> already in the list)
 * Output: EXTRA_SELECTED (ArrayList<MedicineItem> final selection) with RESULT_OK
 */
public class MedicineSearchActivity extends BaseDrawerActivity {

    public static final String EXTRA_CURRENT = "current";
    public static final String EXTRA_SELECTED = "selected";

    private EditText etSearch;
    private RecyclerView recycler;
    private TextView txtNoResults;
    private Button btnDone;
    private ImageView btnBack;

    private final List<MedicineItem> catalog = MedicineCatalog.getAll();
    private final List<MedicineItem> shown = new ArrayList<>();
    private final Map<String, MedicineItem> selected = new LinkedHashMap<>();
    private Adapter adapter;

    @SuppressWarnings({"unchecked", "deprecation"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_medicine_search);

        etSearch = findViewById(R.id.etSearch);
        recycler = findViewById(R.id.recyclerMedicines);
        txtNoResults = findViewById(R.id.txtNoResults);
        btnDone = findViewById(R.id.btnDone);
        btnBack = findViewById(R.id.btnBack);

        // Keep what the user already has (with their quantities)
        Serializable cur = getIntent().getSerializableExtra(EXTRA_CURRENT);
        if (cur instanceof ArrayList) {
            for (Object o : (ArrayList<Object>) cur) {
                if (o instanceof MedicineItem) {
                    MedicineItem m = (MedicineItem) o;
                    selected.put(m.id, m);
                }
            }
        }

        adapter = new Adapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnBack.setOnClickListener(v -> finish());

        btnDone.setOnClickListener(v -> {
            Intent data = new Intent();
            data.putExtra(EXTRA_SELECTED, new ArrayList<>(selected.values()));
            setResult(RESULT_OK, data);
            finish();
        });

        filter("");
        updateDoneButton();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private void filter(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (MedicineItem m : catalog) {
            if (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(m);
            }
        }
        adapter.notifyDataSetChanged();
        txtNoResults.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateDoneButton() {
        btnDone.setText("Done (" + selected.size() + " selected)");
    }

    private void toggle(MedicineItem catalogItem) {
        if (selected.containsKey(catalogItem.id)) {
            selected.remove(catalogItem.id);
        } else {
            // new instance so qty changes never touch the catalog
            selected.put(catalogItem.id, new MedicineItem(
                    catalogItem.id, catalogItem.name, catalogItem.unit, catalogItem.price));
        }
        updateDoneButton();
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            CheckBox check;
            TextView name, sub, price;

            VH(@NonNull View v) {
                super(v);
                check = v.findViewById(R.id.chkMedicine);
                name = v.findViewById(R.id.txtMedName);
                sub = v.findViewById(R.id.txtMedUnit);
                price = v.findViewById(R.id.txtMedPrice);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_medicine_search, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            MedicineItem m = shown.get(position);
            h.name.setText(m.name);
            h.sub.setText(m.unit);
            h.price.setText(MedicineItem.money(m.price));
            h.check.setChecked(selected.containsKey(m.id));

            h.itemView.setOnClickListener(v -> {
                toggle(m);
                h.check.setChecked(selected.containsKey(m.id));
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}