package br.dev.duofinance.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DescriptionKeysTest {

    @Test
    void of_lowercasesStripsAccentsAndCollapsesSpaces() {
        assertThat(DescriptionKeys.of("  Café   São Paulo ")).isEqualTo("cafe sao paulo");
    }

    @Test
    void of_removesTrailingDigitsStarsAndHashes() {
        assertThat(DescriptionKeys.of("UBER *TRIP 4821")).isEqualTo("uber *trip");
        assertThat(DescriptionKeys.of("Lidl #1234")).isEqualTo("lidl");
    }

    @Test
    void of_digitsOnly_fallsBackToCollapsedText() {
        assertThat(DescriptionKeys.of("12345")).isEqualTo("12345");
    }

    @Test
    void of_cutsToMaxLength() {
        assertThat(DescriptionKeys.of("a".repeat(300))).hasSize(140);
    }
}
