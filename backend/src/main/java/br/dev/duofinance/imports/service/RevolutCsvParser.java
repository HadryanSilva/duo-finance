package br.dev.duofinance.imports.service;

import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses a Revolut CSV statement into rows. Pure and stateless; never logs or retains file contents. */
public final class RevolutCsvParser {

    static final int MAX_ROWS = 5000;

    private static final List<String> REQUIRED = List.of(
            "Type",
            "Product",
            "Started Date",
            "Completed Date",
            "Description",
            "Amount",
            "Fee",
            "Currency",
            "State",
            "Balance");
    /**
     * Header names of localized exports (lower-cased) mapped to the canonical English column. Revolut translates the
     * header and some values to the app language; everything past this parser works with the English forms.
     */
    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            // pt-PT and pt-BR share all but Amount and Fee
            Map.entry("tipo", "Type"),
            Map.entry("produto", "Product"),
            Map.entry("data de início", "Started Date"),
            Map.entry("data de conclusão", "Completed Date"),
            Map.entry("descrição", "Description"),
            Map.entry("montante", "Amount"),
            Map.entry("valor", "Amount"),
            Map.entry("comissão", "Fee"),
            Map.entry("taxa", "Fee"),
            Map.entry("moeda", "Currency"),
            Map.entry("estado", "State"),
            Map.entry("saldo", "Balance"));
    /** Localized {@code State} values mapped to the canonical English ones the planner checks. */
    private static final Map<String, String> STATE_ALIASES = Map.of(
            // pt-PT
            "CONCLUÍDA", "COMPLETED",
            "PENDENTE", "PENDING", // pt-BR too (matching ignores case)
            "REVERTIDA", "REVERTED",
            "RECUSADA", "DECLINED",
            // pt-BR
            "Concluído(a)", "COMPLETED",
            "Revertido(a)", "REVERTED",
            "Recusado(a)", "DECLINED");
    /** Localized {@code Product} values mapped to the canonical English ones (default mapping, fingerprints). */
    private static final Map<String, String> PRODUCT_ALIASES = Map.of("Atual", "Current");
    /** Localized {@code Type} values the planner acts on, mapped to the canonical English ones. */
    private static final Map<String, String> TYPE_ALIASES = Map.of("Câmbio", "EXCHANGE");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** A record's fields plus the 1-based physical line it starts on. */
    private record Record(int line, List<String> fields) {}

    private RevolutCsvParser() {}

    public static List<RevolutRow> parse(byte[] file) {
        List<Record> records = readRecords(new String(file, StandardCharsets.UTF_8));
        if (records.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_FORMAT_UNSUPPORTED);
        }
        Map<String, Integer> columns = indexHeader(records.get(0).fields());

        int dataRows = records.size() - 1;
        if (dataRows > MAX_ROWS) {
            throw new ApiException(ErrorCode.IMPORT_TOO_MANY_ROWS);
        }
        List<RevolutRow> rows = new ArrayList<>(dataRows);
        for (Record record : records.subList(1, records.size())) {
            rows.add(toRow(record, columns));
        }
        return rows;
    }

    private static Map<String, Integer> indexHeader(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.putIfAbsent(canonicalColumn(header.get(i)), i);
        }
        if (!columns.keySet().containsAll(REQUIRED)) {
            throw new ApiException(ErrorCode.IMPORT_FORMAT_UNSUPPORTED);
        }
        return columns;
    }

    private static String canonicalColumn(String name) {
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String required : REQUIRED) {
            if (required.toLowerCase(Locale.ROOT).equals(lower)) {
                return required;
            }
        }
        return HEADER_ALIASES.getOrDefault(lower, trimmed);
    }

    private static String canonical(Map<String, String> aliases, String value) {
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (alias.getKey().equalsIgnoreCase(value)) {
                return alias.getValue();
            }
        }
        return value;
    }

    private static RevolutRow toRow(Record record, Map<String, Integer> columns) {
        try {
            String startedRaw = field(record, columns, "Started Date");
            String completedRaw = field(record, columns, "Completed Date");
            String amountRaw = field(record, columns, "Amount");
            String feeRaw = field(record, columns, "Fee");
            String currency = field(record, columns, "Currency");
            String state = canonical(STATE_ALIASES, field(record, columns, "State"));
            if (startedRaw.isEmpty() || amountRaw.isEmpty() || currency.isEmpty() || state.isEmpty()) {
                throw ApiException.importRowInvalid(record.line());
            }
            return new RevolutRow(
                    record.line(),
                    canonical(TYPE_ALIASES, field(record, columns, "Type")),
                    canonical(PRODUCT_ALIASES, field(record, columns, "Product")),
                    LocalDateTime.parse(startedRaw, DATE_TIME),
                    startedRaw,
                    completedRaw.isEmpty() ? null : LocalDateTime.parse(completedRaw, DATE_TIME),
                    field(record, columns, "Description"),
                    cents(amountRaw),
                    amountRaw,
                    feeRaw.isEmpty() ? 0 : cents(feeRaw),
                    currency,
                    state);
        } catch (DateTimeParseException | ArithmeticException | NumberFormatException e) {
            throw ApiException.importRowInvalid(record.line());
        }
    }

    private static long cents(String raw) {
        return new BigDecimal(raw).movePointRight(2).longValueExact();
    }

    private static String field(Record record, Map<String, Integer> columns, String name) {
        int index = columns.get(name);
        if (index >= record.fields().size()) {
            throw ApiException.importRowInvalid(record.line());
        }
        return record.fields().get(index).trim();
    }

    /** Minimal RFC 4180 reader: quoted fields, {@code ""} escapes, LF or CRLF records, BOM and blank lines skipped. */
    private static List<Record> readRecords(String text) {
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        List<Record> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                endRecord(records, fields, field, recordLine);
                line++;
                recordLine = line;
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw ApiException.importRowInvalid(recordLine);
        }
        endRecord(records, fields, field, recordLine);
        return records;
    }

    private static void endRecord(List<Record> records, List<String> fields, StringBuilder field, int line) {
        fields.add(field.toString());
        field.setLength(0);
        boolean blank = fields.size() == 1 && fields.get(0).isBlank();
        if (!blank) {
            records.add(new Record(line, new ArrayList<>(fields)));
        }
        fields.clear();
    }
}
