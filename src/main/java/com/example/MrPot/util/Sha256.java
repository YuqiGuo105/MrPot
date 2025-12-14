package com.example.MrPot.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Sha256 {
    private Sha256() {}

    // Compute SHA-256 hex for dedup/debug
    public static String hex(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

