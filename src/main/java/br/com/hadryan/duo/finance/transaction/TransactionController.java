package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.transaction.dto.TransactionDtos;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.User;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    /**
     * POST /api/transactions
     */
    @PostMapping
    public ResponseEntity<TransactionDtos.TransactionResponse> create(
            @Valid @RequestBody TransactionDtos.CreateTransactionRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request, currentUser));
    }

    /**
     * GET /api/transactions
     * Query params opcionais: category, type, userId, startDate, endDate, page, size, sort
     */
    @GetMapping
    public ResponseEntity<Page<TransactionDtos.TransactionResponse>> findAll(
            @RequestParam(required = false) TransactionCategory category,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @PageableDefault(size = 20, sort = "date", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal User currentUser
    ) {
        TransactionDtos.TransactionFilter filter =
                new TransactionDtos.TransactionFilter(category, type, userId, startDate, endDate);

        return ResponseEntity.ok(service.findAll(filter, currentUser, pageable));
    }

    /**
     * GET /api/transactions/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionDtos.TransactionResponse> findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(service.findById(id, currentUser));
    }

    /**
     * PUT /api/transactions/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<TransactionDtos.TransactionResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody TransactionDtos.UpdateTransactionRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(service.update(id, request, currentUser));
    }

    /**
     * DELETE /api/transactions/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        service.delete(id, currentUser);
        return ResponseEntity.noContent().build();
    }
}
