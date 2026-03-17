package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.goal.GoalService;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.shared.exception.ResourceNotFoundException;
import br.com.hadryan.duo.finance.transaction.dto.TransactionDtos;
import br.com.hadryan.duo.finance.transaction.dto.TransactionDtos.RecurringScope;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.User;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final GoalService           goalService;

    // ── Criar ─────────────────────────────────────────────────────────────────

    @Transactional
    public TransactionDtos.TransactionResponse create(
            TransactionDtos.CreateTransactionRequest request,
            User currentUser
    ) {
        Couple couple = requireCouple(currentUser);

        Transaction tx = new Transaction();
        tx.setCouple(couple);
        tx.setUser(currentUser);
        tx.setCategory(request.category());
        tx.setType(request.category().getType());
        tx.setAmount(request.amount());
        tx.setDescription(request.description());
        tx.setDate(request.date());
        tx.setRecurring(request.recurring());
        tx.setRecurrenceRule(request.recurrenceRule());
        tx.setRecurrenceEndDate(request.recurrenceEndDate());

        TransactionDtos.TransactionResponse response = toResponse(repository.save(tx));

        if (tx.getType() == TransactionType.EXPENSE) {
            goalService.checkAndPublishAlerts(couple.getId(), tx.getCategory());
        }

        return response;
    }

    // ── Listar ────────────────────────────────────────────────────────────────

    @Transactional
    public Page<TransactionDtos.TransactionResponse> findAll(
            TransactionDtos.TransactionFilter filter,
            User currentUser,
            Pageable pageable
    ) {
        Couple couple = requireCouple(currentUser);

        String description = (filter.description() != null && !filter.description().isBlank())
                ? filter.description().trim()
                : null;

        Specification<Transaction> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("couple").get("id"), couple.getId()));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (filter.category() != null)
                predicates.add(cb.equal(root.get("category"), filter.category()));
            if (filter.type() != null)
                predicates.add(cb.equal(root.get("type"), filter.type()));
            if (filter.userId() != null)
                predicates.add(cb.equal(root.get("user").get("id"), filter.userId()));
            if (filter.startDate() != null)
                predicates.add(cb.greaterThanOrEqualTo(root.get("date"), filter.startDate()));
            if (filter.endDate() != null)
                predicates.add(cb.lessThanOrEqualTo(root.get("date"), filter.endDate()));
            if (description != null)
                predicates.add(cb.like(cb.lower(root.get("description")),
                        "%" + description.toLowerCase() + "%"));

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return repository.findAll(spec, pageable).map(this::toResponse);
    }

    // ── Buscar por ID ─────────────────────────────────────────────────────────

    @Transactional
    public TransactionDtos.TransactionResponse findById(UUID id, User currentUser) {
        Couple couple = requireCouple(currentUser);
        return toResponse(requireTransaction(id, couple.getId()));
    }

    // ── Atualizar (transação simples) ─────────────────────────────────────────

    @Transactional
    public TransactionDtos.TransactionResponse update(
            UUID id,
            TransactionDtos.UpdateTransactionRequest request,
            User currentUser
    ) {
        Couple couple = requireCouple(currentUser);
        Transaction tx = requireTransaction(id, couple.getId());

        tx.setCategory(request.category());
        tx.setType(request.category().getType());
        tx.setAmount(request.amount());
        tx.setDescription(request.description());
        tx.setDate(request.date());

        var saved = repository.save(tx);

        if (tx.getType() == TransactionType.EXPENSE) {
            goalService.checkAndPublishAlerts(tx.getCouple().getId(), tx.getCategory());
        }

        return toResponse(saved);
    }

    // ── RF42: Atualizar série recorrente ──────────────────────────────────────

    @Transactional
    public TransactionDtos.TransactionResponse updateRecurring(
            UUID id,
            TransactionDtos.UpdateRecurringRequest request,
            User currentUser
    ) {
        Couple couple = requireCouple(currentUser);
        Transaction tx = requireTransaction(id, couple.getId());

        if (request.scope() == RecurringScope.SINGLE) {
            // Comportamento simples — edita só esta ocorrência
            tx.setCategory(request.category());
            tx.setType(request.category().getType());
            tx.setAmount(request.amount());
            tx.setDescription(request.description());
            tx.setDate(request.date());
            return toResponse(repository.save(tx));
        }

        // THIS_AND_FUTURE — resolve o pai
        Transaction parent = resolveParent(tx);

        // 1. Encerra a série atual no dia anterior a esta ocorrência
        if (!tx.getId().equals(parent.getId())) {
            // tx é filho — encerra o pai antes da data desta ocorrência
            parent.setRecurrenceEndDate(tx.getDate().minusDays(1));
            repository.save(parent);
        } else {
            // tx é o próprio pai — soft-delete a si mesmo mais abaixo
            parent.setRecurrenceEndDate(tx.getDate().minusDays(1));
            repository.save(parent);
        }

        // 2. Soft-delete dos filhos a partir desta data (inclusive)
        repository.softDeleteFutureChildren(parent.getId(), tx.getDate(), LocalDateTime.now());

        // 3. Se tx for filho, soft-delete dele também
        if (!tx.getId().equals(parent.getId())) {
            tx.setDeletedAt(LocalDateTime.now());
            repository.save(tx);
        }

        // 4. Cria novo pai com os dados atualizados a partir da data escolhida
        Transaction newParent = new Transaction();
        newParent.setCouple(parent.getCouple());
        newParent.setUser(currentUser);
        newParent.setCategory(request.category());
        newParent.setType(request.category().getType());
        newParent.setAmount(request.amount());
        newParent.setDescription(request.description());
        newParent.setDate(request.date());
        newParent.setRecurring(true);
        newParent.setRecurrenceRule(parent.getRecurrenceRule());
        newParent.setRecurrenceEndDate(parent.getRecurrenceEndDate() != null &&
                parent.getRecurrenceEndDate().isAfter(request.date())
                ? parent.getRecurrenceEndDate() : null);

        var saved = repository.save(newParent);

        if (saved.getType() == TransactionType.EXPENSE) {
            goalService.checkAndPublishAlerts(couple.getId(), saved.getCategory());
        }

        return toResponse(saved);
    }

    // ── Excluir (soft delete simples) ─────────────────────────────────────────

    @Transactional
    public void delete(UUID id, User currentUser) {
        Couple couple = requireCouple(currentUser);
        Transaction tx = requireTransaction(id, couple.getId());
        tx.setDeletedAt(LocalDateTime.now());
        repository.save(tx);
    }

    // ── RF43: Excluir série recorrente ────────────────────────────────────────

    @Transactional
    public void deleteRecurring(
            UUID id,
            TransactionDtos.DeleteRecurringRequest request,
            User currentUser
    ) {
        Couple couple = requireCouple(currentUser);
        Transaction tx = requireTransaction(id, couple.getId());
        LocalDateTime now = LocalDateTime.now();

        switch (request.scope()) {
            case SINGLE -> {
                // Apenas esta ocorrência
                tx.setDeletedAt(now);
                repository.save(tx);
            }
            case THIS_AND_FUTURE -> {
                Transaction parent = resolveParent(tx);

                // Encerra a série antes desta data
                parent.setRecurrenceEndDate(tx.getDate().minusDays(1));
                repository.save(parent);

                // Soft-delete dos filhos a partir desta data (inclusive)
                repository.softDeleteFutureChildren(parent.getId(), tx.getDate(), now);

                // Se tx for filho, soft-delete dele também
                if (!tx.getId().equals(parent.getId())) {
                    tx.setDeletedAt(now);
                    repository.save(tx);
                }
            }
            case ALL -> {
                Transaction parent = resolveParent(tx);

                // Soft-delete de todos os filhos
                repository.softDeleteAllChildren(parent.getId(), now);

                // Soft-delete do pai
                parent.setDeletedAt(now);
                repository.save(parent);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolve o pai de uma transação.
     * Se tx já é pai (recurring = true), retorna ele mesmo.
     * Se tx é filho, retorna o pai via parentTransaction.
     */
    private Transaction resolveParent(Transaction tx) {
        if (tx.isRecurring()) return tx;

        if (tx.getParentTransaction() != null) {
            return repository.findByIdWithUser(tx.getParentTransaction().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Série recorrente não encontrada"));
        }

        throw new BusinessException("Esta transação não pertence a uma série recorrente.");
    }

    private Couple requireCouple(User user) {
        if (user.getCouple() == null) {
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        }
        return user.getCouple();
    }

    private Transaction requireTransaction(UUID id, UUID coupleId) {
        return repository.findByIdAndCoupleId(id, coupleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lançamento não encontrado: " + id));
    }

    private TransactionDtos.TransactionResponse toResponse(Transaction tx) {
        return new TransactionDtos.TransactionResponse(
                tx.getId(),
                tx.getCategory(),
                tx.getCategory().getLabel(),
                tx.getType(),
                tx.getAmount(),
                tx.getDescription(),
                tx.getDate(),
                tx.isRecurring(),
                tx.getRecurrenceRule(),
                tx.getRecurrenceEndDate(),
                tx.getParentTransaction() != null ? tx.getParentTransaction().getId() : null,
                new TransactionDtos.AuthorResponse(
                        tx.getUser().getId(),
                        tx.getUser().getFirstName(),
                        tx.getUser().getLastName(),
                        tx.getUser().getAvatarUrl()
                ),
                tx.getCreatedAt()
        );
    }
}