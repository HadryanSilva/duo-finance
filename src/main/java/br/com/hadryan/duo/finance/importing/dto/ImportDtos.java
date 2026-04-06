package br.com.hadryan.duo.finance.importing.dto;

import br.com.hadryan.duo.finance.transaction.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ImportDtos {

    /**
     * A pair of transactions suspected to be duplicates.
     *
     * @param importedId          ID of the transaction that was just imported
     * @param importedDescription Description from the file
     * @param existingId          ID of the already existing transaction in the system
     * @param existingDescription Description of the existing transaction (possibly edited by user)
     * @param date                Shared date (same for both)
     * @param amount              Shared amount (same for both)
     * @param type                Shared type — INCOME or EXPENSE (same for both)
     */
    public record SuspectedDuplicate(
            UUID importedId,
            String importedDescription,
            UUID existingId,
            String existingDescription,
            LocalDate date,
            BigDecimal amount,
            TransactionType type
    ) {}

    /**
     * Result returned to the frontend after a bank statement import.
     *
     * @param totalFound         Total transactions read from the file
     * @param totalImported      Transactions effectively saved
     * @param totalSkipped       Transactions ignored — import hash already exists
     * @param suspectedDuplicates Imported transactions that share date+amount+type
     *                           with an already existing transaction (possible manual duplicates)
     */
    public record ImportResult(
            int totalFound,
            int totalImported,
            int totalSkipped,
            List<SuspectedDuplicate> suspectedDuplicates
    ) {}
}
