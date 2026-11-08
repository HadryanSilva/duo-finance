package br.dev.duofinance.subscription.service;

import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.subscription.dto.SubscriptionResponse;
import br.dev.duofinance.subscription.repository.SubscriptionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final EntitlementService entitlement;

    public SubscriptionService(SubscriptionRepository subscriptions, EntitlementService entitlement) {
        this.subscriptions = subscriptions;
        this.entitlement = entitlement;
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse get(UUID accountId) {
        var subscription = subscriptions
                .findByAccountId(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Subscription not found"));
        return new SubscriptionResponse(
                subscription.getPlan(),
                subscription.getStatus(),
                subscription.getCurrentPeriodEnd(),
                entitlement.hasAccess(accountId));
    }
}
