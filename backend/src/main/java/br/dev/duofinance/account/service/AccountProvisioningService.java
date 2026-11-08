package br.dev.duofinance.account.service;

import br.dev.duofinance.account.model.Account;
import br.dev.duofinance.account.model.AccountMember;
import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.category.service.DefaultCategorySeeder;
import br.dev.duofinance.subscription.model.PaymentProvider;
import br.dev.duofinance.subscription.model.Subscription;
import br.dev.duofinance.subscription.model.SubscriptionPlan;
import br.dev.duofinance.subscription.model.SubscriptionStatus;
import br.dev.duofinance.subscription.repository.SubscriptionRepository;
import br.dev.duofinance.user.model.User;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountProvisioningService {

    private final AccountRepository accounts;
    private final AccountMemberRepository members;
    private final SubscriptionRepository subscriptions;
    private final DefaultCategorySeeder categorySeeder;
    private final Clock clock;

    public AccountProvisioningService(
            AccountRepository accounts,
            AccountMemberRepository members,
            SubscriptionRepository subscriptions,
            DefaultCategorySeeder categorySeeder,
            Clock clock) {
        this.accounts = accounts;
        this.members = members;
        this.subscriptions = subscriptions;
        this.categorySeeder = categorySeeder;
        this.clock = clock;
    }

    /** Creates the user's solo account with membership, BETA subscription and localized default categories. */
    @Transactional
    public Account createSoloAccount(User user) {
        Instant now = Instant.now(clock);
        Account account = accounts.save(new Account(accountName(user), now));
        members.save(new AccountMember(account.getId(), user.getId(), now));
        subscriptions.save(new Subscription(
                account.getId(), SubscriptionPlan.BETA, SubscriptionStatus.ACTIVE, PaymentProvider.NONE, now));
        categorySeeder.seed(account.getId(), user.getLocale());
        return account;
    }

    private static String accountName(User user) {
        return "pt-BR".equals(user.getLocale())
                ? "Finanças de " + user.getDisplayName()
                : user.getDisplayName() + "'s finances";
    }
}
