package com.example.seha;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** One medicine in the user's list / order. Serializable so it can travel between activities. */
public class MedicineItem implements Serializable {

    public String id;
    public String name;
    public String unit;     // e.g. "10 tablets"
    public double price;    // price of ONE pack
    public int qty = 1;     // number of packs

    public MedicineItem(String id, String name, String unit, double price) {
        this.id = id;
        this.name = name;
        this.unit = unit;
        this.price = price;
    }

    public double lineTotal() {
        return price * qty;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("unit", unit);
        m.put("price", price);
        m.put("qty", qty);
        return m;
    }

    public static MedicineItem fromMap(Map<?, ?> m) {
        String id = String.valueOf(m.get("id"));
        String name = String.valueOf(m.get("name"));
        Object u = m.get("unit");
        Object p = m.get("price");
        Object q = m.get("qty");
        MedicineItem item = new MedicineItem(
                id, name,
                u == null ? "" : String.valueOf(u),
                p instanceof Number ? ((Number) p).doubleValue() : 0.0);
        item.qty = q instanceof Number ? ((Number) q).intValue() : 1;
        return item;
    }

    public static String money(double v) {
        return String.format(Locale.US, "Rs. %,.2f", v);
    }
}