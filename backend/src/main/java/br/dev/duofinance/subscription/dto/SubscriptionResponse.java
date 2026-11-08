package br.dev.duofinance.subscription.dto;

import br.dev.duofinance.subscription.model.SubscriptionPlan;
import br.dev.duofinance.subscription.model.SubscriptionStatus;
import java.time.Instant;

public record SubscriptionResponse(
        SubscriptionPlan plan, SubscriptionStatus status, Instant currentPeriodEnd, boolean hasAccess) {}
