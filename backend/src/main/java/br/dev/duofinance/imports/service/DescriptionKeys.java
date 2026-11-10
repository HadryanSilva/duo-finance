package br.dev.duofinance.imports.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalises a statement description into the key used to remember categories. */
public final class DescriptionKeys {

    static final int MAX_LENGTH = 140;

    private static final Pattern MARKS = Pattern.compile("\\p{M}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_NOISE = Pattern.compile("[\\s\\d*#]+$");

    private DescriptionKeys() {}

    public static String of(String description) {
        String text = Normalizer.normalize(description, Normalizer.Form.NFD);
        text = MARKS.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").trim();
        String stripped = TRAILING_NOISE.matcher(collapsed).replaceFirst("");
        String key = stripped.isEmpty() ? collapsed : stripped;
        return key.length() > MAX_LENGTH ? key.substring(0, MAX_LENGTH) : key;
    }
}
