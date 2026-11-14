package br.dev.duofinance.imports.service;

import br.dev.duofinance.imports.dto.ImportBatchResponse;
import br.dev.duofinance.imports.model.ImportBatch;
import br.dev.duofinance.imports.repository.ImportBatchRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.web.PageRequests;
import br.dev.duofinance.shared.web.PageResponse;
import br.dev.duofinance.transaction.repository.TransactionRepository;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportBatchService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ImportBatchRepository batches;
    private final TransactionRepository transactions;
    private final UserRepository users;
    private final Clock clock;

    public ImportBatchService(
            ImportBatchRepository batches, TransactionRepository transactions, UserRepository users, Clock clock) {
        this.batches = batches;
        this.transactions = transactions;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportBatchResponse> list(UUID accountId, int page, int size) {
        Page<ImportBatch> result = batches.findByAccountId(accountId, PageRequests.of(page, size, NEWEST_FIRST));
        Set<UUID> creatorIds = result.getContent().stream()
                .map(ImportBatch::getCreatedBy)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> names = new HashMap<>();
        for (User u : users.findAllById(creatorIds)) {
            names.put(u.getId(), u.getDisplayName());
        }
        return PageResponse.of(
                result, b -> toResponse(b, b.getCreatedBy() == null ? null : names.get(b.getCreatedBy())));
    }

    /** Deletes every transaction of the batch (edited ones included) and marks it undone; category memory stays. */
    @Transactional
    public void undo(UUID accountId, UUID batchId) {
        ImportBatch batch = batches.findByIdAndAccountIdForUpdate(batchId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (batch.getUndoneAt() != null) {
            throw new ApiException(ErrorCode.IMPORT_ALREADY_UNDONE);
        }
        // marked first: the bulk delete flushes this change and then clears the persistence context
        batch.markUndone(clock.instant());
        transactions.deleteByImportBatch(accountId, batchId);
    }

    private static ImportBatchResponse toResponse(ImportBatch b, String creatorName) {
        return new ImportBatchResponse(
                b.getId(),
                b.getSource(),
                b.getBankAccountId(),
                b.getCreatedBy(),
                creatorName,
                b.getCreatedAt(),
                b.getRowCount(),
                b.getUndoneAt());
    }
}
