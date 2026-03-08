package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.shared.exception.ResourceNotFoundException;
import br.com.hadryan.duo.finance.transaction.dto.TransactionDtos;
import br.com.hadryan.duo.finance.user.User;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class TransactionService {

    private final TransactionRepository repository;

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
        tx.setType(request.category().getType());   // derivado do enum — nunca inconsistente
        tx.setAmount(request.amount());
        tx.setDescription(request.description());
        tx.setDate(request.date());
        tx.setRecurring(request.recurring());
        tx.setRecurrenceRule(request.recurrenceRule());
        tx.setRecurrenceEndDate(request.recurrenceEndDate());

        return toResponse(repository.save(tx));
    }

    // ── Listar (paginado + filtros) ───────────────────────────────────────────

    @Transactional
    public Page<TransactionDtos.TransactionResponse> findAll(
            TransactionDtos.TransactionFilter filter,
            User currentUser,
            Pageable pageable
    ) {
        Couple couple = requireCouple(currentUser);

        return repository.findAllWithFilters(
                couple.getId(),
                filter.category(),
                filter.type(),
                filter.userId(),
                filter.startDate(),
                filter.endDate(),
                pageable
        ).map(this::toResponse);
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

        return toResponse(repository.save(tx));
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
