package br.dev.duofinance.subscription.service;

import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.subscription.model.SubscriptionStatus;
import br.dev.duofinance.subscription.repository.SubscriptionRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EntitlementService {

    private static final Set<SubscriptionStatus> ENTITLED =
            Set.of(SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE);

    private final SubscriptionRepository subscriptions;

    public EntitlementService(SubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    public boolean hasAccess(UUID accountId) {
        return subscriptions
                .findByAccountId(accountId)
                .map(s -> ENTITLED.contains(s.getStatus()))
                .orElse(false);
    }

    public void requireAccess(UUID accountId) {
        if (!hasAccess(accountId)) {
            throw new ApiException(ErrorCode.SUBSCRIPTION_INACTIVE, "Subscription is not active");
        }
    }
}
