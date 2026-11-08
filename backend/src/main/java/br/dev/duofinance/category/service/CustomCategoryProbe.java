package br.dev.duofinance.category.service;

import br.dev.duofinance.account.service.AccountDataProbe;
import br.dev.duofinance.category.repository.CategoryRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Categories the user created themselves (not seeded defaults) count as account data. */
@Component
public class CustomCategoryProbe implements AccountDataProbe {

    private final CategoryRepository categories;

    public CustomCategoryProbe(CategoryRepository categories) {
        this.categories = categories;
    }

    @Override
    public boolean hasUserData(UUID accountId) {
        return categories.existsByAccountIdAndSystemDefaultFalse(accountId);
    }
}
