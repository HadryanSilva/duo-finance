package br.dev.duofinance.subscription.dto;

import br.dev.duofinance.subscription.model.SubscriptionPlan;
import br.dev.duofinance.subscription.model.SubscriptionStatus;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record SubscriptionResponse(
        SubscriptionPlan plan,
        SubscriptionStatus status,
        @Nullable Instant currentPeriodEnd,
        boolean hasAccess) {}
