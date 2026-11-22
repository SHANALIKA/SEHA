package com.example.seha;

import java.util.ArrayList;
import java.util.List;

/**
 * Sample medicine catalog (names + prices are demo data).
 * Later you can move this into a Firestore "Medicines" collection and load it from there.
 */
public class MedicineCatalog {

    public static List<MedicineItem> getAll() {
        List<MedicineItem> l = new ArrayList<>();
        add(l, "paracetamol500", "Paracetamol 500mg", "10 tablets", 30);
        add(l, "ibuprofen400", "Ibuprofen 400mg", "10 tablets", 80);
        add(l, "diclofenac50", "Diclofenac 50mg", "10 tablets", 70);
        add(l, "aspirin75", "Aspirin 75mg", "10 tablets", 45);
        add(l, "cetirizine10", "Cetirizine 10mg", "10 tablets", 60);
        add(l, "loratadine10", "Loratadine 10mg", "10 tablets", 120);
        add(l, "amoxicillin500", "Amoxicillin 500mg", "10 capsules", 450);
        add(l, "azithromycin500", "Azithromycin 500mg", "3 tablets", 520);
        add(l, "ciprofloxacin500", "Ciprofloxacin 500mg", "10 tablets", 380);
        add(l, "metronidazole400", "Metronidazole 400mg", "10 tablets", 90);
        add(l, "metformin500", "Metformin 500mg", "10 tablets", 65);
        add(l, "metformin850", "Metformin 850mg", "10 tablets", 95);
        add(l, "gliclazide80", "Gliclazide 80mg", "10 tablets", 110);
        add(l, "amlodipine5", "Amlodipine 5mg", "10 tablets", 50);
        add(l, "amlodipine10", "Amlodipine 10mg", "10 tablets", 85);
        add(l, "atorvastatin10", "Atorvastatin 10mg", "10 tablets", 140);
        add(l, "atorvastatin20", "Atorvastatin 20mg", "10 tablets", 210);
        add(l, "losartan50", "Losartan 50mg", "10 tablets", 90);
        add(l, "enalapril5", "Enalapril 5mg", "10 tablets", 40);
        add(l, "atenolol50", "Atenolol 50mg", "10 tablets", 35);
        add(l, "clopidogrel75", "Clopidogrel 75mg", "10 tablets", 160);
        add(l, "furosemide40", "Furosemide 40mg", "10 tablets", 30);
        add(l, "omeprazole20", "Omeprazole 20mg", "10 capsules", 100);
        add(l, "pantoprazole40", "Pantoprazole 40mg", "10 tablets", 150);
        add(l, "domperidone10", "Domperidone 10mg", "10 tablets", 55);
        add(l, "salbutamol100", "Salbutamol Inhaler 100mcg", "1 inhaler", 650);
        add(l, "montelukast10", "Montelukast 10mg", "10 tablets", 280);
        add(l, "prednisolone5", "Prednisolone 5mg", "10 tablets", 25);
        add(l, "levothyroxine50", "Levothyroxine 50mcg", "30 tablets", 320);
        add(l, "folicacid5", "Folic Acid 5mg", "30 tablets", 60);
        add(l, "vitaminc500", "Vitamin C 500mg", "10 tablets", 75);
        add(l, "vitamind3", "Vitamin D3 1000IU", "30 capsules", 450);
        add(l, "calciumd3", "Calcium + Vitamin D3", "30 tablets", 380);
        add(l, "ors", "ORS Sachets", "5 sachets", 100);
        add(l, "bromhexine", "Bromhexine Cough Syrup", "100 ml", 180);
        return l;
    }

    private static void add(List<MedicineItem> l, String id, String name, String unit, double price) {
        l.add(new MedicineItem(id, name, unit, price));
    }
}