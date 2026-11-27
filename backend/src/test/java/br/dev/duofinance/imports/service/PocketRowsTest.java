package br.dev.duofinance.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PocketRowsTest {

    private static final String DEPOSIT = "Carregamento de subconta EUR Reserva de Emergência de EUR";

    private static RevolutRow row(String type, String product, String description, String amount, long cents) {
        return new RevolutRow(2, type, product, LocalDateTime.of(2026, 11, 26, 14, 34, 39), "2026-11-26 14:34:39",
                LocalDateTime.of(2026, 11, 26, 14, 34, 39), description, cents, amount, 0, "EUR", "COMPLETED");
    }

    @Test
    void pocketName_ptPt() {
        assertThat(PocketRows.pocketName(DEPOSIT)).contains("Reserva de Emergência");
    }

    @Test
    void pocketName_other_empty() {
        assertThat(PocketRows.pocketName("Transfer to a friend")).isEmpty();
        assertThat(PocketRows.pocketName("Carregamento de subconta EUR Setup")).isEmpty();
    }

    @Test
    void isPocketRow_savingsTransfer_true() {
        assertThat(PocketRows.isPocketRow(row("Transferência", "Poupanças", DEPOSIT, "250.00", 25000))).isTrue();
        assertThat(PocketRows.isPocketRow(row("Transfer", "Savings", "Some new wording", "-50.00", -5000)))
                .isTrue();
    }

    @Test
    void isPocketRow_currentWithPocketDescription_true() {
        assertThat(PocketRows.isPocketRow(row("Transferência", "Current", DEPOSIT, "-250.00", -25000))).isTrue();
    }

    @Test
    void isPocketRow_savingsInterest_false() {
        assertThat(PocketRows.isPocketRow(row("Juros", "Poupanças", "Juros brutos", "0.12", 12))).isFalse();
        assertThat(PocketRows.isPocketRow(row("Transferência", "Current", "Transfer to a friend", "-5.00", -500)))
                .isFalse();
    }

    @Test
    void pocketName_english() {
        assertThat(PocketRows.pocketName("To pocket EUR Setup from EUR")).contains("Setup");
        assertThat(PocketRows.pocketName("From pocket EUR Reserva de Emergência to EUR"))
                .contains("Reserva de Emergência");
        assertThat(PocketRows.pocketName("Pocket Withdrawal")).isEmpty();
    }

    @Test
    void isPocketRow_englishExport_true() {
        assertThat(PocketRows.isPocketRow(row("TRANSFER", "Savings", "To pocket EUR Setup from EUR", "100.00", 10000)))
                .isTrue();
        assertThat(PocketRows.isPocketRow(row("Transfer", "Current", "To pocket EUR Setup from EUR", "-100.00", -10000)))
                .isTrue();
        assertThat(PocketRows.isPocketRow(row("TRANSFER", "Current", "Pocket Withdrawal", "40.00", 4000)))
                .isTrue();
    }

    @Test
    void isPocketRow_englishInterest_false() {
        assertThat(PocketRows.isPocketRow(row("INTEREST", "Savings", "Gross interest", "0.12", 12))).isFalse();
        assertThat(PocketRows.isPocketRow(row("TRANSFER", "Current", "To Ana Silva", "-5.00", -500))).isFalse();
    }

    @Test
    void savingsProductFor_englishWording_savings() {
        assertThat(PocketRows.savingsProductFor("To pocket EUR Setup from EUR")).isEqualTo("Savings");
        assertThat(PocketRows.savingsProductFor("Pocket Withdrawal")).isEqualTo("Savings");
    }

    @Test
    void savingsProductFor_followsWording() {
        assertThat(PocketRows.savingsProductFor(DEPOSIT)).isEqualTo("Poupanças");
        assertThat(PocketRows.savingsProductFor("Some new wording")).isEqualTo("Savings");
    }
}
