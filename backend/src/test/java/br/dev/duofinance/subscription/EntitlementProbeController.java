package br.dev.duofinance.subscription;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint behind the entitlement gate. */
@RestController
class EntitlementProbeController {

    @GetMapping("/api/v1/transactions/__probe")
    Map<String, String> financial() {
        return Map.of("ok", "true");
    }
}
