package br.dev.duofinance.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Forces a nullability decision for every reference-typed response component. A component must either be annotated
 * {@code @Nullable} (it is then {@code T | null} in the generated client types) or be listed in
 * {@link #KNOWN_NON_NULL} as "Record.component". A new field fails here until one of the two is chosen.
 */
class ResponseNullabilityTest {

    private static final Set<String> KNOWN_NON_NULL = Set.of(
            "AccessCodeResponse.createdAt",
            "AccessCodeResponse.id",
            "AccountBalance.id",
            "AccountBalance.name",
            "AccountBalance.type",
            "AccountResponse.currency",
            "AccountResponse.id",
            "AccountResponse.members",
            "AccountResponse.name",
            "AuthResponse.accessToken",
            "AuthResponse.user",
            "AuthorRef.displayName",
            "AuthorRef.userId",
            "BankAccountRef.id",
            "BankAccountRef.name",
            "BankAccountResponse.createdAt",
            "BankAccountResponse.id",
            "BankAccountResponse.name",
            "BankAccountResponse.type",
            "BankAccountResponse.updatedAt",
            "BudgetResponse.category",
            "BudgetResponse.id",
            "BudgetResponse.month",
            "BudgetResponse.status",
            "CategoryRef.color",
            "CategoryRef.icon",
            "CategoryRef.id",
            "CategoryRef.name",
            "CategoryResponse.color",
            "CategoryResponse.icon",
            "CategoryResponse.id",
            "CategoryResponse.kind",
            "CategoryResponse.name",
            "CategorySpend.categoryId",
            "CategorySpend.color",
            "CategorySpend.icon",
            "CategorySpend.name",
            "CreatedAccessCodeResponse.code",
            "CreatedAccessCodeResponse.plainCode",
            "DashboardResponse.accounts",
            "DashboardResponse.balancesAsOf",
            "DashboardResponse.budgets",
            "DashboardResponse.currency",
            "DashboardResponse.month",
            "DashboardResponse.spendingByCategory",
            "DashboardResponse.upcoming",
            "InvitationPreviewResponse.accountName",
            "InvitationPreviewResponse.email",
            "InvitationSummary.email",
            "InvitationSummary.expiresAt",
            "InvitationSummary.id",
            "MeResponse.accountId",
            "MeResponse.displayName",
            "MeResponse.email",
            "MeResponse.id",
            "MeResponse.locale",
            "MeResponse.role",
            "MemberResponse.displayName",
            "MemberResponse.email",
            "MemberResponse.joinedAt",
            "MemberResponse.userId",
            "PageResponse.content",
            "RecurringRuleResponse.frequency",
            "RecurringRuleResponse.id",
            "RecurringRuleResponse.kind",
            "RecurringRuleResponse.nextRunDate",
            "RecurringRuleResponse.startDate",
            "RegistrationModeResponse.mode",
            "SubscriptionResponse.plan",
            "SubscriptionResponse.status",
            "TransactionResponse.createdAt",
            "TransactionResponse.date",
            "TransactionResponse.id",
            "TransactionResponse.kind",
            "TransactionResponse.updatedAt",
            "UpcomingOccurrence.date",
            "UpcomingOccurrence.kind",
            "UpcomingOccurrence.ruleId",
            "WaitlistEntryResponse.createdAt",
            "WaitlistEntryResponse.email",
            "WaitlistEntryResponse.id",
            "WaitlistEntryResponse.locale",
            "WaitlistEntryResponse.status",
            "WaitlistPageResponse.content");

    @Test
    void everyReferenceComponentIsDecidedNullableOrKnownNonNull() {
        List<String> undecided = new ArrayList<>();
        List<String> seenNonNull = new ArrayList<>();
        for (Class<?> type : ResponseRecords.all()) {
            for (RecordComponent component : type.getRecordComponents()) {
                String key = type.getSimpleName() + "." + component.getName();
                if (component.getType().isPrimitive()) {
                    continue;
                }
                if (ResponseRecords.isNullable(component)) {
                    assertThat(KNOWN_NON_NULL)
                            .as("%s is nullable and also listed as non-null", key)
                            .doesNotContain(key);
                } else if (KNOWN_NON_NULL.contains(key)) {
                    seenNonNull.add(key);
                } else {
                    undecided.add(key);
                }
            }
        }
        assertThat(undecided)
                .as("annotate with @Nullable or add to KNOWN_NON_NULL")
                .isEmpty();
        assertThat(seenNonNull).as("stale KNOWN_NON_NULL entries").containsExactlyInAnyOrderElementsOf(KNOWN_NON_NULL);
    }

    @Test
    void responseRecordsAreDiscovered() {
        assertThat(ResponseRecords.all().stream().map(Class::getSimpleName))
                .contains("TransactionResponse", "AuthorRef", "PageResponse", "AccountBalance")
                .doesNotContain("TransactionRequest", "TransactionFilter");
    }
}
