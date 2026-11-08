package br.dev.duofinance.category.controller;

import br.dev.duofinance.category.dto.CategoryRequest;
import br.dev.duofinance.category.dto.CategoryResponse;
import br.dev.duofinance.category.dto.UpdateCategoryRequest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.category.service.CategoryService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private static final String NAME_UNIQUE_INDEX = "uq_categories_active_name";

    /** Foreign keys from transactions and recurring_rules to categories (default PostgreSQL names). */
    private static final Pattern IN_USE_CONSTRAINT = Pattern.compile("(transactions|recurring_rules)_category_id_fkey");

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryResponse> list(
            CurrentUser current,
            @RequestParam(required = false) CategoryKind kind,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(current.accountId(), kind, includeArchived);
    }

    @GetMapping("/{id}")
    public CategoryResponse get(CurrentUser current, @PathVariable UUID id) {
        return service.get(current.accountId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(CurrentUser current, @Valid @RequestBody CategoryRequest request) {
        return mapNameTaken(() -> service.create(current.accountId(), request));
    }

    @PutMapping("/{id}")
    public CategoryResponse update(
            CurrentUser current, @PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
        return mapNameTaken(() -> service.update(current.accountId(), id, request));
    }

    @PostMapping("/{id}/archive")
    public CategoryResponse archive(CurrentUser current, @PathVariable UUID id) {
        return service.archive(current.accountId(), id);
    }

    @PostMapping("/{id}/unarchive")
    public CategoryResponse unarchive(CurrentUser current, @PathVariable UUID id) {
        return mapNameTaken(() -> service.unarchive(current.accountId(), id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser current, @PathVariable UUID id) {
        try {
            service.delete(current.accountId(), id);
        } catch (DataIntegrityViolationException e) {
            // The service transaction has already rolled back here, so nothing is left half-flushed.
            if (violates(e, name -> IN_USE_CONSTRAINT.matcher(name).matches())) {
                throw new ApiException(ErrorCode.CATEGORY_IN_USE);
            }
            throw e;
        }
    }

    private static <T> T mapNameTaken(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            if (violates(e, NAME_UNIQUE_INDEX::equals)) {
                throw new ApiException(ErrorCode.CATEGORY_NAME_TAKEN);
            }
            throw e;
        }
    }

    private static boolean violates(Throwable e, Predicate<String> constraintName) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && constraintName.test(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
