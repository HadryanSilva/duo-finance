package br.com.hadryan.duo.finance.category;

import br.com.hadryan.duo.finance.category.dto.CustomCategoryDtos;
import br.com.hadryan.duo.finance.user.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/custom-categories")
public class CustomCategoryController {

    private final CustomCategoryService service;

    public CustomCategoryController(CustomCategoryService service) {
        this.service = service;
    }

    /** GET /api/custom-categories — todas (incluindo inativas), para gerenciamento */
    @GetMapping
    public ResponseEntity<List<CustomCategoryDtos.CustomCategoryResponse>> listAll(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(service.listAll(currentUser));
    }

    /** GET /api/custom-categories/active — apenas ativas, para selects de transação */
    @GetMapping("/active")
    public ResponseEntity<List<CustomCategoryDtos.CustomCategoryResponse>> listActive(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(service.listActive(currentUser));
    }

    /** POST /api/custom-categories */
    @PostMapping
    public ResponseEntity<CustomCategoryDtos.CustomCategoryResponse> create(
            @Valid @RequestBody CustomCategoryDtos.CreateRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request, currentUser));
    }

    /** PUT /api/custom-categories/{id} */
    @PutMapping("/{id}")
    public ResponseEntity<CustomCategoryDtos.CustomCategoryResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CustomCategoryDtos.UpdateRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(service.update(id, request, currentUser));
    }

    /** PATCH /api/custom-categories/{id}/toggle */
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<CustomCategoryDtos.CustomCategoryResponse> toggle(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(service.toggleActive(id, currentUser));
    }

    /** DELETE /api/custom-categories/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        service.delete(id, currentUser);
        return ResponseEntity.noContent().build();
    }
}