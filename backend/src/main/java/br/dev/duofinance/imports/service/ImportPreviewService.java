package br.dev.duofinance.imports.service;

import br.dev.duofinance.imports.dto.PreviewResponse;
import br.dev.duofinance.transaction.service.TransactionReferenceValidator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
     * shows the effective mapping; savings products are not listed, as their rows belong to the statement's account.
     */
    @Transactional(readOnly = true)
    public PreviewResponse preview(
            UUID accountId,
            UUID userId,
            byte[] file,
            UUID bankAccountId,
            Map<String, UUID> productMapping) {
        references.validateBankAccount(accountId, bankAccountId);
        if (productMapping != null) {
            productMapping.values().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(id -> references.validateBankAccount(accountId, id));
        }
        List<RevolutRow> rows = RevolutCsvParser.parse(file);
        PlanContext ctx = contexts.load(accountId, userId, bankAccountId, productMapping, rows);
        return PreviewResponse.of(ImportPlanner.plan(rows, ctx), ctx.productMapping());
    }
}
