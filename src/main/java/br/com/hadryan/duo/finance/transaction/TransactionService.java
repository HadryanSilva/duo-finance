package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.goal.GoalService;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.shared.exception.ResourceNotFoundException;
import br.com.hadryan.duo.finance.transaction.dto.TransactionDtos;
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
    private final GoalService goalService;

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

        // RF37 — verifica metas após salvar despesa
        if (tx.getType() == TransactionType.EXPENSE) {
            goalService.checkAndPublishAlerts(couple.getId(), tx.getCategory());
        }

        return response;
    }

    // ── Listar (paginado + filtros dinâmicos via Specification) ───────────────
    //
    // Usa JpaSpecificationExecutor para construir os predicados em Java.
    // Apenas os filtros não-nulos viram cláusulas WHERE — nenhum parâmetro
    // nulo chega ao PostgreSQL, eliminando o erro "could not determine data type".

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

            // Sempre obrigatório — isolamento por casal
            predicates.add(cb.equal(root.get("couple").get("id"), couple.getId()));

            // Soft delete
            predicates.add(cb.isNull(root.get("deletedAt")));

            // Filtros opcionais — só adicionados ao SQL se não forem null
            if (filter.category() != null) {
                predicates.add(cb.equal(root.get("category"), filter.category()));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("type"), filter.type()));
            }
            if (filter.userId() != null) {
                predicates.add(cb.equal(root.get("user").get("id"), filter.userId()));
            }
            if (filter.startDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("date"), filter.startDate()));
            }
            if (filter.endDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("date"), filter.endDate()));
            }
            if (description != null) {
                predicates.add(cb.like(
                        cb.lower(root.get("description")),
                        "%" + description.toLowerCase() + "%"
                ));
            }

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

    // ── Atualizar ─────────────────────────────────────────────────────────────

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

        var updatedTransaction = repository.save(tx);

        // RF37 — verifica metas após atualizar para despesa
        if (tx.getType() == TransactionType.EXPENSE) {
            goalService.checkAndPublishAlerts(tx.getCouple().getId(), tx.getCategory());
        }

        return toResponse(updatedTransaction);
    }

    // ── Excluir (soft delete) ─────────────────────────────────────────────────

    @Transactional
    public void delete(UUID id, User currentUser) {
        Couple couple = requireCouple(currentUser);
        Transaction tx = requireTransaction(id, couple.getId());
        tx.setDeletedAt(LocalDateTime.now());
        repository.save(tx);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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