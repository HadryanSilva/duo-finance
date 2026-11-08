package br.dev.duofinance.access.controller;

import br.dev.duofinance.access.dto.AccessCodeResponse;
import br.dev.duofinance.access.dto.CreateAccessCodeRequest;
import br.dev.duofinance.access.dto.CreatedAccessCodeResponse;
import br.dev.duofinance.access.repository.AccessCodeRepository;
import br.dev.duofinance.access.service.AccessCodeService;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/access-codes")
public class AdminAccessCodeController {

    private final AccessCodeService service;
    private final AccessCodeRepository codes;

    public AdminAccessCodeController(AccessCodeService service, AccessCodeRepository codes) {
        this.service = service;
        this.codes = codes;
    }

    @GetMapping
    public List<AccessCodeResponse> list(CurrentUser current) {
        return codes.findAll(Sort.by(Sort.Direction.DESC, "createdAt", "id")).stream()
                .map(AccessCodeResponse::of)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedAccessCodeResponse create(CurrentUser current, @Valid @RequestBody CreateAccessCodeRequest request) {
        var generated = service.generate(
                request.email(), request.maxUsesOrDefault(), request.expiresAt(), request.note(), current.userId());
        var code = codes.findById(generated.id()).orElseThrow();
        return new CreatedAccessCodeResponse(AccessCodeResponse.of(code), generated.plainCode());
    }

    @PostMapping("/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(CurrentUser current, @PathVariable UUID id) {
        service.revoke(id);
    }
}
