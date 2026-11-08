package br.dev.duofinance.subscription.controller;

import br.dev.duofinance.shared.security.CurrentUser;
import br.dev.duofinance.subscription.dto.SubscriptionResponse;
import br.dev.duofinance.subscription.service.SubscriptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    public SubscriptionResponse get(CurrentUser current) {
        return subscriptions.get(current.accountId());
    }
}
