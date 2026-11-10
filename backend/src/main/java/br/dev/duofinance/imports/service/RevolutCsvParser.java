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
import java.util.Map;

/** Parses a Revolut CSV statement into rows. Pure and stateless; never logs or retains file contents. */
public final class RevolutCsvParser {

    static final int MAX_ROWS = 5000;

    private static final List<String> REQUIRED = List.of(
            "Type", "Product", "Started Date", "Completed Date", "Description", "Amount", "Fee", "Currency", "State",
            "Balance");
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
            columns.putIfAbsent(header.get(i).trim(), i);
        }
        if (!columns.keySet().containsAll(REQUIRED)) {
            throw new ApiException(ErrorCode.IMPORT_FORMAT_UNSUPPORTED);
        }
        return columns;
    }

    private static RevolutRow toRow(Record record, Map<String, Integer> columns) {
        String line = "line " + record.line();
        try {
            String startedRaw = field(record, columns, "Started Date");
            String completedRaw = field(record, columns, "Completed Date");
            String amountRaw = field(record, columns, "Amount");
            String feeRaw = field(record, columns, "Fee");
            String currency = field(record, columns, "Currency");
            String state = field(record, columns, "State");
            if (startedRaw.isEmpty() || amountRaw.isEmpty() || currency.isEmpty() || state.isEmpty()) {
                throw new ApiException(ErrorCode.IMPORT_ROW_INVALID, line);
            }
            return new RevolutRow(
                    record.line(),
                    field(record, columns, "Type"),
                    field(record, columns, "Product"),
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
            throw new ApiException(ErrorCode.IMPORT_ROW_INVALID, line);
        }
    }

    private static long cents(String raw) {
        return new BigDecimal(raw).movePointRight(2).longValueExact();
    }

    private static String field(Record record, Map<String, Integer> columns, String name) {
        int index = columns.get(name);
        if (index >= record.fields().size()) {
            throw new ApiException(ErrorCode.IMPORT_ROW_INVALID, "line " + record.line());
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
            throw new ApiException(ErrorCode.IMPORT_ROW_INVALID, "line " + recordLine);
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
