package br.com.hadryan.duo.finance.couple;

import br.com.hadryan.duo.finance.couple.dto.CoupleDtos;
import br.com.hadryan.duo.finance.user.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/couples")
public class CoupleController {

    private final CoupleService coupleService;

    public CoupleController(CoupleService coupleService) {
        this.coupleService = coupleService;
    }

    /**
     * POST /api/couples
     * Cria uma nova conta de casal vinculando o usuário autenticado como 1º parceiro.
     */
    @PostMapping
    public ResponseEntity<CoupleDtos.CoupleResponse> create(
            @Valid @RequestBody CoupleDtos.CreateCoupleRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(coupleService.create(request, currentUser));
    }

    /**
     * GET /api/couples/me
     * Retorna os dados da conta do casal do usuário autenticado.
     */
    @GetMapping("/me")
    public ResponseEntity<CoupleDtos.CoupleResponse> findMine(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(coupleService.findMine(currentUser));
    }

    /**
     * PUT /api/couples/me
     * Atualiza o nome da conta do casal.
     */
    @PutMapping("/me")
    public ResponseEntity<CoupleDtos.CoupleResponse> update(
            @Valid @RequestBody CoupleDtos.UpdateCoupleRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(coupleService.update(request, currentUser));
    }

    /**
     * POST /api/couples/invite
     * Envia convite por e-mail ao 2º parceiro.
     */
    @PostMapping("/invite")
    public ResponseEntity<CoupleDtos.InviteResponse> invite(
            @Valid @RequestBody CoupleDtos.InvitePartnerRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(coupleService.invite(request, currentUser));
    }

    /**
     * POST /api/couples/join/{token}
     * 2º parceiro aceita o convite e é vinculado à conta.
     */
    @PostMapping("/join/{token}")
    public ResponseEntity<CoupleDtos.JoinCoupleResponse> join(
            @PathVariable String token,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(coupleService.join(token, currentUser));
    }
}
