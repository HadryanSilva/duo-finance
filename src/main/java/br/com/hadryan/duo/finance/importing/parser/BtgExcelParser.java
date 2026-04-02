package br.com.hadryan.duo.finance.importing.parser;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class BtgExcelParser {

    private static final DateTimeFormatter BTG_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final DateTimeFormatter BTG_DATE_ONLY_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Índices das colunas relevantes no sheet
    private static final int COL_DATETIME    = 1;
    private static final int COL_CATEGORY    = 2;
    private static final int COL_TRANSACTION = 3;
    private static final int COL_DESCRIPTION = 6;
    private static final int COL_VALUE       = 10;

    // Primeira linha de dados (0-indexed); as anteriores são cabeçalho
    private static final int FIRST_DATA_ROW = 11;

    public List<ParsedTransaction> parse(MultipartFile file) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheet("Extrato");
            if (sheet == null) {
                throw new IllegalArgumentException(
                        "Arquivo inválido: sheet 'Extrato' não encontrado. " +
                                "Verifique se o arquivo é um extrato BTG Pactual.");
            }
            return extractRows(sheet);
        }
    }

    private List<ParsedTransaction> extractRows(Sheet sheet) {
        List<ParsedTransaction> result = new ArrayList<>();

        for (int i = FIRST_DATA_ROW; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            String dateStr    = stringValue(row, COL_DATETIME);
            String btgCat     = stringValue(row, COL_CATEGORY);
            String btgTxType  = stringValue(row, COL_TRANSACTION);
            String description = stringValue(row, COL_DESCRIPTION);
            Double rawValue   = numericValue(row, COL_VALUE);

            // Ignora linhas sem data ou sem valor numérico
            if (dateStr.isBlank() || rawValue == null) continue;

            // Ignora linhas de saldo diário
            if ("Saldo Diário".equalsIgnoreCase(description.trim())) continue;

            LocalDate date = parseDate(dateStr);
            if (date == null) continue;

            BigDecimal amount = BigDecimal.valueOf(Math.abs(rawValue))
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            TransactionType type = rawValue >= 0 ? TransactionType.INCOME : TransactionType.EXPENSE;
            TransactionCategory category = mapCategory(btgCat, btgTxType, type);

            result.add(new ParsedTransaction(
                    date,
                    amount,
                    type,
                    category,
                    buildDescription(btgTxType, description)
            ));
        }

        return result;
    }

    // ── Mapeamento de categorias BTG → DuoFinance ─────────────────────────────

    /**
     * Mapeia a categoria e o tipo de transação do BTG para o enum TransactionCategory.
     * Regra de prioridade: primeiro verifica a categoria BTG, depois o tipo de transação.
     */
    TransactionCategory mapCategory(String btgCategory, String btgTxType, TransactionType type) {
        String cat = normalize(btgCategory);
        String tx  = normalize(btgTxType);

        if (type == TransactionType.INCOME) {
            return TransactionCategory.OTHER_INCOME;
        }

        return switch (cat) {
            case "saude"             -> TransactionCategory.HEALTH;
            case "transporte"        -> TransactionCategory.TRANSPORT;
            case "compras"           -> TransactionCategory.OTHER_EXPENSE;
            case "investimentos"     -> TransactionCategory.OTHER_EXPENSE;
            case "tarifas"           -> TransactionCategory.SERVICES;
            case "contas" -> {
                if (tx.contains("fatura")) yield TransactionCategory.OTHER_EXPENSE;
                yield TransactionCategory.SERVICES;
            }
            case "transferencia", "outra categoria" -> TransactionCategory.OTHER_EXPENSE;
            default -> TransactionCategory.OTHER_EXPENSE;
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildDescription(String btgTxType, String counterpart) {
        String desc = counterpart.trim();
        if (desc.isBlank()) return btgTxType.trim();
        return desc;
    }

    private LocalDate parseDate(String raw) {
        String value = raw.trim();
        try {
            return LocalDateTime.parse(value, BTG_DATE_FORMAT).toLocalDate();
        } catch (Exception e) {
            try {
                return LocalDate.parse(value, BTG_DATE_ONLY_FORMAT);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private String stringValue(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    // BTG exporta a data como string, mas por segurança tratamos os dois casos
                    yield cell.getLocalDateTimeCellValue()
                            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
                }
                yield String.valueOf((long) cell.getNumericCellValue());
            }
            default -> "";
        };
    }

    private Double numericValue(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
        if (cell.getCellType() == CellType.STRING) {
            try {
                return Double.parseDouble(cell.getStringCellValue().replace(",", "."));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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

    // ── Record que representa uma linha parseada ──────────────────────────────

    public record ParsedTransaction(
            LocalDate date,
            BigDecimal amount,
            TransactionType type,
            TransactionCategory category,
            String description
    ) {}
}
