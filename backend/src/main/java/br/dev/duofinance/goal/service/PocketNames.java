package br.dev.duofinance.goal.service;

import java.text.Normalizer;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Revolut pocket names are matched ignoring case, accents and spacing; the typed name is kept for display. */
public final class PocketNames {

    private PocketNames() {}

    public static String normalise(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .strip()
                .replaceAll("\\s+", " ");
    }

    /** The pocket as stored: stripped, with a blank one meaning none. */
    public static @Nullable String clean(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.strip();
    }
}
