package br.com.hadryan.duo.finance.importing.dto;

public class ImportDtos {

    /**
     * Result returned to the frontend after a bank statement import.
     *
     * @param totalFound    Total transactions read from the file (excluding daily balances)
     * @param totalImported Transactions effectively saved
     * @param totalSkipped  Transactions ignored due to deduplication
     */
    public record ImportResult(
            int totalFound,
            int totalImported,
            int totalSkipped
    ) {}
}
