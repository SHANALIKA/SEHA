package com.example.seha;

import java.util.Locale;

/**
 * Firebase Auth needs an e-mail. Nurses (NIC), ambulances (vehicle number) and hospitals
 * (hospital name) don't type an e-mail, so a hidden, stable e-mail is made from their ID:
 *   nurse     199012345678      -> 199012345678@nurse.seha.app
 *   ambulance WP CAB-1234       -> wpcab1234@ambulance.seha.app
 *   hospital  Negombo District… -> negombodistrictgeneralhospital@hospital.seha.app
 * Patients keep using their real e-mail.
 */
public final class AuthIds {

    private AuthIds() { }

    public static String nic(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
    }

    /** Old NIC (9 digits + V/X) or new NIC (12 digits). */
    public static boolean validNic(String s) {
        String n = nic(s);
        return n.matches("[0-9]{9}[VX]") || n.matches("[0-9]{12}");
    }

    private static String slug(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** @param identifier e-mail (patient) / NIC (nurse) / vehicle number (ambulance) / hospital name */
    public static String emailFor(String role, String identifier) {
        if (identifier == null) identifier = "";
        if (RoleRouter.ROLE_NURSE.equals(role)) {
            return slug(nic(identifier)) + "@nurse.seha.app";
        } else if (RoleRouter.ROLE_AMBULANCE.equals(role)) {
            return slug(identifier) + "@ambulance.seha.app";
        } else if (RoleRouter.ROLE_HOSPITAL.equals(role)) {
            return slug(identifier) + "@hospital.seha.app";
        }
        return identifier.trim();
    }
}
