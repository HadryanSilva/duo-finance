package br.dev.duofinance.shared.email;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("e2e")
@Hidden
@RequestMapping("/api/v1/__e2e")
public class E2eOutboxController {

    public record OutboxMessage(String to, String subject, String html, List<String> links) {}

    private final E2eOutbox outbox;

    public E2eOutboxController(E2eOutbox outbox) {
        this.outbox = outbox;
    }

    @GetMapping("/outbox")
    public List<OutboxMessage> outbox(@RequestParam String to) {
        return outbox.sentTo(to).stream()
                .map(m -> new OutboxMessage(m.to(), m.subject(), m.html(), EmailLinks.extract(m.html())))
                .toList();
    }
}
