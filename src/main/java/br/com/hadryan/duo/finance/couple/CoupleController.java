package br.com.hadryan.duo.finance.couple;

import br.com.hadryan.duo.finance.couple.dto.CoupleDtos;
import br.com.hadryan.duo.finance.user.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/couples")
public class CoupleController {

    private final CoupleService coupleService;

    public CoupleController(CoupleService coupleService) {
        this.coupleService = coupleService;
    }

    /** POST /api/couples */
    @PostMapping
    public ResponseEntity<CoupleDtos.CoupleResponse> create(
            @Valid @RequestBody CoupleDtos.CreateCoupleRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(coupleService.create(request, currentUser));
    }

    /** GET /api/couples/me */
    @GetMapping("/me")
    public ResponseEntity<CoupleDtos.CoupleResponse> findMine(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(coupleService.findMine(currentUser));
    }

    /** PUT /api/couples/me */
    @PutMapping("/me")
    public ResponseEntity<CoupleDtos.CoupleResponse> update(
            @Valid @RequestBody CoupleDtos.UpdateCoupleRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(coupleService.update(request, currentUser));
    }

    /** POST /api/couples/invite */
    @PostMapping("/invite")
    public ResponseEntity<CoupleDtos.InviteResponse> invite(
            @Valid @RequestBody CoupleDtos.InvitePartnerRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(coupleService.invite(request, currentUser));
    }

    /** POST /api/couples/join/{token} */
    @PostMapping("/join/{token}")
    public ResponseEntity<CoupleDtos.JoinCoupleResponse> join(
            @PathVariable String token,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(coupleService.join(token, currentUser));
    }

    /**
     * DELETE /api/couples/members/{userId}
     * RF34 — Desvincula um membro do casal.
     * Qualquer membro pode remover o outro. Não apaga dados — apenas seta couple = null no usuário alvo.
     */
    @DeleteMapping("/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID userId,
            @AuthenticationPrincipal User currentUser
    ) {
        coupleService.removeMember(userId, currentUser);
        return ResponseEntity.noContent().build();
    }
}