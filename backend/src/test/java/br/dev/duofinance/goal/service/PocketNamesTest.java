package br.dev.duofinance.goal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PocketNamesTest {

    @Test
    void normalise_ignoresCaseAccentsAndSpaces() {
        assertThat(PocketNames.normalise("  Reserva de  Emergência ")).isEqualTo("reserva de emergencia");
    }
}
