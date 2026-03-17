package br.com.hadryan.duo.finance.category;

import br.com.hadryan.duo.finance.category.dto.CustomCategoryDtos;
import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.shared.exception.ResourceNotFoundException;
import br.com.hadryan.duo.finance.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomCategoryService {

    private static final String DEFAULT_ICON = "pi pi-tag";

    private final CustomCategoryRepository repository;

    // ── RF30: Criar ───────────────────────────────────────────────────────────

    @Transactional
    public CustomCategoryDtos.CustomCategoryResponse create(
            CustomCategoryDtos.CreateRequest request,
            User currentUser
    ) {
        Couple couple = requireCouple(currentUser);

        if (repository.existsByCoupleIdAndNameIgnoreCase(couple.getId(), request.name().trim())) {
            throw new BusinessException("Já existe uma categoria com o nome \"" + request.name().trim() + "\".");
        }

        CustomCategory cat = new CustomCategory();
        cat.setCouple(couple);
        cat.setName(request.name().trim());
        cat.setType(request.type());
        cat.setIcon(request.icon() != null && !request.icon().isBlank() ? request.icon().trim() : DEFAULT_ICON);

        return toResponse(repository.save(cat));
    }

    // ── RF31: Atualizar ───────────────────────────────────────────────────────

    @Transactional
    public CustomCategoryDtos.CustomCategoryResponse update(
            UUID id,
            CustomCategoryDtos.UpdateRequest request,
            User currentUser
    ) {
        Couple couple = requireCouple(currentUser);
        CustomCategory cat = requireCategory(id, couple.getId());

        if (repository.existsByCoupleIdAndNameIgnoreCaseAndIdNot(
                couple.getId(), request.name().trim(), id)) {
            throw new BusinessException("Já existe uma categoria com o nome \"" + request.name().trim() + "\".");
        }

        cat.setName(request.name().trim());
        if (request.icon() != null && !request.icon().isBlank()) {
            cat.setIcon(request.icon().trim());
        }

        return toResponse(repository.save(cat));
    }

    // ── RF31: Excluir — bloqueia se há transações ─────────────────────────────

    @Transactional
    public void delete(UUID id, User currentUser) {
        Couple couple = requireCouple(currentUser);
        CustomCategory cat = requireCategory(id, couple.getId());

        if (repository.hasActiveTransactions(id)) {
            throw new BusinessException(
                    "Não é possível excluir \"" + cat.getName() + "\" pois há transações vinculadas a ela. " +
                            "Reatribua as transações antes de excluir a categoria."
            );
        }

        repository.delete(cat);
    }

    // ── RF31: Ativar / pausar ─────────────────────────────────────────────────

    @Transactional
    public CustomCategoryDtos.CustomCategoryResponse toggleActive(UUID id, User currentUser) {
        Couple couple = requireCouple(currentUser);
        CustomCategory cat = requireCategory(id, couple.getId());
        cat.setActive(!cat.isActive());
        return toResponse(repository.save(cat));
    }

    // ── Listar (todas, incluindo inativas) ────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CustomCategoryDtos.CustomCategoryResponse> listAll(User currentUser) {
        Couple couple = requireCouple(currentUser);
        return repository.findByCoupleId(couple.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Listar apenas ativas (para selects de transação) ──────────────────────

    @Transactional(readOnly = true)
    public List<CustomCategoryDtos.CustomCategoryResponse> listActive(User currentUser) {
        Couple couple = requireCouple(currentUser);
        return repository.findByCoupleIdAndActiveTrue(couple.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Couple requireCouple(User user) {
        if (user.getCouple() == null) {
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        }
        return user.getCouple();
    }

    private CustomCategory requireCategory(UUID id, UUID coupleId) {
        return repository.findByIdAndCoupleId(id, coupleId)
                .orElseThrow(() -> new ResourceNotFoundException("Categoria não encontrada: " + id));
    }

    public CustomCategoryDtos.CustomCategoryResponse toResponse(CustomCategory cat) {
        return new CustomCategoryDtos.CustomCategoryResponse(
                cat.getId(),
                cat.getName(),
                cat.getType(),
                cat.getIcon(),
                cat.isActive(),
                cat.getCreatedAt(),
                cat.getUpdatedAt()
        );
    }
}