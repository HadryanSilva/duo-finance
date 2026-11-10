package br.dev.duofinance.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RevolutCsvParserTest {

    private static final String HEADER =
            "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\n";

    private static final String ROW =
            "CARD_PAYMENT,Current,2026-10-03 12:04:51,2026-10-04 08:15:02,Cafe,-12.50,0.00,EUR,COMPLETED,1.00\n";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sample() throws IOException {
        try (var in = RevolutCsvParserTest.class.getResourceAsStream("/imports/revolut-sample.csv")) {
            return in.readAllBytes();
        }
    }

    private static void assertCode(byte[] file, ErrorCode code) {
        assertThatThrownBy(() -> RevolutCsvParser.parse(file))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void parse_sampleFile_readsAllRows() throws IOException {
        List<RevolutRow> rows = RevolutCsvParser.parse(sample());

        assertThat(rows).hasSize(16);
        RevolutRow first = rows.get(0);
        assertThat(first.lineNumber()).isEqualTo(2);
        assertThat(first.type()).isEqualTo("CARD_PAYMENT");
        assertThat(first.product()).isEqualTo("Current");
        assertThat(first.startedAt()).isEqualTo(LocalDateTime.of(2026, 10, 3, 12, 4, 51));
        assertThat(first.startedRaw()).isEqualTo("2026-10-03 12:04:51");
        assertThat(first.completedAt()).isEqualTo(LocalDateTime.of(2026, 10, 4, 8, 15, 2));
        assertThat(first.description()).isEqualTo("Cafe Aurora");
        assertThat(first.amountCents()).isEqualTo(-1250);
        assertThat(first.amountRaw()).isEqualTo("-12.50");
        assertThat(first.feeCents()).isZero();
        assertThat(first.currency()).isEqualTo("EUR");
        assertThat(first.state()).isEqualTo("COMPLETED");
        assertThat(rows.get(14).description().length()).isGreaterThan(140);
    }

    @Test
    void parse_headerInAnyOrderWithExtraColumns_works() {
        String csv = "Extra,State,Currency,Fee,Amount,Description,Completed Date,Started Date,Product,Type,Balance\n"
                + "x,COMPLETED,EUR,0.10,-3.00,Shop,2026-10-04 08:15:02,2026-10-03 12:04:51,Current,CARD_PAYMENT,9\n";

        List<RevolutRow> rows = RevolutCsvParser.parse(bytes(csv));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).isEqualTo("Shop");
        assertThat(rows.get(0).amountCents()).isEqualTo(-300);
        assertThat(rows.get(0).feeCents()).isEqualTo(10);
        assertThat(rows.get(0).state()).isEqualTo("COMPLETED");
    }

    @Test
    void parse_missingColumn_throwsFormatUnsupported() {
        String csv = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,State,Balance\n";

        assertCode(bytes(csv), ErrorCode.IMPORT_FORMAT_UNSUPPORTED);
    }

    @Test
    void parse_semicolonSeparated_throwsFormatUnsupported() {
        String csv = HEADER.replace(',', ';') + ROW.replace(',', ';');

        assertCode(bytes(csv), ErrorCode.IMPORT_FORMAT_UNSUPPORTED);
    }

    @Test
    void parse_crlfAndBom_work() {
        String csv = "﻿" + (HEADER + ROW).replace("\n", "\r\n");

        List<RevolutRow> rows = RevolutCsvParser.parse(bytes(csv));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).state()).isEqualTo("COMPLETED");
    }

    @Test
    void parse_quotedFieldWithComma_keepsComma() {
        String csv = HEADER
                + "CARD_PAYMENT,Current,2026-10-03 12:04:51,2026-10-04 08:15:02,\"Amazon, Inc.\",-1.00,0.00,EUR,COMPLETED,1\n";

        assertThat(RevolutCsvParser.parse(bytes(csv)).get(0).description()).isEqualTo("Amazon, Inc.");
    }

    @Test
    void parse_escapedQuote_isUnescaped() {
        String csv = HEADER
                + "CARD_PAYMENT,Current,2026-10-03 12:04:51,2026-10-04 08:15:02,\"The \"\"Big\"\" Shop\",-1.00,0.00,EUR,COMPLETED,1\n";

        assertThat(RevolutCsvParser.parse(bytes(csv)).get(0).description()).isEqualTo("The \"Big\" Shop");
    }

    @Test
    void parse_blankCompletedDate_isNull() {
        String csv = HEADER + "CARD_PAYMENT,Current,2026-10-03 12:04:51,,Cafe,-1.00,0.00,EUR,PENDING,1\n";

        assertThat(RevolutCsvParser.parse(bytes(csv)).get(0).completedAt()).isNull();
    }

    @Test
    void parse_badAmount_throwsRowInvalidWithLine() {
        String csv = HEADER + ROW
                + "CARD_PAYMENT,Current,2026-10-03 12:04:51,2026-10-04 08:15:02,Cafe,abc,0.00,EUR,COMPLETED,1\n";

        assertThatThrownBy(() -> RevolutCsvParser.parse(bytes(csv)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.IMPORT_ROW_INVALID);
                    assertThat(e.getMessage()).isEqualTo("line 3");
                });
    }

    @Test
    void parse_amountWithThreeDecimals_throwsRowInvalid() {
        String csv = HEADER
                + "CARD_PAYMENT,Current,2026-10-03 12:04:51,2026-10-04 08:15:02,Cafe,-1.005,0.00,EUR,COMPLETED,1\n";

        assertCode(bytes(csv), ErrorCode.IMPORT_ROW_INVALID);
    }

    @Test
    void parse_badStartedDate_throwsRowInvalid() {
        String csv = HEADER + "CARD_PAYMENT,Current,03/10/2026,,Cafe,-1.00,0.00,EUR,PENDING,1\n";

        assertCode(bytes(csv), ErrorCode.IMPORT_ROW_INVALID);
    }

    @Test
    void parse_5001Rows_throwsTooManyRows() {
        String csv = HEADER + ROW.repeat(5001);

        assertCode(bytes(csv), ErrorCode.IMPORT_TOO_MANY_ROWS);
    }

    @Test
    void parse_5000Rows_works() {
        String csv = HEADER + ROW.repeat(5000) + "\n\n";

        assertThat(RevolutCsvParser.parse(bytes(csv))).hasSize(5000);
    }
}
