package br.com.hadryan.duo.finance.importing.parser;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and parses OFX (Open Financial Exchange) bank statements.
 *
 * Supports the SGML-based OFX 1.x format exported by Brazilian banks
 * (Nubank, Itaú, Bradesco, etc.).
 *
 * Fields extracted per transaction:
 *  <TRNTYPE>  → CREDIT = income / DEBIT = expense
 *  <DTPOSTED> → date in format YYYYMMDDHHmmss[offset:TZ]
 *  <TRNAMT>   → signed amount (positive = income, negative = expense)
 *  <FITID>    → unique transaction ID from the bank (used for deduplication)
 *  <MEMO>     → description
 */
@Component
public class OfxParser {

    private static final DateTimeFormatter OFX_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    public List<ParsedTransaction> parse(MultipartFile file) throws IOException {
        List<String> lines = readLines(file);
        validateOfx(lines);
        return extractTransactions(lines);
    }

    private List<ParsedTransaction> extractTransactions(List<String> lines) {
        List<ParsedTransaction> result = new ArrayList<>();

        String trnType    = null;
        String dtPosted   = null;
        String trnAmt     = null;
        String fitId      = null;
        String memo       = null;
        boolean inStmtTrn = false;

        for (String raw : lines) {
            String line = raw.trim();

            if (line.equalsIgnoreCase("<STMTTRN>")) {
                inStmtTrn = true;
                trnType = dtPosted = trnAmt = fitId = memo = null;
                continue;
            }

            if (line.equalsIgnoreCase("</STMTTRN>")) {
                if (inStmtTrn && trnAmt != null && dtPosted != null) {
                    ParsedTransaction pt = buildTransaction(trnType, dtPosted, trnAmt, fitId, memo);
                    if (pt != null) result.add(pt);
                }
                inStmtTrn = false;
                continue;
            }

            if (!inStmtTrn) continue;

            trnType  = extractTag(line, "TRNTYPE",  trnType);
            dtPosted = extractTag(line, "DTPOSTED", dtPosted);
            trnAmt   = extractTag(line, "TRNAMT",   trnAmt);
            fitId    = extractTag(line, "FITID",    fitId);
            memo     = extractTag(line, "MEMO",     memo);
        }

        return result;
    }

    private ParsedTransaction buildTransaction(
            String trnType, String dtPosted, String trnAmt, String fitId, String memo) {
        try {
            double rawValue = Double.parseDouble(trnAmt.trim().replace(",", "."));
            BigDecimal amount = BigDecimal.valueOf(Math.abs(rawValue))
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            // TRNTYPE é a fonte primária; o sinal do valor confirma
            TransactionType type;
            if ("CREDIT".equalsIgnoreCase(trnType)) {
                type = TransactionType.INCOME;
            } else {
                type = rawValue >= 0 ? TransactionType.INCOME : TransactionType.EXPENSE;
            }

            LocalDate date = parseDate(dtPosted);
            if (date == null) return null;

            String description = memo != null ? memo.trim() : "";
            TransactionCategory category = mapCategory(description, type);

            return new ParsedTransaction(date, amount, type, category, description, fitId);

        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Category mapping via MEMO keywords ───────────────────────────────────

    TransactionCategory mapCategory(String memo, TransactionType type) {
        if (type == TransactionType.INCOME) {
            return TransactionCategory.OTHER_INCOME;
        }

        String m = normalize(memo);

        if (m.contains("salario") || m.contains("salário") || m.contains("folha")) {
            return TransactionCategory.SALARY;
        }
        if (m.contains("farmacia") || m.contains("drogaria") || m.contains("clinica")
                || m.contains("hospital") || m.contains("medico") || m.contains("saude")
                || m.contains("laboratorio") || m.contains("plano de saude")) {
            return TransactionCategory.HEALTH;
        }
        if (m.contains("posto") || m.contains("combustivel") || m.contains("gasolina")
                || m.contains("uber") || m.contains("99 taxi") || m.contains("pedágio")
                || m.contains("pedagio") || m.contains("estacionamento")) {
            return TransactionCategory.TRANSPORT;
        }
        if (m.contains("supermercado") || m.contains("mercado") || m.contains("hortifruti")
                || m.contains("atacadao") || m.contains("assai") || m.contains("carrefour")
                || m.contains("extra ")) {
            return TransactionCategory.SUPERMARKET;
        }
        if (m.contains("restaurante") || m.contains("lanchonete") || m.contains("ifood")
                || m.contains("delivery") || m.contains("pizza") || m.contains("hamburguer")
                || m.contains("padaria") || m.contains("cafe ") || m.contains("bar ")) {
            return TransactionCategory.FOOD;
        }
        if (m.contains("escola") || m.contains("faculdade") || m.contains("universidade")
                || m.contains("curso") || m.contains("mensalidade") || m.contains("colegio")) {
            return TransactionCategory.EDUCATION;
        }
        if (m.contains("netflix") || m.contains("spotify") || m.contains("amazon prime")
                || m.contains("disney") || m.contains("hbo") || m.contains("apple")
                || m.contains("youtube") || m.contains("assinatura")) {
            return TransactionCategory.SUBSCRIPTIONS;
        }
        if (m.contains("telefonica") || m.contains("vivo") || m.contains("claro")
                || m.contains("tim ") || m.contains("oi ") || m.contains("internet")
                || m.contains("energia") || m.contains("agua") || m.contains("luz ")
                || m.contains("saneago") || m.contains("chesp") || m.contains("cemig")
                || m.contains("copel") || m.contains("fatura")) {
            return TransactionCategory.SERVICES;
        }
        if (m.contains("aluguel") || m.contains("condominio") || m.contains("iptu")) {
            return TransactionCategory.HOUSING;
        }
        if (m.contains("roupa") || m.contains("calcado") || m.contains("moda")
                || m.contains("renner") || m.contains("c&a") || m.contains("riachuelo")
                || m.contains("marisa") || m.contains("salon") || m.contains("cabelo")
                || m.contains("beleza")) {
            return TransactionCategory.CLOTHING;
        }
        if (m.contains("pet") || m.contains("veterinario") || m.contains("racao")) {
            return TransactionCategory.PETS;
        }
        if (m.contains("cinema") || m.contains("teatro") || m.contains("show ")
                || m.contains("ingresso") || m.contains("parque") || m.contains("viagem")
                || m.contains("hotel") || m.contains("passagem")) {
            return TransactionCategory.LEISURE;
        }

        return TransactionCategory.OTHER_EXPENSE;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateOfx(List<String> lines) {
        boolean hasOfxHeader = lines.stream()
                .limit(10)
                .anyMatch(l -> l.trim().startsWith("OFXHEADER:") || l.trim().equalsIgnoreCase("<OFX>"));
        if (!hasOfxHeader) {
            throw new IllegalArgumentException(
                    "Arquivo inválido: não é um extrato OFX reconhecido. " +
                            "Verifique se o arquivo foi exportado corretamente pelo seu banco.");
        }
    }

    private List<String> readLines(MultipartFile file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Extracts the value of an OFX SGML tag from a line.
     * Example: "<TRNAMT>-19.99" → "-19.99"
     * Returns the existing value unchanged if the tag is not present on this line.
     */
    private String extractTag(String line, String tag, String current) {
        String open = "<" + tag + ">";
        if (line.toUpperCase().startsWith(open.toUpperCase())) {
            return line.substring(open.length()).trim();
        }
        return current;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.length() < 8) return null;
        try {
            return LocalDate.parse(raw.trim().substring(0, 8), OFX_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim()
                .toLowerCase()
                .replaceAll("[áàãâä]", "a")
                .replaceAll("[éèêë]", "e")
                .replaceAll("[íìîï]", "i")
                .replaceAll("[óòõôö]", "o")
                .replaceAll("[úùûü]", "u")
                .replaceAll("[ç]", "c");
    }

    // ── Parsed row record ─────────────────────────────────────────────────────

    public record ParsedTransaction(
            LocalDate date,
            BigDecimal amount,
            TransactionType type,
            TransactionCategory category,
            String description,
            String fitId           // null quando não disponível
    ) {}
}
