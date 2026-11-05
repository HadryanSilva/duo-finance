package br.dev.duofinance.shared.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecureTokensTest {

    @Test
    void generate_isUrlSafeAnd43Chars() {
        assertThat(SecureTokens.generate()).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void sha256Hex_isStable() {
        assertThat(SecureTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void normalizeEmail_trimsAndLowercases() {
        assertThat(SecureTokens.normalizeEmail("  Ana@Mail.COM ")).isEqualTo("ana@mail.com");
    }
}
