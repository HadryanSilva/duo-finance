package br.com.hadryan.duo.finance.importing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates a deterministic, immutable SHA-256 hash for imported transactions.
 *
 * The hash is computed from raw file data before any user editing,
 * ensuring deduplication survives description changes or manual re-entry.
 *
 * Strategy per format:
 *  - OFX:  SHA-256(coupleId + "|" + fitId)
 *            → FITID is the bank's own unique identifier, most reliable source.
 *  - XLSX: SHA-256(coupleId + "|" + date + "|" + amount + "|" + rawDescription)
 *            → No external ID available; uses raw file fields before any processing.
 */
public final class ImportHashUtil {

    private ImportHashUtil() {}

    /**
     * Hash for OFX transactions — based on the bank's FITID.
     */
    public static String forOfx(UUID coupleId, String fitId) {
        String input = coupleId.toString() + "|ofx|" + fitId.trim();
        return sha256(input);
    }

    /**
     * Hash for XLSX transactions — based on raw date, amount and description from the file.
     *
     * @param rawDescription description exactly as read from the file, before any trimming or editing
     */
    public static String forXlsx(UUID coupleId, LocalDate date, BigDecimal amount, String rawDescription) {
        String desc  = rawDescription == null ? "" : rawDescription.trim().toLowerCase();
        String input = coupleId.toString() + "|xlsx|" + date + "|" + amount.toPlainString() + "|" + desc;
        return sha256(input);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on all JVM implementations
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
