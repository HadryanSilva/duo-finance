package br.dev.duofinance.imports.service;

import br.dev.duofinance.imports.dto.PreviewResponse;
import br.dev.duofinance.transaction.service.TransactionReferenceValidator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportPreviewService {

    private final TransactionReferenceValidator references;
    private final ImportContextLoader contexts;

    public ImportPreviewService(TransactionReferenceValidator references, ImportContextLoader contexts) {
        this.references = references;
        this.contexts = contexts;
    }

    /**
     * Plans the statement without writing anything. Bank accounts are validated before the file is read. The response
     * shows the effective mapping, including the remembered pockets account when it fills the savings product.
     */
    @Transactional(readOnly = true)
    public PreviewResponse preview(
            UUID accountId,
            UUID userId,
            byte[] file,
            UUID bankAccountId,
            Map<String, UUID> productMapping,
            @Nullable UUID pocketsBankAccountId) {
        references.validateBankAccount(accountId, bankAccountId);
        if (productMapping != null) {
            productMapping.values().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(id -> references.validateBankAccount(accountId, id));
        }
        if (pocketsBankAccountId != null) {
            references.validateBankAccount(accountId, pocketsBankAccountId);
        }
        List<RevolutRow> rows = RevolutCsvParser.parse(file);
        PlanContext ctx = contexts.load(accountId, userId, bankAccountId, productMapping, pocketsBankAccountId, rows);
        return PreviewResponse.of(ImportPlanner.plan(rows, ctx), ctx.productMapping());
    }
}
